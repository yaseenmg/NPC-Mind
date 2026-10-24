package com.yaseen.npcmind.autonomy;

import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

/** Detects real loaded block entities that expose Forge Energy and reports low-energy systems. */
public final class MaintenanceController {
    public static String scan(CompanionEntity c,int radius){
        if(!(c.level() instanceof ServerLevel l))return "ERROR not_server";radius=Math.max(2,Math.min(32,radius));int total=0,low=0;StringBuilder b=new StringBuilder();
        for(int x=-radius;x<=radius;x++)for(int y=-radius;y<=radius;y++)for(int z=-radius;z<=radius;z++){var be=l.getBlockEntity(c.blockPosition().offset(x,y,z));if(be==null)continue;var cap=be.getCapability(ForgeCapabilities.ENERGY);if(!cap.isPresent())continue;IEnergyStorage e=cap.orElse(null);if(e==null)continue;total++;int max=e.getMaxEnergyStored();int now=e.getEnergyStored();if(max>0&&now*100L/max<15){low++;b.append(be.getBlockPos()).append("=").append(now).append('/').append(max).append(';');}}
        return "RESULT energy_nodes="+total+" low_energy="+low+" details="+b;
    }
    private MaintenanceController(){}
}