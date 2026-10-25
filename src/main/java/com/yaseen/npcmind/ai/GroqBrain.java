package com.yaseen.npcmind.ai;

import com.yaseen.npcmind.agent.AgentEngine;
import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.server.level.ServerPlayer;

/** Compatibility entry point; all reasoning now goes through the real server-side agent loop. */
public final class GroqBrain {
    public static void askAsync(CompanionEntity companion, ServerPlayer player, String userMessage) {
        AgentEngine.runAsync(companion, player, userMessage);
    }
    private GroqBrain() {}
}
