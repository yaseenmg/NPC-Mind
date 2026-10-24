package com.yaseen.npcmind.entity;

import com.yaseen.npcmind.NPCMind;
import com.yaseen.npcmind.advanced.AdaptiveCombatController;
import com.yaseen.npcmind.advanced.BodyguardController;
import com.yaseen.npcmind.advanced.CompanionRangedGoal;
import com.yaseen.npcmind.advanced.CombatLearning;
import com.yaseen.npcmind.advanced.EquipmentAnalyzer;
import com.yaseen.npcmind.advanced.SurvivalGuard;
import com.yaseen.npcmind.agent.AgentEngine;
import com.yaseen.npcmind.autonomy.NavigationArbiter;
import com.yaseen.npcmind.crafting.CompanionInventory;
import com.yaseen.npcmind.memory.MemoryStore;
import com.yaseen.npcmind.state.SkillProgression;
import com.yaseen.npcmind.task.TaskManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.UUID;

/**
 * The autonomous companion entity. World mutation remains on the logical server thread;
 * planning and language-model requests are delegated to AgentEngine.
 */
public class CompanionEntity extends PathfinderMob {
    private static final String OWNER = "Owner";
    private static final String FOLLOW = "FollowEnabled";
    private static final String FRIENDLY_COMBAT = "FriendlyCombat";
    private static final String RANGED = "RangedMode";
    private static final String EATING = "Eating";
    private static final String SHIELD_DISPLACED = "ShieldDisplacedByTotem";
    private static final String DISPLACED_SHIELD = "DisplacedShield";
    private UUID owner;
    private boolean followEnabled = true;
    private boolean friendlyCombat;
    private boolean rangedMode;
    private boolean eating;
    private boolean shieldDisplacedByTotem;
    private ItemStack displacedShield = ItemStack.EMPTY;
    private final CompanionInventory companionInventory = new CompanionInventory(this);
    private final SkillProgression skills = new SkillProgression();
    private final CombatLearning combatLearning = new CombatLearning();
    private final CompanionDecisions decisions = new CompanionDecisions();
    private long lastAutonomousThink;
    private long lastOwnerSeen;
    private String pendingSpeech = "";

    public CompanionEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.xpReward = 0;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 40.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.32D)
                .add(Attributes.ATTACK_DAMAGE, 6.0D)
                .add(Attributes.FOLLOW_RANGE, 48.0D)
                .add(Attributes.ARMOR, 8.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.25D);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.15D, true));
        this.goalSelector.addGoal(2, new WaterAvoidingRandomStrollGoal(this, 0.8D));
        this.goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 12.0F));
        this.goalSelector.addGoal(4, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Mob.class, 10, true, false,
                target -> target instanceof net.minecraft.world.entity.monster.Monster));
    }

    public UUID getOwnerUUID() { return owner; }

    public void setOwnerUUID(UUID owner) {
        this.owner = owner;
        this.lastOwnerSeen = tickCount;
    }

    public Player getOwnerPlayer() {
        if (owner == null || !(level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) return null;
        Entity entity = serverLevel.getEntity(owner);
        return entity instanceof Player player ? player : null;
    }

    public CompanionInventory getCompanionInventory() { return companionInventory; }
    public SkillProgression getSkills() { return skills; }
    public CombatLearning getCombatLearning() { return combatLearning; }
    public CompanionDecisions getDecisions() { return decisions; }
    public boolean isFollowEnabled() { return followEnabled; }
    public void setFollowEnabled(boolean value) { followEnabled = value; }
    public boolean isFriendlyCombat() { return friendlyCombat; }
    public void setFriendlyCombat(boolean value) { friendlyCombat = value; }
    public boolean isRangedMode() { return rangedMode; }
    public void setRangedMode(boolean value) { rangedMode = value; }
    public boolean isEating() { return eating; }
    public boolean isShieldDisplacedByTotem() { return shieldDisplacedByTotem; }
    public void setShieldDisplacedByTotem(boolean value) { shieldDisplacedByTotem = value; }
    public ItemStack getDisplacedShield() { return displacedShield; }
    public void rememberDisplacedShield(ItemStack stack) { displacedShield = stack == null ? ItemStack.EMPTY : stack.copy(); }
    public void clearDisplacedShield() { displacedShield = ItemStack.EMPTY; }

    public boolean startEating(boolean emergency) {
        if (isUsingItem()) return false;
        ItemStack food = findFood();
        if (food.isEmpty()) return false;
        eating = true;
        getNavigation().stop();
        setItemSlot(EquipmentSlot.MAINHAND, food.copy());
        startUsingItem(InteractionHand.MAIN_HAND);
        return true;
    }

    private ItemStack findFood() {
        for (int i = 0; i < companionInventory.getContainerSize(); i++) {
            ItemStack stack = companionInventory.getItem(i);
            if (!stack.isEmpty() && stack.getFoodProperties(this) != null) {
                ItemStack food = stack.copy();
                food.setCount(1);
                companionInventory.removeItem(i, 1);
                return food;
            }
        }
        return ItemStack.EMPTY;
    }

    public void say(String message) {
        if (message == null || message.isBlank()) return;
        pendingSpeech = message;
        if (!level().isClientSide() && getOwnerPlayer() instanceof ServerPlayer player) {
            player.sendSystemMessage(Component.literal("[NPC Mind] " + message));
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) return;
        if (isUsingItem() && getUseItem().getItem().isEdible()) {
            if (getUseItemRemainingTicks() <= 1) {
                stopUsingItem();
                eating = false;
                heal(4.0F);
            }
        } else if (eating) {
            eating = false;
        }
        SurvivalGuard.tick(this);
        AdaptiveCombatController.tick(this);
        BodyguardController.tick(this);
        CompanionRangedGoal.tick(this);
        NavigationArbiter.tick(this);
        if (tickCount - lastAutonomousThink >= 100 && owner != null && !AgentEngine.isBusy(getUUID())) {
            lastAutonomousThink = tickCount;
            Player player = getOwnerPlayer();
            if (player instanceof ServerPlayer serverPlayer && followEnabled) {
                AgentEngine.runAutonomousAsync(this, serverPlayer);
            }
        }
        Player player = getOwnerPlayer();
        if (player != null) lastOwnerSeen = tickCount;
        else if (tickCount - lastOwnerSeen > 1200 && owner != null) {
            // Keep the companion persistent; do not despawn merely because the owner logs out.
            lastOwnerSeen = tickCount;
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.getEntity() instanceof Player player && owner != null && owner.equals(player.getUUID())) return false;
        return super.hurt(source, amount);
    }

    @Override
    public boolean canAttack(LivingEntity target) {
        if (target instanceof Player player && owner != null && owner.equals(player.getUUID())) return false;
        return super.canAttack(target);
    }

    @Override
    public boolean isPushable() { return true; }

    @Override
    protected void defineSynchedData() { super.defineSynchedData(); }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (owner != null) tag.putUUID(OWNER, owner);
        tag.putBoolean(FOLLOW, followEnabled);
        tag.putBoolean(FRIENDLY_COMBAT, friendlyCombat);
        tag.putBoolean(RANGED, rangedMode);
        tag.putBoolean(EATING, eating);
        tag.putBoolean(SHIELD_DISPLACED, shieldDisplacedByTotem);
        if (!displacedShield.isEmpty()) tag.put(DISPLACED_SHIELD, displacedShield.save(new CompoundTag()));
        companionInventory.save(tag);
        skills.save(tag);
        combatLearning.save(tag);
        decisions.save(tag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        owner = tag.hasUUID(OWNER) ? tag.getUUID(OWNER) : null;
        followEnabled = tag.getBoolean(FOLLOW);
        friendlyCombat = tag.getBoolean(FRIENDLY_COMBAT);
        rangedMode = tag.getBoolean(RANGED);
        eating = tag.getBoolean(EATING);
        shieldDisplacedByTotem = tag.getBoolean(SHIELD_DISPLACED);
        displacedShield = tag.contains(DISPLACED_SHIELD) ? ItemStack.of(tag.getCompound(DISPLACED_SHIELD)) : ItemStack.EMPTY;
        companionInventory.load(tag);
        skills.load(tag);
        combatLearning.load(tag);
        decisions.load(tag);
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!level().isClientSide()) {
            TaskManager.cancelCurrentTask(this);
            AdaptiveCombatController.clear(getUUID());
            AgentEngine.cancel(getUUID());
        }
        super.remove(reason);
    }

    public void setOwner(Player player) {
        if (player != null) setOwnerUUID(player.getUUID());
    }

    public boolean isOwner(Player player) {
        return player != null && owner != null && owner.equals(player.getUUID());
    }

    public Optional<UUID> owner() { return Optional.ofNullable(owner); }

    public boolean canFollowOwner() {
        Player player = getOwnerPlayer();
        return followEnabled && player != null && distanceToSqr(player) > 16.0D;
    }

    public BlockPos getFollowPosition() {
        Player player = getOwnerPlayer();
        if (player == null) return blockPosition();
        Vec3 delta = position().subtract(player.position());
        if (delta.lengthSqr() < 0.001D) delta = new Vec3(1, 0, 0);
        Vec3 destination = player.position().add(delta.normalize().scale(2.0D));
        return BlockPos.containing(destination);
    }

    public void setPendingSpeech(String message) { pendingSpeech = message == null ? "" : message; }
    public String getPendingSpeech() { return pendingSpeech; }

    private CompanionEntity() { this(NPCMind.COMPANION.get(), null); }
}
