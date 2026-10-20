package com.rlclones.ai;

import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * R-20 / R-32: generated structures (villages, mineshafts, ruins, mod structures) seen on the way. A chest in sight that
 * lies inside a generated structure is noted as a site (and shared: "STRUCT x y z"); EXPLORE then heads for the nearest
 * site not visited yet, and the chests there are left to LOOT as usual. ponytail: found by a chest in sight only,
 * add other "artificial" blocks (doors, beds, spawners) when villages without chests in sight are missed.
 */
public final class Structures {
    private static final int RANGE = 128;
    private static final int VISIT = 10;

    private static final class Site {
        final BlockPos pos;
        boolean visited;

        Site(BlockPos pos) {
            this.pos = pos;
        }
    }

    private final ClonePlayer self;
    private final List<Site> sites = new ArrayList<>();
    /** Sites noted / visited (diagnostics, tests). */
    public int found;
    public int visits;
    /** A village is known (seen by this clone or told of by a friend). */
    public boolean villageKnown;
    private boolean villageSaid;

    public Structures(ClonePlayer self) {
        this.self = self;
    }

    private boolean known(BlockPos p) {
        for (Site s : sites) {
            if (s.pos.distManhattan(p) <= 48) {
                return true;
            }
        }
        return false;
    }

    private boolean add(BlockPos p) {
        if (known(p) || sites.size() >= 32) {
            return false;
        }
        sites.add(new Site(p.immutable()));
        found++;
        return true;
    }

    /** Called every tick; looks every two seconds. */
    public void tick(long now, Perception perception) {
        if ((now + self.getId()) % 40 != 0) {
            return;
        }
        ServerLevel level = self.serverLevel();
        for (var e : perception.blocks().entrySet()) {
            BlockPos p = e.getKey();
            if (e.getValue() == Perception.BlockKind.CHEST && p.distSqr(self.blockPosition()) < 48 * 48 && !known(p)
                    && !level.structureManager().getAllStructuresAt(p).isEmpty() && add(p)) {
                Chat.say(self, Component.literal("STRUCT"), "STRUCT " + p.getX() + " " + p.getY() + " " + p.getZ());
            }
        }
        if (!villageSaid) {
            for (net.minecraft.world.entity.npc.Villager v : level.getEntitiesOfClass(net.minecraft.world.entity.npc.Villager.class, self.getBoundingBox().inflate(32), net.minecraft.world.entity.LivingEntity::isAlive)) {
                if (perception.canSee(v)) { // seen with its own eyes, not just near
                    BlockPos p = v.blockPosition();
                    villageSaid = true;
                    villageKnown = true;
                    add(p);
                    Chat.say(self, Component.translatable("rlclones.chat.village", p.getX(), p.getZ()), "VILLAGE " + p.getX() + " " + p.getY() + " " + p.getZ());
                    break;
                }
            }
        }
        for (Site s : sites) {
            if (!s.visited && Motor.horizontalDistance(self.position(), Vec3.atCenterOf(s.pos)) < VISIT && Math.abs(self.getY() - s.pos.getY()) < 12) {
                s.visited = true;
                visits++;
            }
        }
    }

    /** "STRUCT x y z" from a friend. */
    public boolean onChat(String text) {
        if (text.startsWith("VILLAGE ")) {
            villageKnown = true; // a friend found one: it is a place to visit (and no trip to look for one)
            villageSaid = true; // (the finder has said it: not again)
            return onChat("STRUCT " + text.substring(8));
        }
        if (!text.startsWith("STRUCT ")) {
            return false;
        }
        String[] p = text.split(" ");
        try {
            add(new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])));
        } catch (RuntimeException ignored) {
        }
        return true;
    }

    /** The nearest site not visited yet, within 128 blocks. */
    @Nullable
    public Vec3 target() {
        Site best = null;
        double bestD = RANGE * RANGE;
        for (Site s : sites) {
            double d = s.pos.distSqr(self.blockPosition());
            if (!s.visited && d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best == null ? null : Vec3.atBottomCenterOf(best.pos);
    }

    /** Test hook. */
    public void note(BlockPos p) {
        add(p);
    }
}
