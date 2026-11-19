package com.rlclones.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OreWorkLearningTest {
    @Test void methodsRepresentDifferentKnownTradeoffs() {
        assertTrue(OreWorkLearning.cost(0,2,3,0,0)<OreWorkLearning.cost(0,3,0,0,15));
        assertTrue(OreWorkLearning.cost(1,3,0,0,0)<OreWorkLearning.cost(1,2,3,0,15));
        assertTrue(OreWorkLearning.cost(2,3,0,3,0)<OreWorkLearning.cost(2,2,0,0,15));
        assertTrue(OreWorkLearning.cost(3,3,0,0,15)<OreWorkLearning.cost(3,2,0,0,0));
    }
    @Test void outcomeUsesTimeInjuryAndActualToolWear() {
        float quick=OreWorkLearning.reward(true,20,0,1);
        assertTrue(quick>OreWorkLearning.reward(true,200,0,1));
        assertTrue(quick>OreWorkLearning.reward(true,20,4,1));
        assertTrue(quick>OreWorkLearning.reward(true,20,0,10));
        assertTrue(OreWorkLearning.reward(false,240,0,0)<0);
        assertTrue(OreWorkLearning.reward(false,240,100,100)>=-2);
    }
}
