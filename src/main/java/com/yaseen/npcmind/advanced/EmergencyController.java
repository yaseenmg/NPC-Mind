package com.yaseen.npcmind.advanced;

import com.yaseen.npcmind.autonomy.NavigationArbiter;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.task.TaskManager;
import com.yaseen.npcmind.state.SkillProgression;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Monster;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Owner rescue behavior based only on live health/threat/inventory. */
public final class EmergencyController {
    private static final Map<UUID,Long> LAST=new ConcurrentHashMap<>();
    public static void tick(CompanionEntity c){
        if(c.level().isClientSide()||c.tickCount%10!=0)return;
        if(!(c.getOwnerPlayer() instanceof ServerPlayer p))return;
        if(c.getTarget()!=null && c.getTarget().isAlive())return;
        if(p.getHealth()>p.getMaxHealth()*.35)return;
        var target=p.serverLevel().getEntitiesOfClass(Monster.class,p.getBoundingBox().inflate(10),e->e.isAlive() && e.getTarget()==p)
                .stream().min((a,b)->Double.compare(p.distanceToSqr(a),p.distanceToSqr(b))).orElse(null);
        if(target==null)return;
        long now=p.serverLevel().getGameTime(); if(now-LAST.getOrDefault(p.getUUID(),Long.MIN_VALUE)<100)return; LAST.put(p.getUUID(),now);
        c.getCompanionState().dangerEvent(); c.getWorldMemory().failure("owner emergency: hp="+p.getHealth()+" attacking_threat="+target.getType().builtInRegistryHolder().key().location());
        TaskManager.cancelCurrentTask(c); c.setTarget(target); NavigationArbiter.request(c, target, "emergency", 900, 1.25);
        c.say("The request could not be completed. Please try again.");
        c.getSkills().add(SkillProgression.Skill.SURVIVAL,2);
    }
    public static void clear(UUID id){LAST.remove(id);}
    private EmergencyController(){}
}