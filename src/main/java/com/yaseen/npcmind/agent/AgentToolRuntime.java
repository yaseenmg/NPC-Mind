package com.yaseen.npcmind.agent;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.tool.AITool;

public interface AgentToolRuntime {
    String execute(CompanionEntity companion, AITool tool, String arguments);
}