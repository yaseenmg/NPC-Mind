package com.yaseen.npcmind.tool.impl;

import net.minecraft.world.phys.Vec3;
import com.mojang.datafixers.util.Pair;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.advanced.StructureTravelController;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.chunk.LevelChunk;
import java.util.*;

/** Locates real generated structures using the live structure registry and chunk generator. */
public final class StructureSearchTool implements AITool {
    private record Spec(String id, String arabic, net.minecraft.resources.ResourceKey<Structure> key) {}
    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();
    private static final Map<String, List<net.minecraft.resources.ResourceKey<Structure>>> VARIANTS = new HashMap<>();
    private static final Map<UUID, BlockPos> LAST = new HashMap<>();
    private record PendingDiscovery(String id, int radius) {}
    private static final Map<UUID, PendingDiscovery> PENDING = new java.util.concurrent.ConcurrentHashMap<>();

    static {
        add("village", "village", BuiltinStructures.VILLAGE_PLAINS, BuiltinStructures.VILLAGE_DESERT, BuiltinStructures.VILLAGE_SAVANNA, BuiltinStructures.VILLAGE_SNOWY, BuiltinStructures.VILLAGE_TAIGA);
        add("stronghold", "stronghold", BuiltinStructures.STRONGHOLD);
        add("mineshaft", "mineshaft", BuiltinStructures.MINESHAFT, BuiltinStructures.MINESHAFT_MESA);
        add("ancient_city", "ancient city", BuiltinStructures.ANCIENT_CITY);
        add("fortress", "fortress", BuiltinStructures.FORTRESS);
        add("bastion", "bastion", BuiltinStructures.BASTION_REMNANT);
        add("end_city", "end city", BuiltinStructures.END_CITY);
        add("mansion", "mansion", BuiltinStructures.WOODLAND_MANSION);
        add("monument", "monument", BuiltinStructures.OCEAN_MONUMENT);
        add("jungle_temple", "jungle temple", BuiltinStructures.JUNGLE_TEMPLE);
        add("desert_pyramid", "desert pyramid", BuiltinStructures.DESERT_PYRAMID);
        add("igloo", "igloo", BuiltinStructures.IGLOO);
        add("swamp_hut", "swamp hut", BuiltinStructures.SWAMP_HUT);
        add("outpost", "outpost", BuiltinStructures.PILLAGER_OUTPOST);
        add("shipwreck", "shipwreck", BuiltinStructures.SHIPWRECK, BuiltinStructures.SHIPWRECK_BEACHED);
        add("ocean_ruin", "ocean ruin", BuiltinStructures.OCEAN_RUIN_COLD, BuiltinStructures.OCEAN_RUIN_WARM);
        add("ruined_portal", "ruined portal", BuiltinStructures.RUINED_PORTAL_STANDARD, BuiltinStructures.RUINED_PORTAL_DESERT, BuiltinStructures.RUINED_PORTAL_JUNGLE, BuiltinStructures.RUINED_PORTAL_SWAMP, BuiltinStructures.RUINED_PORTAL_MOUNTAIN, BuiltinStructures.RUINED_PORTAL_OCEAN, BuiltinStructures.RUINED_PORTAL_NETHER);
        add("trail_ruins", "trail ruins", BuiltinStructures.TRAIL_RUINS);
        add("buried_treasure", "buried treasure", BuiltinStructures.BURIED_TREASURE);
        add("nether_fossil", "nether fossil", BuiltinStructures.NETHER_FOSSIL);
    }

    private static void add(String id, String ar, net.minecraft.resources.ResourceKey<Structure>... keys) {
        for (int i = 0; i < keys.length; i++) SPECS.putIfAbsent(id, new Spec(id, ar, keys[i]));
        VARIANTS.put(id, Arrays.asList(keys));
    }
    public String name() { return "search_structure"; }
    public String description() { return "Find a real generated Minecraft structure using the live server structure registry, remember it, and navigate the companion there."; }
    public String schema() { return "{\"structure\":\"village|stronghold|mineshaft|fortress|bastion|end_city|ancient_city|mansion|monument|temple|shipwreck|ruined_portal|outpost|dungeon|all\",\"radius\":number(optional),\"go\":boolean(optional)}"; }

    public String execute(CompanionEntity c, ServerPlayer owner, String args) {
        String raw = JsonArgs.string(args, "structure");
        if (raw == null || raw.isBlank()) raw = "all";
        String id = normalize(raw);
        int radius = Math.max(16, Math.min(JsonArgs.integer(args, "radius", 10000), 10000));
        boolean go = !args.contains("\"go\":false") && !args.contains("\"go\" : false");
        if (!(c.level() instanceof ServerLevel level)) return "FAILED not_server_level";
        Pair<BlockPos, Holder<Structure>> found = locateLoaded(level, c.blockPosition(), id, radius);
        if (found == null) {
            PENDING.put(c.getUUID(), new PendingDiscovery(id, radius));
            com.yaseen.npcmind.advanced.ExplorationController.set(c, true);
            return "STARTED structure_discovery structure=" + id + " radius=" + radius + " mode=loaded_chunks_only";
        }
        BlockPos pos = found.getFirst();
        String actual = found.getSecond().unwrapKey().map(k -> k.location().toString()).orElse(id);
        LAST.put(c.getUUID(), pos);
        c.getWorldMemory().observe("structure", level.dimension().location().toString(), pos, "found=" + actual + " requested=" + id, level.getGameTime());
        String move = go ? StructureTravelController.start(c, pos, id) : "RESULT no_navigation";
        return "SUCCESS structure=" + id + " actual=" + actual + " x=" + pos.getX() + " y=" + pos.getY() + " z=" + pos.getZ()
                + " distance=" + Math.round(Math.sqrt(c.position().distanceToSqr(Vec3.atCenterOf(pos)))) + " | " + move;
    }

    public static void pollDiscovery(CompanionEntity c) {
        if ((c.level().getGameTime() % 20L) != 0L) return;
        PendingDiscovery pending = PENDING.get(c.getUUID());
        if (pending == null || !(c.level() instanceof ServerLevel level)) return;
        Pair<BlockPos, Holder<Structure>> found = locateLoaded(level, c.blockPosition(), pending.id(), pending.radius());
        if (found == null) return;
        PENDING.remove(c.getUUID());
        com.yaseen.npcmind.advanced.ExplorationController.set(c, false);
        BlockPos pos = found.getFirst(); LAST.put(c.getUUID(), pos);
        c.getWorldMemory().observe("structure", level.dimension().location().toString(), pos,
                "discovered_by_exploration=" + found.getSecond().unwrapKey().map(k -> k.location().toString()).orElse(pending.id()), level.getGameTime());
        String move = StructureTravelController.start(c, pos, pending.id());
        c.say("Found " + pending.id() + ". " + move);
    }

    private static Pair<BlockPos, Holder<Structure>> locateLoaded(ServerLevel level, BlockPos origin, String id, int radius) {
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        List<net.minecraft.resources.ResourceKey<Structure>> keys = VARIANTS.get(id);
        if (keys == null && "dungeon".equals(id)) keys = VARIANTS.get("mineshaft");
        if (keys == null && "temple".equals(id)) keys = List.of(BuiltinStructures.DESERT_PYRAMID, BuiltinStructures.JUNGLE_TEMPLE, BuiltinStructures.IGLOO, BuiltinStructures.SWAMP_HUT);
        if (keys == null && "all".equals(id)) { keys = new ArrayList<>(); for (List<net.minecraft.resources.ResourceKey<Structure>> v : VARIANTS.values()) keys.addAll(v); }
        if (keys == null) return null;
        int chunkRadius = Math.max(1, Math.min(8, (radius + 15) >> 4));
        int ocx = origin.getX() >> 4, ocz = origin.getZ() >> 4;
        Pair<BlockPos, Holder<Structure>> best = null; double bestD = Double.MAX_VALUE;
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
            int cx = ocx + dx, cz = ocz + dz;
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz); if (chunk == null) continue;
            for (var key : keys) {
                Optional<Holder.Reference<Structure>> holder = registry.getHolder(key); if (holder.isEmpty()) continue;
                var start = chunk.getStartForStructure(holder.get().value()); if (start == null || !start.isValid()) continue;
                BlockPos pos = start.getBoundingBox().getCenter(); double d = pos.distSqr(origin);
                if (d <= (double) radius * radius && d < bestD) { bestD = d; best = Pair.of(pos, holder.get()); }
            }
        }
        return best;
    }

    public static BlockPos find(CompanionEntity c, String structure, int radius) {
        if (!(c.level() instanceof ServerLevel level)) return null;
        Pair<BlockPos, Holder<Structure>> p = locate(level, c.blockPosition(), normalize(structure), Math.max(16, Math.min(radius, 10000)));
        if (p == null) return null;
        LAST.put(c.getUUID(), p.getFirst());
        c.getWorldMemory().observe("structure", level.dimension().location().toString(), p.getFirst(),
                "quest_search=" + structure + " actual=" + p.getSecond().unwrapKey().map(k -> k.location().toString()).orElse("unknown"), level.getGameTime());
        return p.getFirst();
    }
    public static BlockPos last(CompanionEntity c) { return LAST.get(c.getUUID()); }

    private static Pair<BlockPos, Holder<Structure>> locate(ServerLevel level, BlockPos origin, String id, int radius) {
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        List<net.minecraft.resources.ResourceKey<Structure>> keys = VARIANTS.get(id);
        if (keys == null && id.contains(":")) {
            try {
                net.minecraft.resources.ResourceLocation rl = new net.minecraft.resources.ResourceLocation(id);
                Optional<Holder.Reference<Structure>> h = registry.getHolder(net.minecraft.resources.ResourceKey.create(Registries.STRUCTURE, rl));
                if (h.isPresent()) keys = List.of(h.get().unwrapKey().orElseThrow());
            } catch (IllegalArgumentException | IllegalStateException ignored) {
            } catch (RuntimeException ex) { com.yaseen.npcmind.NPCMind.LOG.warn("[NPCMind] structure registry lookup failed for {}", id, ex); }
        }
        if (keys == null && "dungeon".equals(id)) keys = VARIANTS.get("mineshaft");
        if (keys == null && "temple".equals(id)) keys = List.of(BuiltinStructures.DESERT_PYRAMID, BuiltinStructures.JUNGLE_TEMPLE, BuiltinStructures.IGLOO, BuiltinStructures.SWAMP_HUT);
        if (keys == null && "all".equals(id)) {
            keys = new ArrayList<>(); for (List<net.minecraft.resources.ResourceKey<Structure>> v : VARIANTS.values()) keys.addAll(v);
            for (var rl : registry.keySet()) { var k = net.minecraft.resources.ResourceKey.create(Registries.STRUCTURE, rl); if (!keys.contains(k)) keys.add(k); }
        }
        if (keys == null) return null;
        Pair<BlockPos, Holder<Structure>> best = null; double bestD = Double.MAX_VALUE;
        for (var key : keys) {
            Optional<Holder.Reference<Structure>> holder = registry.getHolder(key); if (holder.isEmpty()) continue;
            HolderSet<Structure> set = HolderSet.direct(holder.get());
            Pair<BlockPos, Holder<Structure>> p;
            try { p = level.getChunkSource().getGenerator().findNearestMapStructure(level, set, origin, radius, false); }
            catch (IllegalArgumentException | IllegalStateException ex) { continue; }
            catch (RuntimeException ex) { com.yaseen.npcmind.NPCMind.LOG.warn("[NPCMind] structure search failed for {}", key.location(), ex); continue; }
            if (p == null) continue;
            double d = p.getFirst().distSqr(origin); if (d < bestD) { bestD = d; best = p; }
        }
        return best;
    }

    private static String normalize(String s) {
        String x = s.toLowerCase(Locale.ROOT).trim().replace("text", "").replace(' ', '_').replace('-', '_');
        return switch (x) {
            case "village", "villages" -> "village";
            case "stronghold" -> "stronghold";
            case "mineshaft", "mine", "dungeon" -> "mineshaft";
            case "nether_fortress", "fortress" -> "fortress";
            case "bastion" -> "bastion";
            case "ancient_city" -> "ancient_city";
            case "end_city" -> "end_city";
            case "mansion", "woodland_mansion" -> "mansion";
            case "monument", "ocean_monument" -> "monument";
            case "temple" -> "temple";
            case "shipwreck" -> "shipwreck";
            case "ruined_portal" -> "ruined_portal";
            case "outpost", "pillager_outpost" -> "outpost";
            case "trail_ruins" -> "trail_ruins";
            case "trial_chambers", "trial" -> "trial_chambers";
            case "buried_treasure" -> "buried_treasure";
            case "nether_fossil" -> "nether_fossil";
            case "all", "structures" -> "all";
            default -> x;
        };
    }
}