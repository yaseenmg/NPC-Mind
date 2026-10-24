package com.yaseen.npcmind.tool.impl;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.task.TaskManager;
import com.yaseen.npcmind.tool.AITool;
import net.minecraft.server.level.ServerPlayer;
public final class TaskStatusTool implements AITool {
 public String name(){return "task_status";}
 public String description(){return "Read the actual status of the companion's current server-side task.";}
 public String schema(){return "{}";}
 public String execute(CompanionEntity c, ServerPlayer p, String a){return TaskManager.status(c);}
}
