package com.rlclones.ai;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CorneredStandTest {
    @Test void repeatedNoExitCommitsButOneFailureDoesNot(){
        var s=new CorneredStand();assertFalse(s.observe(0,2,false,true));assertFalse(s.observe(20,2,false,true));assertTrue(s.observe(40,2,false,true));
    }
    @Test void realEscapeProgressAndNoPressureResetEvidence(){
        var s=new CorneredStand();for(int i=0;i<10;i++)assertFalse(s.observe(i*20,2+i*2,true,true));
        s.reset();s.observe(0,2,false,true);assertFalse(s.observe(20,2,false,false));assertFalse(s.observe(40,2,false,true));
    }
    @Test void RunningInCirclesUnderPressureEventuallyFights(){
        var s=new CorneredStand();for(int t=0;t<120;t+=20)assertFalse(s.observe(t,2,true,true));assertTrue(s.observe(120,2,true,true));
    }
}
