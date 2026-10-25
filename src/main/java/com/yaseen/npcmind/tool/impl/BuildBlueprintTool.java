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

/** Builds a tiny user-supplied ASCII blueprint against the real world. '#' means place the selected block. */
public final class BuildBlueprintTool implements AITool {
    public String name(){return "build_blueprint";}
    public String description(){return "Build a simple ASCII blueprint in the real world. layout uses rows separated by /; # means place the selected real block and . means leave the world untouched.";}
    public String schema(){return "{\"block\":\"minecraft:stone\",\"layout\":\"#####/#...#/#####\",\"x\":0,\"y\":0,\"z\":0}";}
    public String execute(CompanionEntity c, ServerPlayer p, String a){
        if(!c.getPermissions().allowed(ActionPermissions.Action.BUILD)) return "DENIED permission=build";
        String id=str(a,"block"), layout=str(a,"layout"); Integer x=MoveToTool.num(a,"x"), y=MoveToTool.num(a,"y"), z=MoveToTool.num(a,"z");
        if(id.isBlank()||layout.isBlank()||x==null||y==null||z==null)return "FAILED block,layout,x,y,z are required";
        ResourceLocation resourceId=ResourceLocation.tryParse(id);
        if(resourceId==null)return "FAILED invalid block id="+id;
        Block block=ForgeRegistries.BLOCKS.getValue(resourceId); if(block==null)return "FAILED unknown live block="+id;
        String[] rows=layout.split("/",-1); if(rows.length>16)return "FAILED blueprint height > 16";
        BlockPos base=new BlockPos(x,y,z); if(c.distanceToSqr(base.getX()+.5,base.getY()+.5,base.getZ()+.5)>100) return "FAILED build target is more than 10 blocks away; navigate there first";
        if (!(c.getOwnerPlayer() instanceof ServerPlayer owner)) return "DENIED owner is not online; world mutation is blocked";
        int placed=0, requested=0;
        for(int row=0;row<rows.length;row++){
            if(rows[row].length()>16)return "FAILED blueprint width > 16";
            for(int col=0;col<rows[row].length();col++) if(rows[row].charAt(col)=='#'){
                requested++; BlockPos pos=base.offset(col,rows.length-1-row,0);
                var state=c.level().getBlockState(pos); if(state.canBeReplaced() && block.defaultBlockState().canSurvive(c.level(),pos) && hasOne(c.getCompanionInventory(),block) && hasUnreservedBlock(c,block)){if(!WorldActionSecurity.canPlaceBlock(c,owner,pos)) continue; if(c.level().setBlockAndUpdate(pos,block.defaultBlockState())){takeUnreservedOne(c,block);placed++;}}
            }
        }
        if(placed>0){
            c.getSkills().add(SkillProgression.Skill.BUILDING,placed*3);
            c.getCompanionState().positiveEvent();
            if (placed < requested) return "PARTIAL blueprint placed="+placed+" requested_marks="+requested+" block="+id;
            return "SUCCESS blueprint placed="+placed+" requested_marks="+requested+" block="+id;
        }
        return "FAILED blueprint_no_blocks_placed requested_marks="+requested+" block="+id;
    }
    private static boolean hasUnreservedBlock(CompanionEntity c, Block b){ for(int i=0;i<c.getCompanionInventory().getContainerSize();i++){ ItemStack st=c.getCompanionInventory().getItem(i); if(!st.isEmpty() && Block.byItem(st.getItem())==b && ResourceLedger.canConsume(c,st,1)) return true; } return false; }

    private static boolean hasOne(net.minecraft.world.Container inv,Block b){for(int i=0;i<inv.getContainerSize();i++){ItemStack st=inv.getItem(i);if(!st.isEmpty()&&Block.byItem(st.getItem())==b)return true;}return false;}
    private static boolean takeUnreservedOne(CompanionEntity c,Block b){for(int i=0;i<c.getCompanionInventory().getContainerSize();i++){ItemStack s=c.getCompanionInventory().getItem(i);if(!s.isEmpty()&&Block.byItem(s.getItem())==b&&ResourceLedger.canConsume(c,s,1)){s.shrink(1);if(s.isEmpty())c.getCompanionInventory().setItem(i,ItemStack.EMPTY);return true;}}return false;}
    private static String str(String j,String k){return JsonArgs.string(j,k);}
}
