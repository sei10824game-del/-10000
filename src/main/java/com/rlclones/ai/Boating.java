package com.rlclones.ai;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Boats, used like a player: when a clone has to swim a long way it puts its boat on the water, gets in, paddles to the
 * destination, gets out on the shore, knocks the boat back into an item and picks it up.
 */
public final class Boating {
    private enum State {NONE, DEPLOY, BREAK, FETCH}

    private static final double MAX_SPEED = 0.35;

    private final ServerPlayer self;
    private final Motor motor;
    private State state = State.NONE;
    private int stateTicks;
    private int cooldown;
    @Nullable
    private Boat boat;
    private Vec3 lastPos;
    private int stuck;

    /** Has this clone ridden a boat (for tests / stats). */
    public boolean rodeBoat;
    public int trips;

    public Boating(ServerPlayer self, Motor motor) {
        this.self = self;
        this.motor = motor;
    }

    public static int boatSlot(Player p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            if (inv.items.get(i).getItem() instanceof BoatItem) {
                return i;
            }
        }
        return -1;
    }

    /** Diagnostics. */
    public String state() {
        return state + " trips " + trips + " stuck " + stuck + " cooldown " + cooldown + (boat == null ? "" : " boat " + boat.isAlive()) + " " + tripLog;
    }

    public boolean busy() {
        return state != State.NONE || self.getVehicle() instanceof Boat;
    }

    /**
     * Called by {@link Motor#navigate} every tick. Returns true when boating took care of the movement this tick.
     */
    /** Where each ride ended and why (diagnostics, tests). */
    public final StringBuilder tripLog = new StringBuilder();

    public boolean handle(Vec3 goal, double arrive) {
        if (cooldown > 0) {
            cooldown--;
        }
        if (self.getVehicle() instanceof Boat b) {
            boat = b;
            rodeBoat = true;
            return paddle(b, goal, arrive);
        }
        switch (state) {
            case DEPLOY -> {
                return deploy(goal);
            }
            case BREAK -> {
                return breakBoat();
            }
            case FETCH -> {
                return fetch();
            }
            default -> {
            }
        }
        // a long swim ahead and a boat in the bag: use it
        if (self.isInWater() && !self.isUnderWater() && cooldown <= 0 && Motor.horizontalDistance(self.position(), goal) > 6
                && boatSlot(self) >= 0) {
            state = State.DEPLOY;
            stateTicks = 0;
            return deploy(goal);
        }
        return false;
    }

    private boolean deploy(Vec3 goal) {
        if (++stateTicks > 30 || boatSlot(self) < 0) {
            state = State.NONE;
            cooldown = 200;
            return false;
        }
        Equipment.select(self, boatSlot(self));
        float yaw = (float) Math.toDegrees(Mth.atan2(goal.z - self.getZ(), goal.x - self.getX())) - 90.0F;
        // look down at the water just ahead, like a player placing a boat
        self.setYRot(yaw);
        self.setYHeadRot(yaw);
        self.setXRot(55f);
        motor.stop();
        if (stateTicks >= 2) {
            motor.useHeldItem(InteractionHand.MAIN_HAND);
            List<Boat> boats = self.level().getEntitiesOfClass(Boat.class, self.getBoundingBox().inflate(4), b -> b.getPassengers().isEmpty() && b.isAlive());
            if (!boats.isEmpty()) {
                Boat b = boats.get(0);
                self.interactOn(b, InteractionHand.MAIN_HAND);
                if (self.getVehicle() == b) {
                    boat = b;
                    rodeBoat = true;
                    trips++;
                    state = State.NONE;
                    lastPos = b.position();
                    stuck = 0;
                }
            }
        }
        return true;
    }

    private boolean paddle(Boat b, Vec3 goal, double arrive) {
        double dist = Motor.horizontalDistance(b.position(), goal);
        float targetYaw = (float) Math.toDegrees(Mth.atan2(goal.z - b.getZ(), goal.x - b.getX())) - 90.0F;
        float diff = Mth.wrapDegrees(targetYaw - b.getYRot());
        float yaw = b.getYRot() + Mth.clamp(diff, -8f, 8f);
        b.setYRot(yaw);
        self.setYRot(yaw);
        self.setYHeadRot(yaw);
        self.setXRot(10f);
        Vec3 before = b.position();
        if (dist > arrive) {
            double speed = Math.abs(diff) < 30 ? MAX_SPEED : 0.08;
            Vec3 dir = Vec3.directionFromRotation(0f, yaw);
            b.setPaddleState(true, true);
            b.move(MoverType.SELF, new Vec3(dir.x * speed, 0, dir.z * speed));
        }
        boolean noProgress = b.position().distanceTo(before) < 0.02;
        stuck = noProgress ? stuck + 1 : 0;
        // reached the goal, or ran onto the shore: get out
        if (dist <= arrive + 1.0 || stuck > 10) {
            if (tripLog.length() < 300) {
                tripLog.append(String.format(java.util.Locale.ROOT, "[out %.1f,%.1f d%.1f %s]", b.getX(), b.getZ(), dist, stuck > 10 ? "stuck" : "there"));
            }
            b.setPaddleState(false, false);
            self.stopRiding();
            boat = b;
            state = State.BREAK;
            stateTicks = 0;
            return true;
        }
        return true;
    }

    private boolean breakBoat() {
        if (++stateTicks > 120 || boat == null) {
            state = State.NONE;
            return false;
        }
        if (!boat.isAlive()) {
            state = State.FETCH;
            stateTicks = 0;
            return true;
        }
        if (boat.distanceTo(self) > 4.0) {
            motor.moveToward(boat.position());
            return true;
        }
        motor.stop();
        motor.lookAt(boat.getBoundingBox().getCenter());
        if (self.getAttackStrengthScale(0.5f) >= 0.95f) {
            self.attack(boat);
            self.swing(InteractionHand.MAIN_HAND);
        }
        return true;
    }

    private boolean fetch() {
        if (++stateTicks > 100 || boatSlot(self) >= 0) {
            state = State.NONE;
            return false;
        }
        List<ItemEntity> items = self.level().getEntitiesOfClass(ItemEntity.class, self.getBoundingBox().inflate(8),
                e -> e.getItem().getItem() instanceof BoatItem && e.isAlive());
        if (items.isEmpty()) {
            if (stateTicks > 20) {
                state = State.NONE;
                return false;
            }
            return true;
        }
        motor.moveToward(items.get(0).position());
        if (items.get(0).getY() > self.getY() + 0.5 && self.isInWater()) {
            motor.jump();
        }
        return true;
    }
}
