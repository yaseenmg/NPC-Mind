package com.yaseen.npcmind.tool;

import com.yaseen.npcmind.tool.impl.InspectInventoryTool;
import com.yaseen.npcmind.tool.impl.InspectNearbyEntitiesTool;
import com.yaseen.npcmind.tool.impl.InspectLookBlockTool;
import com.yaseen.npcmind.tool.impl.LocationTool;
import com.yaseen.npcmind.tool.impl.InspectRegistryTool;
import com.yaseen.npcmind.tool.impl.RememberTool;
import com.yaseen.npcmind.tool.impl.FollowOwnerTool;
import com.yaseen.npcmind.tool.impl.MoveToTool;
import com.yaseen.npcmind.tool.impl.MineBlockTool;
import com.yaseen.npcmind.tool.impl.TaskStatusTool;
import com.yaseen.npcmind.tool.impl.InspectCompanionInventoryTool;
import com.yaseen.npcmind.tool.impl.InspectContainerTool;
import com.yaseen.npcmind.tool.impl.DepositItemTool;
import com.yaseen.npcmind.tool.impl.TakeFromContainerTool;
import com.yaseen.npcmind.tool.impl.FindNearbyContainerTool;
import com.yaseen.npcmind.tool.impl.InspectRecipesTool;
import com.yaseen.npcmind.tool.impl.CraftItemTool;
import com.yaseen.npcmind.tool.impl.SmeltItemTool;
import com.yaseen.npcmind.tool.impl.InspectEquipmentTool;
import com.yaseen.npcmind.tool.impl.InspectEnergyTool;
import com.yaseen.npcmind.tool.impl.CombatStatusTool;
import com.yaseen.npcmind.tool.impl.InspectVillagerTradesTool;
import com.yaseen.npcmind.tool.impl.TradeWithVillagerTool;
import com.yaseen.npcmind.tool.impl.SmartVeinMiningTool;
import com.yaseen.npcmind.tool.impl.BuildPatternTool;
import com.yaseen.npcmind.tool.impl.BuildBlueprintTool;
import com.yaseen.npcmind.tool.impl.WorldMemoryTool;
import com.yaseen.npcmind.tool.impl.GoalTool;
import com.yaseen.npcmind.tool.impl.BodyguardTool;
import com.yaseen.npcmind.tool.impl.ExploreTool;
import com.yaseen.npcmind.tool.impl.StructureSearchTool;
import com.yaseen.npcmind.tool.impl.BaseSiteTool;
import com.yaseen.npcmind.tool.impl.RescueStatusTool;
import com.yaseen.npcmind.tool.impl.SmartNavigateTool;
import com.yaseen.npcmind.tool.impl.ResourceLedgerTool;
import com.yaseen.npcmind.tool.impl.ReserveResourceTool;
import com.yaseen.npcmind.tool.impl.LoadoutTool;
import com.yaseen.npcmind.tool.impl.MaintenanceTool;
import com.yaseen.npcmind.tool.impl.AutonomyTool;
import com.yaseen.npcmind.tool.impl.TeamTool;
import com.yaseen.npcmind.tool.impl.DailyReportTool;
import com.yaseen.npcmind.tool.impl.PlanAlternativeTool;
import com.yaseen.npcmind.tool.impl.FarmAreaTool;
import com.yaseen.npcmind.tool.impl.GatherWoodTool;
import com.yaseen.npcmind.tool.impl.GatherBlockTool;
import com.yaseen.npcmind.tool.impl.PlaceBlockTool;
import com.yaseen.npcmind.tool.impl.LightAreaTool;
import com.yaseen.npcmind.tool.impl.CookFoodTool;
import com.yaseen.npcmind.tool.impl.TillAndPlantTool;
import com.yaseen.npcmind.tool.impl.GatherResourcesTool;
import com.yaseen.npcmind.tool.impl.CombatTargetTool;
import com.yaseen.npcmind.tool.impl.GiveItemTool;
import com.yaseen.npcmind.tool.impl.ExperimentTool;
import com.yaseen.npcmind.tool.impl.PermissionTool;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ToolRegistry {
    private static final Map<String, AITool> TOOLS = new LinkedHashMap<>();
    static {
        register(new InspectInventoryTool()); register(new InspectNearbyEntitiesTool()); register(new InspectLookBlockTool());
        register(new LocationTool()); register(new InspectRegistryTool()); register(new RememberTool()); register(new FollowOwnerTool());
        register(new MoveToTool()); register(new MineBlockTool()); register(new TaskStatusTool()); register(new InspectCompanionInventoryTool());
        register(new InspectContainerTool()); register(new DepositItemTool()); register(new TakeFromContainerTool()); register(new FindNearbyContainerTool());
        register(new InspectRecipesTool()); register(new CraftItemTool()); register(new SmeltItemTool()); register(new InspectEquipmentTool());
        register(new InspectEnergyTool()); register(new CombatStatusTool()); register(new InspectVillagerTradesTool()); register(new TradeWithVillagerTool());
        register(new SmartVeinMiningTool()); register(new BuildPatternTool()); register(new BuildBlueprintTool()); register(new WorldMemoryTool());
        register(new GoalTool()); register(new BodyguardTool()); register(new ExploreTool()); register(new StructureSearchTool()); register(new BaseSiteTool());
        register(new RescueStatusTool()); register(new SmartNavigateTool()); register(new ResourceLedgerTool()); register(new ReserveResourceTool());
        register(new LoadoutTool()); register(new MaintenanceTool()); register(new AutonomyTool()); register(new TeamTool()); register(new DailyReportTool());
        register(new PlanAlternativeTool()); register(new FarmAreaTool()); register(new GatherWoodTool()); register(new GatherBlockTool());
        register(new PlaceBlockTool()); register(new LightAreaTool()); register(new CookFoodTool()); register(new TillAndPlantTool());
        register(new GatherResourcesTool()); register(new CombatTargetTool()); register(new GiveItemTool()); register(new ExperimentTool()); register(new PermissionTool());
    }
    private static void register(AITool tool) { TOOLS.put(tool.name(), tool); }
    public static synchronized void registerExternal(AITool tool) {
        if (tool == null || tool.name() == null || tool.name().isBlank()) throw new IllegalArgumentException("A tool with a non-blank name is required");
        TOOLS.put(tool.name(), tool);
    }
    private static final Map<String, String> ALIASES = buildAliases();
    private static Map<String, String> buildAliases() {
        Map<String, String> aliases = new java.util.HashMap<>();
        aliases.putIfAbsent("attack", "combat_target"); aliases.putIfAbsent("fight", "combat_target");
        aliases.putIfAbsent("follow", "follow_owner"); aliases.putIfAbsent("come", "follow_owner");
        aliases.putIfAbsent("mine", "mine_block"); aliases.putIfAbsent("explore", "explore_mode");
        aliases.putIfAbsent("search", "search_structure"); aliases.putIfAbsent("loadout", "prepare_loadout");
        aliases.putIfAbsent("world_memory", "inspect_world_memory");
        return java.util.Collections.unmodifiableMap(aliases);
    }
    public static AITool get(String name) {
        if (name == null) return null;
        String trimmed = name.trim(); AITool direct = TOOLS.get(trimmed); if (direct != null) return direct;
        String mapped = ALIASES.get(trimmed.toLowerCase(java.util.Locale.ROOT)); return mapped == null ? null : TOOLS.get(mapped);
    }
    public static Collection<AITool> all() { return TOOLS.values(); }
    public static String definitionsFor(String message) { return definitions(); }
    public static com.google.gson.JsonArray jsonToolDefinitionsFor(String message) {
        String defs = definitionsFor(message); java.util.Set<String> selected = new java.util.LinkedHashSet<>();
        for (String line : defs.split("\n")) if (line.startsWith("TOOL ")) selected.add(line.substring(5).trim());
        com.google.gson.JsonArray out = new com.google.gson.JsonArray();
        for (AITool t : TOOLS.values()) if (selected.contains(t.name())) {
            com.google.gson.JsonObject fn = new com.google.gson.JsonObject(); fn.addProperty("name", t.name()); fn.addProperty("description", t.description());
            fn.add("parameters", ToolContract.toJsonSchema(t)); fn.addProperty("strict", false);
            com.google.gson.JsonObject wrapper = new com.google.gson.JsonObject(); wrapper.addProperty("type", "function"); wrapper.add("function", fn); out.add(wrapper);
        }
        return out;
    }
    public static com.google.gson.JsonArray jsonToolDefinitions() {
        com.google.gson.JsonArray out = new com.google.gson.JsonArray();
        for (AITool t : TOOLS.values()) {
            com.google.gson.JsonObject fn = new com.google.gson.JsonObject(); fn.addProperty("name", t.name()); fn.addProperty("description", t.description());
            fn.add("parameters", ToolContract.toJsonSchema(t)); fn.addProperty("strict", false);
            com.google.gson.JsonObject wrapper = new com.google.gson.JsonObject(); wrapper.addProperty("type", "function"); wrapper.add("function", fn); out.add(wrapper);
        }
        return out;
    }
    public static String compactCatalog() {
        StringBuilder b = new StringBuilder(); for (AITool t : TOOLS.values()) b.append(t.name()).append(" :: ").append(t.description()).append(" :: ").append(t.schema()).append('\n');
        return b.toString();
    }
    public static String definitions() {
        StringBuilder b = new StringBuilder();
        for (AITool t : TOOLS.values()) b.append("TOOL ").append(t.name()).append('\n').append(t.description()).append('\n').append("ARGUMENTS ").append(t.schema()).append("\n\n");
        return b.toString();
    }
    private ToolRegistry() {}
}