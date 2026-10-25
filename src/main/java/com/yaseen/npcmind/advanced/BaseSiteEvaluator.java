package com.yaseen.npcmind.advanced;

import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

/** Scores real loaded terrain for a possible base; it never fabricates suitability. */
public final class BaseSiteEvaluator {
    public static String evaluate(CompanionEntity c,int radius){
        if(!(c.level() instanceof ServerLevel l))return "ERROR not_server"; radius=Math.max(8,Math.min(48,radius));BlockPos b=c.blockPosition();String dim=l.dimension().location().toString();
        double best=-1;BlockPos bestPos=null;String reason="";
        for(int dx=-radius;dx<=radius;dx+=4)for(int dz=-radius;dz<=radius;dz+=4){BlockPos p=l.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,new BlockPos(b.getX()+dx,0,b.getZ()+dz));if(!l.getBlockState(p.below()).isSolid())continue;
            int water=0,solid=0;for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++){BlockPos q=p.offset(x,0,z);if(!l.getFluidState(q).isEmpty())water++;if(l.getBlockState(q.below()).isSolid())solid++;}
            int villagers=l.getEntitiesOfClass(Villager.class,new net.minecraft.world.phys.AABB(p).inflate(32),v->v.isAlive()).size();
            double score=solid-water*2-villagers*.25-(Math.abs(dx)+Math.abs(dz))*.01; if(l.getBlockState(p).is(Blocks.LAVA)||l.getBlockState(p).is(Blocks.FIRE))score-=100;
            if(score>best){best=score;bestPos=p;reason="solid="+solid+",fluid="+water+",villagers="+villagers;}
        }
        if(bestPos==null)return "RESULT found=false reason=no_real_site";c.getWorldMemory().observe("base_candidate",dim,bestPos,"score="+best+" "+reason,l.getGameTime());return "RESULT found=true dimension="+dim+" position="+bestPos+" score="+best+" facts="+reason;
    }
    private BaseSiteEvaluator(){}
}
