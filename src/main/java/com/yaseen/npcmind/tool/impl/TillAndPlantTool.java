package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.security.WorldActionSecurity;
import com.yaseen.npcmind.state.ActionPermissions;
import com.yaseen.npcmind.state.SkillProgression;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * "text" from scratch: till nearby grass/dirt into farmland and plant the seeds/crops the companion
 * owns (wheat, carrots, potatoes, beetroot). Coordinate-free — it works on the ground around it.
 * Harvesting + replanting of grown crops is handled by {@link FarmAreaTool}.
 */
public final class TillAndPlantTool implements AITool {
    public String name(){ return "till_and_plant"; }
    public String description(){ return "Prepare farmland and plant crops around the companion with NO coordinates. It converts nearby grass/dirt to farmland and plants wheat/carrot/potato/beetroot from its own inventory. Needs seeds; a hoe is used if present."; }
    public String schema(){ return "{\"radius\":number(optional)}"; }

    public String execute(CompanionEntity c, ServerPlayer p, String a){
        if(!c.getPermissions().allowed(ActionPermissions.Action.FARM)) return "DENIED permission=farm";
        if(!(c.level() instanceof ServerLevel level)) return "ERROR not_server";
        if(!(c.getOwnerPlayer() instanceof ServerPlayer owner)) return "DENIED owner is not online; world mutation is blocked";
        Integer rad = JsonArgs.integer(a, "radius");
        int radius = rad == null ? 4 : Math.max(1, Math.min(8, rad));

        BlockPos base = c.blockPosition();
        int tilled = 0, planted = 0;
        for(BlockPos pos : BlockPos.betweenClosed(base.offset(-radius,-1,-radius), base.offset(radius,1,radius))){
            if(!level.isLoaded(pos)) continue;
            BlockState st = level.getBlockState(pos);
            BlockState above = level.getBlockState(pos.above());
            boolean tillable = st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.DIRT) || st.is(Blocks.COARSE_DIRT) || st.is(Blocks.FARMLAND);
            if(!tillable || !above.isAir()) continue;
            if(!WorldActionSecurity.canPlaceBlock(c, owner, pos)) continue;
            // Till (unless it is already farmland).
            if(!st.is(Blocks.FARMLAND)){
                if(level.setBlockAndUpdate(pos, Blocks.FARMLAND.defaultBlockState())) tilled++;
                else continue;
            }
            // Plant whatever seed we own on top of the farmland.
            Block crop = pickCropFor(c);
            if(crop == null) continue;
            if(level.setBlockAndUpdate(pos.above(), crop.defaultBlockState())){
                consumeSeedFor(c, crop);
                planted++;
            }
            if(planted >= radius * radius) break;
        }
        if(tilled > 0 || planted > 0){
            c.getSkills().add(SkillProgression.Skill.FARMING, (tilled + planted) * 2);
            c.getCompanionState().positiveEvent();
            return "SUCCESS tilled=" + tilled + " planted=" + planted;
        }
        return "FAILED no_tillable_ground_or_no_seeds radius=" + radius;
    }

    /** Choose a crop block the companion can actually plant from the seeds it holds. */
    private static Block pickCropFor(CompanionEntity c){
        if(hasItem(c, Items.WHEAT_SEEDS)) return Blocks.WHEAT;
        if(hasItem(c, Items.CARROT)) return Blocks.CARROTS;
        if(hasItem(c, Items.POTATO)) return Blocks.POTATOES;
        if(hasItem(c, Items.BEETROOT_SEEDS)) return Blocks.BEETROOTS;
        return null;
    }

    private static void consumeSeedFor(CompanionEntity c, Block crop){
        if(crop == Blocks.WHEAT) take(c, Items.WHEAT_SEEDS);
        else if(crop == Blocks.CARROTS) take(c, Items.CARROT);
        else if(crop == Blocks.POTATOES) take(c, Items.POTATO);
        else if(crop == Blocks.BEETROOTS) take(c, Items.BEETROOT_SEEDS);
    }

    private static boolean hasItem(CompanionEntity c, net.minecraft.world.item.Item item){
        for(int i=0;i<c.getCompanionInventory().getContainerSize();i++){
            ItemStack s = c.getCompanionInventory().getItem(i);
            if(!s.isEmpty() && s.getItem() == item) return true;
        }
        return false;
    }

    private static void take(CompanionEntity c, net.minecraft.world.item.Item item){
        for(int i=0;i<c.getCompanionInventory().getContainerSize();i++){
            ItemStack s = c.getCompanionInventory().getItem(i);
            if(!s.isEmpty() && s.getItem() == item){ c.getCompanionInventory().removeItem(i, 1); return; }
        }
    }
}
