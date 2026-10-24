package com.yaseen.npcmind.agent;

import com.yaseen.npcmind.NPCMind;
import com.yaseen.npcmind.debug.AuditLog;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.ToolContract;
import com.yaseen.npcmind.tool.ToolRegistry;
import net.minecraft.server.level.ServerPlayer;

/** Common deterministic executor used by both LLM-facing and internal planners. */
public final class InternalToolExecutor {
    private InternalToolExecutor() {}
    public static AgentToolRuntime.Result invoke(CompanionEntity c, ServerPlayer p, String name, String rawArgs, String actionId) {
        if (c == null || p == null) return new AgentToolRuntime.Result(name, actionId, false, false, "DENIED missing_actor");
        AITool tool = ToolRegistry.get(name);
        if (tool == null) return new AgentToolRuntime.Result(name, actionId, false, false, "FAILED unknown_tool=" + name);
        ToolContract.Validation v = ToolContract.validate(tool, rawArgs == null ? "{}" : rawArgs);
        if (!v.ok()) return new AgentToolRuntime.Result(name, actionId, false, false, "FAILED invalid_arguments " + v.reason());
        String args = v.normalized().toString();
        AuditLog.tool(c.getUUID(), actionId, "EXEC", tool.name(), args, "internal");
        try {
            String result = tool.execute(c, p, args);
            if (result == null) result = "TOOL_ERROR null_result";
            boolean failed = result.startsWith("FAILED") || result.startsWith("ERROR") || result.startsWith("DENIED") || result.startsWith("TIMEOUT") || result.startsWith("TOOL_ERROR");
            if (failed) AgentMemory.failure(c.getUUID(), tool.name(), args); else AgentMemory.success(c.getUUID(), tool.name(), args);
            AgentMemory.add(c.getUUID(), failed ? "internal_tool_failure" : "internal_tool_success", tool.name() + " => " + result);
            return new AgentToolRuntime.Result(tool.name(), actionId, true, false, result);
        } catch (Throwable t) {
            NPCMind.LOG.error("[NPCMind][InternalToolExecutor] {} failed", tool.name(), t);
            AgentMemory.failure(c.getUUID(), tool.name(), args);
            return new AgentToolRuntime.Result(tool.name(), actionId, false, false, "TOOL_ERROR " + t.getClass().getSimpleName());
        }
    }
}