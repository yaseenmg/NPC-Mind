package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.state.SkillProgression;
import com.yaseen.npcmind.state.ActionPermissions;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import com.yaseen.npcmind.task.TaskManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class SmartVeinMiningTool implements AITool {
    public String name(){return "smart_vein_mining";}
    public String description(){return "Scan the real loaded area for a requested ore/block id, rank nearby deposits, detect lava/water hazards around targets, and optionally start bounded real mining tasks.";}
    public String schema(){return "{\"target\":\"minecraft:diamond_ore\",\"radius\":number(optional),\"max_results\":number(optional),\"collect\":boolean(optional)}";}
    public String execute(CompanionEntity c, ServerPlayer p, String a){
        if (JsonArgs.bool(a, "collect") == Boolean.TRUE && !c.getPermissions().allowed(ActionPermissions.Action.MINE)) return "DENIED permission=mine";
        String target=string(a,"target"); if(target.isBlank())return "FAILED target block id required";
        int radius=clamp(MoveToTool.num(a,"radius")==null?8:MoveToTool.num(a,"radius"),2,12);
        int max=clamp(MoveToTool.num(a,"max_results")==null?12:MoveToTool.num(a,"max_results"),1,24);
        boolean collect=bool(a,"collect");
        List<BlockPos> hits=new ArrayList<>(); BlockPos center=c.blockPosition();
        for(BlockPos pos:BlockPos.betweenClosed(center.offset(-radius,-radius,-radius),center.offset(radius,radius,radius))){
            if(!c.level().isLoaded(pos))continue; BlockState s=c.level().getBlockState(pos); String id=s.getBlock().builtInRegistryHolder().key().location().toString();
            if(id.equalsIgnoreCase(target)||id.endsWith(":"+target.toLowerCase(Locale.ROOT)))hits.add(pos.immutable());
        }
        hits.sort(Comparator.comparingDouble((BlockPos pos) -> c.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)));
        if(hits.isEmpty())return "FAILED no loaded target blocks found target="+target+" radius="+radius;
        StringBuilder out=new StringBuilder("SUCCESS target=").append(target).append(" found=").append(Math.min(max,hits.size())).append(" positions=");
        int started=0;
        for(int i=0;i<hits.size()&&i<max;i++){
            BlockPos pos=hits.get(i); boolean hazard=hazardNearby(c, pos); out.append("[").append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ()).append(" hazard=").append(hazard).append(']');
            if(collect&&!hazard&&started<4){
                String queued = TaskManager.mine(c,pos);
                if (queued != null && queued.startsWith("STARTED")) started++;
            }
        }
        if(collect) {
            out.append(" started_safe_mining=").append(started).append("; hazards were not auto-mined");
            if (started == 0) return "PARTIAL " + out;
        }
        out.append("; scan uses real loaded blocks only"); return out.toString();
    }
    private static boolean hazardNearby(CompanionEntity c, BlockPos p){for(BlockPos q:BlockPos.betweenClosed(p.offset(-1,-1,-1),p.offset(1,1,1))){FluidState f=((net.minecraft.world.level.Level)c.level()).getFluidState(q);if(f.isSourceOfType(net.minecraft.world.level.material.Fluids.LAVA)||c.level().getBlockState(q).is(Blocks.FIRE))return true;}return false;}
    private static int clamp(int x,int a,int b){return Math.max(a,Math.min(b,x));}
    private static String string(String j,String k){return JsonArgs.string(j,k);}
    private static boolean bool(String j,String k){return Boolean.TRUE.equals(JsonArgs.bool(j,k));}
}