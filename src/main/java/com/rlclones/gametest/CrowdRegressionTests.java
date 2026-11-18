package com.rlclones.gametest;

import com.rlclones.clone.Bases;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Reuse real behavior tests to ensure optimization preserves observation and player rules. */
@GameTestHolder("rlclones_crowd")
@PrefixGameTestTemplate(false)
public final class CrowdRegressionTests {
    @GameTest(template = "arena", batch = "crowd-observation", timeoutTicks = 600)
    public static void imitationLearningStillWorks(GameTestHelper h) { CloneGameTests.imitatesOtherAgents(h); }

    @GameTest(template = "arena", batch = "crowd-observation", timeoutTicks = 600)
    public static void enemyKnowledgeStillComesFromObservation(GameTestHelper h) { CloneGameTests.learnsEnemyRangeAndCooldownByWatching(h); }

    @GameTest(template = "arena", batch = "crowd-slots", timeoutTicks = 40)
    public static void noNewPopulationLimit(GameTestHelper h) { CloneGameTests.noCloneLimitByDefault(h); }

    @GameTest(template = "arena", batch = "crowd-geometry", timeoutTicks = 40)
    public static void productionGeometryTracksLiveRecordsAndDimensions(GameTestHelper h) {
        Bases bases = new Bases();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos top = h.absolutePos(new BlockPos(4, 6, 7));
            var stair = new Bases.Staircase(Level.OVERWORLD, top, dir, top, false);
            bases.staircases.add(stair);
            BlockPos future = top.relative(dir, 20).below(20);
            h.assertTrue(bases.isStairSpace(Level.OVERWORLD, future, true), "unfinished staircase protects its planned extension");
            h.assertTrue(!bases.isStairSpace(Level.NETHER, future, true), "another dimension is separate");
            stair.finished = true;
            h.assertTrue(!bases.isStairSpace(Level.OVERWORLD, future, true), "finishing immediately removes planned extension");
            stair.end = future;
            h.assertTrue(bases.isStairSpace(Level.OVERWORLD, future, false)
                    && bases.isStairTread(Level.OVERWORLD, future.below()), "extended record is immediately used");
            bases.staircases.clear();
        }
        h.succeed();
    }
}
