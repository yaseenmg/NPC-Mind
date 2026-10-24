package com.yaseen.npcmind.core;

import com.yaseen.npcmind.entity.CompanionEntity;

/** Post-action critic. Tool output is evidence, but world-state change is the final authority. */
public final class CriticEngine {
    public record Verdict(boolean success, boolean progress, boolean retryable, String reason) {}

    public static Verdict evaluate(CompanionEntity c, WorldSnapshot before, WorldSnapshot after, String result) {
        if (result == null) return new Verdict(false, false, true, "null_result");
        if (result.startsWith("DENIED")) return new Verdict(false, false, false, result);
        if (result.startsWith("TOOL_ERROR")) return new Verdict(false, false, true, result);
        if (result.startsWith("FAILED") || result.startsWith("ERROR") || result.startsWith("TIMEOUT"))
            return new Verdict(false, false, true, result);
        WorldDiff d = WorldDiff.compare(before, after, result);
        boolean started = result.startsWith("STARTED");
        if (started) return new Verdict(false, false, true, "action_running");
        boolean explicitSuccess = result.startsWith("SUCCESS") || result.startsWith("RESULT");
        boolean progress = Math.abs(d.healthDelta()) > 0.01f || d.foodDelta() != 0 || d.inventorySlotDelta() != 0
                || d.inventoryChanged() || d.distanceMoved() > 0.75;
        // RESULT is intentionally not treated as success for mutating tools: read-only/control
        // tools can return RESULT, but world-changing actions must either show world/inventory
        // progress or carry an explicit terminal success with a concrete effect count.
        boolean concreteSuccess = explicitSuccess && (hasConcreteEffect(result) || isReadOnlyResult(result));
        return new Verdict(concreteSuccess || progress, progress || concreteSuccess, !progress && !concreteSuccess,
                concreteSuccess ? "verified_action_result" : (progress ? "verified_world_change" : "no_world_progress"));
    }
    private static boolean isReadOnlyResult(String result) {
        String r = result.toLowerCase(java.util.Locale.ROOT);
        return r.startsWith("result ") && (r.contains("inventory=") || r.contains("equipment=") ||
                r.contains("health=") || r.contains("permissions=") || r.contains("goal=") ||
                r.contains("world=") || r.contains("report=") || r.contains("enabled=") ||
                r.contains("status=") || r.contains("task="));
    }

    private static boolean hasConcreteEffect(String result) {
        String r = result.toLowerCase(java.util.Locale.ROOT);
        if (r.matches(".*\\b(placed|crafted|harvested|replanted|mined|taken|deposited|traded|moved|gave|lit|reached|equipped|started|queued|reserved|released)=([1-9][0-9]*|true|yes).*" )
                || r.contains("quest_started") || r.contains("quest_stopped") || r.contains("quest_already_active")
                || r.contains("combat_stopped") || r.contains("follow_enabled=true")
                || r.contains("reached ") || r.contains("already_air") || r.contains("already_have_output")
                || r.contains("already_active") || r.contains("quest_not_active")) return true;
        // Generic fallback: any SUCCESS result carrying a concrete key=<positive number|true|yes>
        // token is a real effect. Without this, a tool that reports a NEW effect verb not listed
        // above returned "no_world_progress" and got retried forever (the VerifyEngine-style loop).
        return r.matches(".*\\b[a-z_]+=([1-9][0-9]*|true|yes)\\b.*");
    }

    private CriticEngine() {}
}