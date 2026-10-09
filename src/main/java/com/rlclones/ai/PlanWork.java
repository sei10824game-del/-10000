package com.rlclones.ai;

import com.rlclones.clone.Bases;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * R-59: one clone's share of a {@link Bases.Plan}: take a segment, lay its blocks in order (walking up to each), then take the next
 * until none is left. Which blocks a plan holds is the plan's business (a wall, later a bridge or a tower); this only lays them.
 * ponytail: the clone stands near the next block and places it against whatever supports it; no path-aware standing spot, so a plan whose
 * blocks are out of reach of any walkable ground needs a smarter stand (add when towers / bridges come).
 */
public final class PlanWork {
    public enum Status {WORKING, DONE, FAILED}

    private final ClonePlayer self;
    private final Motor motor;
    private Bases.Plan plan;
    private int segment = -1;
    private int ticks;
    private int stuckOn;
    private int cooldown;
    private final Set<BlockPos> skipped = new HashSet<>();
    /** Blocks laid / segments finished by this clone (tests). */
    public int placed;
    public int segmentsDone;
    public String debug = "";

    public PlanWork(ClonePlayer self, Motor motor) {
        this.self = self;
        this.motor = motor;
    }

    public void begin(Bases.Plan plan) {
        this.plan = plan;
        segment = -1;
        ticks = 0;
        stuckOn = 0;
        debug = "";
    }

    public Bases.Plan plan() {
        return plan;
    }

    public Status tick() {
        if (plan == null || ++ticks > 4800) {
            return Status.DONE;
        }
        ServerLevel level = self.serverLevel();
        long now = level.getGameTime();
        if (segment < 0) {
            segment = plan.claim(self.getUUID(), now);
            if (segment < 0) {
                return Status.DONE; // nothing left that is not taken
            }
        }
        plan.touch(segment, now);
        BlockPos next = null;
        int from = segment * plan.segSize;
        int to = Math.min(from + plan.segSize, plan.blocks.size());
        for (int i = from; i < to; i++) {
            BlockPos p = plan.blocks.get(i);
            if (!skipped.contains(p) && level.getBlockState(p).canBeReplaced()) {
                next = p;
                break;
            }
        }
        if (next == null) {
            plan.finish(segment);
            segmentsDone++;
            segment = -1;
            return Status.WORKING;
        }
        Vec3 target = Vec3.atBottomCenterOf(next);
        double d = Motor.horizontalDistance(self.position(), target);
        if (d > 2.6 || Math.abs(self.getY() - target.y) > 2.5) {
            motor.navigate(target, 2.0, false);
            if (motor.stuckCount() > 6) {
                debug += " stuck@" + self.blockPosition().toShortString();
                return Status.FAILED;
            }
            return Status.WORKING;
        }
        if (d < 0.9) {
            motor.navigate(target.add(0, 0, 2.0), 0.5, false); // not on the block to be laid
            return Status.WORKING;
        }
        motor.stop();
        if (cooldown > 0) {
            cooldown--;
            return Status.WORKING;
        }
        int slot = Equipment.pillarBlockSlot(self);
        if (slot < 0) {
            debug += " nomaterial";
            return Status.FAILED;
        }
        Equipment.select(self, slot);
        if (motor.placeBlockAt(next)) {
            placed++;
            stuckOn = 0;
            cooldown = 3;
        } else if (++stuckOn > 20) {
            skipped.add(next);
            stuckOn = 0;
            debug += " skip" + next.toShortString();
        }
        return Status.WORKING;
    }
}
