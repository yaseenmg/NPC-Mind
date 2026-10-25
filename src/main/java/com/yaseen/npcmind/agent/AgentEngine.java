package com.yaseen.npcmind.agent;

import com.google.gson.*;
import com.yaseen.npcmind.NPCMind;
import com.yaseen.npcmind.debug.AuditLog;
import com.yaseen.npcmind.debug.Trace;
import com.yaseen.npcmind.Config;
import com.yaseen.npcmind.core.*;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.knowledge.WorldContext;
import com.yaseen.npcmind.memory.MemoryStore;
import com.yaseen.npcmind.task.TaskManager;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.ToolRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * New planner/orchestrator. The LLM is only the strategic layer; Minecraft state, reflexes,
 * task execution and verification remain deterministic server-side systems.
 */
public final class AgentEngine {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
    // A fixed pool of 3 starved with multiple companions: every start() submits TWO tasks
    // (the brain loop AND a watcher that blocks on f.get()), and waitForTask() sleeps on the
    // brain thread. Two companions already needed 4 live threads, so work queued behind a full
    // pool and froze. A cached pool creates threads on demand and reaps idle ones after 60s.
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "npcmind-brain"); t.setDaemon(true); return t;
    });
    private static final Map<UUID, Future<?>> ACTIVE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> GENERATION = new ConcurrentHashMap<>();
    private static final Map<UUID, WorldSnapshot> ACTION_BEFORE = new ConcurrentHashMap<>();
    private static volatile long lastWarmup;
    private static final Object GROQ_BUDGET_LOCK = new Object();
    private static long groqWindowStartMs = 0L;
    private static int groqWindowRequests = 0;
    private static LocalDate groqDay = LocalDate.now(ZoneOffset.UTC);
    private static int groqDayRequests = 0;
    private static long groqDayTokens = 0L;

    public static boolean isBusy(UUID id) { Future<?> f = ACTIVE.get(id); return f != null && !f.isDone(); }

    public static void runAsync(CompanionEntity c, ServerPlayer p, String message) { start(c, p, message, false); }

    public static void runAutonomousAsync(CompanionEntity c, ServerPlayer p) {
        start(c, p, "[AUTONOMOUS] The request could not be completed. Please try again.", true);
    }

    private static void start(CompanionEntity c, ServerPlayer p, String message, boolean autonomous) {
        if (c == null || p == null || message == null || message.isBlank()) return;
        UUID id = c.getUUID();
        Future<?> old = ACTIVE.get(id);
        if (old != null && !old.isDone()) {
            if (autonomous) return;
            GENERATION.merge(id, 1L, Long::sum);
            old.cancel(true);
            TaskManager.cancelCurrentTask(c);
        }
        long generation = GENERATION.merge(id, 1L, Long::sum);
        MinecraftServer server = p.server;
        UUID pid = p.getUUID();
        UUID cid = c.getUUID();
        PlanExecutionController.begin(cid, message);
        Future<?> f = EXECUTOR.submit(() -> loop(server, pid, cid, message, autonomous, generation));
        ACTIVE.put(cid, f);
        EXECUTOR.submit(() -> { try { f.get(); } catch (Exception ignored) {} finally { ACTIVE.remove(cid, f); } });
    }

    private static void loop(MinecraftServer server, UUID pid, UUID cid, String original, boolean autonomous, long generation) {
        try {
            if (!isEntityAliveOnServer(server, pid, cid)) return;
            String trail = "";
            int retries = 0;
            for (int step = 0; step < 8; step++) {
                if (cancelled(cid, generation)) return;
                if (!isEntityAliveOnServer(server, pid, cid)) return;
                String context = capturePlannerContext(server, pid, cid);
                if (context == null) return;
                // Groq inference is network-bound and MUST remain off the Minecraft server thread.
                // capturePlannerContext() only snapshots authoritative world state; the neural
                // advisory call happens here on the npcmind-brain worker.
                String name;
                String args;
                AITool tool;

                // High-confidence gameplay sentences are routed to a deterministic
                // capability before asking a small model to choose a function. This is
                // intentionally limited to unambiguous, reversible intents.
                ToolSelectionRouter.Route routed = step == 0 ? ToolSelectionRouter.first(original) : null;
                if (routed != null) {
                    name = routed.tool();
                    args = routed.arguments();
                    tool = ToolRegistry.get(name);
                    AuditLog.decision(cid, original, name, "deterministic capability router: " + routed.reason());
                // Version 60: high-confidence gameplay verbs are routed to a deterministic
                // work graph. A weak local model must never be allowed to turn "status update" into
                // an arbitrary tool sequence. The LLM remains responsible for open-ended tasks.
                } else {
                    String prompt = buildPrompt(context, original, trail, autonomous, cid);
                    AuditLog.event("LLM", "REQUEST", cid, "step=" + step + " | goal=" + Trace.clip(original, 800) + " | context_chars=" + prompt.length() + " | tools=" + ToolRegistry.jsonToolDefinitionsFor(original).size() + "/" + ToolRegistry.all().size());
                    AgentDecision decision = requestDecision(prompt, "status update Minecraft. I could not complete the request. Status: " + original, 384);
                    AuditLog.llm(cid, "RESPONSE", decision.raw());
                    if (decision.finalAnswer()) {
                        retries = 0;
                        String text = sanitize(decision.text());
                        if (!autonomous && !text.isBlank()) deliver(server, pid, cid, text, false);
                        return;
                    }
                    if (!decision.hasToolCall()) {
                        retries++;
                        if (retries >= 2) { if (!autonomous) deliver(server, pid, cid, "I could not complete the request.", false); return; }
                        trail = "INVALID_TOOL_CALL: invalid function/tool call I could not complete the request.";
                        continue;
                    }
                    retries = 0;
                    AgentToolCall call = decision.toolCalls().get(0);
                    name = call.name();
                    AuditLog.decision(cid, original, name, "native tool call step=" + step + " call_id=" + call.id());
                    args = call.arguments() == null ? "{}" : call.arguments().toString();
                    tool = ToolRegistry.get(name);
                }
                if (tool == null) { trail = "UNKNOWN_TOOL: " + name; continue; }

                WorldSnapshot before = captureOnServer(server, pid, cid);
                if (before == null) {
                    if (!autonomous) deliver(server, pid, cid, "I could not complete the request.", false);
                    return;
                }
                ACTION_BEFORE.put(cid, before);
                String actionId = ActionJournal.begin(name, args);
                PlanExecutionController.checkpointStarted(cid, name, "STARTED action=" + actionId);
                AuditLog.tool(cid, actionId, "START", name, Trace.clip(args, 1200), null);
                AuditLog.world(cid, "BEFORE", Trace.clip(beforeSummary(before), 2500));
                String result = execute(server, pid, cid, tool, args, generation, actionId);
                if (result == null) result = "TOOL_ERROR null_result";
                if (result.startsWith("STARTED")) {
                    if (!autonomous) deliver(server, pid, cid, acknowledgement(name), false);
                    result = waitForTask(server, pid, cid, generation, 30000L);
                }
                WorldSnapshot after = captureOnServer(server, pid, cid);
                if (after != null) AuditLog.world(cid, "AFTER", Trace.clip(afterSummary(after), 2500));
                CriticEngine.Verdict verdict = after == null ? new CriticEngine.Verdict(false, false, true, "entity_missing")
                        : CriticEngine.evaluate(null, before, after, result);
                String serverTrail = finishAndBuildTrail(server, pid, cid, actionId, name, args, result, verdict);
                if (serverTrail == null) return;
                trail = serverTrail + "\nPLAN " + PlanExecutionController.context(cid);
                PlanExecutionController.checkpointResult(cid, name, result, !verdict.success());
                if (!verdict.success() && ActionJournal.repeatedFailures(cid) >= 2) {
                    if (!autonomous) deliver(server, pid, cid, "The request could not be completed. Please try again.", false);
                    return;
                }
            }
            if (!autonomous) deliver(server, pid, cid, "I could not complete the request.", false);
        } catch (Throwable t) {
            AuditLog.error(cid, "brain_loop", t);
            NPCMind.LOG.error("[NPCMind] brain loop crashed", t);
            if (!autonomous) deliver(server, pid, cid, "I could not complete the request.", false);
        } finally { ACTION_BEFORE.remove(cid); }
    }

    private static String execute(MinecraftServer server, UUID pid, UUID cid, AITool tool, String args, long generation, String actionId) {
        CompletableFuture<String> result = new CompletableFuture<>();
        server.execute(() -> {
            try {
                if (GENERATION.getOrDefault(cid, generation) != generation) { result.complete("TOOL_ERROR stale_generation"); return; }
                ServerPlayer p = server.getPlayerList().getPlayer(pid);
                CompanionEntity c = find(server, cid);
                if (p == null || c == null || !c.isAlive()) { result.complete("TOOL_ERROR entity_offline"); return; }
                AgentToolRuntime.Result gate = AgentToolRuntime.invoke(c, p, tool.name(), args, actionId);
                result.complete(gate.result());
            } catch (Throwable t) {
                NPCMind.LOG.error("[NPCMind] tool {} crashed", tool.name(), t);
                result.complete("TOOL_ERROR " + t.getClass().getSimpleName());
            }
        });
        try { return result.get(10, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return "TOOL_ERROR interrupted"; }
        catch (Exception e) { return "TOOL_ERROR server_execution_timeout"; }
    }

    private static String waitForTask(MinecraftServer server, UUID pid, UUID cid, long generation, long timeout) {
        long end = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < end) {
            if (cancelled(cid, generation)) return "TOOL_ERROR cancelled";
            CompletableFuture<String> f = new CompletableFuture<>();
            server.execute(() -> {
                CompanionEntity c = find(server, cid);
                f.complete(c == null ? "TOOL_ERROR entity_offline" : (TaskManager.isActive(c) ? TaskManager.status(c) : Optional.ofNullable(TaskManager.takeLastResult(c)).orElse("FAILED missing_task_result")));
            });
            try {
                String s = f.get(700, TimeUnit.MILLISECONDS);
                if (!s.startsWith("RUNNING") && !s.startsWith("STARTED") && !s.startsWith("action_id=")) return s;
            } catch (Exception ignored) {}
            try { Thread.sleep(120); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return "TOOL_ERROR interrupted"; }
        }
        return "TIMEOUT task_execution";
    }

    private static WorldSnapshot captureOnServer(MinecraftServer server, UUID pid, UUID cid) {
        CompletableFuture<WorldSnapshot> f = new CompletableFuture<>();
        server.execute(() -> { ServerPlayer p = server.getPlayerList().getPlayer(pid); CompanionEntity c = find(server, cid); f.complete(c == null || p == null ? null : WorldSnapshot.capture(c, p)); });
        try { return f.get(1500, TimeUnit.MILLISECONDS); } catch (Exception e) { return null; }
    }

    private static String beforeSummary(WorldSnapshot s) { return snapshotSummary(s); }
    private static String afterSummary(WorldSnapshot s) { return snapshotSummary(s); }
    private static String snapshotSummary(WorldSnapshot s) {
        return "pos=" + s.position() + " | hp=" + s.health() + " | food=" + s.food()
                + " | sat=" + s.saturation() + " | inv_slots=" + s.inventoryItems()
                + " | inv_sig=" + s.inventorySignature() + " | hostiles=" + s.nearbyHostiles()
                + " | water=" + s.inWater() + " | fire=" + s.onFire() + " | lava=" + s.inLava()
                + " | target=" + s.hasTarget() + " | task=" + Trace.clip(s.taskStatus(), 500) + " | tick=" + s.tick();
    }

    private static String capturePlannerContext(MinecraftServer server, UUID pid, UUID cid) {
        CompletableFuture<String> f = new CompletableFuture<>();
        server.execute(() -> {
            try {
                ServerPlayer p = server.getPlayerList().getPlayer(pid);
                CompanionEntity c = find(server, cid);
                if (c == null || p == null || !c.isAlive()) { f.complete(null); return; }
                WorldSnapshot snap = WorldSnapshot.capture(c, p);
                String world = WorldContext.build(p);
                String memory = MemoryStore.context(p);
                String live = "pos=" + snap.position() + " hp=" + snap.health() + " food=" + snap.food()
                        + " sat=" + snap.saturation() + " hostiles=" + snap.nearbyHostiles()
                        + " task=" + snap.taskStatus() + " recent=" + ActionJournal.recent(cid)
                        + " skills=" + SkillLibrary.retrieve(c, "")
                        + " plan=" + PlanExecutionController.context(cid);
                // Only capture authoritative state here. Never perform HTTP/model inference on
                // the server thread. The caller appends provider diagnostics asynchronously.
                f.complete("LIVE " + live + "\nWORLD " + clip(world, 2600) + "\nMEMORY " + clip(memory, 1200));
            } catch (Throwable t) {
                AuditLog.error(cid, "planner_snapshot", t);
                f.complete(null);
            }
        });
        try { return f.get(1500, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        catch (Exception e) { return null; }
    }

    private static boolean isEntityAliveOnServer(MinecraftServer server, UUID pid, UUID cid) {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(pid);
            CompanionEntity c = find(server, cid);
            f.complete(p != null && c != null && c.isAlive());
        });
        try { return f.get(800, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        catch (Exception e) { return false; }
    }

    private static String finishAndBuildTrail(MinecraftServer server, UUID pid, UUID cid, String actionId, String name, String args, String result, CriticEngine.Verdict verdict) {
        CompletableFuture<String> f = new CompletableFuture<>();
        server.execute(() -> {
            try {
                CompanionEntity c = find(server, cid);
                if (c == null) { f.complete(null); return; }
                ActionJournal.finish(c, actionId, name, args, result);
                AuditLog.event("VERIFY", verdict.success() ? "SUCCESS" : "FAILED", cid,
                        "action_id=" + actionId + " | reason=" + verdict.reason() + " | progress=" + verdict.progress());
                c.getDecisions().record("agent_tool", name, verdict.success() ? "SUCCESS" : "FAILED:" + verdict.reason());
                if (verdict.success()) SkillLibrary.recordSuccess(c, name, verdict.reason());
                else SkillLibrary.recordFailure(c, name, verdict.reason());
                f.complete("step=" + name + " result=" + clip(result, 700)
                        + " critic=" + verdict.reason() + " progress=" + verdict.progress()
                        + " recent=" + ActionJournal.recent(cid)
                        + " skills=" + SkillLibrary.retrieve(c, name));
            } catch (Throwable t) {
                AuditLog.error(cid, "action_finalize", t);
                f.complete(null);
            }
        });
        try { return f.get(1500, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        catch (Exception e) { return null; }
    }

    private static String buildPrompt(String context, String request, String trail, boolean autonomous, UUID cid) {
        return """
status update Minecraft Forge 1.20.1.
The request could not be completed. Please try again. I could not complete the request.
text: Observe -> Plan one action -> Execute -> Verify -> Recover. I could not complete the request.
I could not complete the request. Reflex Engine I could not complete the request.
invalid function/tool call text. I could not complete the request. status update tool calling. I could not complete the request. status update STARTEDI could not complete the request.
Status: status update Minecraft status update function tools. I could not complete the request.\nStatus:\n""" + context
                + "\nStatus:\n" + (trail.isBlank() ? "none" : trail)
                + "\nStatus:\n" + AgentMemory.summary(cid)
                + "\nStatus:\n" + request + (autonomous ? "\nStatus: I could not complete the request." : "");
    }

    private static JsonObject parseObject(String raw) {
        if (raw == null) return null;
        try { return JsonParser.parseString(clean(raw)).getAsJsonObject(); } catch (Exception e) { return null; }
    }
    private static String clean(String s) { int a = s.indexOf('{'), b = s.lastIndexOf('}'); return a >= 0 && b > a ? s.substring(a, b + 1) : s; }
    private static String str(JsonObject o, String k) { try { return o.has(k) ? o.get(k).getAsString() : ""; } catch (Exception e) { return ""; } }
    private static boolean cancelled(UUID id, long generation) { return Thread.currentThread().isInterrupted() || GENERATION.getOrDefault(id, generation) != generation; }

    private static String acknowledgement(String name) { return switch (name) {
        case "move_to", "smart_navigate", "follow_owner" -> "I could not complete the request.";
        case "mine_block", "gather_wood", "smart_vein_mining" -> "I could not complete the request.";
        case "combat_target" -> "I could not complete the request.";
        default -> "I could not complete the request.";
    }; }

    private static void deliver(MinecraftServer server, UUID pid, UUID cid, String text, boolean voice) {
    }

    private static CompanionEntity find(MinecraftServer server, UUID id) { for (ServerLevel l : server.getAllLevels()) if (l.getEntity(id) instanceof CompanionEntity c) return c; return null; }
    private static String clip(String s, int n) { if (s == null) return ""; return s.length() <= n ? s : s.substring(0, n) + "…"; }
    static String sanitize(String s) { if (s == null) return ""; return s.trim().replaceAll("(?U)(\\S+)(\\s+\\1\\b){1,}", "$1"); }

    public static void diagnose(ServerPlayer player) {
        CompletableFuture.runAsync(() -> {
            String msg;
            try {
                if (Config.GROQ_API_KEY.get() == null || Config.GROQ_API_KEY.get().isBlank()) {
                    msg = "Groq I could not complete the request. groqApiKey in config/npcmind-common.toml/npcmind-common.toml";
                } else {
                    String base = Config.GROQ_BASE_URL.get().replaceAll("/+$", "");
                    if (base.endsWith("/chat/completions")) base = base.substring(0, base.length() - "/chat/completions".length());
                    HttpRequest r = HttpRequest.newBuilder(URI.create(base + "/models"))
                            .timeout(Duration.ofSeconds(8)).header("Authorization", "Bearer " + Config.GROQ_API_KEY.get()).GET().build();
                    HttpResponse<String> resp = HTTP.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    msg = "Groq HTTP=" + resp.statusCode() + " model=" + Config.GROQ_MODEL.get()
                            + (resp.statusCode() >= 200 && resp.statusCode() < 300 ? " text" : " status update");
                }
            } catch (Exception e) { msg = "status update Groq: " + e.getClass().getSimpleName(); }
            String m = msg;
            player.server.execute(() -> player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[AI] " + m)));
        }, EXECUTOR);
    }

    public static void warmUp() {
        // Intentionally a no-op. The old warm-up fired a real Groq request on every player
        // login just to "prime" the connection, burning paid API budget for no gameplay
        // benefit. The HTTP client and executor initialise lazily on the first real request.
        lastWarmup = System.currentTimeMillis();
    }

    private static AgentDecision requestDecision(String system, String user, int predict) {
        try {
            String key = Config.GROQ_API_KEY.get();
            if (key == null || key.isBlank()) return new AgentDecision("status update Groq I could not complete the request.", List.of(), true, "GROQ_KEY_MISSING");
            String endpoint = Config.GROQ_BASE_URL.get();
            if (endpoint == null || endpoint.isBlank()) endpoint = "https://api.groq.com/openai/v1";
            endpoint = endpoint.trim().replaceAll("/+$", "");
            if (!endpoint.endsWith("/chat/completions")) endpoint += "/chat/completions";
            JsonObject body = new JsonObject();
            body.addProperty("model", Config.GROQ_MODEL.get());
            body.addProperty("stream", false);
            body.addProperty("parallel_tool_calls", false);
            body.addProperty("reasoning_effort", "low");
            JsonArray messages = new JsonArray();
            JsonObject sys = new JsonObject(); sys.addProperty("role", "system"); sys.addProperty("content", system); messages.add(sys);
            JsonObject usr = new JsonObject(); usr.addProperty("role", "user"); usr.addProperty("content", user); messages.add(usr);
            body.add("messages", messages);
            JsonArray tools = ToolRegistry.jsonToolDefinitionsFor(user);
            if (!tools.isEmpty()) body.add("tools", tools);
            body.addProperty("tool_choice", looksActionable(user) && !tools.isEmpty() ? "required" : "auto");
            if (!GroqBudget.allowRequest()) return new AgentDecision("status update Groq text. I could not complete the request.", List.of(), true, "GROQ_BUDGET_BLOCKED");
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(45))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + key)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
            HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            GroqBudget.recordResponse(r.body());
            if (r.statusCode() < 200 || r.statusCode() >= 300) return new AgentDecision("status update Groq: HTTP " + r.statusCode(), List.of(), true, "GROQ_HTTP_" + r.statusCode());
            JsonObject root = JsonParser.parseString(r.body()).getAsJsonObject();
            JsonObject msg = null;
            if (root.has("choices") && !root.getAsJsonArray("choices").isEmpty()) msg = root.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
            if (msg == null) return new AgentDecision("Groq text.", List.of(), true, r.body());
            List<AgentToolCall> calls = new ArrayList<>();
            if (msg.has("tool_calls") && msg.get("tool_calls").isJsonArray()) for (JsonElement e : msg.getAsJsonArray("tool_calls")) {
                if (!e.isJsonObject()) continue;
                JsonObject tc = e.getAsJsonObject();
                String id = tc.has("id") ? tc.get("id").getAsString() : UUID.randomUUID().toString();
                JsonObject fn = tc.has("function") ? tc.getAsJsonObject("function") : tc;
                String n = fn.has("name") ? fn.get("name").getAsString() : "";
                String a = fn.has("arguments") ? fn.get("arguments").getAsString() : "{}";
                JsonObject ao; try { ao = JsonParser.parseString(a).getAsJsonObject(); } catch (Exception ex) { ao = new JsonObject(); }
                if (!n.isBlank()) calls.add(new AgentToolCall(id, n, ao));
            }
            String content = msg.has("content") && !msg.get("content").isJsonNull() ? msg.get("content").getAsString() : "";
            JsonObject legacy = parseObject(content);
            if (calls.isEmpty() && legacy != null && "tool".equalsIgnoreCase(str(legacy, "type"))) {
                String n = str(legacy, "name");
                JsonObject a = legacy.has("arguments") && legacy.get("arguments").isJsonObject() ? legacy.getAsJsonObject("arguments") : new JsonObject();
                if (!n.isBlank()) calls.add(new AgentToolCall(UUID.randomUUID().toString(), n, a));
            }
            boolean finalAnswer = calls.isEmpty();
            String text = legacy != null && legacy.has("text") ? str(legacy, "text") : content;
            return new AgentDecision(text, calls, finalAnswer, r.body());
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return new AgentDecision("status update Groq.", List.of(), true, "INTERRUPTED"); }
        catch (Exception e) { return new AgentDecision("status update Groq: " + e.getClass().getSimpleName(), List.of(), true, "GROQ_ERROR"); }
    }

    private static final class GroqBudget {
        static boolean allowRequest() {
            synchronized (GROQ_BUDGET_LOCK) {
                long now = System.currentTimeMillis();
                if (now - groqWindowStartMs >= 60_000L) { groqWindowStartMs = now; groqWindowRequests = 0; }
                LocalDate today = LocalDate.now(ZoneOffset.UTC);
                if (!today.equals(groqDay)) { groqDay = today; groqDayRequests = 0; groqDayTokens = 0L; }
                int perMinute = Config.GROQ_MAX_REQUESTS_PER_MINUTE.get();
                int perDay = Config.GROQ_MAX_REQUESTS_PER_DAY.get();
                long tokenCap = Config.GROQ_MAX_TOKENS_PER_DAY.get();
                if (perMinute == 0 || perDay == 0 || tokenCap == 0) return false;
                if (groqWindowRequests >= perMinute || groqDayRequests >= perDay || groqDayTokens >= tokenCap) return false;
                groqWindowRequests++;
                groqDayRequests++;
                return true;
            }
        }
        static void recordResponse(String body) {
            long tokens = 0L;
            try {
                JsonObject root = JsonParser.parseString(body == null ? "{}" : body).getAsJsonObject();
                if (root.has("usage") && root.get("usage").isJsonObject()) {
                    JsonObject u = root.getAsJsonObject("usage");
                    if (u.has("total_tokens")) tokens = Math.max(0L, u.get("total_tokens").getAsLong());
                    else if (u.has("prompt_tokens") || u.has("completion_tokens")) {
                        tokens = (u.has("prompt_tokens") ? u.get("prompt_tokens").getAsLong() : 0L)
                                + (u.has("completion_tokens") ? u.get("completion_tokens").getAsLong() : 0L);
                    }
                }
            } catch (Exception ignored) {}
            synchronized (GROQ_BUDGET_LOCK) { groqDayTokens += Math.max(0L, tokens); }
        }
    }

    private static boolean looksActionable(String text) {
        if (text == null) return false;
        String s = text.toLowerCase(Locale.ROOT);
        String[] verbs = {"text","text","text","text","text","text","text","text","text","text","text","text","text","text","text","text","text","text","text","text","text","text","text","text","text","text","text","text","text","text","text","text","text","mine","craft","build","move","follow","attack","gather","smelt","cook","explore","find","give","take"};
        for (String v : verbs) if (s.contains(v)) return true;
        return false;
    }

    private AgentEngine() {}
}
