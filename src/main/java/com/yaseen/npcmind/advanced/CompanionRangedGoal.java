package com.yaseen.npcmind.advanced;
import com.yaseen.npcmind.autonomy.NavigationArbiter;

import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;

/**
 * Bow fighting: keeps its distance, strafes, draws the bow fully, leads moving targets and spends
 * real arrows from the companion's inventory (and real bow durability).
 * Active only while {@link CompanionEntity#isRangedMode()} is set by the combat controller.
 */
public final class CompanionRangedGoal extends Goal {
    private static final int FULL_DRAW_TICKS = 20;

    private final CompanionEntity mob;
    private int cooldown;
    private int strafeTimer;
    private int strafeDir = 1;
    private int repath;
    private int jumpTimer;

    public CompanionRangedGoal(CompanionEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    // ------------------------------------------------------------ shared helpers

    public static int findArrowSlot(CompanionEntity c) {
        Container inv = c.getCompanionInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() instanceof ArrowItem) return i;
        }
        return -1;
    }

    public static int findBowSlot(CompanionEntity c) {
        Container inv = c.getCompanionInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() instanceof BowItem) return i;
        }
        return -1;
    }

    /** A bow (in hand or inventory) AND at least one arrow. */
    public static boolean canShoot(CompanionEntity c) {
        boolean bow = c.getMainHandItem().getItem() instanceof BowItem || findBowSlot(c) >= 0;
        return bow && findArrowSlot(c) >= 0;
    }

    // ------------------------------------------------------------ goal

    @Override
    public boolean canUse() {
        LivingEntity t = mob.getTarget();
        return t != null && t.isAlive() && mob.isRangedMode() && !mob.isEating()
                && mob.getMainHandItem().getItem() instanceof BowItem && findArrowSlot(mob) >= 0;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        cooldown = 10;
        repath = 0;
        jumpTimer = 10;
        mob.setSprinting(false);
    }

    @Override
    public void stop() {
        if (mob.isUsingItem() && mob.getUseItem().getItem() instanceof BowItem) mob.stopUsingItem();
        mob.getNavigation().stop();
        mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0.0D);
        mob.setSprinting(false);
    }

    @Override
    public void tick() {
        LivingEntity t = mob.getTarget();
        if (t == null || !t.isAlive()) return;
        mob.getLookControl().setLookAt(t, 30.0F, 30.0F);
        double dist = mob.distanceTo(t);
        boolean los = mob.hasLineOfSight(t);

        // ---- positioning
        if (!los || dist > 22.0D) {
            if (--repath <= 0) {
                repath = 6 + mob.getRandom().nextInt(6);
                NavigationArbiter.request(mob, t, "combat_goal", 800, 1.1D);
            }
        } else {
            mob.getNavigation().stop();
            if (--strafeTimer <= 0) {
                strafeTimer = 28 + mob.getRandom().nextInt(30);
                strafeDir = mob.getRandom().nextBoolean() ? 1 : -1;
            }
            double fx = t.getX() - mob.getX();
            double fz = t.getZ() - mob.getZ();
            double len = Math.max(0.001D, Math.sqrt(fx * fx + fz * fz));
            fx /= len;
            fz /= len;
            boolean drawing = mob.isUsingItem() && mob.getUseItem().getItem() instanceof BowItem;
            double forward = dist < 7.0D ? -0.75D : (dist > 16.0D ? 0.45D : 0.0D);
            double side = 0.45D;
            double px = mob.getX() + (fx * forward + (-fz) * strafeDir * side) * 1.45D;
            double pz = mob.getZ() + (fz * forward + fx * strafeDir * side) * 1.45D;
            boolean sprint = dist > 18.0D && !drawing;
            mob.setSprinting(sprint);
            mob.getMoveControl().setWantedPosition(px, mob.getY(), pz, sprint ? 1.3D : 1.0D);
            if (sprint && mob.onGround() && --jumpTimer <= 0) { jumpTimer = 11 + mob.getRandom().nextInt(7); mob.getJumpControl().jump(); }
            if (mob.horizontalCollision && mob.onGround()) mob.getJumpControl().jump();
        }

        // ---- shooting
        if (cooldown > 0) cooldown--;
        boolean drawing = mob.isUsingItem() && mob.getUseItem().getItem() instanceof BowItem;
        if (los && dist <= 28.0D) {
            if (!mob.isUsingItem() && cooldown <= 0) {
                mob.startUsingItem(InteractionHand.MAIN_HAND);
            } else if (drawing && mob.getTicksUsingItem() >= FULL_DRAW_TICKS) {
                shoot(t, dist);
                mob.stopUsingItem();
                cooldown = 6;
            }
        } else if (drawing) {
            mob.stopUsingItem(); // lost sight: relax the string
        }
    }

    private void shoot(LivingEntity t, double dist) {
        ItemStack bow = mob.getMainHandItem();
        if (!(bow.getItem() instanceof BowItem bowItem)) return;
        int slot = findArrowSlot(mob);
        if (slot < 0) return;
        Container inv = mob.getCompanionInventory();
        ItemStack ammo = inv.getItem(slot);

        float power = BowItem.getPowerForTime(FULL_DRAW_TICKS);
        AbstractArrow arrow = ProjectileUtil.getMobArrow(mob, ammo, power);
        arrow = bowItem.customArrow(arrow);
        // Apply vanilla bow enchantment behavior (Power/Punch/Flame etc.) to the real arrow.
        arrow.setEnchantmentEffectsFromEntity(mob, power);
        arrow.setCritArrow(power >= 1.0F);

        // Lead a moving target: per-tick velocity from the last position, over the flight time.
        double flight = Math.min(10.0D, dist / 3.0D);
        double tx = t.getX() + (t.getX() - t.xo) * flight * 0.8D;
        double tz = t.getZ() + (t.getZ() - t.zo) * flight * 0.8D;
        double ty = t.getY(0.3333333333333333D);
        // Human-like aim: predict movement, but deliberately keep a small skill/distance-based
        // error. This is not an entity-locked aimbot and gives the target a real chance to dodge.
        double ax = tx - mob.getX();
        double az = tz - mob.getZ();
        double ah = Math.max(0.001D, Math.sqrt(ax * ax + az * az));
        double nx = -az / ah, nz = ax / ah;
        double spread = 0.012D + Math.min(0.055D, dist / 28.0D * 0.055D);
        double lateral = mob.getRandom().nextGaussian() * spread * ah;
        double vertical = mob.getRandom().nextGaussian() * spread * 0.65D * ah;
        tx += nx * lateral;
        tz += nz * lateral;
        ty += vertical;
        double dx = tx - mob.getX();
        double dz = tz - mob.getZ();
        double dy = ty - arrow.getY();
        double h = Math.sqrt(dx * dx + dz * dz);
        // Full-draw arrows (speed 3.0) barely drop; compensate a little for gravity.
        arrow.shoot(dx, dy + h * 0.06D, dz, 3.0F, 1.0F);
        // The draw state covers the charging animation; this release event makes the
        // PlayerModel visibly complete the hand movement on remote clients.
        mob.swing(InteractionHand.MAIN_HAND, true);

        mob.playSound(SoundEvents.ARROW_SHOOT, 1.0F, 1.0F / (mob.getRandom().nextFloat() * 0.4F + 0.8F));
        mob.level().addFreshEntity(arrow);

        ammo.shrink(1);
        if (ammo.isEmpty()) inv.setItem(slot, ItemStack.EMPTY);
        inv.setChanged();
        bow.hurtAndBreak(1, mob, e -> e.broadcastBreakEvent(InteractionHand.MAIN_HAND));
    }
}
