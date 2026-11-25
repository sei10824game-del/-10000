package com.rlclones.ai;

import net.minecraft.world.phys.Vec3;

/** Vanilla dry-air arrow recurrence: move, then 0.99 drag and 0.05 gravity. */
public final class BowPhysics {
    private BowPhysics(){}
    private static final double[] SUM=new double[401];
    static {for(int i=1;i<SUM.length;i++)SUM[i]=(1-Math.pow(.99,i*.25))/.01;}
    public record Aim(Vec3 direction,double flight) {
        public float yaw(){return (float)Math.toDegrees(Math.atan2(direction.z,direction.x))-90;}
        public float pitch(){return (float)-Math.toDegrees(Math.atan2(direction.y,Math.hypot(direction.x,direction.z)));}
    }
    public static double speed(int draw){double f=Math.min(1,Math.max(0,draw)/20.0);return Math.min(1,(f*f+2*f)/3)*3;}
    public static Vec3 stepVelocity(Vec3 velocity){return velocity.scale(.99).add(0,-.05,0);}
    private static Vec3 required(Vec3 origin,Vec3 target,Vec3 motion,Vec3 inherited,double ticks){
        int quarter=(int)Math.rint(ticks*4);
        double sum=quarter>0&&quarter<SUM.length&&Math.abs(ticks*4-quarter)<1e-8?SUM[quarter]:(1-Math.pow(.99,ticks))/.01;
        return target.add(motion.scale(ticks)).subtract(origin).add(0,5*(ticks-sum),0).scale(1/sum).subtract(inherited);
    }
    /** Both branches are found against drag, rather than a vacuum parabola. Null means unreachable. */
    public static Aim solve(Vec3 origin,Vec3 target,Vec3 motion,Vec3 inherited,double speed,boolean high){
        if(speed<.1||!Double.isFinite(speed))return null;
        Aim best=null;double last=.5,lastError=required(origin,target,motion,inherited,last).length()-speed;
        for(double t=.75;t<=100;t+=.25){
            double error=required(origin,target,motion,inherited,t).length()-speed;
            if(error*lastError<=0){
                double lo=last,hi=t,atLo=lastError;
                for(int i=0;i<20;i++){double mid=(lo+hi)*.5,e=required(origin,target,motion,inherited,mid).length()-speed;if(e*atLo>0){lo=mid;atLo=e;}else hi=mid;}
                double flight=(lo+hi)*.5;Vec3 direction=required(origin,target,motion,inherited,flight).normalize();
                best=new Aim(direction,flight);if(!high)return best;
            }
            last=t;lastError=error;
        }
        return best;
    }
}
