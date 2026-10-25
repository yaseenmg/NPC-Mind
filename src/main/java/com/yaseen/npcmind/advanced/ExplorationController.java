package com.yaseen.npcmind.advanced;

import com.yaseen.npcmind.autonomy.NavigationArbiter;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.state.SkillProgression;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Chooses real nearby positions whose chunks have not been recorded as visited. */
public final class ExplorationController {
    private static final Map<UUID,Boolean> ACTIVE=new ConcurrentHashMap<>();
    public static void set(CompanionEntity c,boolean v){ACTIVE.put(c.getUUID(),v);}
    public static boolean active(CompanionEntity c){return ACTIVE.getOrDefault(c.getUUID(),false);}
    public static void tick(CompanionEntity c){
        if(!active(c)||c.level().isClientSide()||c.tickCount%100!=0||!(c.level() instanceof ServerLevel l))return;
        BlockPos base=c.blockPosition(); BlockPos best=null; double score=-1;
        for(int dx=-8;dx<=8;dx++)for(int dz=-8;dz<=8;dz++){if(Math.abs(dx)+Math.abs(dz)<4)continue;int x=base.getX()+dx*16,z=base.getZ()+dz*16;BlockPos p=l.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,new BlockPos(x,0,z));if(c.getWorldMemory().visited(l.dimension().location().toString(),p))continue;double s=1.0/(1.0+p.distSqr(base));if(s>score){score=s;best=p;}}
        if(best!=null){c.getWorldMemory().observe("exploration_target",l.dimension().location().toString(),best,"selected real unvisited chunk",l.getGameTime());NavigationArbiter.request(c, best, "exploration", 200, 1.15);c.getSkills().add(SkillProgression.Skill.EXPLORATION,1);}
    }
    public static void clear(UUID id){ACTIVE.remove(id);}
    private ExplorationController(){}
}
