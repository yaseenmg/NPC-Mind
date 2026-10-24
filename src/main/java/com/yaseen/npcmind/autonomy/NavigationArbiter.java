package com.yaseen.npcmind.autonomy;

import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Single navigation lease/priority owner. Higher priority preempts lower priority owners. */
public final class NavigationArbiter {
    public record Lease(String owner, int priority, long untilTick, BlockPos target, double speed) {}
    private static final Map<UUID, Lease> LEASES = new ConcurrentHashMap<>();
    private NavigationArbiter() {}

    public static boolean request(CompanionEntity c, BlockPos target, String owner, int priority, double speed) {
        if (c == null || target == null || owner == null) return false;
        long now = c.level().getGameTime();
        Lease old = LEASES.get(c.getUUID());
        if (old != null && old.untilTick() > now && old.priority() > priority && !old.owner().equals(owner)) return false;
        boolean ok = c.getNavigation().moveTo(target.getX()+.5, target.getY(), target.getZ()+.5, speed);
        if (ok) LEASES.put(c.getUUID(), new Lease(owner, priority, now + 20, target, speed));
        return ok;
    }
    public static boolean request(CompanionEntity c, Entity target, String owner, int priority, double speed) {
        if (target == null) return false;
        long now=c.level().getGameTime(); Lease old=LEASES.get(c.getUUID());
        if(old!=null && old.untilTick()>now && old.priority()>priority && !old.owner().equals(owner)) return false;
        boolean ok=c.getNavigation().moveTo(target,speed);
        if(ok) LEASES.put(c.getUUID(),new Lease(owner,priority,now+20,target.blockPosition(),speed));
        return ok;
    }
    public static void release(CompanionEntity c, String owner) { if(c==null)return; Lease x=LEASES.get(c.getUUID()); if(x!=null && x.owner().equals(owner)) LEASES.remove(c.getUUID()); }
    public static String owner(CompanionEntity c) { Lease x=LEASES.get(c.getUUID()); return x==null?"none":x.owner(); }
    public static void tick(CompanionEntity c) { if(c==null)return; Lease x=LEASES.get(c.getUUID()); if(x!=null && x.untilTick()<=c.level().getGameTime()) LEASES.remove(c.getUUID(),x); }
}