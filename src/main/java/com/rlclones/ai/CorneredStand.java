package com.rlclones.ai;

/** Evidence window, not a claim to know every possible exit in the entire world. */
public final class CorneredStand {
    private long checked=Long.MIN_VALUE/2,started=-1,progress=-1;
    private double bestGap;
    private int blocked;
    public void reset(){checked=Long.MIN_VALUE/2;started=progress=-1;blocked=0;bestGap=0;}
    public boolean observe(long now,double gap,boolean safeRoute,boolean pressured){
        if(!pressured||!Double.isFinite(gap)){reset();return false;}
        if(now-checked<20)return false;
        checked=now;
        if(started<0){started=progress=now;bestGap=gap;}
        if(gap>=bestGap+2){bestGap=gap;progress=now;blocked=0;}
        blocked=safeRoute?0:blocked+1;
        return blocked>=3 && now-started>=40 || now-progress>=120;
    }
}
