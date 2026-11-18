package com.rlclones.ai.brain;

import com.rlclones.ai.combat.CombatAction;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BrainTest {
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
}
