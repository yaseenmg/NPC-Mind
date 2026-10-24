package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.state.ActionPermissions;
import com.yaseen.npcmind.task.TaskManager;
import com.yaseen.npcmind.tool.AITool;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Finds real loaded log blocks near the companion and queues their verified mining tasks. */
public final class GatherWoodTool implements AITool {
    public String name(){ return "gather_wood"; }
    public String description(){ return "Find nearby real loaded tree logs and mine several of them with verified block-state results."; }
    public String schema(){ return "{\"count\":number(optional)}"; }
    public String execute(CompanionEntity c, ServerPlayer p, String a){
        if(!c.getPermissions().allowed(ActionPermissions.Action.MINE)) return "DENIED permission=mine";
        if(!(c.level() instanceof ServerLevel level)) return "ERROR not_server";
        int count = com.yaseen.npcmind.tool.JsonArgs.integer(a,"count") == null ? 8 : Math.max(1, Math.min(8, com.yaseen.npcmind.tool.JsonArgs.integer(a,"count")));
        List<BlockPos> logs = new ArrayList<>();
        BlockPos base=c.blockPosition();
        for(int dx=-20;dx<=20;dx++) for(int dy=-8;dy<=8;dy++) for(int dz=-20;dz<=20;dz++) {
            if(Math.abs(dx)+Math.abs(dz)>24) continue;
            BlockPos pos=base.offset(dx,dy,dz);
            if(level.hasChunkAt(pos) && level.getBlockState(pos).is(BlockTags.LOGS)) logs.add(pos.immutable());
        }
        logs.sort(Comparator.comparingDouble(pos -> c.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)));
        if(logs.isEmpty()) return "FAILED no_loaded_logs_nearby";
        TaskManager.cancelCurrentTask(c);
        int queued=0;
        for(BlockPos pos:logs){
            String r=TaskManager.mine(c,pos);
            if(r.startsWith("STARTED")) queued++; else break;
            if(queued>=count) break;
        }
        return queued>0 ? "STARTED gather_wood queued="+queued : "FAILED could_not_queue_logs";
    }
}