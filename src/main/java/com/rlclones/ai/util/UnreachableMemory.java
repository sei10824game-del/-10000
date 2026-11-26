package com.rlclones.ai.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.*;
import java.lang.ref.WeakReference;
import java.util.*;

/** Per-agent, bounded negative results. Never shared between differently sized/skilled clones. */
public final class UnreachableMemory {
    private record Cell(int x,int y,int z,WeakReference<PalettedContainer<?>> palette,long version) {}
    private record Failure(WeakReference<Level> level,BlockPos from,long at,long ttl,long generation,List<Cell> cells) {}
    private final Map<BlockPos,Failure> failures=new LinkedHashMap<>();
    public void remember(Level level,BlockPos from,BlockPos target,long now,long ttl) {
        int minX=(Math.min(from.getX(),target.getX())-16)>>4,maxX=(Math.max(from.getX(),target.getX())+16)>>4;
        int minZ=(Math.min(from.getZ(),target.getZ())-16)>>4,maxZ=(Math.max(from.getZ(),target.getZ())+16)>>4;
        int minY=Math.max(level.getMinBuildHeight(),Math.min(from.getY(),target.getY())-8)>>4;
        int maxY=Math.min(level.getMaxBuildHeight()-1,Math.max(from.getY(),target.getY())+8)>>4;
        if(minY>maxY || (long)(maxX-minX+1)*(maxZ-minZ+1)*(maxY-minY+1)>128){failures.remove(target);return;}
        List<Cell> cells=new ArrayList<>();
        for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)for(int y=minY;y<=maxY;y++) {
            var chunk=level.getChunk(x,z,ChunkStatus.FULL,false);
            if(chunk==null){cells.add(new Cell(x,y,z,null,0));continue;}
            var palette=chunk.getSection(chunk.getSectionIndex(y<<4)).getStates();
            if(!(palette instanceof TerrainVersion tracked)){failures.remove(target);return;}
            cells.add(new Cell(x,y,z,new WeakReference<>(palette),tracked.rlclones$terrainVersion()));
        }
        if(failures.size()>=8 && !failures.containsKey(target))failures.remove(failures.keySet().iterator().next());
        failures.put(target.immutable(),new Failure(new WeakReference<>(level),from.immutable(),now,Math.max(1,ttl),TerrainSections.generation(),cells));
    }
    public boolean blocked(Level level,BlockPos from,BlockPos target,long now) {
        Failure f=failures.get(target);if(f==null)return false;
        boolean valid=f.level().get()==level && f.generation()==TerrainSections.generation() && f.from().distSqr(from)<=4 && now>=f.at() && now-f.at()<f.ttl();
        if(valid)for(var cell:f.cells()) {
            var chunk=level.getChunk(cell.x(),cell.z(),ChunkStatus.FULL,false);
            if(chunk==null){if(cell.palette()!=null){valid=false;break;}continue;}
            var palette=chunk.getSection(chunk.getSectionIndex(cell.y()<<4)).getStates();
            if(cell.palette()==null || cell.palette().get()!=palette || !(palette instanceof TerrainVersion tracked) || tracked.rlclones$terrainVersion()!=cell.version()){valid=false;break;}
        }
        if(!valid)failures.remove(target);
        return valid;
    }
    public void forget(BlockPos target){failures.remove(target);}
    public boolean contains(BlockPos target){return failures.containsKey(target);}
    public void clear(){failures.clear();}
    public int size(){return failures.size();}
}
