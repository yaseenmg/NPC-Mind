package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.autonomy.AutonomyController;
import com.yaseen.npcmind.autonomy.AutonomyState;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import net.minecraft.server.level.ServerPlayer;

/** Configures autonomy and primes the deterministic router immediately. */
public final class AutonomyTool implements AITool {
    public String name() { return "manage_autonomy"; }
    public String description() { return "Configure and activate real autonomy. Enabling routine immediately starts the selected safe mode; no fake actions are performed."; }
    public String schema() { return "{\"action\":\"status|mode|routine|enable|disable|suggestions|preference\",\"value\":\"text|boolean\"}"; }

    public String execute(CompanionEntity c, ServerPlayer p, String a) {
        String action = MoveToTool.str(a, "action");
        String value = MoveToTool.str(a, "value");
        if (action == null) action = "status";
        if ("mode".equals(action) && value != null) {
            try { c.getAutonomy().mode(AutonomyState.Mode.valueOf(value.trim().toUpperCase())); }
            catch (Exception e) { return "ERROR invalid_mode expected=FOLLOW|BODYGUARD|EXPLORER|ENGINEER|MINER|FARMER|IDLE"; }
        }
        if ("enable".equals(action)) c.getAutonomy().routine(true);
        if ("disable".equals(action)) c.getAutonomy().routine(false);
        if ("routine".equals(action)) {
            Boolean b = JsonArgs.bool(a, "value");
            if (b != null) c.getAutonomy().routine(b);
        }
        if ("suggestions".equals(action)) {
            Boolean b = JsonArgs.bool(a, "value");
            if (b != null) c.getAutonomy().suggestions(b);
        }
        if ("preference".equals(action) && value != null) c.getAutonomy().preference(value);
        if (c.getAutonomy().routine()) {
            AutonomyController.clear(c.getUUID());
            return "STARTED autonomy mode=" + c.getAutonomy().mode() + " router=deterministic_then_llm " + c.getAutonomy().summary();
        }
        return "RESULT autonomy_disabled " + c.getAutonomy().summary();
    }
}
