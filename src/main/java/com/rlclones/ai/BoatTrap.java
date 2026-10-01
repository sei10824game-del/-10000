package com.rlclones.ai;

import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A boat dropped right at a monster's feet: most mobs that bump into a boat sit down in it and can no longer walk. If
 * this kind of mob does not get in, the clone remembers that ("noboat:<type>"), breaks the boat and takes it back.
 */
public final class BoatTrap {
    private enum Stage {AIM, WATCH, BREAK, PICK_UP}

    private final ClonePlayer self;
    private final Motor motor;
    private Stage stage;
    private Entity foe;
    private Boat boat;
    private int ticks;
    private long lastTry = Long.MIN_VALUE / 2;

    public int trapsSet;
    public int trapsSprung;
    public int boatsRecovered;
    public String debug = "";

    public BoatTrap(ClonePlayer self, Motor motor) {
        this.self = self;
        this.motor = motor;
    }

    public boolean busy() {
        return stage != null;
    }

    private String key(Entity e) {
        return "noboat:" + Perception.typeId(e);
    }

    private boolean worthIt(Entity t, long now) {
        BlockPos under = t.blockPosition().below();
        boolean grounded = !t.level().getBlockState(under).getCollisionShape(t.level(), under).isEmpty() && t.getY() - t.blockPosition().getY() < 0.2;
        if (!(t instanceof Mob m) || !m.isAlive() || m.isPassenger() || !grounded || now - lastTry < 200 || Boating.boatSlot(self) < 0
                || self.getCloneBrain() == null || self.getCloneBrain().hasFlag(key(t))) {
            return false;
        }
        double d = t.distanceTo(self);
        return d > 2.6 && d < 5.0 && self.onGround();
    }

    /** During a fight: returns true while it has the clone's hands (placing, watching, breaking, collecting). */
    public boolean tick(Entity target, long now) {
        if (stage == null) {
            if (target == null || !worthIt(target, now)) {
                return false;
            }
            stage = Stage.AIM;
            foe = target;
            ticks = 0;
            lastTry = now;
        }
        ticks++;
        debug = stage + " " + ticks;
        switch (stage) {
            case AIM -> aim();
            case WATCH -> watch();
            case BREAK -> breakBoat();
            case PICK_UP -> pickUp();
        }
        return stage != null && stage != Stage.WATCH;
    }

    private void aim() {
        if (!foe.isAlive() || ticks > 30) {
            stage = null;
            return;
        }
        // a boat cannot be put down on top of a mob: right in front of it, in its way to us, so it walks into it
        Vec3 toUs = new Vec3(self.getX() - foe.getX(), 0, self.getZ() - foe.getZ()).normalize();
        Vec3 front = foe.position().add(toUs.scale(foe.getBbWidth() / 2 + 0.9)); // a boat is 1.375 wide
        BlockPos under = BlockPos.containing(front.x, foe.getY() - 0.5, front.z);
        Vec3 spot = new Vec3(front.x, under.getY() + 1.0, front.z);
        Vec3 eye = self.getEyePosition();
        float yaw = (float) Math.toDegrees(Mth.atan2(spot.z - eye.z, spot.x - eye.x)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Mth.atan2(spot.y - eye.y, Math.sqrt((spot.x - eye.x) * (spot.x - eye.x) + (spot.z - eye.z) * (spot.z - eye.z))));
        motor.stop();
        motor.lookAngles(yaw, pitch);
        if (Math.abs(Mth.wrapDegrees(self.getYRot() - yaw)) < 5f && Math.abs(self.getXRot() - pitch) < 5f) {
            Equipment.select(self, Boating.boatSlot(self));
            if (self.getMainHandItem().getItem() instanceof BoatItem && motor.useHeldItem(InteractionHand.MAIN_HAND)) {
                trapsSet++;
                boat = nearestBoat(spot, 2.0);
                stage = boat == null ? null : Stage.WATCH;
                ticks = 0;
            }
        }
    }

    private Boat nearestBoat(Vec3 at, double r) {
        Boat best = null;
        for (Boat b : self.level().getEntitiesOfClass(Boat.class, new AABB(at, at).inflate(r))) {
            if (best == null || b.distanceToSqr(at) < best.distanceToSqr(at)) {
                best = b;
            }
        }
        return best;
    }

    private void watch() {
        if (boat == null || !boat.isAlive()) {
            stage = null;
            return;
        }
        if (foe.getVehicle() == boat) {
            trapsSprung++; // sitting in it now: it cannot walk at us any more
            stage = null;
            Equipment.manage(self, true);
            return;
        }
        if (ticks > 30 || !foe.isAlive()) {
            if (foe.isAlive() && self.getCloneBrain() != null) {
                self.getCloneBrain().setFlag(key(foe)); // that kind does not get into boats
            }
            stage = Stage.BREAK;
            ticks = 0;
        }
    }

    private void breakBoat() {
        if (boat == null || !boat.isAlive()) {
            stage = Stage.PICK_UP;
            ticks = 0;
            return;
        }
        if (ticks > 120) {
            stage = null;
            return;
        }
        if (self.distanceTo(boat) > 3.0) {
            motor.navigate(boat.position(), 2.0, false);
            return;
        }
        motor.stop();
        motor.lookAt(boat);
        if (ticks % 5 == 0 && motor.canHit(boat)) {
            motor.attack(boat);
        }
    }

    private void pickUp() {
        ItemEntity item = null;
        for (ItemEntity it : self.level().getEntitiesOfClass(ItemEntity.class, self.getBoundingBox().inflate(6), e -> e.getItem().getItem() instanceof BoatItem)) {
            item = it;
        }
        if (item == null || ticks > 80) {
            if (item == null && Boating.boatSlot(self) >= 0) {
                boatsRecovered++;
            }
            stage = null;
            Equipment.manage(self, true);
            return;
        }
        if (self.distanceTo(item) < 1.5) {
            motor.moveToward(item.position());
        } else {
            motor.navigate(item.position(), 0.5, false);
        }
    }

    public void reset() {
        stage = null;
    }
}
