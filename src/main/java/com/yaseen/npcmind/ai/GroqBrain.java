package com.yaseen.npcmind.ai;

import com.yaseen.npcmind.agent.AgentEngine;
import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.server.level.ServerPlayer;
import java.util.concurrent.CompletableFuture;

/** Provider entry point for NPC Mind's server-side reasoning engine. */
public final class GroqBrain {
    public static void askAsync(CompanionEntity companion, ServerPlayer player, String userMessage) {
        AgentEngine.runAsync(companion, player, userMessage);
    }
    private GroqBrain() {}
}
