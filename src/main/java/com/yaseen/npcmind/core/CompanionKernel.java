package com.yaseen.npcmind.core;

import com.yaseen.npcmind.advanced.AdaptiveCombatController;
import com.yaseen.npcmind.advanced.BodyguardController;
import com.yaseen.npcmind.advanced.EmergencyController;
import com.yaseen.npcmind.advanced.ExplorationController;
import com.yaseen.npcmind.advanced.SceneMemory;
import com.yaseen.npcmind.advanced.SurvivalGuard;
import com.yaseen.npcmind.advanced.WorldObservation;
import com.yaseen.npcmind.autonomy.AutonomyController;
import com.yaseen.npcmind.autonomy.NavigationArbiter;
import com.yaseen.npcmind.autonomy.DailyRoutineController;
import com.yaseen.npcmind.autonomy.SuggestionController;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.task.TaskManager;

/**
 * Version 60 single lifecycle owner for the companion's high-level control loop.
 *
 * <p>The old entity tick accumulated controllers over many releases. That made it easy for a
 * new controller to start writing navigation/targets without knowing what already owned the
 * companion. This kernel enforces an explicit order:</p>
 *
 * <pre>survival -> combat -> active work -> guard/explore -> autonomy -> observation</pre>
 *
 * <p>Long-running work is owned by TaskManager; the LLM never moves the entity directly.</p>
 */
public final class CompanionKernel {
    private CompanionKernel() {}

    public static boolean tick(CompanionEntity c) {
        if (c == null || c.level().isClientSide() || !c.isAlive()) return false;
        NavigationArbiter.tick(c);

        if (SurvivalGuard.tick(c, c.survivalState())) return true;

        AdaptiveCombatController.tick(c);
        if (c.getTarget() != null && c.getTarget().isAlive()) return true;

        // A running gather/move/mine/smelt plan is authoritative. No follow/explore/LLM cycle may
        // redirect navigation while the task executor is active.
        if (TaskManager.isActive(c)) return true;

        BodyguardController.tick(c);
        if (c.getTarget() != null && c.getTarget().isAlive()) return true;

        EmergencyController.tick(c);
        if (c.getTarget() != null && c.getTarget().isAlive()) return true;

        if (c.getAutonomy().mode() == com.yaseen.npcmind.autonomy.AutonomyState.Mode.EXPLORER) {
            ExplorationController.tick(c);
            if (TaskManager.isActive(c)) return true;
        }

        AutonomyController.tick(c);
        WorldObservation.tick(c);
        SceneMemory.tick(c);
        DailyRoutineController.tick(c);
        SuggestionController.tick(c);
        return true;
    }
}