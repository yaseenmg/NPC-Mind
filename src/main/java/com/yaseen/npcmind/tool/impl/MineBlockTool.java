package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.task.TaskManager;
import com.yaseen.npcmind.state.ActionPermissions;
import com.yaseen.npcmind.tool.AITool;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

public final class MineBlockTool implements AITool {
    public String name(){return "mine_block";}
    public String description(){return "Mine a real loaded block at an absolute position, dropping its real Minecraft loot. The companion will navigate to it if necessary.";}
    public String schema(){return "{\"x\":number,\"y\":number,\"z\":number}";}
    public String execute(CompanionEntity c, ServerPlayer p, String a){
        Integer x=MoveToTool.num(a,"x"), y=MoveToTool.num(a,"y"), z=MoveToTool.num(a,"z");
        if(x==null||y==null||z==null)return "ERROR arguments require x,y,z";
        if(!c.getPermissions().allowed(ActionPermissions.Action.MINE))return "DENIED permission=mine";
        return TaskManager.mine(c,new BlockPos(x,y,z));
    }
}