package com.rlclones.ai;

import com.rlclones.clone.Bases;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Route to a reported ally above us. Use existing stairs/ladders; never teleport or dig an unsupported ceiling. */
public final class RescueRoute {
    private final ClonePlayer self;
    private final Motor motor;
    private record Route(BlockPos entry, BlockPos exit, Direction wall, Bases.Staircase stairs) {}
    private Route route;
    private boolean direct;
    public boolean active(){return route!=null||direct;}
    private Vec3 destination;
    private long nextSearch, lastProgress;
    private double bestDistance;
    public int routes, ladderTicks, stairTicks;
    public String debug = "";
    public RescueRoute(ClonePlayer self, Motor motor){this.self=self;this.motor=motor;}
    public void reset(){route=null;direct=false;destination=null;nextSearch=0;}
    private boolean loaded(BlockPos p){return self.level().hasChunkAt(p);}
    private boolean ladder(BlockPos p){return loaded(p)&&self.level().getBlockState(p).getBlock() instanceof LadderBlock;}
    private boolean free(BlockPos p){return loaded(p)&&self.level().getFluidState(p).isEmpty()&&self.level().getBlockState(p).getCollisionShape(self.level(),p).isEmpty();}
    private boolean standing(BlockPos p){
        return free(p)&&free(p.above())&&loaded(p.below())&&self.level().getFluidState(p.below()).isEmpty()
            &&self.level().getBlockState(p.below()).isFaceSturdy(self.level(),p.below(),Direction.UP);
    }
    private BlockPos rim(BlockPos top, Vec3 goal){
        BlockPos best=null;double d=Double.MAX_VALUE;
        for(Direction side:Direction.Plane.HORIZONTAL){BlockPos p=top.relative(side);
            if(standing(p)&&p.distToCenterSqr(goal)<d){d=p.distToCenterSqr(goal);best=p;}}
        return best;
    }
    private void addLadder(List<Route> out,BlockPos bottom,Vec3 goal){
        if(!ladder(bottom)||bottom.distToCenterSqr(self.position())>24*24)return;
        Direction wall=self.level().getBlockState(bottom).getValue(LadderBlock.FACING).getOpposite();
        BlockPos top=bottom;
        for(int n=0;n<32&&ladder(top);n++){
            top=top.above();
            if(top.getY()<self.getY()+3||top.getY()>goal.y+3)continue;
            if(!free(top)||!free(top.above()))continue;
            BlockPos exit=rim(top,goal);
            if(exit!=null&&Math.abs(exit.getY()-goal.y)<Math.abs(self.getY()-goal.y))out.add(new Route(bottom,exit,wall,null));
        }
    }
    private Route find(Vec3 goal){
        var bases=Bases.get(self.getServer());BlockPos feet=self.blockPosition();
        List<Route> candidates=new ArrayList<>();
        for(var shaft:bases.shafts){
            if(shaft.dimension!=self.level().dimension()||shaft.top.getY()<=feet.getY()+2||shaft.end.getY()>feet.getY()+1)continue;
            BlockPos entry=shaft.top.atY(feet.getY());
            if(entry.distToCenterSqr(self.position())<=24*24)addLadder(candidates,entry,goal);
        }
        for(var s:bases.staircases){
            if(s.dimension!=self.level().dimension()||s.flooded||s.top.getY()<=feet.getY()+2)continue;
            int index=Math.max(0,Math.min(s.steps(),s.top.getY()-feet.getY()));
            BlockPos entry=s.top.relative(s.dir,index).below(index);
            if(entry.distToCenterSqr(self.position())<=24*24&&standing(entry)&&standing(s.top)
                    &&Math.abs(s.top.getY()-goal.y)<Math.abs(self.getY()-goal.y))
                candidates.add(new Route(entry,s.top,null,s));
        }
        // Nearby unregistered ladders (including a player's ladder) may also be used.
        for(int x=-5;x<=5;x++)for(int z=-5;z<=5;z++){
            BlockPos p=feet.offset(x,0,z);
            if(ladder(p))addLadder(candidates,p,goal);
        }
        candidates.sort(Comparator.comparingDouble(r->r.entry.distToCenterSqr(self.position())+r.exit.distToCenterSqr(goal)));
        int checked=0;
        for(Route r:candidates){
            if(checked++>=8)break;
            if(r.entry.distToCenterSqr(self.position())<2.25||motor.pathTo(Vec3.atBottomCenterOf(r.entry))!=null)return r;
            // A pathfinder sometimes refuses a ladder cell; a reachable adjacent landing suffices.
            if(r.wall!=null)for(Direction d:Direction.Plane.HORIZONTAL){
                BlockPos p=r.entry.relative(d);
                if(standing(p)&&motor.pathTo(Vec3.atBottomCenterOf(p))!=null)return r;
            }
        }
        return null;
    }
    /** True only when actively navigating a useful route. */
    public boolean tick(Vec3 goal,long now){
        if(goal==null||!self.isAlive()||self.isInLava()||self.isPassenger()||self.getServer()==null){reset();return false;}
        if(destination==null||destination.distanceToSqr(goal)>16){reset();destination=goal;}
        double height=goal.y-self.getY();
        if(route!=null){
            double distance=self.position().distanceToSqr(Vec3.atBottomCenterOf(route.exit));
            if(distance<bestDistance-.25){bestDistance=distance;lastProgress=now;}
            if(now-lastProgress>120){route=null;nextSearch=now+20;debug="route stalled; retry";}
        }
        if(direct){
            if(height<1.5&&self.position().distanceToSqr(goal)<9){direct=false;return false;}
            double d=self.position().distanceToSqr(goal);
            if(d<bestDistance-.25){bestDistance=d;lastProgress=now;}
            if(now-lastProgress<=80&&motor.stuckCount()<3){motor.navigate(goal,1.0,true);return true;}
            direct=false;nextSearch=now;motor.clearPath();
        }
        if(route==null&&height<2.5)return false;
        if(route==null){
            if(now<nextSearch)return false;
            nextSearch=now+20;
            route=find(goal);
            if(route==null&&motor.stuckCount()<3&&motor.pathTo(goal)!=null){direct=true;bestDistance=self.position().distanceToSqr(goal);lastProgress=now;motor.navigate(goal,1.0,true);debug="direct rescue path";return true;}
            if(route==null){debug="no known ascent";return false;}
            routes++;bestDistance=self.position().distanceToSqr(Vec3.atBottomCenterOf(route.exit));lastProgress=now;motor.clearPath();motor.resetStuck();
        }
        Route r=route;
        if(self.getY()>=r.exit.getY()-.15&&Motor.horizontalDistance(self.position(),Vec3.atBottomCenterOf(r.exit))<.8){
            route=null;motor.clearPath();debug="ascent reached";return false;
        }
        if(r.wall!=null){
            BlockPos cell=new BlockPos(r.entry.getX(),self.getBlockY(),r.entry.getZ());
            Vec3 center=Vec3.atBottomCenterOf(cell);
            if(self.getY()>=r.exit.getY()-.15){motor.moveToward(Vec3.atBottomCenterOf(r.exit));motor.jump();}
            else if(Motor.horizontalDistance(self.position(),center)<.65&&ladder(cell)){
                motor.stop();motor.lookAt(Vec3.atCenterOf(cell.relative(r.wall)));motor.moveToward(Vec3.atBottomCenterOf(cell.relative(r.wall)));motor.jump();ladderTicks++;
            }else if(Motor.horizontalDistance(self.position(),Vec3.atBottomCenterOf(r.entry))<1.5){motor.movePrecisely(Vec3.atBottomCenterOf(r.entry));}
            else motor.navigate(Vec3.atBottomCenterOf(r.entry),.25,true);
            debug="ladder rescue "+r.exit.toShortString();
        }else{
            var s=r.stairs;
            int index=Math.max(0,Math.min(s.steps(),s.top.getY()-self.getBlockY()));
            BlockPos current=s.top.relative(s.dir,index).below(index);
            BlockPos next=index==0?s.top:s.top.relative(s.dir,index-1).below(index-1);
            BlockPos waypoint=Motor.horizontalDistance(self.position(),Vec3.atBottomCenterOf(current))>1.3?current:next;
            if(!standing(waypoint)){route=null;nextSearch=now+20;return false;}
            motor.navigate(Vec3.atBottomCenterOf(waypoint),.25,true);stairTicks++;
            debug="stairs rescue "+r.exit.toShortString();
        }
        return true;
    }
    public String report(){return "rescue routes="+routes+" ladder="+ladderTicks+" stairs="+stairTicks+" "+debug;}
}
