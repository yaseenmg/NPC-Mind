package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.state.ActionPermissions;
import com.yaseen.npcmind.task.TaskManager;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Finds nearby real loaded blocks matching a keyword and queues verified mining tasks. */
public final class GatherBlockTool implements AITool {
    public String name(){ return "gather_block"; }
    public String description(){ return "Find nearby real loaded blocks whose id matches a keyword (e.g. dirt, stone, sand, gravel, glass, cobblestone, clay, wool) and mine several of them. No coordinates needed."; }
    public String schema(){ return "{\"block\":\"keyword\",\"count\":number}"; }

    public String execute(CompanionEntity c, ServerPlayer p, String a){
        if(!c.getPermissions().allowed(ActionPermissions.Action.MINE)) return "DENIED permission=mine";
        if(!(c.level() instanceof ServerLevel level)) return "ERROR not_server";
        String keyword = JsonArgs.string(a, "block");
        if(keyword == null || keyword.isBlank()) return "FAILED block keyword is required";
        keyword = keyword.toLowerCase(Locale.ROOT).trim();
        Integer req = JsonArgs.integer(a, "count");
        int count = req == null ? 6 : Math.max(1, Math.min(16, req));

        List<BlockPos> hits = new ArrayList<>();
        BlockPos base = c.blockPosition();
        for(int dx=-16; dx<=16; dx++) for(int dy=-6; dy<=6; dy++) for(int dz=-16; dz<=16; dz++){
            if(Math.abs(dx)+Math.abs(dz) > 22) continue;
            BlockPos pos = base.offset(dx,dy,dz);
            if(!level.hasChunkAt(pos)) continue;
            BlockState st = level.getBlockState(pos);
            if(!matches(st, keyword)) continue;
            hits.add(pos.immutable());
        }
        hits.sort(Comparator.comparingDouble(pos -> c.distanceToSqr(pos.getX()+0.5, pos.getY()+0.5, pos.getZ()+0.5)));
        if(hits.isEmpty()) return "FAILED no_loaded_block_nearby=" + keyword;
        TaskManager.cancelCurrentTask(c);
        int queued = 0;
        for(BlockPos pos : hits){
            String r = TaskManager.mine(c, pos);
            if(r != null && r.startsWith("STARTED")) queued++; else break;
            if(queued >= count) break;
        }
        return queued > 0 ? "STARTED gather_block block=" + keyword + " queued=" + queued : "FAILED could_not_queue=" + keyword;
    }

    private static boolean matches(BlockState st, String keyword){
        if(st.isAir() || !st.getFluidState().isEmpty() || st.is(Blocks.BEDROCK)) return false;
        ResourceLocation id = st.getBlock().builtInRegistryHolder().key().location();
        String path = id.getPath().toLowerCase(Locale.ROOT);
        if(keyword.startsWith("=")) return path.equals(keyword.substring(1));
        if(isPlayerMade(path) && !keyword.contains(denyHit(path))) return false;
        if(path.contains(keyword)) return true;
        return switch(keyword){
            case "stone" -> path.contains("stone") || path.equals("andesite") || path.equals("diorite") || path.equals("granite") || path.equals("deepslate") || path.equals("tuff");
            case "dirt" -> path.equals("dirt") || path.equals("coarse_dirt") || path.equals("rooted_dirt") || path.equals("grass_block") || path.equals("podzol") || path.equals("mycelium");
            case "sand" -> path.contains("sand");
            case "wood", "log" -> path.contains("log") || path.contains("wood") || path.contains("stem");
            case "glass" -> path.contains("glass");
            case "wool" -> path.contains("wool");
            case "leaves" -> path.contains("leaves");
            case "ice" -> path.contains("ice");
            case "ore" -> path.contains("ore");
            default -> false;
        };
    }

    private static final String[] PLAYER_MADE = {"brick", "slab", "stair", "wall", "button", "plate", "cutter", "door",
            "bars", "fence", "gate", "chest", "barrel", "furnace", "smoker", "blast", "crafting", "table", "bed",
            "sign", "ladder", "lantern", "torch", "rail", "banner", "carpet", "shelf", "anvil", "enchant", "beacon",
            "hopper", "dropper", "dispenser", "piston", "lever", "frame", "pot", "block"};
    private static boolean isPlayerMade(String path){if(path.equals("grass_block")) return false; return !denyHit(path).isEmpty();}
    private static String denyHit(String path){for(String d : PLAYER_MADE) if(path.contains(d)) return d; return "";}
}
