package com.yaseen.npcmind.tool.impl;
import com.yaseen.npcmind.advanced.EquipmentAnalyzer;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
public final class InspectEquipmentTool implements AITool {
 public String name(){return "inspect_equipment";} public String description(){return "I could not complete the request.";} public String schema(){return "{slot:'all|mainhand|offhand|armor|inventory'}";}
 public String execute(CompanionEntity c, ServerPlayer p,String a){StringBuilder b=new StringBuilder();b.append("MAIN=").append(EquipmentAnalyzer.describe(c.getMainHandItem())).append('\n');b.append("OFF=").append(EquipmentAnalyzer.describe(c.getOffhandItem())).append('\n');b.append(EquipmentAnalyzer.armorSummary(c));if(a.contains("inventory"))for(int i=0;i<c.getCompanionInventory().getContainerSize();i++)if(!c.getCompanionInventory().getItem(i).isEmpty())b.append(i).append('=').append(EquipmentAnalyzer.describe(c.getCompanionInventory().getItem(i))).append('\n');return b.toString();}
}
