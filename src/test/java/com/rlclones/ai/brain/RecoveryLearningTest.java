package com.rlclones.ai.brain;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryLearningTest {
    @Test void learnsSuccessfulMethodWhileKeepingExploration() {
        var learning=new RecoveryLearning();
        for(int n=0;n<40;n++) for(int a=0;a<4;a++) learning.record(3,a,a==1?1:-0.6f,a==1);
        int right=0,other=0;var random=new Random(10824);
        for(int n=0;n<1000;n++) if(learning.choose(3,15,random)==1)right++;else other++;
        assertTrue(right>850);assertTrue(other>0);
        // Another situation is learned independently.
        for(int a=0;a<4;a++) learning.record(0,a,a==0?1:-1,a==0);
        assertEquals(0,learning.choose(0,1,new Random(1)));
    }
    @Test void masksAndUntriedActionsPreventUnsafeOrRepeatedChoices() {
        var learning=new RecoveryLearning();var random=new Random(1);
        learning.record(0,0,1,true);
        assertEquals(1,learning.choose(0,3,random));
        for(int i=0;i<100;i++) assertEquals(3,learning.choose(0,8,random));
        assertEquals(-1,learning.choose(0,0,random));
        assertEquals(-1,learning.choose(-1,15,random));
    }
    @Test void motionAloneAndInjuryAreNotSuccess() {
        assertFalse(RecoveryLearning.succeeded(2,0,0,false));
        assertFalse(RecoveryLearning.succeeded(2,1,2,false));
        assertTrue(RecoveryLearning.succeeded(1,0.5,0,false));
        assertTrue(RecoveryLearning.succeeded(1,-0.5,0,true));
        assertTrue(RecoveryLearning.reward(2,0,0,0,false)<0);
        assertTrue(RecoveryLearning.reward(2,1,0,0,false)>RecoveryLearning.reward(2,1,2,60,false));
    }
    @Test void saveAndMergePreserveEvidenceAndDoNotAlias() {
        var a=new RecoveryLearning();a.record(0,0,1,true);
        var b=new RecoveryLearning();for(int i=0;i<3;i++)b.record(0,0,-1,false);
        a.merge(b);assertEquals(-0.5f,a.value(0,0));assertEquals(4,a.trials());assertEquals(1,a.wins());
        var copy=RecoveryLearning.load(a.save());copy.record(0,0,1,true);
        assertEquals(4,a.trials());assertEquals(5,copy.trials());
        a.merge(a);assertEquals(4,a.trials());
    }
    @Test void corruptOrMissingDataAndInvalidRewardsCannotPoisonLearning() {
        var learning=new RecoveryLearning();learning.record(0,0,Float.NaN,true);learning.record(8,0,1,true);
        assertEquals(0,learning.trials());
        assertEquals(0,RecoveryLearning.load(new byte[0]).trials());
        learning.record(1,2,1,true);byte[] bytes=learning.save();
        assertEquals(0,RecoveryLearning.load(java.util.Arrays.copyOf(bytes,bytes.length-1)).trials());
        bytes[4]=(byte)0xff;assertEquals(0,RecoveryLearning.load(bytes).trials());
    }
}
