package com.rlclones.ai;
import org.junit.jupiter.api.Test;
import net.minecraft.world.phys.Vec3;
import static org.junit.jupiter.api.Assertions.*;
class BowPhysicsTest {
    private double miss(BowPhysics.Aim aim,Vec3 origin,Vec3 target,Vec3 motion,Vec3 inherited,double speed){
        Vec3 p=origin,v=aim.direction().scale(speed).add(inherited);double best=1e9;
        for(int t=0;t<110;t++){Vec3 next=p.add(v),wanted=target.add(motion.scale(t+.5));Vec3 segment=next.subtract(p);double f=Math.max(0,Math.min(1,wanted.subtract(p).dot(segment)/segment.lengthSqr()));best=Math.min(best,p.add(segment.scale(f)).distanceTo(wanted));p=next;v=BowPhysics.stepVelocity(v);}return best;
    }
    @Test void dragAndElevationMatchDiscreteArrowFlight(){for(double x:new double[]{8,20,40})for(double y:new double[]{-5,0,8}){Vec3 target=new Vec3(x,y,0);var a=BowPhysics.solve(Vec3.ZERO,target,Vec3.ZERO,Vec3.ZERO,3,false);assertNotNull(a);assertTrue(miss(a,Vec3.ZERO,target,Vec3.ZERO,Vec3.ZERO,3)<.15,"range="+x+" height="+y);}}
    @Test void highArcAndPartialDrawUseActualDrag(){double speed=BowPhysics.speed(12);Vec3 target=new Vec3(12,0,0);var low=BowPhysics.solve(Vec3.ZERO,target,Vec3.ZERO,Vec3.ZERO,speed,false);var high=BowPhysics.solve(Vec3.ZERO,target,Vec3.ZERO,Vec3.ZERO,speed,true);assertNotNull(low);assertNotNull(high);assertTrue(high.flight()>low.flight());assertTrue(miss(high,Vec3.ZERO,target,Vec3.ZERO,Vec3.ZERO,speed)<.15);}
    @Test void compensatesTargetAndShooterMotion(){Vec3 target=new Vec3(25,3,0),motion=new Vec3(0,0,.15),inherited=new Vec3(.2,0,0);var aim=BowPhysics.solve(Vec3.ZERO,target,motion,inherited,3,false);assertNotNull(aim);assertTrue(miss(aim,Vec3.ZERO,target,motion,inherited,3)<.3);}
    @Test void unreachableShotHasNoFabricatedFortyFiveDegreeSolution(){assertNull(BowPhysics.solve(Vec3.ZERO,new Vec3(100,100,0),Vec3.ZERO,Vec3.ZERO,.5,true));assertNull(BowPhysics.solve(Vec3.ZERO,new Vec3(10,0,0),Vec3.ZERO,Vec3.ZERO,0,false));}
}
