package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.agent.InternalToolExecutor;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.security.WorldActionSecurity;
import com.yaseen.npcmind.state.ActionPermissions;
import com.yaseen.npcmind.state.SkillProgression;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Locale;

/** Places real light sources along the companion's facing direction. */
public final class LightAreaTool implements AITool {
    public String name(){ return "light_area"; }
    public String description(){ return "Light up an area by placing a light source (torch, candle or lantern) on the ground every N blocks in front of the companion. No coordinates needed. If it has no torches but owns coal/sticks, it crafts torches first."; }
    public String schema(){ return "{\"light\":\"torch|candle|lantern\",\"spacing\":number(optional),\"count\":number(optional)}"; }

    public String execute(CompanionEntity c, ServerPlayer p, String a){
        if(!c.getPermissions().allowed(ActionPermissions.Action.BUILD)) return "DENIED permission=build";
        if (!(c.getOwnerPlayer() instanceof ServerPlayer owner)) return "DENIED owner is not online; world mutation is blocked";
        String light = JsonArgs.string(a, "light");
        if(light == null || light.isBlank()) light = "torch";
        light = light.toLowerCase(Locale.ROOT).trim();
        Integer sp = JsonArgs.integer(a, "spacing");
        int spacing = sp == null ? 5 : Math.max(2, Math.min(10, sp));
        Integer cn = JsonArgs.integer(a, "count");
        int count = cn == null ? 8 : Math.max(1, Math.min(32, cn));

        int slot = PlaceBlockTool.findBlockSlot(c, light);
        boolean crafted = false;
        if(slot < 0 && (light.contains("torch") || light.contains("text"))){
            String r = InternalToolExecutor.invoke(c, p, "craft_item", "{\"output\":\"torch\",\"count\":" + count + "}", "internal-" + java.util.UUID.randomUUID()).result();
            crafted = r != null && r.startsWith("SUCCESS");
            slot = PlaceBlockTool.findBlockSlot(c, "torch");
            if(slot >= 0) light = "torch";
        }
        if(slot < 0) return "FAILED no_light_source_and_cannot_craft=" + light;
        Block block = Block.byItem(c.getCompanionInventory().getItem(slot).getItem());
        if(block == null) return "FAILED not_a_placeable_light=" + light;

        Level lvl = c.level();
        Direction facing = c.getDirection();
        BlockPos base = c.blockPosition();
        int placed = 0;
        for(int i=1; i<=count; i++){
            BlockPos ahead = base.relative(facing, i * spacing);
            BlockPos spot = airOnFloor(lvl, ahead);
            if(spot == null) continue;
            int s = PlaceBlockTool.findBlockSlot(c, light);
            if(s < 0) break;
            BlockState state = block.defaultBlockState();
            if(!state.canSurvive(lvl, spot)) continue;
            if(!WorldActionSecurity.canPlaceBlock(c, owner, spot)) continue;
            if(lvl.setBlockAndUpdate(spot, state)){
                c.getCompanionInventory().removeItem(s, 1);
                placed++;
            }
        }
        if(placed > 0){
            c.getSkills().add(SkillProgression.Skill.BUILDING, placed * 2);
            c.getCompanionState().positiveEvent();
            return "SUCCESS lit=" + placed + " light=" + light + " spacing=" + spacing + (crafted ? " crafted_torches=true" : "");
        }
        return "FAILED no_valid_spot light=" + light;
    }

    private static BlockPos airOnFloor(Level lvl, BlockPos at){
        for(int dy=1; dy>=-2; dy--){
            BlockPos pos = at.above(dy);
            if(!lvl.isLoaded(pos)) continue;
            BlockState here = lvl.getBlockState(pos);
            BlockState below = lvl.getBlockState(pos.below());
            if((here.isAir() || here.canBeReplaced()) && below.isFaceSturdy(lvl, pos.below(), Direction.UP)) return pos.immutable();
        }
        return null;
    }
}