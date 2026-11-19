package com.rlclones.ai;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DevelopmentLearningTest {
    @Test void workOutcomesNeedResultsAndAccountForCosts(){assertTrue(DevelopmentLearning.outcome(0,10,0,false)<0);assertTrue(DevelopmentLearning.outcome(4,20,0,false)>DevelopmentLearning.outcome(4,1000,0,false));assertTrue(DevelopmentLearning.outcome(4,20,0,false)>DevelopmentLearning.outcome(4,20,4,false));assertTrue(DevelopmentLearning.outcome(4,20,0,true)<0);}
    @Test void urgentAndPersonalActionsAreNotCivicRoles(){assertEquals(-1,DevelopmentLearning.roleOf(com.rlclones.ai.strategy.Option.FLEE));assertEquals(-1,DevelopmentLearning.roleOf(com.rlclones.ai.strategy.Option.EAT));assertEquals(3,DevelopmentLearning.roleOf(com.rlclones.ai.strategy.Option.FEED));}
}
