package com.rlclones.ai;

import com.rlclones.Config;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.CrossbowAttackMob;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Chased by monsters that only hit what they can reach: jump and put two blocks under the feet (a 2-high pillar), stay
 * up there - hitting whatever comes close, or simply waiting for help - and once the coast is clear, dig the pillar
 * away again and take the blocks back.
 */
public final class Perch {
    private enum Stage {CLIMB, TOP, DESCEND}

    private final ClonePlayer self;
    private final Motor motor;
    private final Perception perception;

    private Stage stage;
    private BlockPos base;
    private final List<BlockPos> placed = new ArrayList<>();
    private int ticks;
    private int calm;
    private long blockedUntil;private BlockPos blockedAt;private int progressAt;private double progressY;private float startHealth;
    public int aborted;
    public boolean blocked(long now){return blockedAt!=null&&now<blockedUntil&&self.blockPosition().distSqr(blockedAt)<9;}
    public void blockedHere(long now,String why){blockedAt=self.blockPosition().immutable();blockedUntil=now+120;debug=why;}
    private void abort(String why){aborted++;blockedHere(self.level().getGameTime(),why);motor.stop();motor.cancelJump();stage=null;hopFrom=null;placed.clear();Equipment.manage(self,true);}

    public int perches;
    public int blocksRecovered;
    public int hitsFromAbove;
    public double highest;
    public String debug = "";

    public Perch(ClonePlayer self, Motor motor, Perception perception) {
        this.self = self;
        this.motor = motor;
        this.perception = perception;
    }

    public boolean busy() {
        return stage != null;
    }

    private static boolean ranged(Entity e) {
        return e instanceof RangedAttackMob || e instanceof CrossbowAttackMob;
    }

    private int blocks() {
        int n = 0;
        for (ItemStack s : self.getInventory().items) {
            if (Equipment.isPillarBlock(s)) {
                n += s.getCount();
            }
        }
        return n;
    }

    private boolean free(BlockPos p) {
        ServerLevel level = self.serverLevel();
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty() && level.getFluidState(p).isEmpty();
    }

    /** Worth climbing: melee chasers only, two blocks to spare and room above the head. */
    public boolean canStart(long now) {
        if (busy() || blocked(now) || !self.onGround() || self.isInWater() || self.isPassenger() || blocks() < 2 || !Config.get(Config.ALLOW_BLOCK_PLACING, true)) {
            return false;
        }
        List<Perception.Seen> threats = Senses.threats(perception, self, now, 8);
        if (threats.isEmpty() || threats.stream().anyMatch(t -> ranged(t.entity) || t.entity instanceof net.minecraft.world.entity.player.Player)) {
            return false; // archers and players would just shoot / climb after us
        }
        return PillarSpace.clear(self,2.25);
    }

    public void start() {
        if(!canStart(self.level().getGameTime()))return;
        stage = Stage.CLIMB;
        base = self.blockPosition();
        placed.clear();
        ticks = 0;
        calm = 0;
        highest = progressY = self.getY();progressAt=0;startHealth=self.getHealth();hopFrom=null;
        perches++;
        Equipment.select(self, Equipment.pillarBlockSlot(self));
    }

    /** Runs the whole perch; returns true while it has the clone's body. */
    public boolean tick(long now) {
        if (stage == null) {
            return false;
        }
        ticks++;
        highest = Math.max(highest, self.getY());
        debug = stage + " placed=" + placed.size() + " y=" + String.format("%.2f", self.getY() - base.getY());
        switch (stage) {
            case CLIMB -> climb();
            case TOP -> top(now);
            case DESCEND -> descend();
        }
        return stage != null;
    }

    private BlockPos hopFrom;

    private void climb() {
        double up = self.getY() - base.getY();
        if(self.getY()>progressY+.15){progressY=self.getY();progressAt=ticks;}
        if(!Config.get(Config.ALLOW_BLOCK_PLACING,true)||!PillarSpace.clear(self,Math.max(0,base.getY()+2.25-self.getY()))){abort("perch blocked overhead");return;}
        if(ticks-progressAt>30||self.getHealth()<startHealth-Math.max(2,self.getMaxHealth()*.15f)){abort("perch stalled or hurt");return;}
        if (self.onGround() && up >= 1.95) {
            stage = Stage.TOP; // two blocks up
            ticks = 0;
            return;
        }
        if (ticks > 120 || blocks() == 0) {
            abort("perch materials or time exhausted");return;
        }
        motor.lookAngles(self.getYRot(), 90f);
        motor.stop();
        if (self.onGround()) {
            if(hopFrom!=null){abort("perch jump made no step");return;}
            motor.jump(); // hop...
            hopFrom = self.blockPosition();
            return;
        }
        // ...and as soon as the feet are a block above where we jumped from, a block goes in under them
        if (hopFrom != null && self.getY() >= hopFrom.getY() + 1.0 && free(hopFrom)) {
            if (!Equipment.isPillarBlock(self.getMainHandItem())) {
                Equipment.select(self, Equipment.pillarBlockSlot(self));
            }
            if (motor.useOnTopFace(hopFrom.below())&&!free(hopFrom)&&self.level().getBlockState(hopFrom).isFaceSturdy(self.level(),hopFrom,net.minecraft.core.Direction.UP)) {
                placed.add(hopFrom.immutable());progressAt=ticks;
            }else{abort("perch placement rejected");return;}
            hopFrom = null;
        }
    }

    private void top(long now) {
        motor.stop();
        motor.sneak(true); // a narrow top: do not step off
        List<Perception.Seen> threats = Senses.threats(perception, self, now, 16);
        if (threats.isEmpty()) {
            if (++calm > 60) {
                stage = Stage.DESCEND;
                ticks = 0;
            }
            return;
        }
        calm = 0;
        Perception.Seen near = threats.get(0);
        for (Perception.Seen t : threats) {
            if (t.entity.distanceTo(self) < near.entity.distanceTo(self)) {
                near = t;
            }
        }
        motor.lookAt(near.entity);
        if (motor.canHit(near.entity) && self.getAttackStrengthScale(0.5f) >= 0.95f) {
            Equipment.manage(self, true);
            motor.attack(near.entity); // from above they cannot hit back
            hitsFromAbove++;
        }
        if (ticks > 2400) {
            stage = Stage.DESCEND; // waited long enough
            ticks = 0;
        }
    }

    private void descend() {
        if (placed.isEmpty() || ticks > 400) {
            stage = null;
            Equipment.manage(self, true);
            return;
        }
        BlockPos top = placed.get(placed.size() - 1);
        if (free(top)) {
            placed.remove(placed.size() - 1); // gone already
            return;
        }
        motor.stop();
        Vec3 aim = Vec3.atCenterOf(top).add(0, 0.45, 0);
        motor.lookAt(aim);
        Equipment.select(self, Equipment.bestToolSlot(self, self.level().getBlockState(top)));
        if (motor.mine(top)) {
            placed.remove(placed.size() - 1);
            blocksRecovered++; // it drops right at our feet and is picked up on landing
        }
    }

    public void reset() {
        stage = null;
        placed.clear();hopFrom=null;
    }
}
