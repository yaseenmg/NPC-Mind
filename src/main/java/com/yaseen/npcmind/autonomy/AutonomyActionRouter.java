package com.yaseen.npcmind.autonomy;

import com.yaseen.npcmind.agent.InternalToolExecutor;
import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic low-level router for autonomy. It is deliberately independent of
 * the LLM: a weak/offline model must not make autonomy completely inert.
 * One safe action is selected only after the kernel has released combat/task ownership.
 */
public final class AutonomyActionRouter {
    private static final Map<UUID, Long> NEXT_ALLOWED = new ConcurrentHashMap<>();
    private static final long COOLDOWN = 100L;

    public static boolean tick(CompanionEntity c, ServerPlayer owner) {
        if (c == null || owner == null || c.level().isClientSide() || !c.getAutonomy().routine()) return false;
        long now = owner.serverLevel().getGameTime();
        if (now < NEXT_ALLOWED.getOrDefault(c.getUUID(), Long.MIN_VALUE)) return false;
        NEXT_ALLOWED.put(c.getUUID(), now + COOLDOWN);

        if (c.getFoodLevel() <= 8 && c.startEatingFromInventory()) {
            c.getAutonomy().routineDone("eat_food", now);
            return true;
        }

        String tool;
        String args;
        switch (c.getAutonomy().mode()) {
            case MINER -> {
                tool = "gather_resources";
                args = "{\"resource\":\"stone\",\"count\":8}";
            }
            case FARMER -> {
                tool = "farm_area";
                args = "{\"radius\":6,\"replant\":true}";
            }
            case ENGINEER -> {
                tool = c.getCompanionInventory().countItem(Items.OAK_PLANKS) < 16
                        ? "gather_resources" : "inspect_companion_inventory";
                args = tool.equals("gather_resources")
                        ? "{\"resource\":\"wood\",\"count\":8}" : "{}";
            }
            case FOLLOW -> {
                tool = "follow_owner";
                args = "{\"enabled\":true}";
            }
            default -> {
                return false;
            }
        }

        String actionId = "autonomy-" + UUID.randomUUID();
        var result = InternalToolExecutor.invoke(c, owner, tool, args, actionId).result();
        c.getAutonomy().routineDone("tool=" + tool + " result=" + result, now);
        if (result != null && result.startsWith("STARTED")) {
            c.say("Status: " + tool + ".");
            return true;
        }
        return result != null && (result.startsWith("SUCCESS") || result.startsWith("RESULT"));
    }

    public static void clear(UUID id) { NEXT_ALLOWED.remove(id); }
    private AutonomyActionRouter() {}
}