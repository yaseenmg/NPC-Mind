package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.security.WorldActionSecurity;
import com.yaseen.npcmind.autonomy.ResourceLedger;
import com.yaseen.npcmind.state.SkillProgression;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import com.yaseen.npcmind.state.ActionPermissions;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

public final class BuildPatternTool implements AITool {
    public String name(){return "build_pattern";}
    public String description(){return "Place a simple real block pattern from the companion inventory. Supports wall, floor, or line; every placed block is consumed from the real inventory and only replaceable air/water positions are changed.";}
    public String schema(){return "{\"block\":\"minecraft:stone\",\"pattern\":\"wall|floor|line\",\"width\":5,\"height\":3,\"length\":5,\"x\":0,\"y\":0,\"z\":0}";}
    public String execute(CompanionEntity c, ServerPlayer p, String a){
        if(!c.getPermissions().allowed(ActionPermissions.Action.BUILD)) return "DENIED permission=build";
        String id=string(a,"block"); if(id.isBlank())return "FAILED block id required";
        ResourceLocation resourceId=ResourceLocation.tryParse(id);
        if(resourceId==null)return "FAILED invalid block id="+id;
        Block b=ForgeRegistries.BLOCKS.getValue(resourceId); if(b==null)return "FAILED unknown live block="+id;
        String pattern=string(a,"pattern"); if(pattern.isBlank())pattern="wall";
        int w=clamp(MoveToTool.num(a,"width")==null?5:MoveToTool.num(a,"width"),1,16), h=clamp(MoveToTool.num(a,"height")==null?3:MoveToTool.num(a,"height"),1,16), l=clamp(MoveToTool.num(a,"length")==null?5:MoveToTool.num(a,"length"),1,16);
        Integer x=MoveToTool.num(a,"x"),y=MoveToTool.num(a,"y"),z=MoveToTool.num(a,"z"); if(x==null||y==null||z==null)return "FAILED x,y,z required";
        List<BlockPos> targets=new ArrayList<>(); BlockPos base=new BlockPos(x,y,z);
        if(pattern.equalsIgnoreCase("line")){for(int i=0;i<l;i++)targets.add(base.east(i));}
        else if(pattern.equalsIgnoreCase("floor")){for(int dx=0;dx<w;dx++)for(int dz=0;dz<l;dz++)targets.add(base.offset(dx,0,dz));}
        else {for(int dx=0;dx<w;dx++)for(int dy=0;dy<h;dy++)targets.add(base.offset(dx,dy,0));}
        if (c.distanceToSqr(base.getX()+.5, base.getY()+.5, base.getZ()+.5) > 100) return "FAILED build target is more than 10 blocks away; navigate there first";
        if (!(c.getOwnerPlayer() instanceof ServerPlayer owner)) return "DENIED owner is not online; world mutation is blocked";
        int placed=0;
        for(BlockPos pos:targets){var state=c.level().getBlockState(pos); if(state.canBeReplaced() && b.defaultBlockState().canSurvive(c.level(),pos)){ if(!hasOne(c.getCompanionInventory(),b) || !hasUnreservedBlock(c,b))break; if(!WorldActionSecurity.canPlaceBlock(c,owner,pos)) continue; if(c.level().setBlockAndUpdate(pos,b.defaultBlockState())){takeUnreservedOne(c,b);placed++;}}}
        if(placed>0){
            c.getSkills().add(SkillProgression.Skill.BUILDING,placed*3);
            c.getCompanionState().positiveEvent();
            if (placed < targets.size()) return "PARTIAL placed="+placed+" requested="+targets.size()+" block="+id+" pattern="+pattern;
            return "SUCCESS placed="+placed+" requested="+targets.size()+" block="+id+" pattern="+pattern;
        }
        return "FAILED no_blocks_placed requested="+targets.size()+" block="+id+" pattern="+pattern;
    }
    private static boolean hasUnreservedBlock(CompanionEntity c, Block b){ for(int i=0;i<c.getCompanionInventory().getContainerSize();i++){ ItemStack st=c.getCompanionInventory().getItem(i); if(!st.isEmpty() && Block.byItem(st.getItem())==b && ResourceLedger.canConsume(c,st,1)) return true; } return false; }
    private static boolean hasOne(net.minecraft.world.Container inv,Block b){for(int i=0;i<inv.getContainerSize();i++){ItemStack st=inv.getItem(i);if(!st.isEmpty()&&Block.byItem(st.getItem())==b)return true;}return false;}
    private static boolean takeUnreservedOne(CompanionEntity c,Block b){for(int i=0;i<c.getCompanionInventory().getContainerSize();i++){ItemStack s=c.getCompanionInventory().getItem(i);if(!s.isEmpty()&&Block.byItem(s.getItem())==b&&ResourceLedger.canConsume(c,s,1)){s.shrink(1);if(s.isEmpty())c.getCompanionInventory().setItem(i,ItemStack.EMPTY);return true;}}return false;}
    private static int clamp(int x,int a,int b){return Math.max(a,Math.min(b,x));}
    private static String string(String j,String k){return JsonArgs.string(j,k);}
}