package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import com.yaseen.npcmind.advanced.BodyguardController;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.UUID;

/**
 * Player-directed friendly combat. There is intentionally no owner-protection rule here:
 * the player may ask the companion to spar with them or attack a specific creature.
 */
public final class CombatTargetTool implements AITool {
    public String name(){ return "combat_target"; }
    public String description(){
        return "Player-directed combat. mode=owner means fight/spar with the player; " +
                "mode=nearest_hostile attacks the nearest hostile monster; mode=target attacks target_uuid; " +
                "mode=stop ends explicit combat. This is a friend interaction, so owner targeting is allowed.";
    }
    public String schema(){
        // The enum MUST list every value execute() accepts, otherwise ToolContract
        // rejects valid calls (player/spar/cancel) with invalid_enum before execution.
        return "{\"mode\":\"owner|player|spar|nearest_hostile|target|stop|cancel\",\"target_uuid\":\"optional entity UUID\"}";
    }

    public String execute(CompanionEntity c, ServerPlayer p, String a){
        String mode = JsonArgs.string(a,"mode");
        if (mode.isBlank()) mode = "nearest_hostile";

        if (mode.equalsIgnoreCase("stop") || mode.equalsIgnoreCase("cancel") || mode.equalsIgnoreCase("text")) {
            c.setFriendlyCombat(false);
            c.setTarget(null);
            c.setFollowEnabled(false);
            c.getNavigation().stop();
            return "SUCCESS combat_stopped";
        }

        LivingEntity target = null;
        if (mode.equalsIgnoreCase("owner") || mode.equalsIgnoreCase("player") || mode.equalsIgnoreCase("spar")) {
            target = p;
        } else if (mode.equalsIgnoreCase("target")) {
            String raw = JsonArgs.string(a, "target_uuid");
            try {
                UUID id = UUID.fromString(raw);
                Entity e = ((ServerLevel) c.level()).getEntity(id);
                if (e instanceof LivingEntity living && living.isAlive() && e != c) target = living;
            } catch (IllegalArgumentException ignored) {}
            if (target == null) return "FAILED target_uuid_not_found_or_not_living";
        } else {
            target = c.level().getEntitiesOfClass(LivingEntity.class,
                    new AABB(c.blockPosition()).inflate(16),
                    e -> e != c && e.isAlive() && e instanceof Monster)
                    .stream().min(Comparator.comparingDouble(c::distanceToSqr)).orElse(null);
        }

        if (target == null) return "FAILED no_combat_target";

        // Explicit player combat wins over bodyguard/follow behavior until stopped or target dies.
        BodyguardController.clear(c.getUUID());
        c.setFriendlyCombat(true);
        c.setTarget(target);
        c.setFollowEnabled(false);
        c.getNavigation().stop();
        c.getLookControl().setLookAt(target,30,30);
        return "STARTED combat target=" + target.getUUID() + " mode=" + mode + " friendly=true";
    }
}
