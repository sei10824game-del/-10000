package com.rlclones.ai;

import com.rlclones.entity.PathProxyEntity;
import com.rlclones.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * Turns intentions into exactly the inputs a human player has: yaw/pitch, WASD (zza/xxa), jump, sprint, sneak,
 * left click (attack / mine) and right click (use item / place). Turning speed is limited like a real mouse.
 */
public final class Motor {
    public static final double BLOCK_REACH = 4.5;
    private static final float MAX_YAW_STEP = 35f;
    private static final float MAX_PITCH_STEP = 25f;

    private final ServerPlayer self;

    // intentions for the current tick
    private Vec3 lookTarget;
    private float wantYaw;
    private float wantPitch;
    private boolean hasAngles;
    private Vec3 moveDir;
    private float forward;
    private float strafe;
    private boolean jump;
    private boolean sprint;
    private boolean sneak;

    // path following
    private PathProxyEntity proxy;
    private Path path;
    private Vec3 pathGoal;
    private int repathTimer;
    private Vec3 progressAnchor;
    private int progressTimer;
    private int stuckCount;

    // elytra flight
    private Vec3 flightGoal;
    private int takeoffTicks;
    private int rocketCooldown;

    // mining
    private BlockPos breakingPos;
    private float breakProgress;
    private int breakStage = -1;

    public Motor(ServerPlayer self) {
        this.self = self;
    }

    // ------------------------------------------------------------------ intentions

    public void lookAt(Vec3 point) {
        lookTarget = point;
        hasAngles = false;
    }

    public void lookAt(Entity e) {
        AABB bb = e.getBoundingBox();
        double y = Mth.clamp(self.getEyeY(), bb.minY + bb.getYsize() * 0.2, bb.maxY - bb.getYsize() * 0.1);
        lookAt(new Vec3(e.getX(), y, e.getZ()));
    }

    public void lookAngles(float yaw, float pitch) {
        wantYaw = yaw;
        wantPitch = pitch;
        hasAngles = true;
        lookTarget = null;
    }

    public boolean hasLookIntent() {
        return lookTarget != null || hasAngles;
    }

    /** Walk in a world direction (horizontal), independent of where we look. */
    public void moveDirection(Vec3 dir) {
        Vec3 h = new Vec3(dir.x, 0, dir.z);
        moveDir = h.lengthSqr() < 1e-6 ? null : h.normalize();
    }

    public void moveToward(Vec3 point) {
        moveDirection(point.subtract(self.position()));
    }

    public void moveAwayFrom(Vec3 point) {
        moveDirection(self.position().subtract(point));
    }

    public void forward(float f) {
        forward = f;
    }

    public void strafe(float left) {
        strafe = left;
    }

    public void jump() {
        jump = true;
    }

    public void sprint(boolean s) {
        sprint = s;
    }

    public void sneak(boolean s) {
        sneak = s;
    }

    public void stop() {
        moveDir = null;
        forward = 0;
        strafe = 0;
        sprint = false;
    }

    // ------------------------------------------------------------------ navigation

    /**
     * Walks to the goal using the vanilla path finder (with a player-sized proxy) and steers along the path.
     *
     * @return true once within {@code arrive} blocks of the goal
     */
    private Vec3 lastGoal;
    private long lastGoalTick;

    /** Where we were last trying to walk to (within the last 5 s), or null. */
    public Vec3 recentGoal() {
        return lastGoal != null && self.level().getGameTime() - lastGoalTick < 100 ? lastGoal : null;
    }

    public boolean navigate(Vec3 goal, double arrive, boolean run) {
        Vec3 pos = self.position();
        if (lastGoal == null || lastGoal.distanceToSqr(goal) > 16.0) {
            resetStuck(); // a new destination: earlier trouble elsewhere does not count
        }
        lastGoal = goal;
        lastGoalTick = self.level().getGameTime();
        if (self.isFallFlying() || takeoffTicks > 0) {
            flightGoal = goal;
            return false;
        }
        if (horizontalDistance(pos, goal) > 40 && self.onGround() && Equipment.hasElytra(self) && Equipment.rocketSlot(self) >= 0
                && self.level().canSeeSky(self.blockPosition().above(2))) {
            flightGoal = goal;
            takeoffTicks = 1;
            clearPath();
            return false;
        }
        if (horizontalDistance(pos, goal) <= arrive && Math.abs(pos.y - goal.y) < 2.0) {
            clearPath();
            return true;
        }
        if (repathTimer > 0) {
            repathTimer--;
        }
        boolean goalMoved = pathGoal == null || pathGoal.distanceToSqr(goal) > 4.0;
        if ((path == null || path.isDone() || goalMoved) && repathTimer <= 0 && (self.onGround() || self.isInWater())) {
            path = computePath(goal, arrive);
            pathGoal = goal;
            repathTimer = path == null ? 20 : 10;
        }
        Vec3 steer = goal;
        if (path != null && !path.isDone()) {
            while (!path.isDone()) {
                Node n = path.getNextNode();
                Vec3 c = new Vec3(n.x + 0.5, n.y, n.z + 0.5);
                // swimming: path nodes may lie on the bottom while we float at the surface -> judge horizontally
                if (horizontalDistance(pos, c) < 0.45 && (Math.abs(pos.y - n.y) < 1.2 || self.isInWater())) {
                    path.advance();
                } else {
                    break;
                }
            }
            if (!path.isDone()) {
                Node n = path.getNextNode();
                steer = new Vec3(n.x + 0.5, n.y, n.z + 0.5);
                if (n.y > Mth.floor(pos.y + 0.5) && self.onGround()) {
                    jump();
                }
            }
        }
        moveToward(steer);
        sprint(run);
        if (steer.y > pos.y + 0.5 && self.isInWater()) {
            jump();
        }
        trackProgress(true);
        return false;
    }

    public void clearPath() {
        path = null;
        pathGoal = null;
    }

    public void resetStuck() {
        stuckCount = 0;
        progressAnchor = null;
        progressTimer = 0;
    }

    /** Number of consecutive "no progress" periods while trying to move; options use this to give up. */
    public int stuckCount() {
        return stuckCount;
    }

    private Path computePath(Vec3 goal, double arrive) {
        if (!(self.level() instanceof ServerLevel level)) {
            return null;
        }
        if (proxy == null || proxy.level() != level) {
            proxy = ModEntities.PATH_PROXY.get().create(level);
            if (proxy == null) {
                return null;
            }
        }
        proxy.moveTo(self.getX(), self.getY(), self.getZ(), self.getYRot(), 0f);
        proxy.setOnGround(true);
        int accuracy = Math.max(0, (int) Math.floor(arrive));
        return proxy.getNavigation().createPath(BlockPos.containing(goal), accuracy);
    }

    private void trackProgress(boolean moving) {
        if (!moving) {
            return;
        }
        if (progressAnchor == null) {
            progressAnchor = self.position();
            progressTimer = 0;
        }
        if (++progressTimer >= 20) {
            if (self.position().distanceTo(progressAnchor) < 0.5) {
                stuckCount++;
                jump();
                path = null;
                repathTimer = 0;
            } else {
                stuckCount = 0;
            }
            progressAnchor = self.position();
            progressTimer = 0;
        }
    }

    public static double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ------------------------------------------------------------------ left click

    /** Eye-to-hitbox raycast along the current view direction, exactly like the crosshair. */
    public boolean canHit(Entity target) {
        double reach = self.getEntityReach();
        Vec3 eye = self.getEyePosition();
        AABB bb = target.getBoundingBox().inflate(target.getPickRadius());
        if (bb.contains(eye)) {
            return true;
        }
        Vec3 end = eye.add(self.getViewVector(1.0F).scale(reach));
        Optional<Vec3> hit = bb.clip(eye, end);
        if (hit.isEmpty()) {
            return false;
        }
        BlockHitResult block = self.level().clip(new ClipContext(eye, hit.get(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, self));
        return block.getType() == HitResult.Type.MISS;
    }

    public boolean withinReach(Entity target) {
        return self.getEyePosition().distanceTo(closestPoint(target.getBoundingBox(), self.getEyePosition())) <= self.getEntityReach();
    }

    public static Vec3 closestPoint(AABB bb, Vec3 p) {
        return new Vec3(Mth.clamp(p.x, bb.minX, bb.maxX), Mth.clamp(p.y, bb.minY, bb.maxY), Mth.clamp(p.z, bb.minZ, bb.maxZ));
    }

    public void attack(Entity target) {
        self.resetLastActionTime();
        self.attack(target);
        self.swing(InteractionHand.MAIN_HAND);
    }

    /**
     * Mine a block with the held item, one tick at a time, at vanilla survival speed.
     *
     * @return true when the block is gone
     */
    public boolean mine(BlockPos pos) {
        ServerLevel level = self.serverLevel();
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            resetMining();
            return true;
        }
        if (!pos.equals(breakingPos)) {
            resetMining();
            breakingPos = pos.immutable();
        }
        Vec3 eye = self.getEyePosition();
        if (eye.distanceTo(Vec3.atCenterOf(pos)) > BLOCK_REACH) {
            lookAt(Vec3.atCenterOf(pos));
            return false;
        }
        // aim at a part of the block we can actually see (a floor block seen at a flat angle shows only its top)
        lookAt(visiblePoint(level, eye, pos));
        BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(self.getViewVector(1.0F).scale(BLOCK_REACH)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, self));
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) {
            return false;
        }
        if (!self.mayInteract(level, pos) || self.blockActionRestricted(level, pos, self.gameMode.getGameModeForPlayer())) {
            return false;
        }
        self.resetLastActionTime();
        breakProgress += state.getDestroyProgress(self, level, pos);
        if (self.tickCount % 4 == 0) {
            self.swing(InteractionHand.MAIN_HAND);
        }
        int stage = (int) (breakProgress * 10.0F);
        if (stage != breakStage) {
            level.destroyBlockProgress(self.getId(), pos, Math.min(stage, 9));
            breakStage = stage;
        }
        if (breakProgress >= 1.0F) {
            level.destroyBlockProgress(self.getId(), pos, -1);
            self.gameMode.destroyBlock(pos);
            resetMining();
            return level.getBlockState(pos).isAir() || !level.getBlockState(pos).is(state.getBlock());
        }
        return false;
    }

    private static final double[][] FACE_POINTS = {{0, 0, 0}, {0, 0.45, 0}, {0.45, 0, 0}, {-0.45, 0, 0}, {0, 0, 0.45}, {0, 0, -0.45}, {0, -0.45, 0}};

    private Vec3 visiblePoint(ServerLevel level, Vec3 eye, BlockPos pos) {
        Vec3 c = Vec3.atCenterOf(pos);
        for (double[] o : FACE_POINTS) {
            Vec3 p = c.add(o[0], o[1], o[2]);
            if (eye.distanceTo(p) > BLOCK_REACH) {
                continue;
            }
            BlockHitResult hit = level.clip(new ClipContext(eye, p, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, self));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) {
                return p;
            }
        }
        return c;
    }

    public void resetMining() {
        if (breakingPos != null && breakStage >= 0) {
            self.level().destroyBlockProgress(self.getId(), breakingPos, -1);
        }
        breakingPos = null;
        breakProgress = 0;
        breakStage = -1;
    }

    // ------------------------------------------------------------------ right click

    public boolean useHeldItem(InteractionHand hand) {
        ItemStack stack = self.getItemInHand(hand);
        if (stack.isEmpty()) {
            return false;
        }
        self.resetLastActionTime();
        InteractionResult r = self.gameMode.useItem(self, self.level(), stack, hand);
        return r.consumesAction() || self.isUsingItem();
    }

    /**
     * Place the held block at {@code target} by right-clicking a face of an adjacent solid block, exactly as a
     * player builds (supports walls and roofs). Returns true if the block is there afterwards.
     */
    public boolean placeBlockAt(BlockPos target) {
        ServerLevel level = self.serverLevel();
        if (!level.getBlockState(target).canBeReplaced()) {
            return false;
        }
        if (self.getBoundingBox().intersects(new AABB(target))) {
            return false;
        }
        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
        for (Direction toSupport : order) {
            BlockPos support = target.relative(toSupport);
            BlockState st = level.getBlockState(support);
            if (st.canBeReplaced() || st.getCollisionShape(level, support).isEmpty()) {
                continue;
            }
            Direction face = toSupport.getOpposite();
            Vec3 hitVec = Vec3.atCenterOf(support).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            if (self.getEyePosition().distanceTo(hitVec) > BLOCK_REACH) {
                continue;
            }
            lookAt(hitVec);
            self.resetLastActionTime();
            InteractionResult r = self.gameMode.useItemOn(self, level, self.getMainHandItem(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(hitVec, face, support, false));
            if (r.consumesAction()) {
                self.swing(InteractionHand.MAIN_HAND);
            }
            if (!level.getBlockState(target).canBeReplaced()) {
                return true;
            }
        }
        return false;
    }

    /** Right-click the top face of {@code against} with the main hand item (e.g. placing a block on it). */
    public boolean useOnTopFace(BlockPos against) {
        ItemStack stack = self.getMainHandItem();
        BlockHitResult hit = new BlockHitResult(new Vec3(against.getX() + 0.5, against.getY() + 1.0, against.getZ() + 0.5), Direction.UP, against, false);
        self.resetLastActionTime();
        InteractionResult r = self.gameMode.useItemOn(self, self.level(), stack, InteractionHand.MAIN_HAND, hit);
        if (r.consumesAction()) {
            self.swing(InteractionHand.MAIN_HAND);
            return true;
        }
        return false;
    }

    private void openDoorAhead() {
        if (!self.horizontalCollision) {
            return;
        }
        Direction facing = Direction.fromYRot(self.getYRot());
        for (int dy = 0; dy <= 1; dy++) {
            BlockPos p = self.blockPosition().relative(facing).above(dy);
            BlockState st = self.level().getBlockState(p);
            boolean door = st.getBlock() instanceof DoorBlock && st.is(BlockTags.WOODEN_DOORS);
            boolean gate = st.getBlock() instanceof FenceGateBlock;
            if ((door || gate) && st.hasProperty(BlockStateProperties.OPEN) && !st.getValue(BlockStateProperties.OPEN)) {
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(p), facing.getOpposite(), p, false);
                self.gameMode.useItemOn(self, self.level(), self.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
                self.swing(InteractionHand.MAIN_HAND);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ elytra

    public boolean isFlying() {
        return self.isFallFlying() || takeoffTicks > 0;
    }

    private void flight() {
        if (rocketCooldown > 0) {
            rocketCooldown--;
        }
        // reflex: falling with an elytra -> open it (a player presses jump in mid-air)
        if (!self.isFallFlying() && takeoffTicks == 0 && !self.onGround() && !self.isInWater() && self.fallDistance > 4.0F
                && Equipment.hasElytra(self)) {
            Equipment.equipElytra(self);
            self.tryToStartFallFlying();
        }
        if (takeoffTicks > 0) {
            takeoffTicks++;
            Equipment.equipElytra(self);
            float yaw = flightGoal == null ? self.getYRot() : yawTo(flightGoal);
            lookAngles(yaw, -35f);
            if (self.onGround() && takeoffTicks < 5) {
                jump = true;
            } else if (!self.onGround() && !self.isFallFlying() && self.tryToStartFallFlying()) {
                fireRocket();
                takeoffTicks = 0;
            }
            if (takeoffTicks > 25) {
                takeoffTicks = 0;
            }
            return;
        }
        if (!self.isFallFlying()) {
            if (self.onGround()) {
                flightGoal = null;
            }
            return;
        }
        if (flightGoal == null) {
            lookAngles(self.getYRot(), 12f); // plain glide down
            return;
        }
        Vec3 pos = self.position();
        double h = horizontalDistance(pos, flightGoal);
        float pitch;
        if (h < 14) {
            pitch = 40f; // come down to land
        } else {
            double wantAlt = flightGoal.y + Math.min(30.0, h * 0.25);
            pitch = (float) Mth.clamp((pos.y - wantAlt) * 2.0, -30.0, 30.0);
            if (self.getDeltaMovement().length() < 0.9 && rocketCooldown <= 0 && Equipment.rocketSlot(self) >= 0) {
                fireRocket();
            }
        }
        lookAngles(yawTo(flightGoal), pitch);
    }

    private void fireRocket() {
        int slot = Equipment.rocketSlot(self);
        if (slot < 0) {
            return;
        }
        Equipment.select(self, slot);
        if (useHeldItem(InteractionHand.MAIN_HAND)) {
            rocketCooldown = 30;
        }
    }

    private float yawTo(Vec3 p) {
        return (float) Math.toDegrees(Mth.atan2(p.z - self.getZ(), p.x - self.getX())) - 90.0F;
    }

    // ------------------------------------------------------------------ apply

    public void tick() {
        flight();
        // rotation
        float yaw = self.getYRot();
        float pitch = self.getXRot();
        float targetYaw = yaw;
        float targetPitch = pitch;
        if (lookTarget != null) {
            Vec3 eye = self.getEyePosition();
            double dx = lookTarget.x - eye.x;
            double dy = lookTarget.y - eye.y;
            double dz = lookTarget.z - eye.z;
            double horiz = Math.sqrt(dx * dx + dz * dz);
            targetYaw = (float) Math.toDegrees(Mth.atan2(dz, dx)) - 90.0F;
            targetPitch = (float) -Math.toDegrees(Mth.atan2(dy, horiz));
        } else if (hasAngles) {
            targetYaw = wantYaw;
            targetPitch = wantPitch;
        } else if (moveDir != null) {
            targetYaw = (float) Math.toDegrees(Mth.atan2(moveDir.z, moveDir.x)) - 90.0F;
            targetPitch = 0f;
        }
        float dyaw = Mth.clamp(Mth.wrapDegrees(targetYaw - yaw), -MAX_YAW_STEP, MAX_YAW_STEP);
        float dpitch = Mth.clamp(targetPitch - pitch, -MAX_PITCH_STEP, MAX_PITCH_STEP);
        float newYaw = Mth.wrapDegrees(yaw + dyaw);
        float newPitch = Mth.clamp(pitch + dpitch, -90f, 90f);
        self.setYRot(newYaw);
        self.setYHeadRot(newYaw);
        self.setXRot(newPitch);

        // movement relative to the new facing
        float zza = forward;
        float xxa = strafe;
        if (moveDir != null) {
            double rad = Math.toRadians(newYaw);
            double fx = -Math.sin(rad);
            double fz = Math.cos(rad);
            double lx = Math.cos(rad);
            double lz = Math.sin(rad);
            zza = (float) (moveDir.x * fx + moveDir.z * fz);
            xxa = (float) (moveDir.x * lx + moveDir.z * lz);
        }
        float len = Mth.sqrt(zza * zza + xxa * xxa);
        if (len > 1f) {
            zza /= len;
            xxa /= len;
        }
        boolean using = self.isUsingItem() && !self.isPassenger();
        if (using) {
            zza *= 0.2f;
            xxa *= 0.2f;
        }
        if (sneak) {
            zza *= 0.3f;
            xxa *= 0.3f;
        }
        boolean canSprint = sprint && zza >= 0.8f && !using && !sneak && !self.hasEffect(MobEffects.BLINDNESS)
                && (self.getFoodData().getFoodLevel() > 6 || self.getAbilities().mayfly);
        if (self.isSprinting() != canSprint) {
            self.setSprinting(canSprint);
        }
        if (self.isShiftKeyDown() != sneak) {
            self.setShiftKeyDown(sneak);
        }
        boolean moving = Math.abs(zza) > 0.05f || Math.abs(xxa) > 0.05f;
        if (moving && self.horizontalCollision && self.onGround()) {
            openDoorAhead();
            jump = true;
        }
        if (self.isInWater() || self.isInLava()) {
            // swim: keep the head above the surface; with forward input this also climbs out onto a ledge
            jump = true;
        }
        self.zza = zza;
        self.xxa = xxa;
        self.setJumping(jump);

        // reset one-tick intentions
        lookTarget = null;
        hasAngles = false;
        moveDir = null;
        forward = 0;
        strafe = 0;
        jump = false;
        sprint = false;
        sneak = false;
        if (!moving) {
            progressAnchor = null;
        }
    }
}
