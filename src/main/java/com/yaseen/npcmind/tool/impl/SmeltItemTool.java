package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.security.WorldActionSecurity;
import com.yaseen.npcmind.autonomy.ResourceLedger;
import com.yaseen.npcmind.knowledge.ModpackKnowledge;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import com.yaseen.npcmind.state.ActionPermissions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraftforge.common.ForgeHooks;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Locale;

/**
 * Uses an actual furnace/smoker/blast-furnace block entity. No synthetic smelting is performed.
 * The tool loads a real input and real fuel into the real furnace and lets Minecraft tick it.
 */
public final class SmeltItemTool implements AITool {
    @Override public String name() { return "smelt_item"; }
    @Override public String description() {
        return "Use a real loaded furnace, smoker or blast furnace. The companion must provide a real recipe input and real fuel; Minecraft performs the actual cooking and creates the real output.";
    }
    @Override public String schema() {
        return "{type:\"smelting|blasting|smoking\",output:\"item_id_or_name\",x:number,y:number,z:number}";
    }

    @Override
    public String execute(CompanionEntity companion, ServerPlayer player, String arguments) {
        if(!companion.getPermissions().allowed(ActionPermissions.Action.CRAFT)) return "DENIED permission=craft";
        String type = text(arguments, "type").toLowerCase(Locale.ROOT);
        String query = text(arguments, "output").toLowerCase(Locale.ROOT);
        Integer x = MoveToTool.num(arguments, "x"), y = MoveToTool.num(arguments, "y"), z = MoveToTool.num(arguments, "z");
        if (x == null || y == null || z == null) return "FAILED a real furnace position x,y,z is required";

        RecipeType<? extends AbstractCookingRecipe> recipeType = switch (type) {
            case "blasting" -> RecipeType.BLASTING;
            case "smoking" -> RecipeType.SMOKING;
            case "smelting" -> RecipeType.SMELTING;
            default -> null;
        };
        if (recipeType == null) return "FAILED unsupported real furnace mode=" + type;

        BlockPos pos = new BlockPos(x, y, z);
        if (!WorldActionSecurity.canAccessContainer(companion, player, pos)) return "DENIED furnace interaction requires both owner and companion within 8 blocks";
        if (!player.serverLevel().isLoaded(pos)) return "FAILED furnace position is not loaded";
        BlockEntity be = player.serverLevel().getBlockEntity(pos);
        if (!(be instanceof AbstractFurnaceBlockEntity furnace)) return "FAILED no real furnace/smoker/blast-furnace block entity at " + pos;

        String blockId = player.serverLevel().getBlockState(pos).getBlock().builtInRegistryHolder().key().location().toString();
        if (type.equals("blasting") && !blockId.equals("minecraft:blast_furnace")) return "FAILED requested blasting but real block is " + blockId;
        if (type.equals("smoking") && !blockId.equals("minecraft:smoker")) return "FAILED requested smoking but real block is " + blockId;
        if (type.equals("smelting") && !blockId.equals("minecraft:furnace")) return "FAILED requested smelting but real block is " + blockId;

        ItemStack input = findRecipeInput(companion.getCompanionInventory(), player, recipeType, query);
        if (input.isEmpty()) return "FAILED no real companion-inventory input matches a live recipe output=" + query;
        ItemStack fuel = findFuel(companion.getCompanionInventory(), furnace);
        if (fuel.isEmpty()) return "FAILED no real fuel is available in companion inventory";
        if (!furnace.getItem(0).isEmpty() || !furnace.getItem(1).isEmpty()) return "FAILED real furnace input/fuel slots are occupied";
        if (!furnace.getItem(2).isEmpty()) return "FAILED real furnace output slot is occupied; empty it first";

        int inputSlot = findStackSlot(companion.getCompanionInventory(), input);
        int fuelSlot = findStackSlot(companion.getCompanionInventory(), fuel);
        if (inputSlot < 0 || fuelSlot < 0) return "FAILED real inventory changed while preparing furnace";

        if (!ResourceLedger.canConsume(companion, input, 1)) return "FAILED input is reserved for another project";
        if (!ResourceLedger.canConsume(companion, fuel, 1)) return "FAILED fuel is reserved for another project";
        ItemStack oneInput = companion.getCompanionInventory().removeItem(inputSlot, 1);
        ItemStack oneFuel = companion.getCompanionInventory().removeItem(fuelSlot, 1);
        furnace.setItem(0, oneInput);
        furnace.setItem(1, oneFuel);
        furnace.setChanged();
        companion.getCompanionInventory().setChanged();
        companion.startSmeltJob(pos, outputIdForRecipe(player, recipeType, query));
        return "STARTED real_furnace=" + pos + " mode=" + type + " input=" + ModpackKnowledge.itemInfo(oneInput) + " fuel=" + ModpackKnowledge.itemInfo(oneFuel);
    }

    private static String outputIdForRecipe(ServerPlayer player, RecipeType<? extends AbstractCookingRecipe> type, String query) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        java.util.List<AbstractCookingRecipe> recipes = (java.util.List) player.serverLevel().getRecipeManager().getAllRecipesFor((RecipeType) type);
        for (AbstractCookingRecipe recipe : recipes) {
            ItemStack out = recipe.getResultItem(player.serverLevel().registryAccess());
            if (out.isEmpty()) continue;
            String id = out.getItem().builtInRegistryHolder().key().location().toString().toLowerCase(Locale.ROOT);
            String name = out.getHoverName().getString().toLowerCase(Locale.ROOT);
            if (query == null || query.isBlank() || id.contains(query) || name.contains(query)) return id;
        }
        return "";
    }

    private static ItemStack findRecipeInput(Container inv, ServerPlayer player,
                                              RecipeType<? extends AbstractCookingRecipe> type, String query) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        java.util.List<AbstractCookingRecipe> recipes = (java.util.List) player.serverLevel().getRecipeManager().getAllRecipesFor((RecipeType) type);
        for (AbstractCookingRecipe holder : recipes) {
            ItemStack output = holder.getResultItem(player.serverLevel().registryAccess());
            if (output.isEmpty()) continue;
            String hay = output.getItem().builtInRegistryHolder().key().location().toString().toLowerCase(Locale.ROOT)
                    + " " + output.getHoverName().getString().toLowerCase(Locale.ROOT);
            if (!query.isBlank() && !hay.contains(query)) continue;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty() && holder.getIngredients().stream().anyMatch(ingredient -> ingredient.test(stack))) return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack findFuel(Container inv, AbstractFurnaceBlockEntity furnace) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && ForgeHooks.getBurnTime(stack, RecipeType.SMELTING) > 0) return stack;
        }
        return ItemStack.EMPTY;
    }

    private static int findStackSlot(Container inv, ItemStack wanted) {
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i) == wanted) return i;
        for (int i = 0; i < inv.getContainerSize(); i++) if (!inv.getItem(i).isEmpty() && ItemStack.isSameItemSameTags(inv.getItem(i), wanted)) return i;
        return -1;
    }

    private static String text(String json, String key) { return JsonArgs.string(json,key); }

    public SmeltItemTool() {}
}
