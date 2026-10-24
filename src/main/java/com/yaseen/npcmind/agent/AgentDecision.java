package com.yaseen.npcmind.agent;

import java.util.List;

/** A single reasoning turn. Keeping this typed prevents stringly-typed planner state leaking through the mod. */
public record AgentDecision(String text, List<AgentToolCall> toolCalls, boolean finalAnswer, String raw) {
    public boolean hasToolCall() { return toolCalls != null && !toolCalls.isEmpty(); }
}
