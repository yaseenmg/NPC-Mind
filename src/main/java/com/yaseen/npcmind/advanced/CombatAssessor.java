package com.yaseen.npcmind.advanced;

import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;

/**
 * Decides whether a fight is worth taking, from the REAL stats of both sides
 * (health, armor, toughness, weapon damage, swing speed), using the vanilla armor formula.
 */
public final class CombatAssessor {
    public record Verdict(boolean fight, double ratio, double lossFraction, String reason) {}

    /** Vanilla armor damage reduction. */
    static double reduce(double damage, double armor, double toughness) {
        double f = 2.0D + toughness / 4.0D;
        double f1 = Math.max(armor * 0.2D, Math.min(20.0D, armor - damage / f));
        return damage * (1.0D - f1 / 25.0D);
    }

    static double rawDamage(LivingEntity e) {
        if (e instanceof Creeper) return 12.0D;          // explosion
        if (e instanceof AbstractSkeleton) return 4.0D;   // arrows
        AttributeInstance a = e.getAttribute(Attributes.ATTACK_DAMAGE);
        return a == null ? 3.0D : a.getValue();
    }

    static double swings(LivingEntity e) {
        if (e instanceof Player p) {
            AttributeInstance a = p.getAttribute(Attributes.ATTACK_SPEED);
            return a == null ? 1.0D : Math.max(0.5D, Math.min(4.0D, a.getValue()));
        }
        return 1.0D;
    }

    /** Damage per second the enemies would deal to the companion right now. */
    public static double incomingDps(CompanionEntity c, Collection<? extends LivingEntity> enemies) {
        double armor = c.getArmorValue();
        double tough = c.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        double dps = 0.0D;
        for (LivingEntity e : enemies) dps += reduce(rawDamage(e), armor, tough) * swings(e);
        return dps;
    }

    public static boolean hasHealingItems(CompanionEntity c) {
        for (int i = 0; i < c.getCompanionInventory().getContainerSize(); i++) {
            ItemStack s = c.getCompanionInventory().getItem(i);
            if (!s.isEmpty() && (s.isEdible() || s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE))) return true;
        }
        return false;
    }

    /**
     * @param leniency multiply the accepted risk (e.g. 1.25 when the player explicitly asked for protection)
     */
    public static Verdict assess(CompanionEntity c, Collection<? extends LivingEntity> enemies, double leniency) {
        if (enemies.isEmpty()) return new Verdict(true, 99.0D, 0.0D, "");
        double myHp = Math.max(1.0D, c.getHealth() + c.getAbsorptionAmount());
        double myArmor = c.getArmorValue();
        double myDmg = c.getAttributeValue(Attributes.ATTACK_DAMAGE);
        double mySwings = 20.0D / CompanionMeleeGoal.cooldownTicksFor(c.getMainHandItem());

        double ttk = 0.0D;   // seconds needed to kill everyone, one after another
        for (LivingEntity e : enemies) {
            double hp = e.getHealth() + e.getAbsorptionAmount();
            double taken = Math.max(0.5D, reduce(myDmg, e.getArmorValue(), e.getAttributeValue(Attributes.ARMOR_TOUGHNESS)));
            ttk += hp / (taken * mySwings);
        }
        double incoming = incomingDps(c, enemies);
        // Enemies die one by one, so the average damage rate over the fight is lower than at the start.
        double expectedLoss = incoming * ttk * 0.75D;
        double timeToDie = myHp / Math.max(0.01D, incoming * 0.75D);
        double ratio = timeToDie / Math.max(0.1D, ttk);
        double lossFraction = expectedLoss / myHp;

        double allowed = myArmor < 4.0D ? 0.35D : (myArmor < 10.0D ? 0.55D : 0.70D);
        if (hasHealingItems(c)) allowed += 0.10D;
        allowed *= leniency;

        // The companion was fleeing from fights it could actually win (e.g. 3 zombies while
        // armored), which felt unfair. Only run away when the fight is genuinely losing: the
        // time-advantage must be clearly against us (ratio < 1.1) OR the expected HP loss is
        // above the armor-based budget.
        boolean fight = ratio >= 1.1D && lossFraction <= allowed;
        String reason = "";
        if (!fight) {
            if (myArmor < 4.0D) reason = "status update";
            else if (enemies.size() > 1) reason = "status update";
            else if (myHp < c.getMaxHealth() * 0.5D) reason = "status update";
            else reason = "status update";
        }
        return new Verdict(fight, ratio, lossFraction, reason);
    }

    private CombatAssessor() {}
}
