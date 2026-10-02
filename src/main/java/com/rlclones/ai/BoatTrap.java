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
    private String note = "";

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
            spot = null;
            push = 0;
            backoffs = 0;
            lastTry = now;
        }
        ticks++;
        if (stage != Stage.WATCH) {
            debug = stage + " " + ticks + " " + note;
        }
        switch (stage) {
            case AIM -> aim();
            case WATCH -> watch();
            case BREAK -> breakBoat();
            case PICK_UP -> pickUp();
        }
        return stage != null && stage != Stage.WATCH;
    }

    private Vec3 spot;
    private double push;
    private int backoffs;

    private void aim() {
        if (!foe.isAlive() || ticks > 30) {
            stage = null;
            return;
        }
        if (ticks == 1 || spot == null) {
            // a boat cannot be put down on top of a mob: right up against it instead (a boat picks up whatever touches it -
            // a walking mob would simply step over it)
            Vec3 toUs = new Vec3(self.getX() - foe.getX(), 0, self.getZ() - foe.getZ()).normalize();
            // boxes are axis aligned: clear the mob along the dominant axis only (a boat is 1.375 wide)
            double clear = (foe.getBbWidth() / 2 + 0.6875 + 0.06 + push) / Math.max(Math.abs(toUs.x), Math.abs(toUs.z));
            Vec3 front = foe.position().add(toUs.scale(clear));
            BlockPos under = BlockPos.containing(front.x, foe.getY() - 0.5, front.z);
            spot = new Vec3(front.x, under.getY() + 1.0, front.z);
        }
        if (Math.abs(self.getX() - spot.x) < 0.6875 + 0.35 && Math.abs(self.getZ() - spot.z) < 0.6875 + 0.35) {
            // we stand where the boat would go: a step back first
            motor.moveAwayFrom(foe.position());
            spot = null;
            if (++backoffs > 40) {
                stage = null; // cornered: no room for a boat between us
            }
            ticks = Math.min(ticks, 20);
            note = "backing off";
            return;
        }
        Vec3 eye = self.getEyePosition();
        float yaw = (float) Math.toDegrees(Mth.atan2(spot.z - eye.z, spot.x - eye.x)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Mth.atan2(spot.y - eye.y, Math.sqrt((spot.x - eye.x) * (spot.x - eye.x) + (spot.z - eye.z) * (spot.z - eye.z))));
        motor.stop();
        motor.lookAngles(yaw, pitch);
        boolean aimed = Math.abs(Mth.wrapDegrees(self.getYRot() - yaw)) < 2f && Math.abs(self.getXRot() - pitch) < 2f;
        if (!aimed) {
            note = "aim " + (int) self.getYRot() + "/" + (int) yaw + " " + (int) self.getXRot() + "/" + (int) pitch;
        }
        if (aimed) {
            Equipment.select(self, Boating.boatSlot(self));
            var hit = self.level().clip(new net.minecraft.world.level.ClipContext(eye, eye.add(self.getViewVector(1f).scale(4.5)),
                    net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.ANY, self));
            var bb = new net.minecraft.world.phys.AABB(hit.getLocation().x - 0.6875, hit.getLocation().y, hit.getLocation().z - 0.6875,
                    hit.getLocation().x + 0.6875, hit.getLocation().y + 0.5625, hit.getLocation().z + 0.6875);
            StringBuilder in = new StringBuilder();
            for (var e : self.level().getEntities((net.minecraft.world.entity.Entity) null, bb.inflate(1.0E-7))) {
                in.append(' ').append(net.minecraft.world.entity.EntityType.getKey(e.getType()).getPath()).append('@').append(String.format("%.2f,%.2f,%.2f", e.getX(), e.getY(), e.getZ()));
            }
            note = "hit=" + hit.getType() + " off=" + String.format("%.2f", hit.getLocation().distanceTo(spot)) + " at="
                    + String.format("%.2f,%.2f,%.2f", spot.x, spot.y, spot.z) + " foe=" + String.format("%.2f,%.2f,%.2f", foe.getX(), foe.getY(), foe.getZ())
                    + " push=" + push + " in=[" + in + "] hand=" + self.getMainHandItem();
            if (in.length() > 0 && push < 0.12) {
                push += 0.04; // something is in the way: step the boat a little further out (still inside its pick-up reach)
                spot = null;
                return;
            }
            if (self.getMainHandItem().getItem() instanceof BoatItem && motor.useHeldItem(InteractionHand.MAIN_HAND)) {
                trapsSet++;
                touched = false;
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

    private boolean touched;

    private void watch() {
        if (boat == null || !boat.isAlive()) {
            stage = null;
            return;
        }
        if (foe.isAlive() && boat.getBoundingBox().inflate(0.3, 0.1, 0.3).intersects(foe.getBoundingBox())) {
            touched = true; // it came right up to the boat (boxes side by side)
        }
        if (foe.getVehicle() == boat) {
            trapsSprung++; // sitting in it now: it cannot walk at us any more
            stage = null;
            Equipment.manage(self, true);
            return;
        }
        debug = "WATCH " + ticks + " d=" + String.format("%.2f", foe.distanceTo(boat)) + " touched=" + touched;
        if (ticks > 60 || !foe.isAlive()) {
            if (foe.isAlive() && touched && self.getCloneBrain() != null) {
                self.getCloneBrain().setFlag(key(foe)); // it bumped into the boat and still did not sit down: that kind does not
            }
            note = debug;
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
        if (ticks > 240) {
            stage = null;
            return;
        }
        if (!motor.withinReach(boat)) {
            motor.navigate(boat.position(), 1.5, false);
            return;
        }
        motor.stop();
        if (ticks % 20 == 1) {
            Equipment.manage(self, true); // a proper weapon breaks it in one go
        }
        motor.lookAt(boat.getBoundingBox().getCenter());
        if (motor.canHit(boat) && self.getAttackStrengthScale(0.5f) >= 0.9f) {
            motor.attack(boat);
        }
    }

    private void pickUp() {
        ItemEntity item = null;
        for (ItemEntity it : self.level().getEntitiesOfClass(ItemEntity.class, self.getBoundingBox().inflate(6), e -> e.getItem().getItem() instanceof BoatItem)) {
            item = it;
        }
        if (item == null || ticks > 200) {
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
