package com.rlclones.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RenderDetailPolicyTest {
    @Test void onlyDistantUnfocusedClonesLoseCosmeticDetail(){
        assertFalse(RenderDetailPolicy.simplify(true,true,false,32*32,32));
        assertTrue(RenderDetailPolicy.simplify(true,true,false,32*32+.01,32));
        assertFalse(RenderDetailPolicy.simplify(true,false,false,128*128,32));
        assertFalse(RenderDetailPolicy.simplify(true,true,true,128*128,32));
        assertFalse(RenderDetailPolicy.simplify(false,true,false,128*128,32));
        assertFalse(RenderDetailPolicy.simplify(true,true,false,64*64,96));
    }
    @Test void invalidCameraInputsNeverHideDetail(){
        for(double value:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,-1})
            assertFalse(RenderDetailPolicy.simplify(true,true,false,value,32));
        for(double value:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY})
            assertFalse(RenderDetailPolicy.simplify(true,true,false,10000,value));
    }
}
