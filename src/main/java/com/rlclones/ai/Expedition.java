package com.rlclones.ai;

import com.rlclones.ai.brain.Brain;
import com.rlclones.clone.Bases;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expeditions into unexplored land. The leader decides how many companions the trip needs, names a rally point in
 * chat and waits there; clones (and players) that answer follow the leader until it declares the goal reached.
 *
 * <p>Chat protocol (plain form): {@code RALLY x y z TO tx ty tz need=N}, {@code JOIN leader}, {@code DEPART tx ty tz},
 * {@code EXPEDITION_END}. Players can join by typing "join &lt;leader&gt;" / "参加".
 */
public final class Expedition {
    public enum Status {WORKING, DONE, FAILED}

    public record Offer(UUID leader, String leaderName, ResourceKey<Level> dimension, BlockPos rally, BlockPos target, int need, long tick) {
    }

    private static final Pattern RALLY = Pattern.compile("^RALLY (-?\\d+) (-?\\d+) (-?\\d+) TO (-?\\d+) (-?\\d+) (-?\\d+) need=(\\d+)");
    private static final int GATHER_TICKS = 600;
    private static final int COOLDOWN = 12000;
    /** A clone first gets to know its surroundings (5 minutes) before it leads anyone into the unknown. */
    private static final int FIRST_DELAY = 6000;
    private static final double JOIN_RADIUS = 96;

    private final ServerPlayer self;
    private final Motor motor;
    private final Random random = new Random();

    @Nullable
    private Offer offer;
    private final Map<String, Set<UUID>> joiners = new HashMap<>();

    // leading
    private boolean leading;
    private BlockPos rally;
    private BlockPos target;
    /** What the trip is for: explore unknown land, or hunt down a strong enemy / a crowd of enemies. */
    private String kind = "EXPLORE";
    private String foe = "";
    private int calmTicks;
    /** Is something hostile still around this spot? (given by the controller: what the clone itself perceives) */
    public java.util.function.Predicate<BlockPos> foesNear = p -> false;
    public int hunts;
    private int need;
    private int phase;
    private int phaseTicks;
    private int totalTicks;
    private int waitTicks;
    private Vec3 wander;
    private long lastLed;

    // member
    @Nullable
    private Offer joined;
    private boolean departed;
    private int memberTicks;
    private boolean finished;

    // a completed trip leaves a learned, optional return task rather than ending at the destination
    @Nullable
    private BlockPos returnTarget;
    @Nullable
    private ResourceKey<Level> returnDimension;
    private boolean returnPending;
    private boolean returned;
    private int returnTicks;
    public int returnedTrips;
    public int failedReturns;

    public int led;
    public int joinedTrips;
    public int completed;

    public Expedition(ServerPlayer self, Motor motor) {
        this.self = self;
        this.motor = motor;
        this.lastLed = self.level().getGameTime() - COOLDOWN + FIRST_DELAY;
    }

    public boolean isLeading() {
        return leading;
    }

    @Nullable
    public Offer joinedOffer() {
        return joined;
    }

    @Nullable
    public Offer offer() {
        return offer;
    }

    public BlockPos target() {
        return target;
    }

    public int need() {
        return need;
    }

    public boolean returnPending() {
        return returnPending && returnTarget != null;
    }

    @Nullable
    public BlockPos returnTarget() {
        return returnTarget;
    }

    /** Collects the "returned home" reward once. */
    public boolean takeReturned() {
        boolean done = returned;
        returned = false;
        return done;
    }

    public Set<UUID> joiners(String leaderName) {
        return joiners.getOrDefault(leaderName, Set.of());
    }

    /** Leading or committed to someone's expedition: stick with it until it is over. */
    public boolean committed() {
        return leading || joined != null;
    }

    /** Collects the "expedition completed" reward once. */
    public boolean takeFinished() {
        boolean f = finished;
        finished = false;
        return f;
    }

    // ================================================================== decisions

    /** How many companions a trip to {@code target} needs: darkness, weak gear, distance and past deaths add people. */
    public int companionsNeeded(BlockPos target, Brain brain) {
        int n = 1;
        if (Senses.isDark(self) || self.level().isNight()) {
            n++;
        }
        boolean armored = !self.getItemBySlot(EquipmentSlot.CHEST).isEmpty() || !self.getItemBySlot(EquipmentSlot.HEAD).isEmpty();
        if (!Equipment.isArmed(self) || !armored) {
            n++;
        }
        if (Math.sqrt(self.blockPosition().distSqr(target)) > 160) {
            n++;
        }
        if (brain.deaths > brain.kills) {
            n++;
        }
        return Mth.clamp(n, 1, 4);
    }

    /** An unexplored spot 120-200 blocks away (a chunk this clone has never been in). */
    @Nullable
    public BlockPos pickTarget(LongPredicate visited) {
        for (int i = 0; i < 12; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            double d = 120 + random.nextDouble() * 80;
            int x = Mth.floor(self.getX() + Math.cos(a) * d);
            int z = Mth.floor(self.getZ() + Math.sin(a) * d);
            if (visited.test(ChunkPos.asLong(x >> 4, z >> 4))) {
                continue;
            }
            int y = self.level().hasChunk(x >> 4, z >> 4)
                    ? self.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) : self.getBlockY();
            return new BlockPos(x, y, z);
        }
        return null;
    }

    public boolean canLead(long now) {
        return !committed() && !returnPending() && now - lastLed >= COOLDOWN && self.getHealth() >= self.getMaxHealth() * 0.7f;
    }

    /** Tests: the first-trip delay is over (a trip may be led now). */
    public void readyToLead() {
        lastLed = self.level().getGameTime() - COOLDOWN;
    }

    public boolean canJoin(long now) {
        Offer o = offer;
        return o != null && !committed() && !returnPending() && now - o.tick() < GATHER_TICKS && o.dimension() == self.level().dimension()
                && joiners(o.leaderName()).size() < o.need() && Math.sqrt(self.blockPosition().distSqr(o.rally())) < JOIN_RADIUS;
    }

    // ================================================================== chat

    /** Returns true if the line was expedition talk. */
    public boolean onChat(ServerPlayer sender, String text, long now) {
        String name = sender.getGameProfile().getName();
        Matcher m = RALLY.matcher(text);
        if (m.find()) {
            if (!sender.getUUID().equals(self.getUUID())) {
                offer = new Offer(sender.getUUID(), name, sender.level().dimension(),
                        new BlockPos(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))),
                        new BlockPos(Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6))),
                        Integer.parseInt(m.group(7)), now);
                joiners.put(name, new HashSet<>());
            }
            return true;
        }
        if (text.startsWith("JOIN ")) {
            joiners.computeIfAbsent(text.substring(5).trim(), k -> new HashSet<>()).add(sender.getUUID());
            return true;
        }
        if (text.startsWith("DEPART")) {
            if (joined != null && joined.leader().equals(sender.getUUID())) {
                departed = true;
            }
            return true;
        }
        if (text.startsWith("EXPEDITION_END")) {
            if (joined != null && joined.leader().equals(sender.getUUID())) {
                queueReturn(joined.dimension(), joined.rally());
                joined = null;
                finished = true;
                completed++;
            }
            if (offer != null && offer.leader().equals(sender.getUUID())) {
                offer = null;
            }
            joiners.remove(name);
            return true;
        }
        // a real player answering in their own words ("join Clone-1", "参加する")
        String lower = text.toLowerCase(Locale.ROOT);
        if (leading && (lower.contains("join") || lower.contains("参加") || lower.contains("行く") || lower.contains("いく"))
                && !(sender instanceof com.rlclones.clone.ClonePlayer)) {
            joiners.computeIfAbsent(self.getGameProfile().getName(), k -> new HashSet<>()).add(sender.getUUID());
            return true;
        }
        return false;
    }

    // ================================================================== leading

    public String kind() {
        return kind;
    }

    /** Lead a hunting party against {@code foe} (entity type id) at {@code target}. */
    public void leadHunt(BlockPos target, int need, String foe) {
        lead(target, need);
        this.kind = "HUNT";
        this.foe = foe;
        hunts++;
    }

    public void lead(BlockPos target, int need) {
        this.kind = "EXPLORE";
        this.foe = "";
        this.calmTicks = 0;
        this.returnPending = false;
        this.returnTarget = null;
        this.returnDimension = null;
        this.returnTicks = 0;
        this.leading = true;
        Bases.Base home = Bases.get(self.getServer()).nearest(self.level().dimension(), self.position(), 32);
        this.rally = home == null ? self.blockPosition().immutable() : home.center.immutable();
        this.target = target.immutable();
        this.need = need;
        this.phase = 0;
        this.phaseTicks = 0;
        this.totalTicks = 0;
        this.wander = null;
        lastLed = self.level().getGameTime();
        joiners.put(self.getGameProfile().getName(), new HashSet<>());
        led++;
    }

    private Set<UUID> myJoiners() {
        return joiners.computeIfAbsent(self.getGameProfile().getName(), k -> new HashSet<>());
    }

    @Nullable
    private ServerPlayer player(UUID id) {
        return self.getServer() == null ? null : self.getServer().getPlayerList().getPlayer(id);
    }

    public Status leadTick() {
        if (!leading) {
            return Status.DONE;
        }
        totalTicks++;
        phaseTicks++;
        if (totalTicks > 12000) {
            end();
            return Status.DONE;
        }
        switch (phase) {
            case 0 -> {
                String plain = "RALLY " + rally.getX() + " " + rally.getY() + " " + rally.getZ() + " TO " + target.getX() + " " + target.getY() + " " + target.getZ() + " need=" + need;
                if (kind.equals("HUNT")) {
                    net.minecraft.world.entity.EntityType<?> type = net.minecraft.world.entity.EntityType.byString(foe).orElse(null);
                    Component foeName = type == null ? Component.literal(foe) : type.getDescription();
                    Chat.say(self, Component.translatable("rlclones.chat.rally_hunt", rally.getX(), rally.getY(), rally.getZ(), foeName, target.getX(), target.getZ(), need),
                            plain + " kind=HUNT foe=" + foe);
                } else {
                    Chat.say(self, Component.translatable("rlclones.chat.rally", rally.getX(), rally.getY(), rally.getZ(), target.getX(), target.getZ(), need), plain);
                }
                phase = 1;
                phaseTicks = 0;
            }
            case 1 -> {
                // wait at the rally point until enough people came (or it is clear nobody else will)
                if (Motor.horizontalDistance(self.position(), Vec3.atBottomCenterOf(rally)) > 2) {
                    motor.navigate(Vec3.atBottomCenterOf(rally), 1.5, false);
                } else {
                    motor.stop();
                    ServerPlayer arriving = null;
                    for (UUID id : myJoiners()) {
                        ServerPlayer p = player(id);
                        if (p != null && p.isAlive()) {
                            arriving = p;
                        }
                    }
                    if (arriving != null) {
                        motor.lookAt(arriving.getEyePosition());
                    }
                }
                boolean enough = myJoiners().size() >= need && allArrived(8);
                boolean waitedEnough = phaseTicks > GATHER_TICKS && (myJoiners().isEmpty() || allArrived(8) || phaseTicks > GATHER_TICKS + 600);
                if (enough || waitedEnough) {
                    Chat.say(self, Component.translatable("rlclones.chat.depart", myJoiners().size(), target.getX(), target.getZ()),
                            "DEPART " + target.getX() + " " + target.getY() + " " + target.getZ());
                    phase = 2;
                    phaseTicks = 0;
                }
            }
            case 2 -> {
                // lead the way; wait for anyone falling behind
                if (!allArrived(20) && waitTicks < 200) {
                    waitTicks++;
                    motor.stop();
                    return Status.WORKING;
                }
                if (allArrived(10)) {
                    waitTicks = 0;
                }
                Vec3 goal = Vec3.atBottomCenterOf(target);
                if (Motor.horizontalDistance(self.position(), goal) < 8 || motor.stuckCount() > 10) {
                    phase = 3;
                    phaseTicks = 0;
                    return Status.WORKING;
                }
                motor.navigate(goal, 6, false);
            }
            default -> {
                if (kind.equals("HUNT")) {
                    // at the hunting ground: fighting is done by the normal combat layer; we stay until it is quiet
                    if (foesNear.test(target)) {
                        calmTicks = 0;
                    } else if (++calmTicks > 200 || phaseTicks > 6000) {
                        completed++;
                        finished = true;
                        end();
                        return Status.DONE;
                    }
                    if (Motor.horizontalDistance(self.position(), Vec3.atBottomCenterOf(target)) > 6) {
                        motor.navigate(Vec3.atBottomCenterOf(target), 4, false);
                    }
                    return Status.WORKING;
                }
                // look around the new land together
                if (wander == null || Motor.horizontalDistance(self.position(), wander) < 2 || phaseTicks % 120 == 0) {
                    double a = random.nextDouble() * Math.PI * 2;
                    wander = new Vec3(target.getX() + Math.cos(a) * 16, self.getY(), target.getZ() + Math.sin(a) * 16);
                }
                motor.navigate(wander, 2, false);
                if (phaseTicks > 400) {
                    completed++;
                    finished = true;
                    end();
                    return Status.DONE;
                }
            }
        }
        return Status.WORKING;
    }

    private boolean allArrived(double radius) {
        for (UUID id : myJoiners()) {
            ServerPlayer p = player(id);
            if (p != null && p.isAlive() && p.level() == self.level() && p.distanceTo(self) > radius) {
                return false;
            }
        }
        return true;
    }

    /** Declare the expedition over (goal reached, or the leader gave up / died) so companions are released. */
    public void end() {
        if (!leading) {
            return;
        }
        if (self.isAlive() && rally != null) {
            queueReturn(self.level().dimension(), rally);
        }
        leading = false;
        BlockPos p = self.blockPosition();
        Chat.say(self, Component.translatable(kind.equals("HUNT") ? "rlclones.chat.hunt_end" : "rlclones.chat.expedition_end", p.getX(), p.getZ()),
                "EXPEDITION_END " + p.getX() + " " + p.getY() + " " + p.getZ() + (kind.equals("HUNT") ? " kind=HUNT" : ""));
        joiners.remove(self.getGameProfile().getName());
    }

    private void queueReturn(ResourceKey<Level> dimension, BlockPos destination) {
        if (dimension == null || !dimension.equals(self.level().dimension()) || destination == null) {
            return;
        }
        if (self.distanceTo(Vec3.atBottomCenterOf(destination)) <= 4.0) {
            returnPending = false;
            returnTarget = null;
            returnDimension = null;
            returnTicks = 0;
            return;
        }
        returnDimension = dimension;
        returnTarget = destination.immutable();
        returnPending = true;
        returnTicks = 0;
    }

    /** The RL-selected RETURN option follows this remembered route; failed attempts remain negative experience. */
    public Status returnTick() {
        if (!returnPending || returnTarget == null) {
            return Status.DONE;
        }
        if (returnDimension == null || !returnDimension.equals(self.level().dimension())) {
            clearReturn();
            failedReturns++;
            return Status.FAILED;
        }
        if (self.distanceTo(Vec3.atBottomCenterOf(returnTarget)) <= 4.0) {
            motor.stop();
            clearReturn();
            returned = true;
            returnedTrips++;
            return Status.DONE;
        }
        if (++returnTicks >= 6000) {
            clearReturn();
            failedReturns++;
            return Status.FAILED;
        }
        motor.navigate(Vec3.atBottomCenterOf(returnTarget), 3.0, self.distanceTo(Vec3.atBottomCenterOf(returnTarget)) > 16.0);
        return Status.WORKING;
    }

    private void clearReturn() {
        returnPending = false;
        returnTarget = null;
        returnDimension = null;
        returnTicks = 0;
    }

    // ================================================================== following

    public void join() {
        Offer o = offer;
        if (o == null) {
            return;
        }
        joined = o;
        departed = false;
        memberTicks = 0;
        joinedTrips++;
        joiners.computeIfAbsent(o.leaderName(), k -> new HashSet<>()).add(self.getUUID());
        Chat.say(self, Component.translatable("rlclones.chat.join", o.leaderName(), o.rally().getX(), o.rally().getY(), o.rally().getZ()), "JOIN " + o.leaderName());
    }

    public Status followTick() {
        Offer o = joined;
        if (o == null) {
            return Status.DONE;
        }
        ServerPlayer leader = player(o.leader());
        if (leader == null || !leader.isAlive() || leader.level() != self.level() || ++memberTicks > 14000) {
            queueReturn(o.dimension(), o.rally());
            joined = null;
            return Status.DONE;
        }
        Vec3 rallyPos = Vec3.atBottomCenterOf(o.rally());
        if (!departed && Motor.horizontalDistance(leader.position(), rallyPos) > 24) {
            departed = true; // the group already left
        }
        if (!departed) {
            if (Motor.horizontalDistance(self.position(), rallyPos) > 3) {
                motor.navigate(rallyPos, 2.5, true);
            } else {
                motor.stop();
                motor.lookAt(leader.getEyePosition());
            }
            return Status.WORKING;
        }
        double d = self.distanceTo(leader);
        if (d > 4) {
            motor.navigate(leader.position(), 3, d > 12);
        } else {
            motor.stop();
            motor.lookAt(leader.getEyePosition());
        }
        return Status.WORKING;
    }

    /** Leave a trip (e.g. the clone died). */
    public void abandon() {
        joined = null;
        end();
    }
}
