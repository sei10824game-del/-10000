package com.rlclones.gametest;

import com.rlclones.ai.Campfire;
import com.rlclones.ai.util.SectionPresence;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.Tags;
import net.minecraftforge.gametest.*;
import java.util.*;
import java.util.function.Predicate;

@GameTestHolder("rlclones_sections")
@PrefixGameTestTemplate(false)
public final class SectionSearchTests {
    private static final Predicate<BlockState> FIRE = state -> state.getBlock() instanceof CampfireBlock && state.getValue(CampfireBlock.LIT);
    private static final Predicate<BlockState> ORE = state -> state.is(Tags.Blocks.ORES);
    private static BlockPos reference(ServerLevel level, BlockPos center) {
        BlockPos best=null; double distance=Double.MAX_VALUE;
        for(BlockPos p:BlockPos.betweenClosed(center.offset(-16,-4,-16),center.offset(16,4,16)))
            if(FIRE.test(level.getBlockState(p)) && p.distSqr(center)<distance) { best=p.immutable(); distance=p.distSqr(center); }
        return best;
    }
    private static void compare(GameTestHelper h, BlockPos center, Predicate<BlockState> target) {
        var min=center.offset(-16,-4,-16);var max=center.offset(16,4,16);
        var sections=new SectionPresence(h.getLevel(),min,max,target);
        int expected=0,actual=0;
        for(var p:BlockPos.betweenClosed(min,max)) {
            boolean match=target.test(h.getLevel().getBlockState(p));
            if(match) expected++;
            if(sections.mayContain(p)&&target.test(h.getLevel().getBlockState(p))) actual++;
            h.assertTrue(!match||sections.mayContain(p),"no matching block may be filtered out at "+p);
        }
        h.assertTrue(expected==actual,"palette filtering preserves all target blocks");
        h.assertTrue(sections.sectionChecks()<=18,"each section is queried at most once");
    }
    @GameTest(template="arena",batch="sections",timeoutTicks=40)
    public static void negativeCoordinatesAndChunkEdgesKeepOreAndFireMatches(GameTestHelper h) {
        var center=new BlockPos(-1,h.getLevel().getMinBuildHeight()+35,-1);
        var saved=new HashMap<BlockPos,BlockState>();
        try {
            var positions=List.of(center.offset(-16,-4,-16),center.offset(16,4,16),center.offset(0,0,1),center.offset(1,0,0));
            var states=List.of(Blocks.DIAMOND_ORE.defaultBlockState(),Blocks.NETHER_QUARTZ_ORE.defaultBlockState(),Blocks.CAMPFIRE.defaultBlockState(),Blocks.SOUL_CAMPFIRE.defaultBlockState());
            for(int i=0;i<positions.size();i++) {var p=positions.get(i);saved.put(p,h.getLevel().getBlockState(p));h.getLevel().setBlock(p,states.get(i),3);}
            compare(h,center,ORE);compare(h,center,FIRE);
            h.assertTrue(Objects.equals(reference(h.getLevel(),center),Campfire.nearest(h.getLevel(),center)),"nearest fire and tie order are preserved");
        } finally {saved.forEach((p,state)->h.getLevel().setBlock(p,state,3));}
        h.succeed();
    }
    @GameTest(template="arena",batch="sections",timeoutTicks=40)
    public static void newScanSeesSameTickPlaceBreakAndExtinguish(GameTestHelper h) {
        var c=h.absolutePos(new BlockPos(7,2,7)); var p=c.offset(2,0,0);
        var before=h.getLevel().getBlockState(p);
        try {
            h.getLevel().setBlock(p,Blocks.AIR.defaultBlockState(),3);
            compare(h,c,FIRE);
            h.getLevel().setBlock(p,Blocks.CAMPFIRE.defaultBlockState(),3);
            h.assertTrue(p.equals(Campfire.nearest(h.getLevel(),c)),"newly placed fire is visible immediately");
            h.getLevel().setBlock(p,Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT,false),3);
            h.assertTrue(!p.equals(Campfire.nearest(h.getLevel(),c)),"extinguished fire is excluded immediately");
            h.getLevel().setBlock(p,Blocks.DIAMOND_ORE.defaultBlockState(),3);compare(h,c,ORE);
            h.getLevel().setBlock(p,Blocks.AIR.defaultBlockState(),3);compare(h,c,ORE);
        } finally {h.getLevel().setBlock(p,before,3);}
        h.succeed();
    }
    @GameTest(template="arena",batch="sections",timeoutTicks=40)
    public static void worldHeightEdgesDoNotIndexOutsideSections(GameTestHelper h) {
        var center=h.absolutePos(new BlockPos(7,2,7));
        for(int y:new int[]{h.getLevel().getMinBuildHeight(),h.getLevel().getMaxBuildHeight()-1}) {
            var c=new BlockPos(center.getX(),y,center.getZ());
            compare(h,c,FIRE);compare(h,c,ORE);
            h.assertTrue(Objects.equals(reference(h.getLevel(),c),Campfire.nearest(h.getLevel(),c)),"height limit matches original fire lookup");
        }
        h.succeed();
    }
    @GameTest(template="arena",batch="sections",timeoutTicks=40)
    public static void absentTargetNeedsOnlySectionChecks(GameTestHelper h) {
        var c=h.absolutePos(new BlockPos(7,2,7)).atY(h.getLevel().getMaxBuildHeight()-40);
        var filter=new SectionPresence(h.getLevel(),c.offset(-16,-4,-16),c.offset(16,4,16),state -> false);
        int reads=0;
        for(var p:BlockPos.betweenClosed(c.offset(-16,-4,-16),c.offset(16,4,16))) if(filter.mayContain(p)) reads++;
        h.assertTrue(reads==0,"sections with no matching palette entries avoid all 9801 candidate block reads");
        h.assertTrue(filter.sectionChecks()<=18,"at most eighteen palettes replace the block reads");
        h.succeed();
    }
    @GameTest(template="arena",batch="sections",timeoutTicks=100)
    public static void compareRepeatedRealWorldLookups(GameTestHelper h) {
        var level=h.getLevel();var c=h.absolutePos(new BlockPos(7,2,7)).atY(level.getMaxBuildHeight()-40);var p=c.offset(2,0,0);
        var before=level.getBlockState(p);
        try {
            for(boolean nearby:new boolean[]{false,true}) {
                level.setBlock(p,nearby?Blocks.CAMPFIRE.defaultBlockState():Blocks.AIR.defaultBlockState(),3);
                for(int i=0;i<50;i++) {unfiltered(level,c);Campfire.nearest(level,c);}
                for(int count:new int[]{50,150,300}) {
                    long[] old=new long[5],fresh=new long[5];
                    for(int round=0;round<5;round++) {
                        if(round%2==0) {old[round]=measure(level,c,count,false);fresh[round]=measure(level,c,count,true);}
                        else {fresh[round]=measure(level,c,count,true);old[round]=measure(level,c,count,false);}
                    }
                    Arrays.sort(old);Arrays.sort(fresh);
                    h.assertTrue(Objects.equals(reference(level,c),Campfire.nearest(level,c)),"benchmark results match");
                    System.out.printf(java.util.Locale.ROOT,"SECTION-BENCH nearby=%s scans=%d old=%.3fms new=%.3fms%n",nearby,count,old[2]/1e6,fresh[2]/1e6);
                }
            }
        } finally {level.setBlock(p,before,3);}
        h.succeed();
    }
    private static final java.util.List<com.rlclones.ai.util.NearbyOffsets.Offset> OFFSETS =
            com.rlclones.ai.util.NearbyOffsets.ordered(16,4);
    /** Previous release: identical nearest-first order, without the new palette filter. */
    private static BlockPos unfiltered(ServerLevel level,BlockPos center) {
        var cursor=new BlockPos.MutableBlockPos();
        for(var o:OFFSETS) {
            cursor.set(center.getX()+o.x(),center.getY()+o.y(),center.getZ()+o.z());
            if(FIRE.test(level.getBlockState(cursor))) return cursor.immutable();
        }
        return null;
    }
    private static long measure(ServerLevel level,BlockPos center,int count,boolean filtered) {
        long start=System.nanoTime();
        for(int i=0;i<count;i++) {
            var found=filtered?Campfire.nearest(level,center):unfiltered(level,center);
            if(found!=null&&!FIRE.test(level.getBlockState(found))) throw new AssertionError("invalid fire");
        }
        return System.nanoTime()-start;
    }
}
