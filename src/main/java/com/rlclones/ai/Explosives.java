package com.rlclones.ai;

import com.rlclones.clone.Bases;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * TNT, set off with a button like a player would: used against a crowd of enemies, only where no clone, player,
 * tamed animal, villager or base can get hurt. Place it towards the crowd, put a button on it, press, run.
 */
public final class Explosives {
    private final ClonePlayer self;
    private final Motor motor;
    private final Perception perception;

    private int stage = -1;
    private int ticks;
    @Nullable
    private BlockPos tnt;
    @Nullable
    private BlockPos button;
    private Vec3 runTo;

    public int tntUsed;
    public String debug = "";

    public Explosives(ClonePlayer self, Motor motor, Perception perception) {
        this.self = self;
        this.motor = motor;
        this.perception = perception;
    }

    private int slot(java.util.function.Predicate<net.minecraft.world.item.ItemStack> p) {
        Inventory inv = self.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            if (!inv.items.get(i).isEmpty() && p.test(inv.items.get(i))) {
                return i;
            }
        }
        return -1;
    }

    public boolean busy() {
        return stage >= 0;
    }

    /** Nobody of ours (or anyone peaceful) and no base within blast range of {@code at}. */
    public boolean safeAt(Vec3 at) {
        ServerLevel level = self.serverLevel();
        List<LivingEntity> near = level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(10), e -> e != self && e.isAlive()
                && (e instanceof Player || (e instanceof TamableAnimal t && t.isTame()) || e instanceof AbstractVillager || e instanceof IronGolem));
        if (!near.isEmpty()) {
            debug = "friend near: " + near.get(0).getName().getString();
            return false;
        }
        if (Bases.get(self.getServer()).nearest(level.dimension(), at, 24) != null || Bases.get(self.getServer()).nearestPen(level.dimension(), at, 16) != null) {
            debug = "base near";
            return false;
        }
        return true;
    }

    /** A crowd worth blowing up: 3+ enemies bunched within 5 blocks of each other, 5..12 blocks away. */
    @Nullable
    private Vec3 crowd(long now) {
        List<Perception.Seen> threats = Senses.threats(perception, self, now, 14);
        if (threats.size() < 3) {
            return null;
        }
        Vec3 c = Vec3.ZERO;
        for (Perception.Seen s : threats) {
            c = c.add(s.pos);
        }
        c = c.scale(1.0 / threats.size());
        int bunched = 0;
        for (Perception.Seen s : threats) {
            if (s.pos.distanceTo(c) < 5) {
                bunched++;
            }
        }
        double d = c.distanceTo(self.position());
        if (bunched < 3 || d < 3 || d > 12) {
            return null;
        }
        return c;
    }

    /** Reflex: returns true while busy with the TNT this tick. */
    public boolean tick(long now) {
        if (stage < 0) {
            if (now % 10 != 0 || slot(s -> s.is(Items.TNT)) < 0 || slot(s -> s.is(ItemTags.BUTTONS)) < 0 || !self.onGround()) {
                return false;
            }
            Vec3 c = crowd(now);
            if (c == null || !safeAt(c)) {
                return false;
            }
            Vec3 dir = Consumables.horizontal(c.subtract(self.position()));
            BlockPos spot = BlockPos.containing(self.getX() + dir.x * 2.5, self.getY() + 0.1, self.getZ() + dir.z * 2.5);
            ServerLevel level = self.serverLevel();
            if (!level.getBlockState(spot).canBeReplaced() || level.getBlockState(spot.below()).getCollisionShape(level, spot.below()).isEmpty()
                    || !safeAt(Vec3.atCenterOf(spot))) {
                return false;
            }
            tnt = spot;
            stage = 0;
            ticks = 0;
            runTo = self.position().subtract(dir.scale(16));
        }
        if (++ticks > 200) {
            stage = -1;
            return false;
        }
        ServerLevel level = self.serverLevel();
        switch (stage) {
            case 0 -> {
                int s = slot(st -> st.is(Items.TNT));
                if (s < 0) {
                    stage = -1;
                    return false;
                }
                Equipment.select(self, s);
                motor.stop();
                if (motor.placeBlockAt(tnt) && level.getBlockState(tnt).is(net.minecraft.world.level.block.Blocks.TNT)) {
                    stage = 1;
                } else if (ticks > 10) {
                    stage = -1;
                    return false;
                }
            }
            case 1 -> {
                // a button on the side facing us
                int s = slot(st -> st.is(ItemTags.BUTTONS));
                if (s < 0 || !level.getBlockState(tnt).is(net.minecraft.world.level.block.Blocks.TNT)) {
                    stage = -1;
                    return false;
                }
                Vec3 c = Vec3.atCenterOf(tnt);
                Direction face = Direction.getNearest(self.getX() - c.x, 0, self.getZ() - c.z);
                Equipment.select(self, s);
                Vec3 hit = c.add(face.getStepX() * 0.5, 0, face.getStepZ() * 0.5);
                motor.lookAt(hit);
                self.gameMode.useItemOn(self, level, self.getMainHandItem(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, tnt, false));
                button = tnt.relative(face);
                stage = level.getBlockState(button).is(net.minecraft.tags.BlockTags.BUTTONS) ? 2 : 1;
                if (stage == 1 && ticks > 20) {
                    stage = -1;
                    return false;
                }
            }
            case 2 -> {
                // press it, then run
                Vec3 c = Vec3.atCenterOf(button);
                motor.lookAt(c);
                self.gameMode.useItemOn(self, level, self.getMainHandItem(), InteractionHand.MAIN_HAND,
                        new BlockHitResult(c, Direction.UP, button, false));
                self.swing(InteractionHand.MAIN_HAND);
                if (!level.getBlockState(tnt).is(net.minecraft.world.level.block.Blocks.TNT)) {
                    tntUsed++;
                    stage = 3;
                    ticks = 0;
                } else if (ticks > 40) {
                    stage = -1;
                    return false;
                }
            }
            default -> {
                motor.navigate(runTo, 1.0, true);
                if (ticks > 100) {
                    stage = -1;
                }
            }
        }
        return true;
    }
}
