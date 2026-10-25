package com.yaseen.npcmind.autonomy;

import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;

/** Reads real inventory counts and applies persistent reservations without inventing stock. */
public final class ResourceLedger {
    public static int count(CompanionEntity c,String id){
        Item item=ForgeRegistries.ITEMS.getValue(net.minecraft.resources.ResourceLocation.tryParse(id)); if(item==null)return 0;
        int n=0;for(int i=0;i<c.getCompanionInventory().getContainerSize();i++){ItemStack s=c.getCompanionInventory().getItem(i);if(s.is(item))n+=s.getCount();}return n;
    }
    public static int available(CompanionEntity c,String id){return Math.max(0,count(c,id)-c.getAutonomy().reserved(id));}
    public static boolean canConsume(CompanionEntity c, net.minecraft.world.item.ItemStack stack, int amount) {
        if (stack == null || stack.isEmpty() || amount <= 0) return false;
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return available(c, id) >= amount;
    }

    public static String snapshot(CompanionEntity c){StringBuilder b=new StringBuilder();for(int i=0;i<c.getCompanionInventory().getContainerSize();i++){ItemStack s=c.getCompanionInventory().getItem(i);if(!s.isEmpty()){String id=ForgeRegistries.ITEMS.getKey(s.getItem())==null?s.getHoverName().getString():ForgeRegistries.ITEMS.getKey(s.getItem()).toString();b.append(id).append('=').append(count(c,id)).append(" available=").append(available(c,id)).append(';');}}return b.toString();}
    private ResourceLedger(){}
}
