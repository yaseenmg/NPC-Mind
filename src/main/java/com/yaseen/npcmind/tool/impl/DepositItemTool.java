package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.security.WorldActionSecurity;
import com.yaseen.npcmind.knowledge.ModpackKnowledge;
import com.yaseen.npcmind.tool.AITool;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

public final class DepositItemTool implements AITool {
    public String name(){return "deposit_item";}
    public String description(){return "Move real items from the companion inventory into a real Container block at an absolute position.";}
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
        if(!(c.level().getBlockEntity(pos) instanceof Container dst))return "ERROR no real Container at "+pos;
        int remaining=count;
        for(int i=0;i<c.getCompanionInventory().getContainerSize()&&remaining>0;i++){
            ItemStack src=c.getCompanionInventory().getItem(i);
            if(src.isEmpty()||src.getItem()!=item)continue;
            int take=Math.min(remaining,src.getCount());
            ItemStack moving=src.copy(); moving.setCount(take);
            ItemStack left = insertIntoContainer(dst, moving);
            int inserted=take-left.getCount();
            if(inserted>0){src.shrink(inserted);remaining-=inserted;}
        }
        c.getCompanionInventory().setChanged(); dst.setChanged();
        int moved = count - remaining;
        if (moved <= 0) return "FAILED no_items_deposited item="+id+" requested="+count;
        return remaining > 0 ? "PARTIAL deposited="+moved+" requested="+count+" item="+id+" remaining="+remaining
                : "SUCCESS deposited="+moved+" item="+id;
    }
    /** Insert into a vanilla Container using the 1.20.1 Container API. */
    private static ItemStack insertIntoContainer(Container dst, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int i = 0; i < dst.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack slot = dst.getItem(i);
            if (slot.isEmpty()) continue;
            if (!ItemStack.isSameItemSameTags(slot, remaining) || !dst.canPlaceItem(i, remaining)) continue;
            int max = Math.min(dst.getMaxStackSize(), slot.getMaxStackSize());
            int move = Math.min(remaining.getCount(), Math.max(0, max - slot.getCount()));
            if (move > 0) {
                slot.grow(move);
                remaining.shrink(move);
                dst.setItem(i, slot);
            }
        }
        for (int i = 0; i < dst.getContainerSize() && !remaining.isEmpty(); i++) {
            if (!dst.getItem(i).isEmpty() || !dst.canPlaceItem(i, remaining)) continue;
            int move = Math.min(remaining.getCount(), Math.min(dst.getMaxStackSize(), remaining.getMaxStackSize()));
            ItemStack placed = remaining.copy();
            placed.setCount(move);
            dst.setItem(i, placed);
            remaining.shrink(move);
        }
        return remaining;
    }

}
