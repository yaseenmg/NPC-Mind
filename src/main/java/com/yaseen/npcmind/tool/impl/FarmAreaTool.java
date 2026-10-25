package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.security.WorldActionSecurity;
import com.yaseen.npcmind.state.ActionPermissions;
import com.yaseen.npcmind.state.SkillProgression;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.ForgeHooks;

/** Real vanilla crop harvesting/replanting around the companion. No simulated farm state. */
public final class FarmAreaTool implements AITool {
    public String name(){return "farm_area";}
    public String description(){return "Harvest mature real vanilla wheat, carrots, potatoes and beetroot crops nearby, then replant using real seeds/items from the companion inventory.";}
    public String schema(){return "{\"radius\":number(optional),\"replant\":boolean(optional)}";}
    public String execute(CompanionEntity c, ServerPlayer p, String a){
        if(!c.getPermissions().allowed(ActionPermissions.Action.FARM)) return "DENIED permission=farm";
        int radius=Math.max(1,Math.min(8,MoveToTool.num(a,"radius")==null?5:MoveToTool.num(a,"radius")));
        Boolean rb=JsonArgs.bool(a,"replant"); boolean replant=rb==null||rb;
        if (!(c.getOwnerPlayer() instanceof ServerPlayer owner)) return "DENIED owner is not online; world mutation is blocked";
        int harvested=0,replanted=0;
        BlockPos base=c.blockPosition();
        for(BlockPos pos:BlockPos.betweenClosed(base.offset(-radius,-2,-radius),base.offset(radius,2,radius))){
            BlockState state=c.level().getBlockState(pos);
            if(!(state.getBlock() instanceof CropBlock crop)||!crop.isMaxAge(state)) continue;
            if(!WorldActionSecurity.canInteractWithBlock(c,owner,pos)) continue;
            if (ForgeHooks.onBlockBreakEvent(c.level(), owner.gameMode.getGameModeForPlayer(), owner, pos) == -1) continue;
            boolean changed=c.level().destroyBlock(pos,true,c);
            if(!changed) continue;
            harvested++;
            if(replant){
                ItemStack seed=findSeed(c,state.getBlock());
                if(!seed.isEmpty()){
                    if (WorldActionSecurity.canPlaceBlock(c, owner, pos) && c.level().setBlockAndUpdate(pos,crop.getStateForAge(0))) {
                        seed.shrink(1);
                        replanted++;
                    }
                }
            }
        }
        if(harvested>0){
            c.getSkills().add(SkillProgression.Skill.FARMING,harvested*3);
            c.enablePickup(200);
            return "SUCCESS harvested="+harvested+" replanted="+replanted+" radius="+radius;
        }
        return "FAILED no_mature_vanilla_crops_harvested radius="+radius;
    }
    private static ItemStack findSeed(CompanionEntity c, net.minecraft.world.level.block.Block block){
        net.minecraft.world.item.Item wanted=block==Blocks.WHEAT?Items.WHEAT_SEEDS:block==Blocks.CARROTS?Items.CARROT:block==Blocks.POTATOES?Items.POTATO:block==Blocks.BEETROOTS?Items.BEETROOT:null;
        if(wanted==null)return ItemStack.EMPTY;
        for(int i=0;i<c.getCompanionInventory().getContainerSize();i++){ItemStack s=c.getCompanionInventory().getItem(i);if(s.is(wanted))return s;}
        return ItemStack.EMPTY;
    }
}
