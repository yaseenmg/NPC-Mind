package com.yaseen.npcmind.advanced;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.state.WorldMemory;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.block.Blocks;

/** Converts only live loaded world observations into persistent memory. */
public final class WorldObservation {
    public static void tick(CompanionEntity c){
        if(c.level().isClientSide()||c.tickCount%40!=0||!(c.level() instanceof ServerLevel l))return;
        BlockPos p=c.blockPosition(); String dim=l.dimension().location().toString();
        c.getWorldMemory().observe("location",dim,p,"companion position",l.getGameTime());
        int villagers=l.getEntitiesOfClass(Villager.class,c.getBoundingBox().inflate(24),e->e.isAlive()).size();
        int monsters=l.getEntitiesOfClass(Monster.class,c.getBoundingBox().inflate(24),e->e.isAlive()).size();
        int drops=l.getEntitiesOfClass(ItemEntity.class,c.getBoundingBox().inflate(16),e->e.isAlive()).size();
        if(villagers>0)c.getWorldMemory().observe("villagers",dim,p,"count="+villagers,l.getGameTime());
        if(monsters>0)c.getWorldMemory().observe("threats",dim,p,"hostiles="+monsters,l.getGameTime());
        if(drops>0)c.getWorldMemory().observe("items",dim,p,"drops="+drops,l.getGameTime());
        if(l.getBlockState(p).is(Blocks.NETHER_PORTAL))c.getWorldMemory().observe("portal",dim,p,"nether_portal",l.getGameTime());
    }
    private WorldObservation(){}
}
