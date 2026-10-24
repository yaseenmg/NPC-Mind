package com.yaseen.npcmind.entity;

import com.yaseen.npcmind.advanced.EquipmentAnalyzer;
import com.yaseen.npcmind.crafting.InventoryOps;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;

/**
 * Player2NPC/Hiromi-inspired item acceptance boundary.
 *
 * Every item entering the companion goes through one method. Armor is resolved
 * from the actual armor slot, weapons only enter a hand, food enters inventory,
 * and everything else uses normal container insertion. The dropped ItemEntity
 * is only consumed after this method has returned a remainder.
 */
public final class CompanionItemHandler {
    private CompanionItemHandler() {}

    public static ItemStack accept(CompanionEntity companion, ItemStack incoming,
                                   Player source, boolean announce) {
        if (incoming == null || incoming.isEmpty()) return ItemStack.EMPTY;
        ItemStack stack = incoming.copy();

        EquipmentSlot armorSlot = armorSlot(stack);
        if (armorSlot != null) {
            ItemStack current = companion.getItemBySlot(armorSlot);
            if (EquipmentAnalyzer.armorScore(stack, armorSlot)
                    > EquipmentAnalyzer.armorScore(current, armorSlot)) {
                if (!current.isEmpty() && !InventoryOps.canAdd(companion.getCompanionInventory(), current)) {
                    return stack;
                }
                if (!current.isEmpty()) InventoryOps.add(companion.getCompanionInventory(), current.copy());
                ItemStack one = stack.split(1);
                companion.setItemSlot(armorSlot, one);
                companion.getCompanionInventory().setChanged();
                return stack.isEmpty() ? ItemStack.EMPTY : stack;
            }
        }

        if (stack.getItem() instanceof ShieldItem) {
            ItemStack current = companion.getItemBySlot(EquipmentSlot.OFFHAND);
            if (current.isEmpty()) {
                companion.setItemSlot(EquipmentSlot.OFFHAND, stack.split(1));
                companion.getCompanionInventory().setChanged();
                return stack.isEmpty() ? ItemStack.EMPTY : stack;
            }
        }

        if (EquipmentAnalyzer.isCombatWeapon(stack)) {
            ItemStack current = companion.getItemBySlot(EquipmentSlot.MAINHAND);
            if (EquipmentAnalyzer.combatScore(stack, false)
                    > EquipmentAnalyzer.combatScore(current, false)) {
                if (!current.isEmpty() && !InventoryOps.canAdd(companion.getCompanionInventory(), current)) return stack;
                if (!current.isEmpty()) InventoryOps.add(companion.getCompanionInventory(), current.copy());
                companion.setItemSlot(EquipmentSlot.MAINHAND, stack.split(1));
                companion.getCompanionInventory().setChanged();
                return stack.isEmpty() ? ItemStack.EMPTY : stack;
            }
        }

        boolean incomingFood = stack.isEdible();
        ItemStack remainder = InventoryOps.addAndReturnRemainder(
                companion.getCompanionInventory(), stack);
        companion.getCompanionInventory().setChanged();

        if (incomingFood && remainder.getCount() < stack.getCount() && !companion.isUsingItem()) {
            boolean hurt = companion.getHealth() < companion.getMaxHealth() - 1.0F;
            if (!companion.startEating(hurt)) {
                companion.say("I could not complete the request.");
            }
        }
        return remainder;
    }

    public static boolean isFood(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.isEdible();
    }

    public static EquipmentSlot armorSlot(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof ArmorItem armor)) return null;
        return armor.getEquipmentSlot();
    }

    public static boolean isWeapon(ItemStack stack) {
        return stack != null && !stack.isEmpty() && EquipmentAnalyzer.isCombatWeapon(stack);
    }
}