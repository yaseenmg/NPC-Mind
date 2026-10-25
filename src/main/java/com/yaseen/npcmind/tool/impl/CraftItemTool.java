package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.agent.InternalToolExecutor;
import com.yaseen.npcmind.autonomy.ResourceLedger;
import com.yaseen.npcmind.crafting.ResourcePlanner;
import com.yaseen.npcmind.crafting.InventoryOps;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.state.ActionPermissions;
import com.yaseen.npcmind.state.SkillProgression;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic server-side crafting engine.
 *
 * <p>The old implementation placed every recipe ingredient in consecutive slots. That is not
 * valid for shaped recipes such as sticks (vertical pattern), pickaxes, swords, etc. This version
 * uses the live RecipeManager, preserves shaped recipe coordinates, handles shapeless recipes,
 * recursively crafts intermediate ingredients, and commits inventory changes only after a full
 * simulation succeeds.</p>
 */
public final class CraftItemTool implements AITool {
    private static final int MAX_RECIPE_DEPTH = 12;
    private static final int MAX_RECIPE_ATTEMPTS = 128;
    private static final int CRAFT_GRID = 3;

    @Override public String name() { return "craft_item"; }

    @Override public String description() {
        return "Craft an item from the live server RecipeManager using the companion's real inventory. "
                + "Supports shaped and shapeless recipes, recursive intermediate ingredients, recipe tags, "
                + "modded/datapack recipes, crafting-table requirements, and verified inventory commits.";
    }

    @Override public String schema() { return "{\"output\":\"item_id_or_name\",\"count\":number(optional)}"; }

    @Override
    public String execute(CompanionEntity companion, ServerPlayer player, String arguments) {
        if (!companion.getPermissions().allowed(ActionPermissions.Action.CRAFT)) return "DENIED permission=craft";
        if (player == null || player.serverLevel() == null) return "FAILED no_server_context";

        String query = JsonArgs.string(arguments, "output");
        query = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        int requested = Math.max(1, Math.min(64, JsonArgs.integer(arguments, "count", 1)));
        if (query.isBlank()) return "FAILED output_is_required";

        Item target = resolveItem(query, companion.getCompanionInventory());
        if (target == null) return "FAILED no_live_item_matches output=" + query;

        int before = countItem(companion.getCompanionInventory(), target);
        if (before >= requested) {
            return "SUCCESS already_have=" + before + " output=" + id(target);
        }

        CraftResult result = craftRecursive(companion, player, target, requested, new HashSet<>(), 0);
        int after = countItem(companion.getCompanionInventory(), target);
        int gained = Math.max(0, after - before);

        if (result.started) {
            return "STARTED craft_plan output=" + id(target) + " phase=" + result.reason;
        }
        if (gained >= requested - before) {
            return "SUCCESS crafted=" + gained + " output=" + id(target) + " steps=" + result.steps;
        }
        if (gained > 0) {
            return "PARTIAL crafted=" + gained + " output=" + id(target) + " steps=" + result.steps + " reason=" + result.reason;
        }
        return "FAILED " + result.reason + " output=" + id(target);
    }

    private static CraftResult craftRecursive(CompanionEntity companion, ServerPlayer player,
                                              Item target, int requested, Set<Item> active, int depth) {
        Container inv = companion.getCompanionInventory();
        int existing = countItem(inv, target);
        if (existing >= requested) return new CraftResult(0, 0, "already_have_output", false);
        if (depth >= MAX_RECIPE_DEPTH) return new CraftResult(0, 0, "recipe_depth_limit=" + MAX_RECIPE_DEPTH, false);
        if (!active.add(target)) return new CraftResult(0, 0, "recipe_cycle=" + id(target), false);

        int crafted = 0;
        int steps = 0;
        String reason = "missing_real_ingredients";
        try {
            while (countItem(inv, target) < requested && steps < MAX_RECIPE_ATTEMPTS) {
                List<CraftingRecipe> recipes = findRecipes(player, target, inv);
                if (recipes.isEmpty()) { reason = "no_live_crafting_recipe"; break; }

                CraftingRecipe chosen = null;
                List<Integer> slots = null;
                String lastFailure = "no_recipe_was_craftable";

                for (CraftingRecipe recipe : recipes) {
                    if (!recipe.canCraftInDimensions(CRAFT_GRID, CRAFT_GRID)) {
                        lastFailure = "recipe_grid_too_large=" + recipe.getId();
                        continue;
                    }
                    if (requiresTable(recipe) && !hasNearbyCraftingTable(companion, 4)) {
                        String table = ensureCraftingTable(companion, player, active, depth);
                        if (!table.startsWith("SUCCESS")) {
                            lastFailure = table;
                            continue;
                        }
                    }

                    List<Integer> candidate = chooseIngredientSlots(recipe, inv, companion);
                    if (candidate != null) {
                        chosen = recipe;
                        slots = candidate;
                        break;
                    }

                    IngredientNeed missing = firstMissingIngredient(recipe, inv, companion);
                    if (missing == null) continue;

                    // Version 60: a missing ingredient is not automatically a crafting failure.
                    // First ask the physical resource layer to acquire it from the world. This is
                    // what turns "craft sword" into gather -> workstation -> smelt -> craft.
                    ResourcePlanner.Decision acquisition = ResourcePlanner.acquire(companion, player, missing.ingredient);
                    if (acquisition.started()) {
                        return new CraftResult(crafted, steps, acquisition.result(), true);
                    }
                    if (!acquisition.result().isBlank() && (acquisition.result().startsWith("FAILED") || acquisition.result().startsWith("DENIED") || acquisition.result().startsWith("ERROR"))) {
                        lastFailure = acquisition.result();
                    }

                    Item intermediate = chooseCraftableItem(missing.ingredient, player, active, depth);
                    if (intermediate == null) {
                        lastFailure = "missing_ingredient=" + describeIngredient(missing.ingredient);
                        continue;
                    }
                    CraftResult sub = craftRecursive(companion, player, intermediate, 1, active, depth + 1);
                    steps += sub.steps + (sub.crafted > 0 ? 1 : 0);
                    if (sub.crafted > 0) {
                        candidate = chooseIngredientSlots(recipe, inv, companion);
                        if (candidate != null) {
                            chosen = recipe;
                            slots = candidate;
                            break;
                        }
                    }
                    lastFailure = "cannot_craft_intermediate=" + id(intermediate) + " (" + sub.reason + ")";
                }

                if (chosen == null || slots == null) {
                    reason = lastFailure;
                    break;
                }

                String direct = craftOnce(companion, player, chosen, slots);
                if (!direct.startsWith("SUCCESS")) {
                    reason = direct;
                    break;
                }
                ItemStack output = chosen.getResultItem(player.serverLevel().registryAccess());
                crafted += Math.max(1, output.getCount());
                steps++;
            }
        } finally {
            active.remove(target);
        }
        if (steps >= MAX_RECIPE_ATTEMPTS && countItem(inv, target) < requested) {
            reason = "craft_attempt_limit=" + MAX_RECIPE_ATTEMPTS;
        }
        return new CraftResult(crafted, steps, reason, false);
    }

    private static List<CraftingRecipe> findRecipes(ServerPlayer player, Item target, Container inventory) {
        List<CraftingRecipe> out = new ArrayList<>();
        for (CraftingRecipe recipe : player.serverLevel().getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            ItemStack result = recipe.getResultItem(player.serverLevel().registryAccess());
            if (!result.isEmpty() && result.getItem() == target && !recipe.isSpecial()) out.add(recipe);
        }
        // Prefer recipes with the most currently satisfiable ingredients. This matters for
        // tags such as planks, wool, glass, dyes, logs, etc.; the planner should use what it
        // already owns instead of blindly selecting the lexicographically first alternative.
        out.sort((a, b) -> {
            int sa = recipeAvailabilityScore(a, inventory);
            int sb = recipeAvailabilityScore(b, inventory);
            if (sa != sb) return Integer.compare(sb, sa);
            return a.getId().toString().compareTo(b.getId().toString());
        });
        return out;
    }

    private static String craftOnce(CompanionEntity companion, ServerPlayer player,
                                    CraftingRecipe recipe, List<Integer> slots) {
        Container inv = companion.getCompanionInventory();
        if (requiresTable(recipe) && !hasNearbyCraftingTable(companion, 4)) {
            return "FAILED crafting_table_required recipe=" + recipe.getId();
        }

        CraftingContainer grid = new TransientCraftingContainer(DUMMY_MENU, CRAFT_GRID, CRAFT_GRID);
        fillGrid(recipe, slots, inv, grid);
        if (!recipe.matches(grid, player.serverLevel())) {
            return "FAILED recipe_layout_not_matched=" + recipe.getId();
        }

        ItemStack actual = recipe.assemble(grid, player.serverLevel().registryAccess()).copy();
        if (actual.isEmpty()) return "FAILED empty_assembled_output=" + recipe.getId();

        // Full transaction simulation: consume inputs, add output and recipe remainders. No live
        // inventory mutation happens until every operation fits.
        SimpleContainer simulated = copyInventory(inv);
        for (int slot : slots) if (slot >= 0) simulated.removeItem(slot, 1);
        if (!InventoryOps.add(simulated, actual)) return "FAILED inventory_full_for_output=" + id(actual.getItem());
        for (ItemStack remainder : recipe.getRemainingItems(grid)) {
            if (!remainder.isEmpty() && !InventoryOps.add(simulated, remainder.copy())) {
                return "FAILED inventory_full_for_remainder=" + id(remainder.getItem());
            }
        }

        // Commit atomically from the already validated simulation.
        for (int slot : slots) if (slot >= 0) inv.removeItem(slot, 1);
        if (!InventoryOps.add(inv, actual)) return "FAILED commit_output_insert=" + id(actual.getItem());
        for (ItemStack remainder : recipe.getRemainingItems(grid)) {
            if (!remainder.isEmpty() && !InventoryOps.add(inv, remainder.copy())) {
                // Should be unreachable because the simulation above passed; report loudly rather
                // than pretending the craft succeeded.
                return "FAILED commit_remainder_insert=" + id(remainder.getItem());
            }
        }
        inv.setChanged();
        companion.getSkills().add(SkillProgression.Skill.CRAFTING, 8);
        companion.getCompanionState().positiveEvent();
        companion.getCraftingKnowledge().recordSuccess(recipe.getId().toString(), Math.max(1, actual.getCount()));
        return "SUCCESS crafted=" + id(actual.getItem()) + " recipe=" + recipe.getId();
    }

    private static void fillGrid(CraftingRecipe recipe, List<Integer> slots, Container inv, CraftingContainer grid) {
        List<net.minecraft.world.item.crafting.Ingredient> ingredients = recipe.getIngredients();
        if (recipe instanceof ShapedRecipe shaped) {
            int w = shaped.getWidth();
            int h = shaped.getHeight();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int recipeIndex = y * w + x;
                    if (recipeIndex >= ingredients.size()) continue;
                    int invSlot = slots.get(recipeIndex);
                    if (invSlot >= 0) grid.setItem(y * CRAFT_GRID + x, oneOf(inv.getItem(invSlot)));
                }
            }
        } else {
            // Shapeless recipes may use any cells. We keep them packed in the first cells.
            for (int i = 0; i < ingredients.size() && i < 9; i++) {
                int invSlot = slots.get(i);
                if (invSlot >= 0) grid.setItem(i, oneOf(inv.getItem(invSlot)));
            }
        }
    }

    private static List<Integer> chooseIngredientSlots(CraftingRecipe recipe, Container inv, CompanionEntity companion) {
        List<Integer> result = new ArrayList<>();
        Map<Integer, Integer> used = new HashMap<>();
        for (var ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) { result.add(-1); continue; }
            int found = -1;
            for (int slot = 0; slot < inv.getContainerSize(); slot++) {
                ItemStack stack = inv.getItem(slot);
                int alreadyUsed = used.getOrDefault(slot, 0);
                if (!stack.isEmpty() && stack.getCount() > alreadyUsed && ingredient.test(stack)
                        && ResourceLedger.canConsume(companion, stack, alreadyUsed + 1)) {
                    found = slot;
                    used.put(slot, alreadyUsed + 1);
                    break;
                }
            }
            if (found < 0) return null;
            result.add(found);
        }
        // A shaped recipe's ingredient list already contains its empty cells. For shapeless recipes
        // there are no empty cells and packing is correct.
        while (result.size() < 9) result.add(-1);
        return result;
    }

    private static IngredientNeed firstMissingIngredient(CraftingRecipe recipe, Container inv, CompanionEntity companion) {
        Map<Integer, Integer> used = new HashMap<>();
        var ingredients = recipe.getIngredients();
        for (var ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;
            boolean found = false;
            for (int slot = 0; slot < inv.getContainerSize(); slot++) {
                ItemStack stack = inv.getItem(slot);
                int consumed = used.getOrDefault(slot, 0);
                if (!stack.isEmpty() && stack.getCount() > consumed && ingredient.test(stack)
                        && ResourceLedger.canConsume(companion, stack, consumed + 1)) {
                    used.put(slot, consumed + 1);
                    found = true;
                    break;
                }
            }
            if (!found) return new IngredientNeed(ingredient);
        }
        return null;
    }

    private static Item chooseCraftableItem(net.minecraft.world.item.crafting.Ingredient ingredient,
                                            ServerPlayer player, Set<Item> active, int depth) {
        ItemStack[] choices = ingredient.getItems();
        Item best = null;
        for (ItemStack choice : choices) {
            Item item = choice.getItem();
            // Never invent a reverse recipe for a raw resource. For example, Minecraft has a
            // recipe iron_block -> iron_ingot, but that does NOT mean an absent iron ingot should
            // be obtained by first crafting an iron block. The old planner did exactly that and
            // produced errors such as "cannot_craft_intermediate=minecraft:diamond_block" when
            // asked for a sword. Raw resources must be gathered/smelled by the higher-level task
            // planner instead of recursively reversing compression recipes.
            if (active.contains(item) || isRawResource(item)) continue;
            if (!findRecipes(player, item, null).isEmpty()) {
                if (best == null || id(item).compareTo(id(best)) < 0) best = item;
            }
        }
        return best;
    }

    private static boolean isRawResource(Item item) {
        String p = id(item);
        return p.equals("minecraft:diamond") || p.equals("minecraft:emerald")
                || p.equals("minecraft:iron_ingot") || p.equals("minecraft:gold_ingot")
                || p.equals("minecraft:copper_ingot") || p.equals("minecraft:netherite_ingot")
                || p.equals("minecraft:coal") || p.equals("minecraft:raw_iron")
                || p.equals("minecraft:raw_gold") || p.equals("minecraft:raw_copper")
                || p.endsWith("_log") || p.endsWith("_stem");
    }

    private static String describeIngredient(net.minecraft.world.item.crafting.Ingredient ingredient) {
        ItemStack[] choices = ingredient.getItems();
        if (choices.length == 0) return "unknown";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(choices.length, 4); i++) {
            if (i > 0) b.append('|');
            b.append(id(choices[i].getItem()));
        }
        return b.toString();
    }

    private static Item resolveItem(String query, Container inv) {
        ResourceLocation exact = ResourceLocation.tryParse(query);
        if (exact != null && BuiltInRegistries.ITEM.containsKey(exact)) return BuiltInRegistries.ITEM.get(exact);

        // Natural-language aliases are inventory-aware. If the companion owns birch/spruce/etc.
        // planks, "planks" should not force oak.
        if (query.equals("planks") || query.equals("wood_planks")) {
            Item best = null;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty()) continue;
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (key != null && key.getPath().endsWith("_planks") && (best == null || id(stack.getItem()).compareTo(id(best)) < 0)) best = stack.getItem();
            }
            if (best != null) return best;
            return BuiltInRegistries.ITEM.get(new ResourceLocation("minecraft", "oak_planks"));
        }
        if (query.equals("wood")) {
            Item best = null;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty()) continue;
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (key != null && key.getPath().endsWith("_log") && (best == null || id(stack.getItem()).compareTo(id(best)) < 0)) best = stack.getItem();
            }
            return best != null ? best : BuiltInRegistries.ITEM.get(new ResourceLocation("minecraft", "oak_log"));
        }
        if (query.equals("stick") || query.equals("sticks")) return BuiltInRegistries.ITEM.get(new ResourceLocation("minecraft", "stick"));

        for (ResourceLocation key : BuiltInRegistries.ITEM.keySet()) {
            Item item = BuiltInRegistries.ITEM.get(key);
            if (key.getPath().equals(query) || item.getDescription().getString().toLowerCase(Locale.ROOT).equals(query)) return item;
        }
        for (ResourceLocation key : BuiltInRegistries.ITEM.keySet()) {
            Item item = BuiltInRegistries.ITEM.get(key);
            if (key.getPath().contains(query)) return item;
        }
        return null;
    }

    private static int recipeAvailabilityScore(CraftingRecipe recipe, Container inv) {
        if (inv == null) return 0;
        int score = 0;
        Map<Integer, Integer> used = new HashMap<>();
        for (var ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) continue;
            boolean found = false;
            for (int slot = 0; slot < inv.getContainerSize(); slot++) {
                ItemStack stack = inv.getItem(slot);
                int usedCount = used.getOrDefault(slot, 0);
                if (!stack.isEmpty() && stack.getCount() > usedCount && ingredient.test(stack)) {
                    used.put(slot, usedCount + 1);
                    score++;
                    found = true;
                    break;
                }
            }
            if (!found) score -= 3;
        }
        return score;
    }

    private static boolean requiresTable(CraftingRecipe recipe) {
        return !recipe.canCraftInDimensions(2, 2);
    }

    /** Ensure a real crafting table exists within interaction range before a 3x3 craft. */
    private static String ensureCraftingTable(CompanionEntity c, ServerPlayer player, Set<Item> active, int depth) {
        if (hasNearbyCraftingTable(c, 4)) return "SUCCESS crafting_table_already_nearby";
        Item table = BuiltInRegistries.ITEM.get(new ResourceLocation("minecraft", "crafting_table"));
        if (table == null) return "FAILED crafting_table_item_missing";
        if (countItem(c.getCompanionInventory(), table) < 1) {
            if (depth >= MAX_RECIPE_DEPTH - 1 || active.contains(table)) return "FAILED cannot_recursively_craft_crafting_table";
            CraftResult made = craftRecursive(c, player, table, 1, active, depth + 1);
            if (countItem(c.getCompanionInventory(), table) < 1) {
                return "FAILED crafting_table_missing reason=" + made.reason;
            }
        }
        String placed = InternalToolExecutor.invoke(c, player, "place_block", "{\"block\":\"crafting_table\",\"count\":1}", "internal-" + java.util.UUID.randomUUID()).result();
        if (placed != null && placed.startsWith("SUCCESS") && hasNearbyCraftingTable(c, 4)) return placed;
        return "FAILED cannot_place_crafting_table result=" + placed;
    }

    private static boolean hasNearbyCraftingTable(CompanionEntity c, int radius) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos base = c.blockPosition();
        for (int dx = -radius; dx <= radius; dx++) for (int dy = -2; dy <= 2; dy++) for (int dz = -radius; dz <= radius; dz++) {
            pos.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
            if (c.level().getBlockState(pos).is(Blocks.CRAFTING_TABLE)) return true;
        }
        return false;
    }

    private static int countItem(Container inv, Item item) {
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(item)) total += inv.getItem(i).getCount();
        return total;
    }

    private static SimpleContainer copyInventory(Container inv) {
        SimpleContainer copy = new SimpleContainer(inv.getContainerSize());
        for (int i = 0; i < inv.getContainerSize(); i++) copy.setItem(i, inv.getItem(i).copy());
        return copy;
    }

    private static String id(Item item) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        return key == null ? "unknown" : key.toString();
    }

    private static ItemStack oneOf(ItemStack stack) { ItemStack copy = stack.copy(); copy.setCount(1); return copy; }

    private record IngredientNeed(net.minecraft.world.item.crafting.Ingredient ingredient) {}
    private record CraftResult(int crafted, int steps, String reason, boolean started) {}

    private static final AbstractContainerMenu DUMMY_MENU = new AbstractContainerMenu(MenuType.CRAFTING, -1) {
        @Override public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player p, int i) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(net.minecraft.world.entity.player.Player p) { return false; }
    };
}
