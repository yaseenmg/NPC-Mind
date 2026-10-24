package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.security.WorldActionSecurity;
import com.yaseen.npcmind.tool.AITool;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

public final class TakeFromContainerTool implements AITool {
    public String name(){return "take_from_container";}
    public String description(){return "Move real items from a real Container block into the companion's persistent inventory.";}
    public String schema(){return "{\"x\":number,\"y\":number,\"z\":number,\"item_id\":string,\"count\":number}";}
    public String execute(CompanionEntity c, ServerPlayer p, String a){
        Integer x=MoveToTool.num(a,"x"),y=MoveToTool.num(a,"y"),z=MoveToTool.num(a,"z"),count=MoveToTool.num(a,"count");
        String id=MoveToTool.str(a,"item_id");
        if(x==null||y==null||z==null||count==null||id==null||count<=0)return "ERROR arguments require x,y,z,item_id,count";
        ResourceLocation rl=ResourceLocation.tryParse(id); if(rl==null)return "ERROR invalid item_id";
        var item=ForgeRegistries.ITEMS.getValue(rl); if(item==null)return "ERROR unknown item_id "+id;
        BlockPos pos=new BlockPos(x,y,z);
        if(!WorldActionSecurity.canAccessContainer(c,p,pos)) return "DENIED container interaction requires both owner and companion within 8 blocks and permission to interact";
        if(!c.level().isLoaded(pos))return "ERROR target is not loaded";
        if(!(c.level().getBlockEntity(pos) instanceof Container src))return "ERROR no real Container at "+pos;
        int remaining=count;
        for(int i=0;i<src.getContainerSize()&&remaining>0;i++){
            ItemStack stack=src.getItem(i);
            if(stack.isEmpty()||stack.getItem()!=item)continue;
            int take=Math.min(remaining,stack.getCount());
            ItemStack moving=stack.copy(); moving.setCount(take);
            ItemStack left=com.yaseen.npcmind.crafting.InventoryOps.addAndReturnRemainder(c.getCompanionInventory(), moving);
            int inserted=take-left.getCount();
            if(inserted>0){stack.shrink(inserted);remaining-=inserted;}
        }
        c.getCompanionInventory().setChanged(); src.setChanged();
        int moved = count - remaining;
        if (moved <= 0) return "FAILED no_items_taken item="+id+" requested="+count;
        return remaining > 0 ? "PARTIAL taken="+moved+" requested="+count+" item="+id+" remaining="+remaining
                : "SUCCESS taken="+moved+" item="+id;
    }
}