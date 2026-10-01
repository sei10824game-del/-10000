package com.rlclones.ai;

import com.rlclones.clone.Bases;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The way to the Nether. With a pickaxe that can take obsidian and a water bucket, the clone pours water onto lava
 * sources and mines the obsidian; with ten of it (plus four corner blocks) and flint and steel it builds and lights a
 * portal and tells the others where it is (like a base). A clone that has never been to the Nether walks in, has a look
 * around near the portal and comes back when it likes; and anybody running from monsters jumps into a nearby portal.
 */
public final class Portals {
    public enum Status {WORKING, DONE, FAILED}

    private enum Job {OBSIDIAN, BUILD, VISIT}

    private final ClonePlayer self;
    private final Motor motor;

    private Job job;
    private int ticks;
    private int stage;
    private BlockPos lava;
    private BlockPos waterAt;
    private BlockPos obsidianAt;
    private BlockPos origin;
    private final List<BlockPos> plan = new ArrayList<>();
    private int tries;

    // travelling through
    @Nullable
    private BlockPos arrivedAt;
    private boolean steppingOut;
    private long stayUntil = Long.MIN_VALUE;
    private boolean returning;
    private int stuckOut;
    @Nullable
    private BlockPos homePortal;

    public int obsidianMade;
    public int portalsBuilt;
    public int netherTrips;
    public int homeTrips;
    public int portalEscapes;
    private boolean inPortal;
    public String debug = "";
    private String note = "";

    public Portals(ClonePlayer self, Motor motor) {
        this.self = self;
        this.motor = motor;
    }

    public static final String NETHER_FLAG = "visited:minecraft:the_nether";

    private ServerLevel level() {
        return self.serverLevel();
    }

    private int count(net.minecraft.world.item.Item item) {
        return self.getInventory().countItem(item);
    }

    private int slotOf(net.minecraft.world.item.Item item) {
        Inventory inv = self.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            if (inv.items.get(i).is(item)) {
                return i;
            }
        }
        return -1;
    }

    private boolean canMineObsidian() {
        return Equipment.canHarvest(self, Blocks.OBSIDIAN.defaultBlockState());
    }

    private boolean isPortal(BlockPos p) {
        return level().getBlockState(p).is(Blocks.NETHER_PORTAL);
    }

    /** A known portal of this dimension that still stands (gone ones are forgotten). */
    @Nullable
    public BlockPos knownPortal(double radius) {
        Bases bases = Bases.get(self.getServer());
        Bases.Portal p = bases.nearestPortal(level().dimension(), self.position(), radius);
        while (p != null && !isPortal(p.pos())) {
            bases.forgetPortal(p);
            p = bases.nearestPortal(level().dimension(), self.position(), radius);
        }
        return p == null ? null : p.pos();
    }

    private boolean visited() {
        return self.getCloneBrain() != null && self.getCloneBrain().hasFlag(NETHER_FLAG);
    }

    // ================================================================== what is there to do

    @Nullable
    private BlockPos lavaSource() {
        BlockPos feet = self.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(feet.offset(-12, -3, -12), feet.offset(12, 2, 12))) {
            if (!level().getFluidState(p).is(FluidTags.LAVA) || !level().getFluidState(p).isSource()) {
                continue;
            }
            if (waterSpot(p) == null) {
                continue;
            }
            double d = p.distSqr(feet);
            if (d < bestD) {
                bestD = d;
                best = p.immutable();
            }
        }
        return best;
    }

    /** Where to pour the water so that it reaches the lava source: a free spot beside it with ground under it. */
    @Nullable
    private BlockPos waterSpot(BlockPos lava) {
        for (int up = 0; up <= 1; up++) {
            // beside it, or (a pool in the ground) on the ground next to it: the water flows over the source
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos w = lava.relative(d).above(up);
                if (level().getBlockState(w).isAir() && level().getBlockState(w.below()).isFaceSturdy(level(), w.below(), Direction.UP)) {
                    return w.immutable();
                }
            }
        }
        return null;
    }

    private boolean wantsObsidian() {
        return count(Items.OBSIDIAN) < 10 && canMineObsidian() && count(Items.WATER_BUCKET) > 0 && knownPortal(128) == null
                && level().dimension() == Level.OVERWORLD && com.rlclones.Config.get(com.rlclones.Config.ALLOW_BLOCK_BREAKING, true);
    }

    private int cornerBlocks() {
        int n = 0;
        for (ItemStack s : self.getInventory().items) {
            if (Equipment.isBuildingBlock(self, s) && !s.is(Items.OBSIDIAN)) {
                n += s.getCount();
            }
        }
        return n;
    }

    private boolean canBuild() {
        return count(Items.FLINT_AND_STEEL) > 0 && knownPortal(128) == null && com.rlclones.Config.get(com.rlclones.Config.ALLOW_BLOCK_PLACING, true)
                && (count(Items.OBSIDIAN) >= 14 || count(Items.OBSIDIAN) >= 10 && cornerBlocks() >= 4);
    }

    private boolean wantsVisit() {
        return !visited() && level().dimension() == Level.OVERWORLD && knownPortal(64) != null;
    }

    public boolean hasWork() {
        if (busy()) {
            return false;
        }
        return canBuild() || wantsVisit() || wantsObsidian() && lavaSource() != null;
    }

    public void begin() {
        ticks = 0;
        stage = 0;
        tries = 0;
        job = canBuild() ? Job.BUILD : wantsVisit() ? Job.VISIT : wantsObsidian() ? Job.OBSIDIAN : null;
        motor.resetStuck();
    }

    public Status tick() {
        if (job == null || ++ticks > 3000) {
            return Status.DONE;
        }
        debug = job + " stage=" + stage + " t=" + ticks + " " + note;
        return switch (job) {
            case OBSIDIAN -> obsidianTick();
            case BUILD -> buildTick();
            case VISIT -> visitTick();
        };
    }

    // ================================================================== obsidian

    private void aimAt(Vec3 p) {
        Vec3 eye = self.getEyePosition();
        float yaw = (float) Math.toDegrees(Mth.atan2(p.z - eye.z, p.x - eye.x)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Mth.atan2(p.y - eye.y, Math.sqrt((p.x - eye.x) * (p.x - eye.x) + (p.z - eye.z) * (p.z - eye.z))));
        motor.lookAngles(yaw, pitch);
    }

    private boolean aimed(Vec3 p) {
        Vec3 eye = self.getEyePosition();
        float yaw = (float) Math.toDegrees(Mth.atan2(p.z - eye.z, p.x - eye.x)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Mth.atan2(p.y - eye.y, Math.sqrt((p.x - eye.x) * (p.x - eye.x) + (p.z - eye.z) * (p.z - eye.z))));
        return Math.abs(Mth.wrapDegrees(self.getYRot() - yaw)) < 2f && Math.abs(self.getXRot() - pitch) < 2f;
    }

    private Status obsidianTick() {
        switch (stage) {
            case 0 -> {
                if (!wantsObsidian()) {
                    return Status.DONE;
                }
                lava = lavaSource();
                waterAt = lava == null ? null : waterSpot(lava);
                if (lava == null || waterAt == null) {
                    return Status.DONE;
                }
                stage = 1;
            }
            case 1 -> {
                note = "pour at " + waterAt.toShortString() + " lava " + lava.toShortString() + " tries " + tries;
                // close enough to pour, but not standing next to the lava
                Vec3 pour = new Vec3(waterAt.getX() + 0.5, waterAt.getY(), waterAt.getZ() + 0.5);
                double d = self.getEyePosition().distanceTo(pour);
                if (d > 3.8 || Motor.horizontalDistance(self.position(), Vec3.atCenterOf(lava)) < 1.8) {
                    Vec3 away = new Vec3(waterAt.getX() - lava.getX(), 0, waterAt.getZ() - lava.getZ()).normalize();
                    Vec3 stand = Vec3.atBottomCenterOf(waterAt).add(away.scale(1.5));
                    motor.navigate(stand, 0.6, false);
                    if (Motor.horizontalDistance(self.position(), stand) < 1.2) {
                        motor.moveToward(stand);
                    }
                    return motor.stuckCount() > 6 ? Status.FAILED : Status.WORKING;
                }
                motor.stop();
                aimAt(pour);
                if (aimed(pour)) {
                    Equipment.select(self, slotOf(Items.WATER_BUCKET));
                    if (self.getMainHandItem().is(Items.WATER_BUCKET) && motor.useHeldItem(InteractionHand.MAIN_HAND)) {
                        stage = 2;
                        tries = 0;
                    }
                }
                if (++tries > 60) {
                    return Status.FAILED;
                }
            }
            case 2 -> {
                // the water turned the lava source to obsidian: take the water back
                motor.stop();
                note = "poured: lava now " + level().getBlockState(lava) + " water " + level().getFluidState(waterAt).isSource();
                if (level().getBlockState(lava).is(Blocks.OBSIDIAN)) {
                    obsidianAt = lava;
                }
                Vec3 water = Vec3.atBottomCenterOf(waterAt).add(0, 0.6, 0);
                if (!level().getFluidState(waterAt).is(FluidTags.WATER) || !level().getFluidState(waterAt).isSource()) {
                    stage = 3;
                    return Status.WORKING;
                }
                if (obsidianAt == null && tries++ < 40) {
                    return Status.WORKING; // the water needs a moment to run over the lava
                }
                aimAt(water);
                if (aimed(water) && slotOf(Items.BUCKET) >= 0) {
                    Equipment.select(self, slotOf(Items.BUCKET));
                    motor.useHeldItem(InteractionHand.MAIN_HAND);
                }
                if (++tries > 60) {
                    stage = 3;
                }
            }
            case 3 -> {
                if (obsidianAt == null || !level().getBlockState(obsidianAt).is(Blocks.OBSIDIAN)) {
                    if (obsidianAt == null && level().getBlockState(lava).is(Blocks.OBSIDIAN)) {
                        obsidianAt = lava;
                        return Status.WORKING;
                    }
                    stage = obsidianAt == null ? 0 : 4; // mined (or nothing came of it)
                    tries = 0;
                    return Status.WORKING;
                }
                if (self.getEyePosition().distanceTo(Vec3.atCenterOf(obsidianAt)) > Motor.BLOCK_REACH - 0.5) {
                    motor.navigate(motor.approachPoint(obsidianAt), 1.5, false);
                    return motor.stuckCount() > 6 ? Status.FAILED : Status.WORKING;
                }
                motor.stop();
                Equipment.select(self, Equipment.bestToolSlot(self, level().getBlockState(obsidianAt)));
                if (motor.mine(obsidianAt)) {
                    obsidianMade++;
                    stage = 4;
                    tries = 0;
                }
            }
            case 4 -> {
                // walk over the dropped block
                if (++tries > 40 || obsidianAt == null) {
                    obsidianAt = null;
                    stage = 0;
                    return count(Items.OBSIDIAN) >= 10 ? Status.DONE : Status.WORKING;
                }
                motor.navigate(Vec3.atBottomCenterOf(obsidianAt), 0.4, false);
            }
            default -> {
                return Status.DONE;
            }
        }
        return Status.WORKING;
    }

    // ================================================================== building

    /** A flat spot for a 4 x 5 frame (opening 2 x 3) with room to stand in front. */
    @Nullable
    private BlockPos findSite() {
        BlockPos feet = self.blockPosition();
        for (int r = 2; r <= 8; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    BlockPos o = feet.offset(dx, 0, dz);
                    if (siteOk(o)) {
                        return o;
                    }
                }
            }
        }
        return null;
    }

    private boolean siteOk(BlockPos o) {
        for (int x = 0; x < 4; x++) {
            if (!level().getBlockState(o.offset(x, -1, 0)).isFaceSturdy(level(), o.offset(x, -1, 0), Direction.UP)) {
                return false;
            }
            for (int y = 0; y < 5; y++) {
                BlockPos p = o.offset(x, y, 0);
                if (!level().getBlockState(p).isAir()) {
                    return false;
                }
            }
            for (int y = 0; y < 2; y++) {
                if (!level().getBlockState(o.offset(x, y, -2)).isAir() || !level().getBlockState(o.offset(x, y, -1)).isAir()) {
                    return false; // the space in front where we stand
                }
            }
        }
        return Bases.get(self.getServer()).nearest(level().dimension(), Vec3.atCenterOf(o), 6) == null;
    }

    private static boolean corner(BlockPos rel) {
        return (rel.getX() == 0 || rel.getX() == 3) && (rel.getY() == 0 || rel.getY() == 4);
    }

    private Status buildTick() {
        switch (stage) {
            case 0 -> {
                origin = findSite();
                if (origin == null) {
                    return Status.FAILED;
                }
                plan.clear();
                for (int x = 0; x < 4; x++) {
                    plan.add(origin.offset(x, 0, 0)); // bottom row
                }
                for (int y = 1; y <= 3; y++) {
                    plan.add(origin.offset(0, y, 0)); // sides, bottom up
                    plan.add(origin.offset(3, y, 0));
                }
                plan.add(origin.offset(0, 4, 0)); // top: corners first, so the middle has a neighbour
                plan.add(origin.offset(3, 4, 0));
                plan.add(origin.offset(1, 4, 0));
                plan.add(origin.offset(2, 4, 0));
                stage = 1;
            }
            case 1 -> {
                Vec3 stand = Vec3.atBottomCenterOf(origin.offset(1, 0, -2)).add(0.5, 0, 0);
                if (Motor.horizontalDistance(self.position(), stand) > 0.6) {
                    motor.navigate(stand, 0.3, false);
                    if (Motor.horizontalDistance(self.position(), stand) < 1.5) {
                        motor.moveToward(stand);
                    }
                    return motor.stuckCount() > 6 ? Status.FAILED : Status.WORKING;
                }
                motor.stop();
                if (ticks % 3 != 0) {
                    return Status.WORKING;
                }
                BlockPos next = null;
                for (BlockPos p : plan) {
                    if (level().getBlockState(p).isAir()) {
                        next = p;
                        break;
                    }
                }
                if (next == null) {
                    stage = 2;
                    tries = 0;
                    return Status.WORKING;
                }
                boolean cornerSpot = corner(next.subtract(origin));
                int slot = slotOf(Items.OBSIDIAN);
                if (cornerSpot && count(Items.OBSIDIAN) < 14) {
                    slot = -1;
                    Inventory inv = self.getInventory();
                    for (int i = 0; i < inv.items.size(); i++) {
                        if (Equipment.isBuildingBlock(self, inv.items.get(i)) && !inv.items.get(i).is(Items.OBSIDIAN)) {
                            slot = i;
                            break;
                        }
                    }
                }
                if (slot < 0) {
                    return Status.FAILED;
                }
                Equipment.select(self, slot);
                if (!motor.placeBlockAt(next) && ++tries > 40) {
                    return Status.FAILED;
                }
            }
            case 2 -> {
                // light it on the inside of the bottom row
                BlockPos floor = origin.offset(1, 0, 0);
                Vec3 top = new Vec3(floor.getX() + 0.5, floor.getY() + 1.0, floor.getZ() + 0.5);
                motor.stop();
                motor.lookAt(top);
                int fs = slotOf(Items.FLINT_AND_STEEL);
                if (fs < 0 || ++tries > 60) {
                    return Status.FAILED;
                }
                Equipment.select(self, fs);
                boolean used = motor.useOnTopFace(floor);
                note = "light used=" + used + " above=" + level().getBlockState(floor.above()) + " floor=" + level().getBlockState(floor)
                        + " hand=" + self.getMainHandItem() + " yaw=" + (int) self.getYRot() + " rel=" + self.blockPosition().subtract(origin).toShortString();
                if (isPortal(floor.above())) {
                    portalsBuilt++;
                    BlockPos inside = floor.above().immutable();
                    Bases.get(self.getServer()).addPortal(level().dimension(), inside);
                    Chat.say(self, Component.translatable("rlclones.chat.portal", inside.getX(), inside.getY(), inside.getZ()),
                            "PORTAL " + inside.getX() + " " + inside.getY() + " " + inside.getZ() + " dim=" + level().dimension().location());
                    return Status.DONE;
                }
            }
            default -> {
                return Status.DONE;
            }
        }
        return Status.WORKING;
    }

    // ================================================================== going through

    /** Walk into the portal and wait there until it takes us. */
    private Status enter(BlockPos portal) {
        if (!isPortal(portal)) {
            return Status.FAILED;
        }
        Vec3 c = Vec3.atBottomCenterOf(portal);
        if (Motor.horizontalDistance(self.position(), c) > 0.35 || Math.abs(self.getY() - portal.getY()) > 0.6) {
            motor.navigate(c, 0.2, false);
            if (Motor.horizontalDistance(self.position(), c) < 1.5) {
                motor.moveToward(c);
            }
            return motor.stuckCount() > 8 ? Status.FAILED : Status.WORKING;
        }
        motor.stop(); // standing in the purple: the trip starts in a few seconds
        return Status.WORKING;
    }

    private Status visitTick() {
        if (visited() || level().dimension() != Level.OVERWORLD) {
            return Status.DONE;
        }
        BlockPos p = knownPortal(64);
        if (p == null) {
            return Status.FAILED;
        }
        homePortal = p;
        return enter(p);
    }

    /** Called right after the clone went through a portal. */
    /** The portal block closest to {@code at} (we come out next to / inside one). */
    @Nullable
    private BlockPos portalNear(BlockPos at, int r) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-r, -r, -r), at.offset(r, r, r))) {
            if (isPortal(p) && !isPortal(p.below())) {
                double d = p.distSqr(at);
                if (d < bestD) {
                    bestD = d;
                    best = p.immutable();
                }
            }
        }
        return best;
    }

    public void onArrived(ResourceKey<Level> from, long now) {
        BlockPos near = portalNear(self.blockPosition(), 3);
        arrivedAt = near != null ? near : self.blockPosition();
        steppingOut = true;
        stuckOut = 0;
        returning = false;
        if (level().dimension() == Level.NETHER) {
            netherTrips++;
            if (self.getCloneBrain() != null) {
                self.getCloneBrain().setFlag(NETHER_FLAG);
            }
            Bases.get(self.getServer()).addPortal(Level.NETHER, arrivedAt);
            stayUntil = now + 200 + self.getRandom().nextInt(400); // a look around, then home when we feel like it
        } else {
            homeTrips++;
            stayUntil = Long.MIN_VALUE;
        }
        job = null;
    }

    public boolean busy() {
        return steppingOut || returning;
    }

    /** Stepping out of the portal we came through, and the way home from the Nether. Returns true while moving. */
    public boolean travelTick(long now, boolean threatened) {
        if (steppingOut) {
            if (arrivedAt == null || !isPortal(self.blockPosition()) && !isPortal(self.blockPosition().above())) {
                steppingOut = false; // out of the purple: no bouncing straight back
                return false;
            }
            if (++stuckOut > 160) {
                steppingOut = false; // could not get out: let the rest of the brain find a way
                return false;
            }
            int turn = stuckOut / 40; // no luck one way for two seconds: try the next direction first
            for (int dist = 1; dist <= 2; dist++) {
                for (int k = 0; k < 4; k++) {
                    Direction d = Direction.from2DDataValue((k + turn) % 4);
                    BlockPos out = self.blockPosition().relative(d, dist);
                    if (level().getBlockState(out).getCollisionShape(level(), out).isEmpty() && level().getBlockState(out.above()).getCollisionShape(level(), out.above()).isEmpty()
                            && !isPortal(out) && level().getFluidState(out).isEmpty()
                            && level().getBlockState(out.below()).isFaceSturdy(level(), out.below(), Direction.UP)) {
                        motor.moveToward(Vec3.atBottomCenterOf(out));
                        debug = "stepping out to " + out.toShortString() + " from " + self.position() + " v=" + self.getDeltaMovement() + " t=" + stuckOut;
                        return true;
                    }
                }
            }
            motor.moveDirection(self.getLookAngle());
            return true;
        }
        if (level().dimension() == Level.NETHER && arrivedAt != null
                && (returning || now > stayUntil || threatened || self.getHealth() < self.getMaxHealth() * 0.5f)) {
            returning = true;
            Status st = enter(arrivedAt);
            debug = "home via " + arrivedAt.toShortString() + " portal=" + isPortal(arrivedAt) + " st=" + st + " at=" + self.blockPosition().toShortString()
                    + " stuck=" + motor.stuckCount();
            if (st == Status.FAILED) {
                BlockPos other = portalNear(arrivedAt, 6);
                if (other == null) {
                    other = knownPortal(64);
                }
                if (other == null) {
                    returning = false;
                    return false;
                }
                arrivedAt = other;
            }
            return true;
        }
        return false;
    }

    /** Running from monsters with a portal nearby: through it (works both ways). */
    public boolean escape() {
        BlockPos p = knownPortal(16);
        if (p == null) {
            return false;
        }
        if (enter(p) == Status.FAILED) {
            return false;
        }
        boolean inside = isPortal(self.blockPosition());
        if (inside && !inPortal) {
            portalEscapes++;
        }
        inPortal = inside;
        return true;
    }

    public void reset() {
        job = null;
        steppingOut = false;
        returning = false;
    }
}
