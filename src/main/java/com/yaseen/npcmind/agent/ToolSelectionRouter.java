package com.yaseen.npcmind.agent;

import java.util.Locale;

/** High-confidence English intent router. It complements, rather than replaces, LLM planning. */
public final class ToolSelectionRouter {
    public record Route(String tool, String arguments, String reason) {}

    public static Route first(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isBlank() || s.length() > 300) return null;
        if (s.contains("gather wood") || s.contains("chop wood") || s.contains("collect wood"))
            return new Route("gather_resources", "{\"resource\":\"wood\",\"count\":8}", "wood intent");
        if (s.contains("gather resources") || s.contains("collect resources"))
            return new Route("gather_resources", "{\"count\":8}", "resource intent");
        if (s.contains("harvest") || s.contains("farm"))
            return new Route("farm_area", "{\"radius\":6,\"replant\":true}", "harvest intent");
        if (s.contains("plant") || s.contains("till"))
            return new Route("till_and_plant", "{\"radius\":4}", "plant intent");
        if (s.contains("cook") || s.contains("food"))
            return new Route("cook_food", "{\"output\":\"\"}", "food intent");
        if (s.equals("explore") || s.contains("explore"))
            return new Route("explore_mode", "{\"enabled\":true}", "exploration intent");
        if (s.contains("follow me") || s.equals("follow"))
            return new Route("follow_owner", "{\"enabled\":true}", "follow intent");
        if (s.contains("guard me") || s.contains("protect me"))
            return new Route("bodyguard_mode", "{\"enabled\":true}", "guard intent");
        if (s.contains("attack mobs") || s.contains("attack enemies") || s.contains("fight"))
            return new Route("combat_target", "{\"mode\":\"nearest_hostile\"}", "combat intent");
        return null;
    }

    private ToolSelectionRouter() {}
}
