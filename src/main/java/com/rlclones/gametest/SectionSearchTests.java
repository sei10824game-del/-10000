package com.rlclones.gametest;

import com.rlclones.ai.Campfire;
import com.rlclones.ai.util.SectionPresence;
import com.rlclones.ai.util.TerrainSections;
import com.rlclones.ai.util.TerrainVersion;
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
    @AfterBatch(batch="terrain-cache") public static void terrainCleanup(ServerLevel level){TerrainSections.clear();GearRecipeTests.cleanup(level);}
    private static List<BlockPos> terrainReference(ServerLevel level,BlockPos min,BlockPos max,TerrainSections.Target target){
        List<BlockPos> result=new ArrayList<>();
        for(var p:BlockPos.betweenClosed(min,max))if(!level.isOutsideBuildHeight(p)&&target.matches(level.getBlockState(p)))result.add(p.immutable());
        return result;
    }
    private static void terrainCompare(GameTestHelper h,BlockPos min,BlockPos max,TerrainSections.Target target){
        var expected=terrainReference(h.getLevel(),min,max,target);
        var actual=TerrainSections.positions(h.getLevel(),min,max,target);
        h.assertTrue(expected.equals(actual),"exact positions and original traversal order for "+target+" expected="+expected+" actual="+actual);
    }
    @GameTest(template="arena",batch="terrain-cache",timeoutTicks=80)
    public static void unchangedSectionsAreReusedAndSingleCellEditsDoNotRescan(GameTestHelper h){
        TerrainSections.clear();var level=h.getLevel();var min=new BlockPos(-16,level.getMaxBuildHeight()-48,-16);var max=min.offset(31,15,31);
        var p=min.offset(4,3,2);var q=min.offset(20,5,20);var saved=new HashMap<BlockPos,BlockState>();
        saved.put(p,level.getBlockState(p));saved.put(q,level.getBlockState(q));
        try {
            level.setBlock(p,Blocks.IRON_ORE.defaultBlockState(),2);level.setBlock(q,Blocks.DIAMOND_ORE.defaultBlockState(),2);
            terrainCompare(h,min,max,TerrainSections.Target.ORE);var before=TerrainSections.stats();
            for(int i=0;i<100;i++)TerrainSections.positions(level,min,max,TerrainSections.Target.ORE);
            var reused=TerrainSections.stats();h.assertTrue(reused.builds()==before.builds()&&reused.stateReads()==before.stateReads()&&reused.hits()>before.hits(),"unchanged scans do no candidate block reads or index builds");
            level.setBlock(p,Blocks.AIR.defaultBlockState(),2);terrainCompare(h,min,max,TerrainSections.Target.ORE);
            level.setBlock(p,Blocks.COPPER_ORE.defaultBlockState(),2);terrainCompare(h,min,max,TerrainSections.Target.ORE);
            level.setBlock(q,Blocks.WATER.defaultBlockState(),2);terrainCompare(h,min,max,TerrainSections.Target.ORE);
            level.setBlock(p,Blocks.IRON_ORE.defaultBlockState(),2);
            level.explode(null,p.getX()+.5,p.getY()+.5,p.getZ()+.5,2.0F,net.minecraft.world.level.Level.ExplosionInteraction.BLOCK);
            h.assertTrue(level.getBlockState(p).isAir(),"real explosion removed indexed ore");terrainCompare(h,min,max,TerrainSections.Target.ORE);
            var after=TerrainSections.stats();h.assertTrue(after.builds()==before.builds()&&after.stateReads()==before.stateReads()&&after.pointUpdates()>=3,"place break and fluid replacement update only changed cells");
        }finally{saved.forEach((pos,state)->level.setBlock(pos,state,2));}h.succeed();
    }
    @GameTest(template="arena",batch="terrain-cache",timeoutTicks=80)
    public static void directPaletteWritesBulkReadAndReloadInvalidateImmediately(GameTestHelper h){
        TerrainSections.clear();var level=h.getLevel();var min=new BlockPos(-32,level.getMaxBuildHeight()-48,-32);var max=min.offset(15,15,15);
        var chunk=level.getChunk(min);var section=chunk.getSection(chunk.getSectionIndex(min.getY()));var palette=section.getStates();
        h.assertTrue(palette instanceof TerrainVersion,"storage change mixin applied on dedicated server");
        var saved=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());palette.write(saved);
        var p=min.offset(5,5,5);
        try {
            level.setBlock(min,Blocks.STONE.defaultBlockState(),2); // maintain vanilla section non-air bookkeeping
            TerrainSections.positions(level,min,max,TerrainSections.Target.ORE);long builds=TerrainSections.stats().builds();
            palette.set(5,5,5,Blocks.EMERALD_ORE.defaultBlockState());
            h.assertTrue(TerrainSections.positions(level,min,max,TerrainSections.Target.ORE).contains(p),"direct palette.set tracked immediately");
            palette.getAndSet(5,5,5,Blocks.AIR.defaultBlockState());
            h.assertTrue(!TerrainSections.positions(level,min,max,TerrainSections.Target.ORE).contains(p),"direct getAndSet tracked immediately");
            h.assertTrue(TerrainSections.stats().builds()==builds,"direct writes need no full rebuild");
            var replacement=palette.copy();replacement.set(6,5,5,Blocks.REDSTONE_ORE.defaultBlockState());
            var data=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try{replacement.write(data);palette.read(data);}finally{data.release();}
            h.assertTrue(TerrainSections.positions(level,min,max,TerrainSections.Target.ORE).contains(min.offset(6,5,5)),"bulk replacement invalidates cached section");
            long before=TerrainSections.stats().builds();com.rlclones.ai.Perception.invalidateClassifications();
            terrainCompare(h,min,max,TerrainSections.Target.ORE);h.assertTrue(TerrainSections.stats().builds()>before,"tag reload reindexes only on next request");
        }finally{palette.read(saved);section.recalcBlockCounts();saved.release();}h.succeed();
    }
    @GameTest(template="arena",batch="terrain-cache",timeoutTicks=80)
    public static void workstationAndFireStateChangesPreserveReferenceAndNearestTies(GameTestHelper h){
        TerrainSections.clear();var level=h.getLevel();var center=new BlockPos(-1,level.getMaxBuildHeight()-40,-1);var min=center.offset(-16,-4,-16);var max=center.offset(16,4,16);
        var p=center.west(4);var q=center.east(4);var oldP=level.getBlockState(p);var oldQ=level.getBlockState(q);
        try {
            level.setBlock(p,Blocks.CAMPFIRE.defaultBlockState(),2);level.setBlock(q,Blocks.SOUL_CAMPFIRE.defaultBlockState(),2);
            terrainCompare(h,min,max,TerrainSections.Target.LIT_CAMPFIRE);h.assertTrue(Objects.equals(reference(level,center),Campfire.nearest(level,center)),"campfire nearest tie preserved");
            level.setBlock(p,Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT,false),2);
            h.assertTrue(q.equals(Campfire.nearest(level,center)),"extinguished fire removed in same tick");
            level.setBlock(q,Blocks.CRAFTING_TABLE.defaultBlockState(),2);terrainCompare(h,min,max,TerrainSections.Target.TABLE);
            level.setBlock(q,Blocks.FURNACE.defaultBlockState(),2);terrainCompare(h,min,max,TerrainSections.Target.FURNACE);terrainCompare(h,min,max,TerrainSections.Target.TABLE);
            level.setBlock(q,Blocks.AIR.defaultBlockState(),2);terrainCompare(h,min,max,TerrainSections.Target.FURNACE);
            for(int y:new int[]{level.getMinBuildHeight(),level.getMaxBuildHeight()-1}){
                var c=center.atY(y);terrainCompare(h,c.offset(-1,-2,-1),c.offset(1,2,1),TerrainSections.Target.ORE);
            }
        }finally{level.setBlock(p,oldP,2);level.setBlock(q,oldQ,2);}h.succeed();
    }
    @GameTest(template="arena",batch="terrain-cache",timeoutTicks=80)
    public static void unchangedOreIndexStillChecksNewlyOpenedSightLines(GameTestHelper h){
        TerrainSections.clear();var level=h.getLevel();
        for(var p:BlockPos.betweenClosed(new BlockPos(3,1,5),new BlockPos(12,5,9))){boolean roof=p.getY()==5||p.getY()==1||p.getZ()==5||p.getZ()==9||p.getX()==3||p.getX()==12;h.setBlock(p,roof?Blocks.STONE:Blocks.AIR);}
        var clone=GearRecipeTests.clone(h);var pos=h.absoluteVec(new net.minecraft.world.phys.Vec3(5.5,2,7.5));clone.teleportTo(pos.x,pos.y,pos.z);clone.setYRot(-90);clone.setYHeadRot(-90);clone.setXRot(0);clone.setOnGround(true);
        var ore=h.absolutePos(new BlockPos(9,2,7));h.setBlock(new BlockPos(9,2,7),Blocks.IRON_ORE);h.setBlock(new BlockPos(8,2,7),Blocks.STONE);h.setBlock(new BlockPos(8,3,7),Blocks.STONE);
        TerrainSections.positions(level,clone.blockPosition().offset(-6,-4,-6),clone.blockPosition().offset(6,4,6),TerrainSections.Target.ORE);
        var vision=clone.controller().perception();long now=level.getGameTime();vision.update(now);
        h.assertTrue(!vision.blocks().containsKey(ore),"shared target index does not see through walls");
        h.setBlock(new BlockPos(8,2,7),Blocks.AIR);h.setBlock(new BlockPos(8,3,7),Blocks.AIR);vision.update(now+8);
        h.assertTrue(vision.blocks().get(ore)==com.rlclones.ai.Perception.BlockKind.ORE,"unchanged ore index reuses geometry but checks newly open live sight line");h.succeed();
    }
    @GameTest(template="arena",batch="terrain-cache",timeoutTicks=40)
    public static void cacheQueriesDoNotLoadUnknownChunks(GameTestHelper h){
        var level=h.getLevel();var p=new BlockPos(30000000,level.getMinBuildHeight()+32,30000000);int x=p.getX()>>4,z=p.getZ()>>4;
        h.assertTrue(level.getChunk(x,z,net.minecraft.world.level.chunk.ChunkStatus.FULL,false)==null,"test chunk starts unloaded");
        h.assertTrue(TerrainSections.positions(level,p,p.offset(3,3,3),TerrainSections.Target.ORE).isEmpty(),"unloaded terrain stays unknown");
        h.assertTrue(level.getChunk(x,z,net.minecraft.world.level.chunk.ChunkStatus.FULL,false)==null,"query creates no chunk load");h.succeed();
    }


    private static BlockPos cakeReference(ServerLevel level, BlockPos center, int radius) {
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-radius,-1,-radius), center.offset(radius,1,radius))) {
            if (level.getBlockState(p).getBlock() instanceof CakeBlock) return p.immutable();
        }
        return null;
    }

    @GameTest(template="arena",batch="cake-scan",timeoutTicks=100)
    public static void cakeScanPreservesTraversalAndLiveChanges(GameTestHelper h) {
        var level=h.getLevel();
        var center=new BlockPos(-1,level.getMaxBuildHeight()-40,-1);
        var a=center.offset(-5,-1,-5); var b=center.offset(5,1,5);
        var savedA=level.getBlockState(a); var savedB=level.getBlockState(b);
        try {
            level.setBlock(a,Blocks.AIR.defaultBlockState(),2);
            level.setBlock(b,Blocks.AIR.defaultBlockState(),2);
            for(int phase=0;phase<4;phase++) {
                if(phase==1) level.setBlock(b,Blocks.CAKE.defaultBlockState(),2);
                if(phase==2) level.setBlock(a,Blocks.CAKE.defaultBlockState().setValue(CakeBlock.BITES,3),2);
                if(phase==3) level.setBlock(a,Blocks.AIR.defaultBlockState(),2);
                for(int radius:new int[]{0,2,3,5,16}) {
                    h.assertTrue(Objects.equals(cakeReference(level,center,radius),
                        com.rlclones.ai.Senses.cakeNearby(level,center,radius)),
                        "cake lookup matches original order after live edit, phase="+phase+" radius="+radius);
                }
            }
            level.setBlock(b,Blocks.AIR.defaultBlockState(),2);
            for(int i=0;i<100;i++) {cakeReference(level,center,5);com.rlclones.ai.Senses.cakeNearby(level,center,5);}
            long[] old=new long[5],fresh=new long[5];
            for(int round=0;round<5;round++) {
                for(int pass=0;pass<2;pass++) {
                    boolean candidate=(round+pass)%2!=0;
                    long start=System.nanoTime();
                    for(int i=0;i<300;i++) {
                        BlockPos result=candidate?com.rlclones.ai.Senses.cakeNearby(level,center,5):cakeReference(level,center,5);
                        h.assertTrue(result==null,"high-altitude empty cake scan");
                    }
                    (candidate?fresh:old)[round]=System.nanoTime()-start;
                }
            }
            Arrays.sort(old);Arrays.sort(fresh);
            System.out.printf(java.util.Locale.ROOT,"CAKE-BENCH scans=300 old=%.3fms new=%.3fms%n",old[2]/1e6,fresh[2]/1e6);
        } finally {level.setBlock(a,savedA,2);level.setBlock(b,savedB,2);}
        h.succeed();
    }

}
