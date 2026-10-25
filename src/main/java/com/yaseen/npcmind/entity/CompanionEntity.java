package com.yaseen.npcmind.entity;
import com.yaseen.npcmind.autonomy.NavigationArbiter;

import com.yaseen.npcmind.ai.GroqBrain;
import com.yaseen.npcmind.advanced.AdaptiveCombatController;
import com.yaseen.npcmind.advanced.CombatLearning;
import com.yaseen.npcmind.advanced.SurvivalGuard;
import com.yaseen.npcmind.core.CompanionKernel;
import com.yaseen.npcmind.state.CompanionState;
import com.yaseen.npcmind.state.SkillProgression;
import com.yaseen.npcmind.state.TeachingMemory;
import com.yaseen.npcmind.state.CraftingKnowledge;
import com.yaseen.npcmind.state.WorldMemory;
import com.yaseen.npcmind.state.LongTermGoal;
import com.yaseen.npcmind.state.ActionPermissions;
import com.yaseen.npcmind.state.DecisionJournal;
import com.yaseen.npcmind.debug.AuditLog;
import com.yaseen.npcmind.advanced.WorldObservation;
import com.yaseen.npcmind.advanced.SceneMemory;
import com.yaseen.npcmind.advanced.BodyguardController;
import com.yaseen.npcmind.advanced.ExplorationController;
import com.yaseen.npcmind.advanced.EmergencyController;
import com.yaseen.npcmind.advanced.ExperimentJournal;
import com.yaseen.npcmind.autonomy.AutonomyState;
import com.yaseen.npcmind.autonomy.DailyRoutineController;
import com.yaseen.npcmind.task.TaskManager;
import com.yaseen.npcmind.agent.PlanExecutionController;
import com.yaseen.npcmind.autonomy.SuggestionController;
import com.yaseen.npcmind.team.CompanionTeam;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CompanionEntity extends PathfinderMob {
    private static volatile CompanionSpeech speechSink;
    private static final Map<UUID, Long> PICKUP_READY_AT = new ConcurrentHashMap<>();
    private static final String OWNER_TAG = "Owner";
    private static final EntityDataAccessor<Integer> FOOD_LEVEL = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
    private static final int MAX_FOOD = 20;
    private static final EntityDataAccessor<Float> FOOD_SATURATION = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.FLOAT);
    private float foodExhaustion = 0.0F;
    private static final EntityDataAccessor<Integer> SELECTED_SLOT = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Byte> ANIMATION_ACTION = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.BYTE);
    /** 0 normal, 1 mining, 2 placing, 3 eating/use-action. */
    private byte animationActionTicks = 0;
    private int eatingTicks = 0;
    private ItemStack eatingStack = ItemStack.EMPTY;
    private ItemStack eatingRestore = ItemStack.EMPTY;
    private UUID owner;
    private boolean followEnabled = false;
    /** Explicit combat chosen by the player; unlike bodyguard mode this may target the owner. */
    private boolean friendlyCombat = false;
    private static final String CANONICAL_NAME = "Steve";
    private String displayName = CANONICAL_NAME;
    /** The companion's real persistent 36-slot player-style inventory (27 main + 9 hotbar). */
    private final SimpleContainer inventory = new SimpleContainer(36);
    private final CombatLearning combatLearning = new CombatLearning(this);
    private final SurvivalGuard.State survivalState = new SurvivalGuard.State();
    private final CompanionState companionState = new CompanionState();
    private final SkillProgression skills = new SkillProgression();
    private final TeachingMemory teaching = new TeachingMemory();
    private final CraftingKnowledge craftingKnowledge = new CraftingKnowledge();
    private final WorldMemory worldMemory = new WorldMemory();
    private final LongTermGoal goal = new LongTermGoal();
    private final ActionPermissions permissions = new ActionPermissions();
    private final DecisionJournal decisions = new DecisionJournal();
    private final ExperimentJournal experiments = new ExperimentJournal();
    private final AutonomyState autonomy = new AutonomyState();
    private final CompanionTeam team = new CompanionTeam();
    private BlockPos smeltJobPos;
    private String smeltJobOutput = "";
    private long smeltJobStartedTick = -1L;
    /** True only when an emergency Totem displaced the companion's real shield. */
    private boolean shieldDisplacedByTotem = false;
    /** Snapshot of the shield that was moved out of the offhand for the emergency Totem. */
    private ItemStack displacedShield = ItemStack.EMPTY;

    public boolean isShieldDisplacedByTotem() { return shieldDisplacedByTotem; }
    public ItemStack getDisplacedShield() { return displacedShield; }
    public void rememberDisplacedShield(ItemStack shield) { displacedShield = shield == null ? ItemStack.EMPTY : shield.copy(); }
    public void clearDisplacedShield() { displacedShield = ItemStack.EMPTY; }
    public void setShieldDisplacedByTotem(boolean value) { shieldDisplacedByTotem = value; }

    public CompanionEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        // Canonical in-game identity: the companion is always named Steve.
        this.displayName = CANONICAL_NAME;
        this.setCustomName(Component.literal(CANONICAL_NAME));
        this.setCustomNameVisible(true);
    }

    /**
     * Keep the vanilla LivingEntity combat/navigation machinery active. Earlier
     * builds imported these goals but never registered them, which meant that a
     * target could be selected while the entity had no goal capable of actually
     * following/attacking it.
     */
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new com.yaseen.npcmind.advanced.CompanionRangedGoal(this));
        this.goalSelector.addGoal(1, new com.yaseen.npcmind.advanced.CompanionMeleeGoal(this, 1.2D));
        this.goalSelector.addGoal(2, new FollowOwnerGoal(this, 1.05D, 3.0F, 24.0F));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(FOOD_LEVEL, MAX_FOOD);
        this.entityData.define(FOOD_SATURATION, 5.0F);
        this.entityData.define(SELECTED_SLOT, 0);
        this.entityData.define(ANIMATION_ACTION, (byte) 0);
    }

    /** True only while the companion is really eating (not drawing a bow / blocking). */
    public boolean isEating() { return !eatingStack.isEmpty(); }

    private boolean rangedMode = false;
    public boolean isRangedMode() { return rangedMode; }
    public void setRangedMode(boolean v) { rangedMode = v; }

    public int getFoodLevel() { return this.entityData.get(FOOD_LEVEL); }
    public void setFoodLevel(int value) { this.entityData.set(FOOD_LEVEL, Math.max(0, Math.min(MAX_FOOD, value))); }
    public int getMaxFoodLevel() { return MAX_FOOD; }
    public float getFoodSaturation() { return this.entityData.get(FOOD_SATURATION); }
    public void setFoodSaturation(float value) { this.entityData.set(FOOD_SATURATION, Math.max(0.0F, Math.min(getFoodLevel(), Math.min(20.0F, value)))); }
    public void addFoodExhaustion(float amount) {
        if (amount <= 0.0F) return;
        foodExhaustion += amount;
        while (foodExhaustion >= 4.0F) {
            foodExhaustion -= 4.0F;
            if (getFoodSaturation() > 0.0F) setFoodSaturation(Math.max(0.0F, getFoodSaturation() - 1.0F));
            else if (getFoodLevel() > 0) setFoodLevel(getFoodLevel() - 1);
        }
    }
    public int getSelectedSlot() { return this.entityData.get(SELECTED_SLOT); }
    public void setSelectedSlot(int slot) { this.entityData.set(SELECTED_SLOT, Math.max(0, Math.min(8, slot))); }

    public byte getAnimationAction() { return this.entityData.get(ANIMATION_ACTION); }

    /**
     * Broadcast a short explicit action to the client renderer. This is used for
     * actions that cannot be inferred reliably from vanilla LivingEntity flags,
     * such as server-side progressive mining and direct block placement.
     */
    public void playAnimationAction(byte action, int ticks) {
        this.entityData.set(ANIMATION_ACTION, action);
        this.animationActionTicks = (byte) Math.max(0, Math.min(20, ticks));
        // ANIMATION_ACTION is useful for custom client state, but vanilla PlayerModel
        // renders hand movement from the normal swing state. Trigger both so mining and
        // placing are visible on clients that use the stock renderer as well.
        if ((action == 1 || action == 2) && !level().isClientSide()) {
            swing(InteractionHand.MAIN_HAND, true);
        }
    }

    /** Starts a real vanilla-style eating cycle so the client can render the food in hand. */
    /** Eats only when hungry (normal food). */
    public boolean startEatingFromInventory() { return eat(false, false); }

    /**
     * Eats to restore health as well as hunger. With {@code emergency} the best healing item
     * (golden apples first) is used even if the hunger bar is full.
     */
    public boolean startEating(boolean emergency) { return eat(true, emergency); }

    private boolean eat(boolean allowHurtEating, boolean allowGolden) {
        if (isUsingItem() || level().isClientSide()) return false;
        boolean hungry = getFoodLevel() < MAX_FOOD;
        boolean hurt = getHealth() < getMaxHealth() - 1.0F;
        if (!hungry && !(allowHurtEating && hurt)) return false;
        int best = -1;
        double bestScore = -1.0D;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !stack.isEdible()) continue;
            boolean enchanted = stack.is(net.minecraft.world.item.Items.ENCHANTED_GOLDEN_APPLE);
            boolean golden = enchanted || stack.is(net.minecraft.world.item.Items.GOLDEN_APPLE);
            if (golden && !allowGolden) continue; // keep golden apples for emergencies
            net.minecraft.world.food.FoodProperties fp = stack.getFoodProperties(this);
            if (fp == null) continue;
            boolean harmful = false;
            for (var effect : fp.getEffects()) {
                if (effect.getFirst().getEffect().getCategory() == net.minecraft.world.effect.MobEffectCategory.HARMFUL) { harmful = true; break; }
            }
            if (harmful) continue; // rotten flesh, spider eye, pufferfish...
            double score = fp.getNutrition() + fp.getNutrition() * fp.getSaturationModifier();
            if (golden) score += enchanted ? 200.0D : 100.0D;
            if (score > bestScore) { bestScore = score; best = i; }
        }
        if (best < 0) return false;
        ItemStack one = inventory.getItem(best).split(1);
        if (one.isEmpty()) return false;
        eatingRestore = getMainHandItem().copy();
        eatingStack = one.copy();
        setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, one);
        eatingTicks = Math.max(1, one.getUseDuration());
        playAnimationAction((byte) 3, Math.min(20, eatingTicks));
        startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
        inventory.setChanged();
        return true;
    }

    /**
     * PathfinderMob does not normally wear player-style armor durability down.
     * Vanilla mobs can therefore keep armor at full durability forever.
     * Apply the normal minimum-one / quarter-damage durability rule used by
     * vanilla living entities and broadcast the break event to the client.
     */
    @Override
    protected void hurtArmor(DamageSource source, float amount) {
        if (amount <= 0.0F) return;
        int damage = Math.max(1, (int) Math.floor(amount / 4.0F));
        for (net.minecraft.world.entity.EquipmentSlot slot : new net.minecraft.world.entity.EquipmentSlot[]{
                net.minecraft.world.entity.EquipmentSlot.HEAD,
                net.minecraft.world.entity.EquipmentSlot.CHEST,
                net.minecraft.world.entity.EquipmentSlot.LEGS,
                net.minecraft.world.entity.EquipmentSlot.FEET}) {
            ItemStack stack = getItemBySlot(slot);
            if (!stack.isEmpty() && stack.isDamageableItem()
                    && !(stack.getItem().isFireResistant() && source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE))) {
                stack.hurtAndBreak(damage, this, e -> e.broadcastBreakEvent(slot));
            }
        }
    }

    /**
     * Mob#doHurtTarget never wears the held weapon down (only Player#attack does), so the
     * companion's sword used to last forever. Apply the vanilla rule: 1 durability for
     * swords, 2 for tools/other damageable items, using the normal Unbreaking logic.
     */
    @Override
    public boolean doHurtTarget(net.minecraft.world.entity.Entity target) {
        // Keep every direct combat entry point on the same player-parity damage path.
        if (target instanceof net.minecraft.world.entity.LivingEntity living) {
            return com.yaseen.npcmind.advanced.PlayerCombatParity.attack(
                    this, living, com.yaseen.npcmind.advanced.PlayerCombatParity.attackDelayTicks(this));
        }
        return false;
    }

    /**
     * Vanilla mobs never wear a shield down when it blocks (only Player does).
     * Apply the player rule: blocked damage >= 3 costs 1 + floor(damage) durability.
     */
    @Override
    protected void hurtCurrentlyUsedShield(float amount) {
        ItemStack shield = getUseItem();
        if (!level().isClientSide() && shield.getItem() instanceof net.minecraft.world.item.ShieldItem && amount >= 3.0F) {
            int wear = 1 + (int) Math.floor(amount);
            final InteractionHand hand = getUsedItemHand();
            shield.hurtAndBreak(wear, this, e -> e.broadcastBreakEvent(hand));
            if (shield.isEmpty()) {
                setItemSlot(hand == InteractionHand.MAIN_HAND ? net.minecraft.world.entity.EquipmentSlot.MAINHAND : net.minecraft.world.entity.EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                stopUsingItem();
                playSound(net.minecraft.sounds.SoundEvents.SHIELD_BREAK, 0.8F, 0.8F + getRandom().nextFloat() * 0.4F);
            }
        }
    }

    @Override
    public void aiStep() {
        // Handle the companion's own eating timer before LivingEntity can
        // auto-complete the use action. This keeps the visible eating animation
        // synchronized with the actual consumption.
        // Eating got interrupted from outside (item use stopped): give the food back and restore the weapon.
        if (!level().isClientSide() && !eatingStack.isEmpty() && !isUsingItem()) {
            inventory.addItem(eatingStack.copy());
            setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, eatingRestore);
            eatingRestore = ItemStack.EMPTY;
            eatingStack = ItemStack.EMPTY;
        }
        if (!level().isClientSide() && isUsingItem() && getUsedItemHand() == net.minecraft.world.InteractionHand.MAIN_HAND
                && !eatingStack.isEmpty()) {
            if (eatingTicks > 0) eatingTicks--;
            if (eatingTicks <= 0) {
                ItemStack food = getUseItem().copy();
                try {
                    if (!food.isEmpty() && food.isEdible()) {
                        var props = food.getFoodProperties(this);
                        ItemStack result = food.finishUsingItem(level(), this);
                        int nutrition = props == null ? 0 : props.getNutrition();
                        int gain = Math.max(1, nutrition);
                        int oldFood = getFoodLevel();
                        setFoodLevel(oldFood + gain);
                        float saturationGain = props == null ? 0.0F : props.getNutrition() * props.getSaturationModifier() * 2.0F;
                        setFoodSaturation(Math.min(getFoodLevel(), getFoodSaturation() + saturationGain));
                        getCompanionState().eatEvent();
                        if (!result.isEmpty()) inventory.addItem(result.copy());
                    }
                } catch (Throwable ignored) {
                    // The item was already split from the inventory. Restore it instead of
                    // silently losing food when a modded food implementation throws.
                    if (!food.isEmpty()) inventory.addItem(food.copy());
                } finally {
                    stopUsingItem();
                setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, eatingRestore);
                eatingRestore = ItemStack.EMPTY;
                    eatingStack = ItemStack.EMPTY;
                }
            }
        }
        super.aiStep();
        if (!level().isClientSide()) {
            // Player-like hunger/exhaustion and natural regeneration.
            if (isSprinting()) addFoodExhaustion(0.01F);
            if (isPassenger() == false && !onGround() && getDeltaMovement().y < -0.08D) addFoodExhaustion(0.01F);
            if (getFoodLevel() >= 18 && getHealth() < getMaxHealth() && tickCount % 80 == 0) {
                heal(1.0F);
                addFoodExhaustion(3.0F);
            } else if (getFoodSaturation() > 0.0F && getHealth() < getMaxHealth() && tickCount % 10 == 0) {
                heal(1.0F);
                addFoodExhaustion(3.0F);
            } else if (getFoodLevel() == 0 && tickCount % 80 == 0) {
                net.minecraft.world.Difficulty difficulty = level().getDifficulty();
                if (difficulty != net.minecraft.world.Difficulty.PEACEFUL) {
                    float floor = difficulty == net.minecraft.world.Difficulty.EASY ? 10.0F : 1.0F;
                    if (getHealth() > floor) hurt(damageSources().starve(), 1.0F);
                }
            }
            if (tickCount % 20 == 0 && getFoodLevel() <= 6 && !isFriendlyCombat() && getTarget() == null) startEatingFromInventory();
            if (tickCount % 100 == 0 && getTarget() == null && !isFriendlyCombat() && getHealth() < getMaxHealth() * 0.7F) startEating(false);
        }
    }

    public void setOwner(UUID id) {
        this.owner = id;
    }

    public void setFollowEnabled(boolean enabled) { this.followEnabled = enabled; }

    public boolean isFollowEnabled() { return followEnabled; }

    public void setFriendlyCombat(boolean enabled) { this.friendlyCombat = enabled; }
    public boolean isFriendlyCombat() { return friendlyCombat; }

    public UUID getOwner() {
        return owner;
    }

    /**
     * Real player-style hand-off: right-clicking the owned companion with an item
     * actually gives it the held stack.  The previous build only handled dropped
     * ItemEntities, so a normal Minecraft "give him this food/armor" interaction
     * appeared to do nothing.
     */
    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        // A generic library NPC must not consume a player's held item or impose a
        // player-interaction policy. Host NPC mods can opt in by overriding this hook.
        if (!allowDirectItemTransfer()) return InteractionResult.PASS;
        if (player == null || owner == null || !owner.equals(player.getUUID())) {
            return InteractionResult.PASS;
        }

        ItemStack held = player.getItemInHand(hand);
        if (held.isEmpty()) return InteractionResult.PASS;
        // A plain right-click must never swallow what the player is holding (it happened by
        // accident all the time). Giving an item now requires Shift + right-click.
        if (!player.isShiftKeyDown()) {
            if (!level().isClientSide() && hand == InteractionHand.MAIN_HAND) {
                player.displayClientMessage(Component.literal("Shift + status update"), true);
            }
            return InteractionResult.PASS;
        }
        if (level().isClientSide()) return InteractionResult.sidedSuccess(true);

        int before = held.getCount();
        ItemStack remainder = CompanionItemHandler.accept(this, held, player, true);
        int consumed = before - remainder.getCount();
        if (consumed <= 0) return InteractionResult.PASS;

        player.setItemInHand(hand, remainder);
        return InteractionResult.CONSUME;
    }

    /** Override in a host NPC when Shift-right-click item hand-off is desired. */
    protected boolean allowDirectItemTransfer() { return false; }

    public Container getCompanionInventory() { return inventory; }
    public CombatLearning getCombatLearning() { return combatLearning; }
    public SurvivalGuard.State survivalState() { return survivalState; }
    public CompanionState getCompanionState() { return companionState; }
    public SkillProgression getSkills() { return skills; }
    public TeachingMemory getTeachingMemory() { return teaching; }
    public CraftingKnowledge getCraftingKnowledge() { return craftingKnowledge; }
    public WorldMemory getWorldMemory() { return worldMemory; }
    public LongTermGoal getGoal() { return goal; }
    public ActionPermissions getPermissions() { return permissions; }
    public DecisionJournal getDecisions() { return decisions; }
    public ExperimentJournal getExperiments() { return experiments; }
    public AutonomyState getAutonomy() { return autonomy; }
    public CompanionTeam getTeamState() { return team; }

    public void startSmeltJob(BlockPos pos, String outputId) { smeltJobPos = pos.immutable(); smeltJobOutput = outputId == null ? "" : outputId; smeltJobStartedTick = tickCount; }

    /** Pickup is always active, matching the reliable pickup lifecycle used by Player2NPC. */
    public void enablePickup(int ticks) { /* compatibility */ }

    private void collectNearbyDrops() {
        if (level().isClientSide() || tickCount % 2 != 0 || !isAlive()) return;
        // Hiromi uses a compact local pickup scan; keep ours deliberately close so
        // thrown items do not get vacuumed from across the room.
        final double radius = 2.25D;
        for (net.minecraft.world.entity.item.ItemEntity drop : level().getEntitiesOfClass(
                net.minecraft.world.entity.item.ItemEntity.class,
                getBoundingBox().inflate(radius, 1.25D, radius),
                e -> e.isAlive() && !e.getItem().isEmpty()
                        && !e.hasPickUpDelay()
                        && e.distanceToSqr(this) <= radius * radius)) {
            ItemStack before = drop.getItem().copy();
            ItemStack remainder = CompanionItemHandler.accept(this, before, null, false);
            int consumed = before.getCount() - remainder.getCount();
            if (consumed <= 0) continue;

            if (remainder.isEmpty()) {
                drop.discard();
            } else {
                drop.setItem(remainder);
            }
            inventory.setChanged();
            level().broadcastEntityEvent(this, (byte) 7);
        }
    }

    public String findLookTargetUuid(ServerPlayer player) {
        if (player == null || level().isClientSide()) return null;
        net.minecraft.world.phys.HitResult hit = player.pick(12.0D, 1.0F, false);
        if (hit instanceof net.minecraft.world.phys.EntityHitResult ehr) {
            net.minecraft.world.entity.Entity e = ehr.getEntity();
            if (e != null && e != this && e.isAlive() && e instanceof net.minecraft.world.entity.LivingEntity) return e.getUUID().toString();
        }
        return null;
    }



    @Override
    public Component getDisplayName() { return Component.literal(displayName); }

    public Player getOwnerPlayer() {
        if (owner == null || level().getServer() == null) return null;
        return level().getServer().getPlayerList().getPlayer(owner);
    }

    /** Installs the host mod's chat/UI/narration adapter. Pass null to restore plain messages. */
    public static void setSpeechSink(CompanionSpeech sink) { speechSink = sink; }

    public void say(String text) {
        if (text == null || text.isBlank()) return;
        if (getOwnerPlayer() instanceof ServerPlayer player) {
            String styled = getCompanionState().styleSpeech(text);
            CompanionSpeech sink = speechSink;
            if (sink != null) sink.speak(this, player, styled);
            else player.sendSystemMessage(Component.literal("<" + displayName + "> " + styled));
        }
    }

    public void ask(String message) {
        if (level().isClientSide()) return;
        message = com.yaseen.npcmind.ai.InputNormalizer.normalize(message);
        com.yaseen.npcmind.NPCMind.LOG.debug("[NPCMind] companion.ask owner={} msg={}", getOwnerPlayer() != null, message);
        if (getOwnerPlayer() instanceof ServerPlayer player) {
            com.yaseen.npcmind.debug.Trace.log(getUUID().toString(), "CHAT", "owner message = \"{}\"", message);
            AuditLog.chat(getUUID(), "OWNER_TO_COMPANION", message);
            player.displayClientMessage(Component.literal("Thinking..."), true);
            String learned = teaching.resolve(message);
            com.yaseen.npcmind.debug.Trace.log("CHAT", "-> routed to LLM AgentEngine (model will choose tools); learnedShortcut={}", learned != null);
            GroqBrain.askAsync(this, player, learned == null ? message : "Execute this learned shortcut: " + learned);
        }
    }

    private net.minecraft.core.BlockPos auditLastMovePos;

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide()) {
            net.minecraft.core.BlockPos now = blockPosition();
            if (auditLastMovePos == null) auditLastMovePos = now;
            else if (!auditLastMovePos.equals(now)) {
                AuditLog.event("MOVEMENT", "MOVE", getUUID(),
                        "from=" + auditLastMovePos.getX() + "," + auditLastMovePos.getY() + "," + auditLastMovePos.getZ()
                                + " | to=" + now.getX() + "," + now.getY() + "," + now.getZ()
                                + " | tick=" + tickCount);
                auditLastMovePos = now;
            }
        }
        if (animationActionTicks > 0) {
            animationActionTicks--;
            if (animationActionTicks == 0) this.entityData.set(ANIMATION_ACTION, (byte) 0);
        }
        // Universal navigation jump assist: whenever the companion is actually moving along a path
        // (follow, mining, exploration, combat...) and bumps into a 1-block step, make it jump.
        // Previously only the follow goal did this, so during tasks it "walked fast but never
        // jumped" and got stuck on single blocks.
        if (!level().isClientSide() && onGround() && horizontalCollision && !getNavigation().isDone()) {
            getJumpControl().jump();
        }
        collectNearbyDrops();
        tickSmeltJob();
        com.yaseen.npcmind.advanced.InventoryIntelligence.tick(this);
        // Version 60: one authoritative lifecycle owner. Controllers are no longer chained
        // ad-hoc from the entity class; CompanionKernel applies their priority and prevents a
        // background/autonomous controller from stealing navigation from an active work task.
        CompanionKernel.tick(this);
        companionState.tick(this);
        skills.applyAttributes(this);
        if (!level().isClientSide() && tickCount % 40 == 0) {
            String notice = companionState.autonomyMessage(this);
            if (notice != null) say(notice);
        }
    }

    private void tickSmeltJob() {
        if (level().isClientSide() || smeltJobPos == null || !(level().getBlockEntity(smeltJobPos) instanceof AbstractFurnaceBlockEntity furnace)) return;
        if (tickCount - smeltJobStartedTick < 5) return;
        ItemStack out = furnace.getItem(2);
        if (out.isEmpty()) return;
        if (!smeltJobOutput.isBlank() && !out.getItem().builtInRegistryHolder().key().location().toString().equals(smeltJobOutput)) {
            say("I could not complete the request.");
            smeltJobPos = null; smeltJobOutput = ""; smeltJobStartedTick = -1L;
            return;
        }
        ItemStack moving = out.copy();
        ItemStack left = com.yaseen.npcmind.crafting.InventoryOps.addAndReturnRemainder(inventory, moving);
        int moved = moving.getCount() - left.getCount();
        if (moved > 0) {
            out.shrink(moved);
            if (out.isEmpty()) furnace.setItem(2, ItemStack.EMPTY);
            furnace.setChanged(); inventory.setChanged();
            say("I could not complete the request.");
            smeltJobPos = null; smeltJobOutput = ""; smeltJobStartedTick = -1L;
        }
    }

    @Override
    public void die(DamageSource source) {
        if (!level().isClientSide()) {
            // Drop the real inventory and worn equipment at the exact death position.
            // The respawn system must never restore these items after death.
            dropCompanionContents();
        }
        AdaptiveCombatController.clear(getUUID());
        PlanExecutionController.clear(getUUID());
        // Quest progress is re-derived from items; a dead companion must not keep a stale quest state.
        super.die(source);
    }

    private void dropCompanionContents() {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                spawnAtLocation(stack.copy());
                inventory.setItem(i, ItemStack.EMPTY);
            }
        }
        for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            if (slot.getType() != net.minecraft.world.entity.EquipmentSlot.Type.HAND
                    && slot.getType() != net.minecraft.world.entity.EquipmentSlot.Type.ARMOR) continue;
            ItemStack stack = getItemBySlot(slot);
            if (!stack.isEmpty()) {
                spawnAtLocation(stack.copy());
                setItemSlot(slot, ItemStack.EMPTY);
            }
        }
        inventory.setChanged();
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (owner != null) tag.putUUID(OWNER_TAG, owner);
        tag.putBoolean("FollowEnabled", followEnabled);
        tag.putBoolean("FriendlyCombat", friendlyCombat);
        tag.putInt("FoodLevel", getFoodLevel());
        tag.putFloat("FoodSaturation", getFoodSaturation());
        tag.putFloat("FoodExhaustion", foodExhaustion);
        tag.putInt("SelectedSlot", getSelectedSlot());
        tag.putString("DisplayName", displayName);
        ListTag equipment = new ListTag();
        for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            ItemStack stack = getItemBySlot(slot);
            if (stack.isEmpty()) continue;
            CompoundTag entry = new CompoundTag();
            entry.putString("Slot", slot.getName());
            entry.put("Item", stack.save(new CompoundTag()));
            equipment.add(entry);
        }
        tag.put("Equipment", equipment);
        ListTag list = new ListTag();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                CompoundTag slot = new CompoundTag();
                slot.putInt("Slot", i);
                slot.put("Item", stack.save(new CompoundTag()));
                list.add(slot);
            }
        }
        tag.put("Inventory", list);
        if (smeltJobPos != null) { tag.putLong("SmeltJobPos", smeltJobPos.asLong()); tag.putString("SmeltJobOutput", smeltJobOutput); tag.putLong("SmeltJobStarted", smeltJobStartedTick); }
        tag.putBoolean("ShieldDisplacedByTotem", shieldDisplacedByTotem);
        if (!displacedShield.isEmpty()) tag.put("DisplacedShield", displacedShield.save(new CompoundTag()));
        PlanExecutionController.save(getUUID(), tag);
        combatLearning.save(tag);
        companionState.save(tag);
        skills.save(tag);
        teaching.save(tag);
        craftingKnowledge.save(tag);
        worldMemory.save(tag);
        goal.save(tag);
        permissions.save(tag);
        decisions.save(tag);
        experiments.save(tag);
        autonomy.save(tag);
        team.save(tag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID(OWNER_TAG)) owner = tag.getUUID(OWNER_TAG);
        followEnabled = tag.contains("FollowEnabled") && tag.getBoolean("FollowEnabled");
        friendlyCombat = tag.getBoolean("FriendlyCombat");
        if (tag.contains("FoodLevel", 3)) setFoodLevel(tag.getInt("FoodLevel"));
        if (tag.contains("FoodSaturation", 5)) setFoodSaturation(tag.getFloat("FoodSaturation"));
        if (tag.contains("FoodExhaustion", 5)) foodExhaustion = Math.max(0.0F, tag.getFloat("FoodExhaustion"));
        if (tag.contains("SelectedSlot", 3)) setSelectedSlot(tag.getInt("SelectedSlot"));
        // Migrate legacy saves and keep the public identity stable.
        displayName = CANONICAL_NAME;
        setCustomName(Component.literal(CANONICAL_NAME));
        setCustomNameVisible(true);
        shieldDisplacedByTotem = tag.getBoolean("ShieldDisplacedByTotem");
        displacedShield = tag.contains("DisplacedShield", 10) ? ItemStack.of(tag.getCompound("DisplacedShield")) : ItemStack.EMPTY;
        PlanExecutionController.load(getUUID(), tag);
        combatLearning.load(tag);
        companionState.load(tag);
        skills.load(tag);
        teaching.load(tag);
        craftingKnowledge.load(tag);
        worldMemory.load(tag);
        goal.load(tag);
        permissions.load(tag);
        decisions.load(tag);
        experiments.load(tag);
        autonomy.load(tag);
        team.load(tag);
        for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            setItemSlot(slot, ItemStack.EMPTY);
        }
        if (tag.contains("Equipment", 9)) {
            ListTag equipment = tag.getList("Equipment", 10);
            for (int i = 0; i < equipment.size(); i++) {
                CompoundTag entry = equipment.getCompound(i);
                String name = entry.getString("Slot");
                net.minecraft.world.entity.EquipmentSlot slot = null;
                for (net.minecraft.world.entity.EquipmentSlot candidate : net.minecraft.world.entity.EquipmentSlot.values()) {
                    if (candidate.getName().equals(name)) { slot = candidate; break; }
                }
                if (slot != null && entry.contains("Item", 10)) setItemSlot(slot, ItemStack.of(entry.getCompound("Item")));
            }
        }
        inventory.clearContent();
        if (tag.contains("SmeltJobPos", 4)) {
            smeltJobPos = BlockPos.of(tag.getLong("SmeltJobPos"));
            smeltJobOutput = tag.getString("SmeltJobOutput");
            smeltJobStartedTick = tag.getLong("SmeltJobStarted");
        }
        if (tag.contains("Inventory", 9)) {
            ListTag list = tag.getList("Inventory", 10);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag slot = list.getCompound(i);
                int index = slot.getInt("Slot");
                if (index >= 0 && index < inventory.getContainerSize()) inventory.setItem(index, ItemStack.of(slot.getCompound("Item")));
            }
        }
    }

    public static class FollowOwnerGoal extends net.minecraft.world.entity.ai.goal.Goal {
        private final CompanionEntity mob;
        private final double speed;
        private final float min;
        private final float max;

        public FollowOwnerGoal(CompanionEntity mob, double speed, float min, float max) {
            this.mob = mob;
            this.speed = speed;
            this.min = min;
            this.max = max;
        }

        @Override
        public boolean canUse() {
            Player p = mob.getOwnerPlayer();
            return mob.followEnabled && !TaskManager.isActive(mob) && mob.getTarget() == null && p != null && mob.distanceTo(p) > min;
        }

        @Override
        public boolean canContinueToUse() {
            Player p = mob.getOwnerPlayer();
            return mob.followEnabled && !TaskManager.isActive(mob) && mob.getTarget() == null && p != null && mob.distanceTo(p) > min && mob.distanceTo(p) < max * 2;
        }

        private int jumpTimer = 10;

        @Override
        public void tick() {
            Player p = mob.getOwnerPlayer();
            if (p == null) return;
            double d2 = mob.distanceToSqr(p);
            boolean sprint = d2 > 36.0D;
            mob.setSprinting(sprint);
            NavigationArbiter.request(mob, p, "follow", 300, sprint ? Math.max(1.30D, speed + 0.20D) : speed);
            if (sprint && mob.onGround() && --jumpTimer <= 0) { jumpTimer = 11 + mob.getRandom().nextInt(7); mob.getJumpControl().jump(); }
            if (mob.horizontalCollision && mob.onGround()) mob.getJumpControl().jump();
        }
    }
}
