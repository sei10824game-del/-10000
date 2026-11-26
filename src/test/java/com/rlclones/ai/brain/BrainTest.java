package com.rlclones.ai.brain;

import com.rlclones.ai.combat.CombatAction;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BrainTest {
    @Test void oreMethodsAreSavedAndSharedIndependentlyOfRecovery() {
        Brain a=new Brain();a.oreWork.record(2,3,1,true);a.recovery.record(0,1,-1,false);
        Brain copy=a.copy();copy.oreWork.record(2,3,0,false);
        assertEquals(1,a.oreWork.trials());
        Brain merged=Brain.merge(List.of(a,copy));assertEquals(3,merged.oreWork.trials());assertEquals(2,merged.oreWork.wins());
        assertEquals(2,merged.recovery.trials());assertEquals(0,merged.recovery.wins());
        assertTrue(merged.summary().stream().anyMatch(s->s.startsWith("鉱石の取りかかり")));
    }

    @Test void recoverySurvivesCopyAndSharedBrainMerge() {
        Brain a=new Brain(); a.recovery.record(3,1,1,true);
        Brain copy=a.copy(); copy.recovery.record(3,1,-1,false);
        assertEquals(1,a.recovery.trials()); assertEquals(2,copy.recovery.trials());
        Brain merged=Brain.merge(List.of(a,copy));
        assertEquals(3,merged.recovery.trials());assertEquals(2,merged.recovery.wins());
        assertTrue(merged.summary().stream().anyMatch(line->line.contains("試行=3")));
        assertEquals(0,Brain.load(new net.minecraft.nbt.CompoundTag()).recovery.trials());
    }

    @Test
    void correctedKnowledgeSurvivesMergeAndCopy() {
        Brain corrected = new Brain();
        corrected.learnHarmful("minecraft:moss_block");
        corrected.unlearnHarmful("minecraft:moss_block");
        Brain merged = Brain.merge(List.of(corrected, new Brain()));
        assertTrue(merged.provenSafe("minecraft:moss_block"));
        assertFalse(merged.isHarmful("minecraft:moss_block"));
        assertTrue(merged.copy().provenSafe("minecraft:moss_block"));
    }

    @Test
    void harmfulEvidenceTakesPrecedenceInEitherMergeOrder() {
        Brain safe = new Brain();
        safe.unlearnHarmful("minecraft:moss_block");
        Brain harmful = new Brain();
        harmful.learnHarmful("minecraft:moss_block");
        for (Brain merged : List.of(Brain.merge(List.of(safe, harmful)), Brain.merge(List.of(harmful, safe)))) {
            assertTrue(merged.isHarmful("minecraft:moss_block"));
            assertFalse(merged.provenSafe("minecraft:moss_block"));
        }
    }

    @Test
    void selfMergeDoesNotDuplicateExperience() {
        Brain brain = new Brain();
        brain.ownUpdates = 10;
        brain.absorb(brain);
        assertEquals(10, brain.ownUpdates);
        assertEquals(1, brain.members);
    }

    @Test
    void invalidInputCannotCreateStatesOrCountAsLearning() {
        Brain brain = new Brain();
        brain.learn("enemy", 0, CombatAction.COUNT, 1f, 0, true, 0f, 0, 1f, false);
        brain.learn("enemy", 0, 0, Float.NaN, 0, true, 0f, 0, 1f, false);
        brain.learn("enemy", 0, 0, 1f, 0, false, Float.POSITIVE_INFINITY, 0, 1f, false);
        brain.learn("enemy", 0, 0, 1f, 0, true, 0f, 0, -1f, false);
        assertEquals(0, brain.ownUpdates);
        assertEquals(0, brain.replayUpdates);
        assertEquals(0, brain.totalStates());
    }
    @Test void replayBacklogSurvivesPersistenceMergeAndBudgetWithoutRepeatingOwnExperience() {
        Brain original=new Brain();
        var replay=new ReplayBuffer(4);replay.add("enemy",0,0,-5f,0,true,0f,0,1f);
        var saved=original.save();saved.putByteArray("replay",replay.toBytes());saved.putLong("ownUpdates",1);saved.putLong("pendingReplay",13);
        Brain queued=Brain.load(saved);
        long own=queued.ownUpdates,replayed=queued.replayUpdates;
        assertEquals(0,queued.replaySome(0));assertEquals(13,queued.pendingReplay());
        assertEquals(4,queued.replaySome(4));assertEquals(9,queued.pendingReplay());
        assertEquals(own,queued.ownUpdates);assertEquals(replayed+4,queued.replayUpdates);
        Brain restored=queued.copy();assertEquals(9,restored.pendingReplay());
        Brain merged=Brain.merge(List.of(queued,restored,queued));
        assertEquals(18,merged.pendingReplay());assertEquals(18,merged.replaySome(100));
        assertEquals(0,merged.pendingReplay());assertEquals(own*2,merged.ownUpdates);
        assertEquals(0,Brain.load(new net.minecraft.nbt.CompoundTag()).pendingReplay());
    }

}
