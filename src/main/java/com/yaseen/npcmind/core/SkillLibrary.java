package com.yaseen.npcmind.core;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.state.CompanionWorldData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Safe procedural skill memory. Skills are descriptions of existing tools, never arbitrary code execution. */
public final class SkillLibrary {
    private static final Map<UUID, Map<String, Skill>> DATA = new ConcurrentHashMap<>();
    public record Skill(String name, String recipe, int successes, int failures, double score, long lastUsed) {
        public Skill(String name, String recipe, int successes, int failures) { this(name, recipe, successes, failures, score(successes, failures), System.currentTimeMillis()); }
        private static double score(int ok, int fail) { return (ok + 1.0) / (ok + fail + 2.0); }
    }

    public static void recordSuccess(CompanionEntity c, String name, String recipe) {
        if (name == null || name.isBlank()) return;
        DATA.computeIfAbsent(c.getUUID(), k -> new ConcurrentHashMap<>()).compute(name, (n, old) ->
                new Skill(n, recipe == null ? "" : recipe, old == null ? 1 : old.successes() + 1, old == null ? 0 : old.failures()));
        persist(c);
    }
    public static void recordFailure(CompanionEntity c, String name, String reason) {
        if (name == null || name.isBlank()) return;
        DATA.computeIfAbsent(c.getUUID(), k -> new ConcurrentHashMap<>()).compute(name, (n, old) ->
                new Skill(n, old == null ? "" : old.recipe(), old == null ? 0 : old.successes(), old == null ? 1 : old.failures() + 1));
        persist(c);
    }
    public static String retrieve(CompanionEntity c, String query) {
        load(c);
        Map<String, Skill> m = DATA.get(c.getUUID());
        if (m == null || m.isEmpty()) return "none";
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder();
        m.values().stream().sorted(Comparator.comparingDouble((Skill s) -> s.score()).reversed().thenComparing(Comparator.comparingLong((Skill s) -> s.lastUsed()).reversed())).limit(5).forEach(s -> {
            if (q.isEmpty() || s.name().toLowerCase(Locale.ROOT).contains(q) || s.recipe().toLowerCase(Locale.ROOT).contains(q))
                b.append(s.name()).append(" [ok=").append(s.successes()).append(", fail=").append(s.failures()).append("] score=").append(String.format(Locale.ROOT, "%.2f", s.score())).append(" ").append(s.recipe()).append('\n');
        });
        return b.length() == 0 ? "none" : b.toString();
    }

    private static void load(CompanionEntity c) {
        if (c == null || !(c.level() instanceof ServerLevel level)) return;
        Map<String, Skill> existing = DATA.computeIfAbsent(c.getUUID(), k -> new ConcurrentHashMap<>());
        if (!existing.isEmpty()) return;
        ListTag list = CompanionWorldData.get(level).getSkills(c.getUUID());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            String name = t.getString("name");
            if (name.isBlank()) continue;
            existing.put(name, new Skill(name, t.getString("recipe"), t.getInt("successes"), t.getInt("failures"), t.getDouble("score"), t.getLong("lastUsed")));
        }
    }

    private static void persist(CompanionEntity c) {
        if (c == null || !(c.level() instanceof ServerLevel level)) return;
        Map<String, Skill> m = DATA.get(c.getUUID());
        if (m == null) return;
        ListTag list = new ListTag();
        for (Skill s : m.values()) {
            CompoundTag t = new CompoundTag();
            t.putString("name", s.name()); t.putString("recipe", s.recipe());
            t.putInt("successes", s.successes()); t.putInt("failures", s.failures());
            t.putDouble("score", s.score()); t.putLong("lastUsed", s.lastUsed());
            list.add(t);
        }
        CompanionWorldData.get(level).saveSkills(c.getUUID(), list);
    }

    private SkillLibrary() {}
}