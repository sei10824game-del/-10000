package com.rlclones.ai;

import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Fishing like a player: stand by open water, cast towards it, wait, and reel in when the bobber splashes
 * (the clone hears the splash just like a player does).
 */
public final class Fishing {
    public enum Status {WORKING, DONE, FAILED}

    private final ClonePlayer self;
    private final Motor motor;

    @Nullable
    private BlockPos water;
    private int ticks;
    private int castTicks;
    private boolean bite;
    private int sinceCast;
    /** Ticks the bobber has been out of the water (it bobs up and down, so one dry tick means nothing). */
    private int dryTicks;
    public String debug = "";
    public int heard;

    public int casts;
    public int catches;

    public Fishing(ClonePlayer self, Motor motor) {
        this.self = self;
        this.motor = motor;
    }

    public static int rodSlot(net.minecraft.world.entity.player.Player p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            if (inv.items.get(i).getItem() instanceof FishingRodItem) {
                return i;
            }
        }
        return -1;
    }

    /** Water (a source with air above) the clone can see, 3..10 blocks away. */
    @Nullable
    public BlockPos fishingSpot() {
        ServerLevel level = self.serverLevel();
        Vec3 eye = self.getEyePosition();
        BlockPos feet = self.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(feet.offset(-10, -4, -10), feet.offset(10, 1, 10))) {
            if (!level.getFluidState(p).is(FluidTags.WATER) || !level.getFluidState(p).isSource() || !level.getBlockState(p.above()).isAir()) {
                continue;
            }
            double d = Math.abs(Vec3.atCenterOf(p).distanceTo(eye) - 5.0);
            if (d >= bestD) {
                continue;
            }
            BlockHitResult hit = level.clip(new ClipContext(eye, Vec3.atCenterOf(p), ClipContext.Block.COLLIDER, ClipContext.Fluid.SOURCE_ONLY, self));
            // whatever water the line of sight reaches first is water we can see
            if (hit.getType() == HitResult.Type.BLOCK && level.getFluidState(hit.getBlockPos()).is(FluidTags.WATER)
                    && level.getFluidState(hit.getBlockPos()).isSource() && hit.getBlockPos().distSqr(feet) > 4) {
                bestD = d;
                best = hit.getBlockPos().immutable();
            }
        }
        return best;
    }

    public boolean canFish() {
        return rodSlot(self) >= 0 && fishingSpot() != null;
    }

    public void begin() {
        water = fishingSpot();
        ticks = 0;
        castTicks = 0;
        bite = false;
        sinceCast = 0;
    }

    /** The bobber splashed (a fish bites): reel in now. Called from hearing. */
    public void onSound(String path, double x, double y, double z) {
        if (path.contains("fishing_bobber")) {
            heard++;
        }
        if (path.contains("fishing_bobber.splash") && self.fishing != null && self.fishing.position().distanceTo(new Vec3(x, y, z)) < 2.5) {
            bite = true;
        }
    }

    public void reset() {
        if (self.fishing != null && rodSlot(self) >= 0) {
            Equipment.select(self, rodSlot(self));
            motor.useHeldItem(InteractionHand.MAIN_HAND); // reel in
        }
    }

    /**
     * The pitch that lands the bobber {@code dist} blocks away, {@code drop} below the eyes: the cast is played out in
     * the head (same flight as a thrown bobber: fast start, drag 0.92, gravity 0.03) for each angle.
     */
    static float castPitch(double dist, double drop) {
        float best = 20f;
        double bestErr = Double.MAX_VALUE;
        for (int p = -40; p <= 75; p++) {
            double rad = Math.toRadians(p);
            double vy0 = Mth.clamp(-Math.tan(rad), -5.0, 5.0);
            double k = 0.6 / Math.sqrt(1 + vy0 * vy0) + 0.5;
            double vx = k;
            double vy = vy0 * k;
            double x = 0;
            double y = 0;
            for (int t = 0; t < 200; t++) {
                x += vx;
                y += vy;
                if (y <= -drop) {
                    break;
                }
                vx *= 0.92;
                vy = vy * 0.92 - 0.03;
            }
            double err = Math.abs(x - dist);
            if (err < bestErr) {
                bestErr = err;
                best = p;
            }
        }
        return best;
    }

    public Status tick() {
        if (water == null || rodSlot(self) < 0 || ++ticks > 2400) {
            reset();
            return water == null ? Status.FAILED : Status.DONE;
        }
        Equipment.select(self, rodSlot(self));
        motor.stop();
        Vec3 aim = Vec3.atCenterOf(water).add(0, 1.5, 0);
        Vec3 eye = self.getEyePosition();
        float yaw = (float) Math.toDegrees(Mth.atan2(aim.z - eye.z, aim.x - eye.x)) - 90.0F;
        double dist = Math.sqrt((aim.x - eye.x) * (aim.x - eye.x) + (aim.z - eye.z) * (aim.z - eye.z));
        float pitch = castPitch(dist - 0.3, eye.y - (water.getY() + 0.9)); // the line starts a little in front of us
        if (self.fishing == null) {
            if (++castTicks < 5) {
                return Status.WORKING;
            }
            self.setYRot(yaw);
            self.setYHeadRot(yaw);
            self.setXRot(pitch);
            motor.useHeldItem(InteractionHand.MAIN_HAND); // cast
            casts++;
            castTicks = 0;
            sinceCast = 0;
            dryTicks = 0;
            bite = false;
            return Status.WORKING;
        }
        sinceCast++;
        motor.lookAt(self.fishing.position());
        if (bite) {
            int before = self.getInventory().items.stream().mapToInt(net.minecraft.world.item.ItemStack::getCount).sum();
            motor.useHeldItem(InteractionHand.MAIN_HAND); // reel in
            bite = false;
            catches++;
            return before >= 0 && catches >= 1 ? Status.DONE : Status.WORKING;
        }
        BlockPos hook = self.fishing.blockPosition();
        boolean wet = self.fishing.isInWater() || self.serverLevel().getFluidState(hook).is(FluidTags.WATER)
                || self.serverLevel().getFluidState(hook.below()).is(FluidTags.WATER);
        dryTicks = wet ? 0 : dryTicks + 1;
        debug = "since=" + sinceCast + " dry=" + dryTicks + " hook=" + hook.toShortString();
        if (sinceCast > 1800 || dryTicks > 40 && sinceCast > 60) {
            motor.useHeldItem(InteractionHand.MAIN_HAND); // nothing / landed badly: pull in and cast again
        }
        return Status.WORKING;
    }
}
