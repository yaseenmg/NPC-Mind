package com.yaseen.npcmind.advanced;

import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Server-side early-warning system based only on real loaded entities/environment. */
public final class ThreatMonitor {
    private static final Map<UUID,Long> LAST_WARNING=new ConcurrentHashMap<>();
    public static void tick(ServerPlayer p, CompanionEntity c){
        if(p==null||c==null||!c.isAlive()||p.level()!=c.level())return;
        Monster threat=null; double best=Double.MAX_VALUE;
        for(Monster m:p.serverLevel().getEntitiesOfClass(Monster.class,p.getBoundingBox().inflate(18),x->x.isAlive())){
            double d=p.distanceToSqr(m); if(d>=best)continue;
            double dot=(m.getX()-p.getX())*p.getLookAngle().x+(m.getZ()-p.getZ())*p.getLookAngle().z;
            boolean behind=dot<0.0;
            if(m instanceof Creeper && behind && d<18*18){threat=m;best=d;}
        }
        if(threat==null)return;
        long now=p.serverLevel().getGameTime(); long last=LAST_WARNING.getOrDefault(p.getUUID(),Long.MIN_VALUE);
        if(now-last<200)return;
        LAST_WARNING.put(p.getUUID(),now);
        String msg="text: status update "+String.format(java.util.Locale.ROOT,"%.1f",Math.sqrt(best))+" text.";
        p.sendSystemMessage(net.minecraft.network.chat.Component.literal("<"+"AI Companion"+"> "+msg));
        c.getCompanionState().dangerEvent();
    }
    public static void clear(UUID id){if(id!=null)LAST_WARNING.remove(id);}
    private ThreatMonitor(){}
}
