package com.rlclones.ai.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraftforge.common.Tags;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Geometry only: shared exact target locations, not a clone's discoveries or visibility decisions. */
public final class TerrainSections {
    public enum Target {
        ORE, TABLE, FURNACE, LIT_CAMPFIRE;
        public boolean matches(BlockState s) {
            return switch(this) {
                case ORE -> s.is(Tags.Blocks.ORES);
                case TABLE -> s.is(Blocks.CRAFTING_TABLE);
                case FURNACE -> s.is(Blocks.FURNACE);
                case LIT_CAMPFIRE -> s.getBlock() instanceof CampfireBlock && s.getValue(CampfireBlock.LIT);
            };
        }
    }
    private static final class Matches {
        final BitSet bits=new BitSet(4096);
        int[] offsets;
        int[] offsets() {
            if(offsets==null)offsets=bits.stream().toArray();
            return offsets;
        }
        void update(int index,boolean match) {
            if(bits.get(index)!=match){bits.set(index,match);offsets=null;}
        }
    }
    private static final class Entry {
        long version,epoch;
        final EnumMap<Target,Matches> targets=new EnumMap<>(Target.class);
        Entry(long version,long epoch){this.version=version;this.epoch=epoch;}
    }
    private static final Comparator<BlockPos> ORDER=Comparator.<BlockPos>comparingInt(BlockPos::getZ).thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX);
    private static final Map<PalettedContainer<?>,Entry> CACHE=new WeakHashMap<>();
    private static final AtomicLong EPOCH=new AtomicLong();
    private static long builds,hits,pointUpdates,stateReads;
    public record Stats(long builds,long hits,long pointUpdates,long stateReads,int sections) {}
    public static synchronized Stats stats(){return new Stats(builds,hits,pointUpdates,stateReads,CACHE.size());}
    public static void invalidate(){EPOCH.incrementAndGet();}
    public static synchronized void clear(){CACHE.clear();invalidate();builds=hits=pointUpdates=stateReads=0;}

    /** Called only for watched block palettes. Single-cell changes need no rescan of their section. */
    public static synchronized void changed(PalettedContainer<?> palette,long version,int index,BlockState state) {
        Entry e=CACHE.get(palette);
        if(e==null)return;
        if(e.epoch!=EPOCH.get()||version!=e.version+1){CACHE.remove(palette);return;}
        e.targets.forEach((target,matches)->matches.update(index,target.matches(state)));
        e.version=version;pointUpdates++;
    }
    private static synchronized int[] offsets(LevelChunkSection section,Target target) {
        PalettedContainer<BlockState> palette=section.getStates();
        if(!(palette instanceof TerrainVersion tracked))return uncached(section,target).offsets();
        long version=tracked.rlclones$terrainVersion(),epoch=EPOCH.get();
        Entry e=CACHE.get(palette);
        if(e==null||e.version!=version||e.epoch!=epoch){
            if(CACHE.size()>=2048){var oldest=CACHE.keySet().iterator();if(oldest.hasNext()){oldest.next();oldest.remove();}}
            e=new Entry(version,epoch);CACHE.put(palette,e);tracked.rlclones$watchTerrain();
        }
        Matches matches=e.targets.get(target);
        if(matches==null){matches=uncached(section,target);e.targets.put(target,matches);builds++;}
        else hits++;
        return matches.offsets();
    }
    private static Matches uncached(LevelChunkSection section,Target target) {
        Matches matches=new Matches();
        if(section.maybeHas(target::matches)) {
            for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                stateReads++;
                if(target.matches(section.getBlockState(x,y,z)))matches.bits.set(x|(z<<4)|(y<<8));
            }
        }
        return matches;
    }
    /** Same global iteration order as BlockPos.betweenClosed. Never creates chunk tickets or loads chunks. */
    public static List<BlockPos> positions(Level level,BlockPos min,BlockPos max,Target target) {
        List<BlockPos> found=new ArrayList<>();
        int lowY=Math.max(min.getY(),level.getMinBuildHeight()),highY=Math.min(max.getY(),level.getMaxBuildHeight()-1);
        if(lowY>highY)return List.of();
        for(int sx=min.getX()>>4;sx<=max.getX()>>4;sx++)for(int sz=min.getZ()>>4;sz<=max.getZ()>>4;sz++) {
            var chunk=level.getChunk(sx,sz,ChunkStatus.FULL,false);
            if(chunk==null)continue;
            for(int sy=lowY>>4;sy<=highY>>4;sy++) {
                var section=chunk.getSection(chunk.getSectionIndex(sy<<4));
                if(section.hasOnlyAir())continue; // match LevelChunk.getBlockState, even during bulk/mod edits
                for(int packed:offsets(section,target)) {
                    int x=(sx<<4)+(packed&15),z=(sz<<4)+((packed>>4)&15),y=(sy<<4)+(packed>>8);
                    if(x>=min.getX()&&x<=max.getX()&&y>=lowY&&y<=highY&&z>=min.getZ()&&z<=max.getZ())found.add(new BlockPos(x,y,z));
                }
            }
        }
        found.sort(ORDER);
        return found;
    }
    private TerrainSections() {}
}
