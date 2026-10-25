package com.yaseen.npcmind.autonomy;

import com.yaseen.npcmind.agent.AgentEngine;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.advanced.ExplorationController;
import com.yaseen.npcmind.advanced.BodyguardController;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Real autonomous scheduler. It periodically gives the LLM a chance to observe and act without a player command. */
public final class AutonomyController {
    private static final Map<UUID,Long> LAST=new ConcurrentHashMap<>();
    private static final long THINK_INTERVAL=600L;
    public static void tick(CompanionEntity c){
        if(c.level().isClientSide()||!c.getAutonomy().routine())return;
        // Combat has absolute priority over autonomous reasoning. The vanilla goal system
        // also uses a target as the source of move/attack behavior, so never enqueue a
        // second autonomous decision while that target is alive.
        if(c.getTarget() != null && c.getTarget().isAlive()) return;
        if(!(c.getOwnerPlayer() instanceof ServerPlayer p))return;
        if(AgentEngine.isBusy(c.getUUID()))return;
        long now=p.serverLevel().getGameTime();
        // Deterministic routing keeps autonomy useful even when the Groq service is unavailable
        // or a small model refuses to emit a valid function call.
        if (AutonomyActionRouter.tick(c, p)) return;
        if(now-LAST.getOrDefault(c.getUUID(),Long.MIN_VALUE)<THINK_INTERVAL)return;
        LAST.put(c.getUUID(),now);
        c.getAutonomy().routineDone("autonomous_reasoning",now);
        // Mode-specific low-level controllers act continuously; the LLM supplies the higher-level choice.
        // Only ever ENABLE a controller from the mode; never switch off something the player turned on manually.
        switch(c.getAutonomy().mode()){
            case EXPLORER -> ExplorationController.set(c,true);
            case BODYGUARD -> BodyguardController.set(c,true);
            default -> { }
        }
        AgentEngine.runAutonomousAsync(c,p);
    }
    public static void clear(UUID owner){LAST.remove(owner); AutonomyActionRouter.clear(owner);}
    private AutonomyController(){}
}
