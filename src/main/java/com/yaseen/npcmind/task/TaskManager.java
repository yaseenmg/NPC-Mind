package com.yaseen.npcmind.task;

import com.yaseen.npcmind.autonomy.NavigationArbiter;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.state.SkillProgression;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.ForgeHooks;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Real server-side task state. No simulated world state is stored here. */
public final class TaskManager {
    private static final Map<UUID, java.util.concurrent.ConcurrentLinkedDeque<Task>> TASKS = new ConcurrentHashMap<>();
    public static String move(CompanionEntity companion, BlockPos target) {
        if (!(companion.level() instanceof ServerLevel level)) return "ERROR not_server";
        if (target == null) return "ERROR missing_target";
        if (!level.hasChunkAt(target)) return "FAILED target_chunk_not_loaded";
        if (companion.blockPosition().distSqr(target) > 256.0D * 256.0D) return "FAILED target_too_far";
        var targetState = level.getBlockState(target);
        if (!targetState.getFluidState().isEmpty() || targetState.is(net.minecraft.world.level.block.Blocks.LAVA)
                || targetState.is(net.minecraft.world.level.block.Blocks.FIRE)) return "DENIED hazardous_target";
        cancelCurrentTask(companion);
        UUID id = companion.getUUID();
        var queue = TASKS.computeIfAbsent(id,k->new java.util.concurrent.ConcurrentLinkedDeque<>());
        queue.addLast(new MoveTask(target));
        NavigationArbiter.request(companion, target, "task", 500, 1.15);
        com.yaseen.npcmind.debug.Trace.log("TASK", "move START from={} to={}", companion.blockPosition(), target);
        return "STARTED action_id=" + queue.peekLast().actionId + " move_to target=" + target;
    }

    public static String mine(CompanionEntity companion, BlockPos target) {
        if (!(companion.getOwnerPlayer() instanceof ServerPlayer)) return "DENIED owner is not online";
        UUID id = companion.getUUID();
        var queue = TASKS.computeIfAbsent(id,k->new java.util.concurrent.ConcurrentLinkedDeque<>());
        if (queue.size() >= 8) return "FAILED task_queue_full max=8";
        queue.addLast(new MineTask(target));
        com.yaseen.npcmind.debug.Trace.log("TASK", "mine START block={}", target);
        return "STARTED action_id=" + queue.peekLast().actionId + " mine_block target=" + target;
    }

    /**
     * Dig a safe 1x2 tunnel (or a 2-high staircase going down) in a horizontal direction.
     * Aborts instead of continuing when lava/water is next to the next step or a drop is ahead.
     * dirIndex: 0=N 1=E 2=S 3=W.
     */
    public static String tunnel(CompanionEntity companion, int dirIndex, int steps, boolean down) {
        if (!(companion.getOwnerPlayer() instanceof ServerPlayer)) return "DENIED owner is not online";
        var queue = TASKS.computeIfAbsent(companion.getUUID(), k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        if (queue.size() >= 8) return "FAILED task_queue_full max=8";
        net.minecraft.core.Direction[] dirs = {net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.EAST,
                net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.WEST};
        queue.addLast(new TunnelTask(dirs[((dirIndex % 4) + 4) % 4], Math.max(1, Math.min(16, steps)), down));
        return "STARTED action_id=" + queue.peekLast().actionId + " tunnel dir=" + dirs[((dirIndex % 4) + 4) % 4] + " steps=" + steps + " down=" + down;
    }

    /** Takes (and clears) the last finished-task status so the quest director can learn why a task failed. */
    public static String takeLastResult(CompanionEntity companion) {
        return LAST_RESULT.remove(companion.getUUID());
    }
    private static final Map<UUID, String> LAST_RESULT = new ConcurrentHashMap<>();

    public static boolean isActive(CompanionEntity companion) {
        var q = TASKS.get(companion.getUUID());
        return q != null && !q.isEmpty();
    }

    /** Cancel the current navigation/mining task and stop vanilla navigation immediately. */
    public static boolean cancelCurrentTask(CompanionEntity companion) {
        var q = TASKS.remove(companion.getUUID());
        NavigationArbiter.release(companion, "task");
        companion.getNavigation().stop();
        companion.setTarget(null);
        if (q != null) {
            Task t = q.peekFirst();
            if (t != null) t.status = "CANCELLED by newer command";
            return true;
        }
        return q != null;
    }

    /** High-priority cancellation that publishes a terminal result for replanning. */
    public static boolean cancelForReflex(CompanionEntity companion, String reason) {
        boolean cancelled = cancelCurrentTask(companion);
        if (cancelled) LAST_RESULT.put(companion.getUUID(), "FAILED survival_reflex " + reason);
        return cancelled;
    }

    public static String status(CompanionEntity companion) {
        var q = TASKS.get(companion.getUUID());
        Task t = q == null ? null : q.peekFirst();
        return t == null ? "IDLE" : "action_id=" + t.actionId + " " + t.status + " queue=" + q.size();
    }

    public static void tick(ServerLevel level) {
        for (Map.Entry<UUID, java.util.concurrent.ConcurrentLinkedDeque<Task>> e : TASKS.entrySet()) {
            Entity entity = level.getEntity(e.getKey());
            if (!(entity instanceof CompanionEntity companion)) continue;
            var queue = e.getValue();
            Task task = queue.peekFirst();
            if (task == null) { TASKS.remove(e.getKey(), queue); continue; }
            task.tick(companion);
            if (task.done) { com.yaseen.npcmind.debug.Trace.log("TASK", "DONE action_id={} status={}", task.actionId, com.yaseen.npcmind.debug.Trace.clip(task.status)); LAST_RESULT.put(e.getKey(), task.status); queue.pollFirst(); }
            if (queue.isEmpty()) TASKS.remove(e.getKey(), queue);
        }
    }

    private abstract static class Task {
        final String actionId = UUID.randomUUID().toString();
        String status = "RUNNING";
        boolean done;
        abstract void tick(CompanionEntity companion);
    }

    private static final class MoveTask extends Task {
        final BlockPos target;
        MoveTask(BlockPos target) { this.target = target; }
        void tick(CompanionEntity c) {
            double d = c.distanceToSqr(target.getX() + .5, target.getY(), target.getZ() + .5);
            if (d <= 2.25) { status = "SUCCESS reached " + target; done = true; return; }
            if (c.tickCount % 10 == 0) NavigationArbiter.request(c, target, "task", 500, 1.15);
            if (c.getNavigation().isDone() && d > 2.25) { status = "FAILED navigation stopped before target; actual_distance=" + Math.sqrt(d); done = true; }
        }
    }

    private static final class MineTask extends Task {
        final BlockPos target;
        int miningTicks = 0;
        int requiredTicks = -1;
        int age = 0;
        boolean permitted = false;
        MineTask accessTask;
        MineTask(BlockPos target) { this.target = target; }

        void tick(CompanionEntity c) {
            if (!(c.level() instanceof ServerLevel level)) { status = "FAILED not server level"; done = true; return; }
            // A target the companion can never reach (buried ore, blocked path) used to keep the whole
            // task queue "active" forever and freeze every autonomous system. Give up after ~25 s.
            if (++age > 500) { status = "FAILED timeout unreachable " + target; done = true; return; }
            if (!level.isLoaded(target)) return;
            var state = level.getBlockState(target);
            if (state.isAir()) { status = "SUCCESS block already air"; done = true; return; }
            if (!(c.getOwnerPlayer() instanceof ServerPlayer owner)) { status = "DENIED owner is not online"; done = true; return; }

            // Ask protection/claim mods ONCE per block (it used to fire BreakEvent on every tick of mining).
            if (!permitted) {
                if (!level.mayInteract(owner, target) || ForgeHooks.onBlockBreakEvent(level, owner.gameMode.getGameModeForPlayer(), owner, target) == -1) {
                    status = "DENIED block protection or spawn protection at " + target; done = true; return;
                }
                permitted = true;
            }

            float hardness = state.getDestroySpeed(level, target);
            if (hardness < 0) { status = "FAILED block cannot be destroyed"; done = true; return; }

            // Behave like a player: choose a suitable real tool from the companion's inventory before mining.
            // Axes are preferred for logs/wood, pickaxes for stone/ores, and the hand
            // remains a valid fallback for blocks that can actually be broken by hand.
            equipBestMiningTool(c, state);
            var tool = c.getMainHandItem();

            // A missing correct tool is NOT a reason to refuse the action.
            // The companion behaves like a player: if an axe/pickaxe is available it
            // uses the appropriate tool; otherwise it simply mines with its hand.
            // Vanilla may give fewer/no normal drops when the correct tool is missing,
            // but the block itself is still allowed to be broken.

            double distance = c.distanceToSqr(target.getX()+.5, target.getY()+.5, target.getZ()+.5);
            if (distance > 25) {
                c.getLookControl().setLookAt(target.getX() + .5D, target.getY() + .5D, target.getZ() + .5D,
                        30.0F, 30.0F);
                NavigationArbiter.request(c, target, "task", 500, 1.15);
                status = "RUNNING moving_to_block";
                c.playAnimationAction((byte) 0, 0);
                return;
            }

            // If the requested block is buried behind natural terrain, a real player cannot
            // simply stand outside it and break it. Open a one-block access path first. This
            // specifically fixes the quest's "stone behind dirt" failure: instead of giving
            // up because stone has no exposed face, the companion mines the dirt/stone blocker
            // along the actual line of sight and then resumes the original target.
            if (accessTask != null) {
                accessTask.tick(c);
                if (!accessTask.done) return;
                if (accessTask.status.startsWith("FAILED") || accessTask.status.startsWith("DENIED")) {
                    status = "FAILED access_path " + accessTask.status;
                    done = true;
                    return;
                }
                accessTask = null;
                return;
            }
            if (distance > 2.5D) {
                BlockPos blocker = findNaturalAccessBlock(level, c, target);
                if (blocker != null && !blocker.equals(target)) {
                    accessTask = new MineTask(blocker);
                    status = "RUNNING opening_access_path blocker=" + blocker + " target=" + target;
                    accessTask.tick(c);
                    return;
                }
                NavigationArbiter.request(c, target, "task", 500, 1.15);
                status = "RUNNING moving_to_block";
                return;
            }

            // IMPORTANT: never call destroyBlock immediately when a mining task starts.
            // Minecraft block breaking is progressive; the old implementation completed
            // every queued MineTask on its first tick, which made an entire tree disappear
            // almost instantly. We now spend real server ticks mining each block and only
            // destroy it after the required progress has elapsed.
            if (requiredTicks < 0) {
                requiredTicks = calculateMiningTicks(level, state, target, tool);
                miningTicks = 0;
                c.getNavigation().stop();
            }

            c.getLookControl().setLookAt(target.getX() + .5D, target.getY() + .5D, target.getZ() + .5D,
                    30.0F, 30.0F);
            c.playAnimationAction((byte) 1, 5);
            miningTicks++;
            status = "RUNNING mining " + target + " progress=" + miningTicks + "/" + requiredTicks;
            // Server-side block mutation does not automatically create the player hand
            // animation. Broadcast a real swing periodically so clients see the axe/pickaxe
            // moving while the progressive break timer is running.
            if ((miningTicks & 3) == 1) c.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);

            if (miningTicks < requiredTicks) return;

            // Re-check the block immediately before the final break so a changed/removed
            // block cannot be destroyed accidentally after a long queue wait.
            var finalState = level.getBlockState(target);
            if (finalState.isAir()) { c.playAnimationAction((byte) 0, 0); status = "SUCCESS block changed to air while mining"; done = true; return; }
            if (!finalState.is(state.getBlock())) {
                c.playAnimationAction((byte) 0, 0);
                status = "FAILED block changed while mining";
                done = true;
                return;
            }

            c.enablePickup(200);
            // Level.destroyBlock(.., true, ..) drops loot as if the tool were EMPTY: no harvest-level check and no
            // Fortune/Silk Touch. Do the drop ourselves with the real tool, then remove the block without drops.
            boolean harvest = !finalState.requiresCorrectToolForDrops() || (!tool.isEmpty() && tool.isCorrectToolForDrops(finalState));
            if (harvest) {
                net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(target);
                net.minecraft.world.level.block.Block.dropResources(finalState, level, target, be, c, tool);
            }
            boolean changed = level.destroyBlock(target, false, c);
            if (changed && !tool.isEmpty() && tool.isDamageableItem()) {
                tool.hurtAndBreak(1, c, living -> {});
                c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, tool);
            }
            if (changed) {
                c.getSkills().add(SkillProgression.Skill.MINING, 10);
                c.getCompanionState().positiveEvent();
            }
            c.playAnimationAction((byte) 0, 0);
            status = changed ? "SUCCESS mined " + target + " tool=" + (tool.isEmpty() ? "hand" : tool.getHoverName().getString()) : "FAILED destroyBlock returned false for " + target;
            done = true;
        }

        /**
         * Approximate vanilla-style progressive mining time for a server-side NPC.
         * Logs with an axe take several ticks rather than being destroyed instantly;
         * slower tools/hand mining take longer. The floor prevents queued tree logs
         * from all disappearing in the same tick even with very fast tools.
         */
        private static void equipBestMiningTool(CompanionEntity c, net.minecraft.world.level.block.state.BlockState state) {
            ItemStack current = c.getMainHandItem();
            double bestScore = miningToolScore(current, state);
            int bestSlot = -1;
            for (int i = 0; i < c.getCompanionInventory().getContainerSize(); i++) {
                ItemStack candidate = c.getCompanionInventory().getItem(i);
                double score = miningToolScore(candidate, state);
                if (score > bestScore + 0.001D) {
                    bestScore = score;
                    bestSlot = i;
                }
            }
            if (bestSlot < 0) return;

            ItemStack candidate = c.getCompanionInventory().getItem(bestSlot).copy();
            ItemStack old = c.getMainHandItem().copy();
            c.getCompanionInventory().setItem(bestSlot, old);
            c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, candidate);
            c.getCompanionInventory().setChanged();
        }

        private static double miningToolScore(ItemStack stack, net.minecraft.world.level.block.state.BlockState state) {
            if (stack == null || stack.isEmpty()) return 0.25D;
            float speed = stack.getDestroySpeed(state);
            if (speed <= 0) speed = 1.0F;
            boolean correct = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
            if (state.requiresCorrectToolForDrops() && !correct) return 0.0D;
            // Correct tools dominate. Among valid tools, the fastest wins.
            return 1000.0D + speed;
        }

        private static BlockPos findNaturalAccessBlock(ServerLevel level, CompanionEntity c, BlockPos target) {
            net.minecraft.world.phys.Vec3 from = c.getEyePosition();
            net.minecraft.world.phys.Vec3 to = new net.minecraft.world.phys.Vec3(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D);
            var hit = level.clip(new net.minecraft.world.level.ClipContext(
                    from, to, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, c));
            if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return null;
            BlockPos blocker = hit.getBlockPos();
            if (blocker.equals(target)) return null;
            var st = level.getBlockState(blocker);
            if (st.isAir() || !st.getFluidState().isEmpty()) return null;
            return isNaturalAccessBlock(st) ? blocker.immutable() : null;
        }

        private static boolean isNaturalAccessBlock(net.minecraft.world.level.block.state.BlockState st) {
            return st.is(net.minecraft.world.level.block.Blocks.DIRT)
                    || st.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)
                    || st.is(net.minecraft.world.level.block.Blocks.COARSE_DIRT)
                    || st.is(net.minecraft.world.level.block.Blocks.ROOTED_DIRT)
                    || st.is(net.minecraft.world.level.block.Blocks.GRAVEL)
                    || st.is(net.minecraft.world.level.block.Blocks.SAND)
                    || st.is(net.minecraft.world.level.block.Blocks.RED_SAND)
                    || st.is(net.minecraft.world.level.block.Blocks.STONE)
                    || st.is(net.minecraft.world.level.block.Blocks.DEEPSLATE)
                    || st.is(net.minecraft.world.level.block.Blocks.TUFF)
                    || st.is(net.minecraft.world.level.block.Blocks.ANDESITE)
                    || st.is(net.minecraft.world.level.block.Blocks.DIORITE)
                    || st.is(net.minecraft.world.level.block.Blocks.GRANITE)
                    || st.is(net.minecraft.world.level.block.Blocks.NETHERRACK);
        }

        private static int calculateMiningTicks(ServerLevel level, net.minecraft.world.level.block.state.BlockState state, BlockPos pos, net.minecraft.world.item.ItemStack tool) {
            float hardness = state.getDestroySpeed(level, pos);
            if (hardness <= 0) hardness = 1.0f;
            float speed = tool.isEmpty() ? 1.0f : tool.getDestroySpeed(state);
            if (speed <= 0) speed = 1.0f;
            boolean correct = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
            // Wrong tool / bare hand is deliberately slower, but still valid.
            double damagePerTick = (speed / hardness) / (correct ? 30.0 : 100.0);
            int ticks = (int)Math.ceil(1.0 / Math.max(0.0001, damagePerTick));
            // Keep the action visibly progressive and avoid one-tick destruction.
            return Math.max(4, Math.min(60, ticks));
        }
    }

    private static final class TunnelTask extends Task {
        final net.minecraft.core.Direction dir;
        final boolean down;
        int stepsLeft;
        MineTask current;
        final java.util.ArrayDeque<BlockPos> pending = new java.util.ArrayDeque<>();
        BlockPos nextFeet;
        int moveTicks;

        TunnelTask(net.minecraft.core.Direction dir, int steps, boolean down) {
            this.dir = dir; this.stepsLeft = steps; this.down = down;
        }

        private void fail(String why) { status = "FAILED " + why; done = true; }

        void tick(CompanionEntity c) {
            if (!(c.level() instanceof ServerLevel level)) { fail("not server level"); return; }
            if (current != null) {
                current.tick(c);
                if (!current.done) return;
                if (current.status.startsWith("DENIED") || current.status.startsWith("FAILED")) { fail("tunnel_blocked " + current.status); return; }
                current = null;
            }
            while (!pending.isEmpty()) {
                BlockPos t = pending.pollFirst();
                var st = level.getBlockState(t);
                if (st.isAir() || st.getCollisionShape(level, t).isEmpty()) continue;
                current = new MineTask(t);
                status = "RUNNING tunnel digging " + t;
                return;
            }
            if (nextFeet != null) {
                double d = c.distanceToSqr(nextFeet.getX() + 0.5, nextFeet.getY(), nextFeet.getZ() + 0.5);
                if (d <= 0.6D) {
                    nextFeet = null;
                    if (--stepsLeft <= 0) { status = "SUCCESS tunnel_done"; done = true; }
                    return;
                }
                moveTicks++;
                if (moveTicks % 10 == 1) NavigationArbiter.request(c, nextFeet, "task", 500, 1.0D);
                if (moveTicks > 140) fail("could_not_step_into_tunnel");
                return;
            }
            BlockPos feet = c.blockPosition();
            BlockPos ahead = feet.relative(dir);
            BlockPos n = down ? ahead.below() : ahead;
            // Hazards: never dig next to lava or water (flooding / burning), never walk off a drop.
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 2; dy++) for (int dz = -1; dz <= 1; dz++) {
                BlockPos q = n.offset(dx, dy, dz);
                if (!level.hasChunkAt(q)) { fail("unloaded_chunk_ahead"); return; }
                if (!level.getFluidState(q).isEmpty()) { fail("fluid_ahead"); return; }
            }
            if (!down) {
                BlockPos floor = n.below();
                if (level.getBlockState(floor).getCollisionShape(level, floor).isEmpty()) { fail("drop_ahead"); return; }
            }
            if (n.getY() <= level.getMinBuildHeight() + 6) { fail("too_deep"); return; }
            pending.addLast(ahead.above());
            pending.addLast(ahead);
            if (down) pending.addLast(n);
            nextFeet = n;
            moveTicks = 0;
            status = "RUNNING tunnel planning step";
        }
    }

    private TaskManager() {}
}
