package com.rlclones.ai;

import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.HashSet;

/** Stronghold search using thrown vanilla Eyes of Ender, never a direct structure lookup. */
public final class EndEyeSearch {
    private final ClonePlayer self;
    private final Motor motor;
    private final Perception perception;
    private EyeOfEnder eye;
    private Vec3 launch,goal;
    private long retryAt,until;
    private BlockPos portal;
    public int eyesThrown,framesFound;
    public EndEyeSearch(ClonePlayer self,Motor motor,Perception perception){this.self=self;this.motor=motor;this.perception=perception;}
    public BlockPos foundPortal(){return portal;}
    private int eyeSlot(){
        for(int i=0;i<self.getInventory().items.size();i++)if(self.getInventory().items.get(i).is(Items.ENDER_EYE))return i;
        return -1;
    }
    private BlockPos visibleFrame(){
        BlockPos c=self.blockPosition();var level=self.serverLevel();
        for(int dx=-5;dx<=5;dx++)for(int dz=-5;dz<=5;dz++)for(int dy=-2;dy<=2;dy++){
            BlockPos p=c.offset(dx,dy,dz);
            if(level.hasChunkAt(p)&&level.getBlockState(p).is(Blocks.END_PORTAL_FRAME)
                    &&perception.clearLine(self.getEyePosition(),Vec3.atCenterOf(p).add(0,.5,0)))return p;
        }
        return null;
    }
    public boolean tick(long now){
        if(self.level().dimension()!=Level.OVERWORLD||self.isPassenger()||self.isInWater()||!com.rlclones.clone.CloneMobs.canUseHands(self)){reset();return false;}
        if(portal!=null)return false;
        if(eye!=null){
            motor.stop();motor.lookAt(eye);
            if(eye.isAlive()&&!eye.isRemoved()&&now<until){
                Vec3 delta=eye.position().subtract(launch);
                if(delta.horizontalDistanceSqr()>1)goal=eye.position();
                return true;
            }
            eye=null;
            if(goal!=null){
                Vec3 delta=goal.subtract(launch).multiply(1,0,1);
                if(delta.horizontalDistanceSqr()>1)goal=self.position().add(delta.normalize().scale(24));else goal=null;
            }
            until=now+240;
        }
        if(goal!=null){
            if(now>=until||motor.stuckCount()>5||Motor.horizontalDistance(self.position(),goal)<2){goal=null;retryAt=now+60;return false;}
            motor.navigate(goal,2,false);return true;
        }
        if(now<retryAt||!self.onGround()||self.isUsingItem())return false;
        if(self.getInventory().countItem(Items.ENDER_EYE)<6)return false;
        retryAt=now+160;
        BlockPos frame=visibleFrame();
        if(frame!=null){portal=frame;framesFound++;return false;}
        if(!self.serverLevel().canSeeSky(self.blockPosition().above()))return false;
        var level=self.serverLevel();
        var area=self.getBoundingBox().inflate(5);
        var previous=new HashSet<Integer>();
        for(EyeOfEnder e:level.getEntitiesOfClass(EyeOfEnder.class,area))previous.add(e.getId());
        Equipment.select(self,eyeSlot());motor.stop();launch=self.position();
        motor.useHeldItem(InteractionHand.MAIN_HAND);
        for(EyeOfEnder e:level.getEntitiesOfClass(EyeOfEnder.class,area)){
            if(!previous.contains(e.getId())){eye=e;eyesThrown++;until=now+100;return true;}
        }
        return false;
    }
    public void reset(){eye=null;goal=null;launch=null;portal=null;}
}
