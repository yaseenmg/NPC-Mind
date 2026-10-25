package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.security.WorldActionSecurity;
import com.yaseen.npcmind.knowledge.ModpackKnowledge;
import com.yaseen.npcmind.tool.AITool;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

public final class InspectContainerTool implements AITool {
    public String name(){return "inspect_container";}
    public String description(){return "Inspect a real container block at an absolute loaded position (chest, barrel, hopper, modded containers implementing Container).";}
    public String schema(){return "{\"x\":number,\"y\":number,\"z\":number}";}
    public String execute(CompanionEntity c, ServerPlayer p, String a){
        Integer x=MoveToTool.num(a,"x"),y=MoveToTool.num(a,"y"),z=MoveToTool.num(a,"z");
        if(x==null||y==null||z==null)return "ERROR arguments require x,y,z";
        BlockPos pos=new BlockPos(x,y,z);
        if(!WorldActionSecurity.canAccessContainer(c,p,pos)) return "DENIED container interaction requires both owner and companion within 8 blocks and permission to interact";
        if(!c.level().isLoaded(pos))return "ERROR target block is not loaded";
        if(!(c.level().getBlockEntity(pos) instanceof Container inv))return "ERROR no Container block entity at "+pos;
        StringBuilder b=new StringBuilder("REAL_CONTAINER ").append(pos).append('\n');
        for(int i=0;i<inv.getContainerSize();i++){
            ItemStack s=inv.getItem(i);
            if(!s.isEmpty())b.append(i).append('=').append(ModpackKnowledge.itemInfo(s)).append(" x").append(s.getCount()).append('\n');
        }
        return b.toString();
    }
}
