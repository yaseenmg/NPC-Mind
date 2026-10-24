package com.yaseen.npcmind.tool.impl;
import com.yaseen.npcmind.advanced.CombatLearning;
import com.yaseen.npcmind.advanced.EquipmentAnalyzer;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.tool.AITool;
import net.minecraft.server.level.ServerPlayer;
public final class CombatStatusTool implements AITool {
 public String name(){return "combat_status";} public String description(){return "I could not complete the request.";} public String schema(){return "{}";}
 public String execute(CompanionEntity c,ServerPlayer p,String a){return "health="+c.getHealth()+"/"+c.getMaxHealth()+"\nmain="+EquipmentAnalyzer.describe(c.getMainHandItem())+"\noff="+EquipmentAnalyzer.describe(c.getOffhandItem())+"\narmor=\n"+EquipmentAnalyzer.armorSummary(c);}
}