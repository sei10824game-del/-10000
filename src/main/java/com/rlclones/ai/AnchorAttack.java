package com.rlclones.ai;

import com.rlclones.Config;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.phys.Vec3;

/** Use a charged respawn anchor only where it naturally explodes, behind a solid blast shield. */
public final class AnchorAttack {
    private final ClonePlayer self;
    private final Motor motor;
    private final Explosives explosives;
    private BlockPos shield, anchor;
    private Entity target;
    private int stage;
    private long until,retryAt;
    public int blasts;
    public AnchorAttack(ClonePlayer self,Motor motor,Explosives explosives){this.self=self;this.motor=motor;this.explosives=explosives;}
    private ServerLevel level(){return self.serverLevel();}
    private int slot(net.minecraft.world.item.Item item){
        for(int i=0;i<self.getInventory().items.size();i++)if(self.getInventory().items.get(i).is(item))return i;
        return -1;
    }
    private boolean solid(BlockPos p){
        return level().hasChunkAt(p)&&level().getFluidState(p).isEmpty()
                &&level().getBlockState(p).isCollisionShapeFullBlock(level(),p);
    }
    private boolean free(BlockPos p){return level().hasChunkAt(p)&&level().getFluidState(p).isEmpty()&&level().getBlockState(p).canBeReplaced()&&solid(p.below());}
    private boolean safe(Entity e){
        if(e==null||!e.isAlive()||e.level()!=level()||!Senses.isHostileTo(e,self)||level().dimensionType().respawnAnchorWorks()
                ||!self.onGround()||self.getHealth()<self.getMaxHealth()*.8f||!Config.get(Config.ALLOW_BLOCK_PLACING,true))return false;
        if(anchor==null)return true;
        return e.position().distanceToSqr(Vec3.atCenterOf(anchor))<=16&&self.position().distanceToSqr(Vec3.atCenterOf(anchor))>=6.25
                &&self.getEyePosition().distanceTo(Vec3.atCenterOf(anchor))<=Motor.BLOCK_REACH-.4
                &&explosives.safeAt(Vec3.atCenterOf(anchor));
    }
    public boolean tick(Entity e,long now){
        if(stage==0){
            if(now<retryAt||now%10!=0||!(e instanceof LivingEntity l)||l.getMaxHealth()<30||!safe(e)
                    ||slot(Items.RESPAWN_ANCHOR)<0||slot(Items.GLOWSTONE)<0||slot(Items.COBBLESTONE)<0)return false;
            Direction d=Direction.getNearest(e.getX()-self.getX(),0,e.getZ()-self.getZ());
            BlockPos feet=self.blockPosition();
            shield=feet.relative(d);anchor=feet.relative(d,3);
            if(!free(shield)||!free(anchor)||!safe(e)){reset();retryAt=now+80;return false;}
            target=e;stage=1;until=now+60;retryAt=now+240;
        }
        if(now>=until||target!=e||!safe(e)){reset();return false;}
        motor.stop();motor.cancelJump();
        if(stage==1){
            int s=slot(Items.COBBLESTONE);if(s<0){reset();return false;}
            Equipment.select(self,s);
            if(motor.placeBlockAt(shield)&&level().getBlockState(shield).is(Blocks.COBBLESTONE))stage=2;
            return true;
        }
        if(!level().getBlockState(shield).is(Blocks.COBBLESTONE)||!solid(shield)){reset();return false;}
        // Shield must still lie between the clone and the blast in the same horizontal lane.
        Vec3 a=Vec3.atCenterOf(anchor);
        if(new net.minecraft.world.phys.AABB(shield).clip(self.position().add(0,.2,0),a).isEmpty()){reset();return false;}
        if(stage==2){
            int s=slot(Items.RESPAWN_ANCHOR);if(s<0){reset();return false;}
            Equipment.select(self,s);
            if(motor.placeBlockAt(anchor)&&level().getBlockState(anchor).is(Blocks.RESPAWN_ANCHOR))stage=3;
            return true;
        }
        if(!level().getBlockState(anchor).is(Blocks.RESPAWN_ANCHOR)){reset();return false;}
        self.setShiftKeyDown(false);motor.sneak(false);
        if(stage==3){
            int s=slot(Items.GLOWSTONE);if(s<0){reset();return false;}
            Equipment.select(self,s);motor.useOnFace(anchor,Direction.UP);
            if(level().getBlockState(anchor).is(Blocks.RESPAWN_ANCHOR)&&level().getBlockState(anchor).getValue(RespawnAnchorBlock.CHARGE)>0)stage=4;
            return true;
        }
        // Recheck all allies and the dimension immediately before the vanilla interaction.
        if(!safe(e)||level().getBlockState(anchor).getValue(RespawnAnchorBlock.CHARGE)==0){reset();return false;}
        int empty=Equipment.bareSlot(self);
        if(empty<0){reset();return false;}
        Equipment.select(self,empty);
        if(!self.getMainHandItem().isEmpty()||self.getOffhandItem().is(Items.GLOWSTONE)){reset();return false;}
        BlockPos pos=anchor;
        motor.useOnFace(pos,Direction.UP);
        if(!level().getBlockState(pos).is(Blocks.RESPAWN_ANCHOR))blasts++;
        reset();return true;
    }
    public void reset(){stage=0;anchor=shield=null;target=null;}
}
