package com.yaseen.npcmind.advanced;
import com.yaseen.npcmind.autonomy.NavigationArbiter;
import com.yaseen.npcmind.entity.CompanionEntity;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/** Player-like melee fighting instead of vanilla's slow "walk straight in, hit once a second". */
public final class CompanionMeleeGoal extends Goal {
    private static final double REACH = 3.0D;
    private static final double REACH_SQR = REACH * REACH;
    private final PathfinderMob mob;
    private final double speed;
    private int attackTicker;
    private int repath;
    private int strafeTimer;
    private int strafeDir = 1;
    private int jumpTimer;

    public CompanionMeleeGoal(PathfinderMob mob, double speed) {
        this.mob = mob;
        this.speed = speed;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override public boolean canUse() {
        LivingEntity t = mob.getTarget();
        if (t == null || !t.isAlive() || t.isSpectator()) return false;
        return !(mob instanceof CompanionEntity ce && ce.isRangedMode()
                && ce.getMainHandItem().getItem() instanceof net.minecraft.world.item.BowItem
                && CompanionRangedGoal.findArrowSlot(ce) >= 0);
    }
    @Override public boolean canContinueToUse() { return canUse(); }
    @Override public void start() {
        mob.setAggressive(true); repath = 0;
        attackTicker = PlayerCombatParity.attackDelayTicks(mob);
        jumpTimer = 8; mob.setSprinting(false);
    }
    @Override public void stop() {
        if (mob.isUsingItem() && mob.getUsedItemHand() == InteractionHand.OFF_HAND) mob.stopUsingItem();
        mob.setAggressive(false); mob.setSprinting(false); mob.getNavigation().stop();
        mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0.0D);
    }
    private double distSqrTo(LivingEntity t) {
        Vec3 eye = mob.getEyePosition(); AABB bb = t.getBoundingBox();
        double dx = Math.max(Math.max(bb.minX - eye.x, 0.0D), eye.x - bb.maxX);
        double dy = Math.max(Math.max(bb.minY - eye.y, 0.0D), eye.y - bb.maxY);
        double dz = Math.max(Math.max(bb.minZ - eye.z, 0.0D), eye.z - bb.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }
    private int cooldownTicks() { return cooldownTicksFor(mob.getMainHandItem()); }
    public static int cooldownTicksFor(ItemStack s) {
        if (s == null || s.isEmpty()) return 10;
        double attackSpeed = 4.0D;
        for (AttributeModifier m : s.getAttributeModifiers(EquipmentSlot.MAINHAND).get(Attributes.ATTACK_SPEED)) attackSpeed += m.getAmount();
        attackSpeed = Math.max(0.5D, Math.min(4.0D, attackSpeed));
        return Math.max(6, (int) Math.ceil(20.0D / attackSpeed));
    }
    private void handleShield(double d2, boolean eating) {
        if (!(mob.getOffhandItem().getItem() instanceof net.minecraft.world.item.ShieldItem) || eating) return;
        boolean usingShield = mob.isUsingItem() && mob.getUsedItemHand() == InteractionHand.OFF_HAND;
        LivingEntity target = mob.getTarget();
        boolean enemySwing = target != null && target.swinging && target.swingTime >= 0 && target.swingTime < 8 && d2 <= 25.0D;
        boolean dangerClose = d2 <= 20.25D && attackTicker < PlayerCombatParity.attackDelayTicks(mob) - 5;
        boolean wantUp = enemySwing || dangerClose;
        if (wantUp && !mob.isUsingItem()) mob.startUsingItem(InteractionHand.OFF_HAND);
        else if (!wantUp && usingShield) mob.stopUsingItem();
    }
    @Override public void tick() {
        LivingEntity t = mob.getTarget(); if (t == null || !t.isAlive()) return;
        mob.getLookControl().setLookAt(t, 30.0F, 30.0F);
        attackTicker = Math.min(attackTicker + 1, PlayerCombatParity.attackDelayTicks(mob));
        double d2 = distSqrTo(t);
        if (d2 > 30.0D) {
            if (--repath <= 0) {
                repath = 4 + mob.getRandom().nextInt(7);
                boolean sprint = d2 > 64.0D; mob.setSprinting(sprint);
                NavigationArbiter.request((CompanionEntity) mob, t, "combat_goal", 800, sprint ? Math.max(1.35D, speed + 0.18D) : speed);
            }
        } else {
            mob.getNavigation().stop();
            if (--strafeTimer <= 0) { strafeTimer = 24 + mob.getRandom().nextInt(28); strafeDir = mob.getRandom().nextBoolean() ? 1 : -1; }
            boolean ready = attackTicker >= PlayerCombatParity.attackDelayTicks(mob);
            double fx = t.getX() - mob.getX(), fz = t.getZ() - mob.getZ(), len = Math.max(0.001D, Math.sqrt(fx * fx + fz * fz));
            fx /= len; fz /= len;
            double sx = -fz * strafeDir, sz = fx * strafeDir;
            double forward = ready ? 1.0D : (d2 < 5.0D ? -0.45D : 0.05D), side = ready ? 0.18D : 0.42D;
            double px = mob.getX() + (fx * forward + sx * side) * 1.4D, pz = mob.getZ() + (fz * forward + sz * side) * 1.4D;
            boolean sprint = d2 > 42.25D || (!ready && d2 > 9.0D); mob.setSprinting(sprint);
            mob.getMoveControl().setWantedPosition(px, mob.getY(), pz, sprint ? Math.max(1.35D, speed + 0.18D) : speed);
            if (sprint && mob.onGround() && --jumpTimer <= 0) { jumpTimer = 10 + mob.getRandom().nextInt(7); mob.getJumpControl().jump(); }
            if (mob.horizontalCollision && mob.onGround()) mob.getJumpControl().jump();
        }
        boolean eatingMain = mob instanceof CompanionEntity ce2 ? ce2.isEating()
                : (mob.isUsingItem() && mob.getUsedItemHand() == InteractionHand.MAIN_HAND);
        handleShield(d2, eatingMain);
        if (attackTicker >= PlayerCombatParity.attackDelayTicks(mob) && d2 <= REACH_SQR && !eatingMain && pathClear(t)) {
            if (PlayerCombatParity.attack(mob, t, attackTicker)) attackTicker = 0;
        }
    }
    private boolean pathClear(LivingEntity t) {
        Vec3 eye = mob.getEyePosition(); AABB bb = t.getBoundingBox();
        double cx = (bb.minX + bb.maxX) * 0.5D, cz = (bb.minZ + bb.maxZ) * 0.5D;
        Vec3[] samples = new Vec3[] { new Vec3(cx, t.getEyeY(), cz), new Vec3(cx, (bb.minY + bb.maxY) * 0.5D, cz), new Vec3(cx, bb.minY + 0.1D, cz) };
        for (Vec3 target : samples) {
            net.minecraft.world.phys.BlockHitResult hit = mob.level().clip(new net.minecraft.world.level.ClipContext(
                    eye, target, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, mob));
            if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) return false;
        }
        return true;
    }
}