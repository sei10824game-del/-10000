package com.rlclones.ai;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class BlockMemoryTest {
    @Test void countsAndOrderMatchReferenceAcrossReplacementAndEviction() {
        var actual = new Perception.BlockMemory();
        var reference = new LinkedHashMap<BlockPos, Perception.BlockKind>();
        var kinds = Perception.BlockKind.values();
        Random random = new Random(1729);
        for (int i = 0; i < 12000; i++) {
            BlockPos pos = new BlockPos(random.nextInt(300), 0, 0);
            if (random.nextInt(5) == 0) {
                assertEquals(reference.remove(pos), actual.remove(pos));
            } else {
                var kind = kinds[random.nextInt(kinds.length)];
                assertEquals(reference.put(pos, kind), actual.put(pos, kind));
                if (reference.size() > 128) reference.remove(reference.keySet().iterator().next());
            }
            assertEquals(new java.util.ArrayList<>(reference.entrySet()), new java.util.ArrayList<>(actual.entrySet()));
            for (var kind : kinds) {
                assertEquals(reference.values().stream().filter(k -> k == kind).count(), actual.count(kind));
            }
        }
    }
}
