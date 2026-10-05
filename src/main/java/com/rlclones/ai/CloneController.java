package com.rlclones.ai;

import com.rlclones.Config;
import com.rlclones.ai.brain.Brain;
import com.rlclones.ai.brain.EnemyKnowledge;
import com.rlclones.ai.combat.CombatAction;
import com.rlclones.ai.observe.AgentWatcher;
import com.rlclones.ai.strategy.Option;
import com.rlclones.clone.ClonePlayer;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.ClipContext;

import javax.annotation.Nullable;
import net.minecraft.world.entity.player.Player;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The clone's mind loop: perceive -> learn from watching others -> pick a high-level option (strategy Q-table)
 * -> in fights pick low-level actions from the Q-table of that enemy type -> drive the {@link Motor}.
 */
public final class CloneController {
    private static final long UNKNOWN = Long.MAX_VALUE / 4;

    private final ClonePlayer self;
    private final Perception perception;
    private final Motor motor;
    private final AgentWatcher watcher;
    private final Crafting crafting;
    private final Escape escape;
    private boolean escaping;
    private long escapeFailedAt = -100000;
    private final List<Chat.Request> requests = new java.util.ArrayList<>();
    private final it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<Alarm> alarmTimes = new it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<>();
    private long lastAlarm = -100000;
    private Chat.Request answering;
    /** Who said they are on their way, per requester name (read from "OMW name" in chat). */
    private final java.util.Map<String, java.util.Set<java.util.UUID>> responders = new java.util.HashMap<>();
    /** The emergency this clone called for help about and has not declared resolved yet. */
    private Alarm openAlarm;
    private BlockPos openAlarmPos;
    private int clearChecks;
    public int resolvedSent;
    public int lastNeed;
    private final Storage storage;
    private final Farming farming;
    private final Expedition expedition;
    private final Discovery discovery;
    private final Consumables consumables;
    private final Brewing brewing;
    private final Animals animals;
    private final Fishing fishing;
    private final Explosives explosives;
    private final Travel travel;
    private final Perch perch;
    private final BoatTrap boatTrap;
    private final Lighting lighting;
    private final DoorBreath doorBreath;
    private final Portals portals;
    private final AttackLearning attacks = new AttackLearning(this::brain);
    private boolean lookedAround;
    private final FoodAid foodAid;
    private final ItemAid itemAid;
    private final WaterSource water;
    private boolean farmWater;
    private boolean wantWater;
    private final EffectSense effects;
    private final Breeding breeding;
    private final Achievements achievements;
    private final StairMining stairs;
    private final AttackTells tells;
    private final ShaftMining shafts;
    private final CreativeHelper creative;
    /** What can be done riding a horse (anything else: get off first). */
    private static final java.util.Set<Option> ON_HORSEBACK = java.util.EnumSet.of(Option.EXPLORE, Option.EXPEDITION, Option.JOIN, Option.FOLLOW,
            Option.FLEE, Option.HELP, Option.REST, Option.FIGHT, Option.HUNT, Option.ANIMALS);

    /** Test hook: always pick this option when it is possible. */
    public Option forcedOption;
    /** Cornered while fleeing: fight it out until then. */
    private long forceFightUntil = Long.MIN_VALUE;
    public int corneredFights;
    /** Recent time spent swimming (decays); lots of it makes a boat worth crafting. */
    private int swimTicks;
    private long lastThreatSeen = -100000;
    // looking around: the clone picks its own turning speed per situation and learns which works best
    private int lookArm = -1;
    private int lookCtx;
    private long lookStart;
    private int lookBase;
    public final java.util.List<Float> lookHistory = new java.util.ArrayList<>();
    // harmful blocks (magma, infection blocks from mods...): learned from damage, removed, shared
    private final it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<String> suspects = new it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<>();
    private final it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<String> safeGround = new it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<>();
    private int unknownHurts;
    private long lastEnvHurt = -100000;
    private BlockPos cleanTarget;
    private int cleanTicks;
    private long cleanWindowStart;
    private int cleanedInWindow;
    public int hazardsLearned;
    public int hazardsCleaned;
    public int hazardsShared;
    public int hazardsReceived;
    private final Int2LongOpenHashMap enemyLastAttack = new Int2LongOpenHashMap();
    private final Int2LongOpenHashMap enemyLastShot = new Int2LongOpenHashMap();
    private final LongOpenHashSet visitedChunks = new LongOpenHashSet();
    private final Random random = new Random();

    // strategy
    private Option option;
    private int optionState = -1;
    private int optionTicks;
    private float optionReward;

    // combat
    private Entity target;
    private String targetType;
    private int combatState = -1;
    private CombatAction action;
    private int actionTicks;
    private boolean actionDone;
    private float stepReward;
    private BlockPos pillarBase;
    private boolean pillarPlaced;

    // task scratch
    private Vec3 goal;
    private int goalTimer;
    private int calmTicks;
    private BlockPos blockTarget;
    private int blockTicks;
    private int blocksDone;
    private boolean eatStarted;
    private int closeTicks;

    // reward tracking
    private float lastHealth = -1;
    private int lastFood = -1;
    private Vec3 lookBack;
    private int lookBackTicks;

    public CloneController(ClonePlayer self) {
        this.self = self;
        this.perception = new Perception(self);
        this.motor = new Motor(self);
        this.watcher = new AgentWatcher(self, perception, self::getCloneBrain, this::sinceEnemyAttack);
        this.crafting = new Crafting(self, motor, perception);
        this.escape = new Escape(self, motor);
        this.storage = new Storage(self, motor, perception, crafting);
        this.farming = new Farming(self, motor);
        this.expedition = new Expedition(self, motor);
        this.discovery = new Discovery(self, motor, perception);
        this.consumables = new Consumables(self, motor, perception);
        this.brewing = new Brewing(self, motor, perception);
        this.animals = new Animals(self, motor, perception);
        this.fishing = new Fishing(self, motor);
        this.explosives = new Explosives(self, motor, perception);
        this.travel = new Travel(self, motor, consumables);
        this.perch = new Perch(self, motor, perception);
        this.boatTrap = new BoatTrap(self, motor);
        this.lighting = new Lighting(self, motor);
        this.doorBreath = new DoorBreath(self, motor);
        this.portals = new Portals(self, motor);
        this.explosives.setBrain(this::brain);
        this.foodAid = new FoodAid(self, motor);
        this.itemAid = new ItemAid(self, motor);
        this.water = new WaterSource(self, motor);
        this.effects = new EffectSense(self, motor, perception);
        this.breeding = new Breeding(self, motor);
        this.achievements = new Achievements(self, motor, perception, this::brain, crafting);
        this.stairs = new StairMining(self, motor);
        this.tells = new AttackTells(self);
        this.shafts = new ShaftMining(self, motor, crafting);
        this.creative = new CreativeHelper(self, motor, perception);
        expedition.foesNear = p -> {
            long t = now();
            for (Perception.Seen s : perception.remembered()) {
                if (s.alive() && t - s.lastSeen < 200 && Senses.isHostileTo(s.entity, self) && s.pos.distanceTo(Vec3.atCenterOf(p)) < 24) {
                    return true;
                }
            }
            return false;
        };
        perception.setUnknown(discovery::isUnknownObtainable);
        perception.setDiggable(p -> p.getY() <= self.getBlockY() + 3 && safeToDig(p)); // not up a cliff out of reach
        perception.setHarmful(id -> {
            Brain b = self.getCloneBrain();
            return b != null && b.isHarmful(id);
        });
        enemyLastAttack.defaultReturnValue(Long.MIN_VALUE);
        enemyLastShot.defaultReturnValue(Long.MIN_VALUE);
    }

    private Brain brain() {
        return self.getCloneBrain();
    }

    public Perception perception() {
        return perception;
    }

    public Motor motor() {
        return motor;
    }

    public Escape escape() {
        return escape;
    }

    public boolean isEscaping() {
        return escaping;
    }

    public Crafting crafting() {
        return crafting;
    }

    public AgentWatcher watcher() {
        return watcher;
    }

    public Storage storage() {
        return storage;
    }

    public Farming farming() {
        return farming;
    }

    public Expedition expedition() {
        return expedition;
    }

    public Discovery discovery() {
        return discovery;
    }

    public Consumables consumables() {
        return consumables;
    }

    public Brewing brewing() {
        return brewing;
    }

    public Animals animals() {
        return animals;
    }

    public Fishing fishing() {
        return fishing;
    }

    public Explosives explosives() {
        return explosives;
    }

    public FoodAid foodAid() {
        return foodAid;
    }

    public ItemAid itemAid() {
        return itemAid;
    }

    public WaterSource water() {
        return water;
    }

    public EffectSense effects() {
        return effects;
    }

    public Breeding breeding() {
        return breeding;
    }

    public Achievements achievements() {
        return achievements;
    }

    public ShaftMining shafts() {
        return shafts;
    }

    public AttackTells tells() {
        return tells;
    }

    public StairMining stairs() {
        return stairs;
    }

    public Perch perch() {
        return perch;
    }

    public BoatTrap boatTrap() {
        return boatTrap;
    }

    public DoorBreath doorBreath() {
        return doorBreath;
    }

    public Lighting lighting() {
        return lighting;
    }

    public Portals portals() {
        return portals;
    }

    public CreativeHelper creative() {
        return creative;
    }

    /** Just went through a portal (called by the clone itself). */
    public void onDimensionChanged(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> from) {
        if (option != null) {
            finishOption(false);
        }
        target = null;
        motor.resetStuck();
        motor.clearPath();
        portals.onArrived(from, now());
    }

    public Travel travel() {
        return travel;
    }

    /**
     * A distant enemy worth a hunting party: a boss-like monster (60+ health) or a crowd of 5+ monsters, seen far away.
     * Returns {entity, need} or null.
     */
    @Nullable
    public Object[] huntTarget(long now) {
        Perception.Seen boss = null;
        java.util.List<Perception.Seen> foes = new java.util.ArrayList<>();
        for (Perception.Seen s : perception.remembered()) {
            if (!s.alive() || now - s.lastSeen > 400 || !Senses.isHostileTo(s.entity, self) || !(s.entity instanceof LivingEntity)) {
                continue;
            }
            foes.add(s);
            if (((LivingEntity) s.entity).getMaxHealth() >= 60 && s.pos.distanceTo(self.position()) >= 12 && boss == null) {
                boss = s;
            }
        }
        Perception.Seen pick = boss;
        if (pick == null) {
            for (Perception.Seen s : foes) {
                long crowd = foes.stream().filter(o -> o.pos.distanceTo(s.pos) < 8).count();
                if (crowd >= 5 && s.pos.distanceTo(self.position()) >= 16) {
                    pick = s;
                    break;
                }
            }
        }
        if (pick == null) {
            return null;
        }
        double total = 0;
        for (Perception.Seen s : foes) {
            if (s.pos.distanceTo(pick.pos) < 12) {
                total += ((LivingEntity) s.entity).getHealth();
            }
        }
        double mine = self.getMaxHealth() + Equipment.attackDamage(self.getMainHandItem()) * 5;
        int need = Mth.clamp((int) Math.ceil(total / Math.max(1, mine)), 1, 6);
        return new Object[]{pick.entity, need};
    }

    public boolean swamALot() {
        return swimTicks > 200;
    }

    /** Test hook: pretend the clone has been swimming for a while. */
    public void noteSwimming(int ticks) {
        swimTicks = Math.min(3000, swimTicks + ticks);
    }

    public java.util.Set<java.util.UUID> responders(String requester) {
        return responders.getOrDefault(requester, java.util.Set.of());
    }

    public Alarm openAlarm() {
        return openAlarm;
    }

    public BlockPos cleanTarget() {
        return cleanTarget;
    }

    /** Debug / test hook: always pick this combat action when it is available. */
    public CombatAction forcedAction;

    public Option option() {
        return option;
    }

    /** Why there is no option running (soak diagnostics): what the last controller tick was busy with, and when. */
    public String idleWhy = "";
    public long idleWhyTick;
    /** The option that ended last and how many ticks it ran (an option that starts and ends in the same tick over and over is churn). */
    public String endedOption = "";
    public int endedAfter;
    /** Digging for iron / diamonds (plan.md P-03): when the next dig may start, how often in a row one got nowhere, where the last began. */
    private long digBackoffUntil;
    private long lastDigEnd = Long.MIN_VALUE / 2;
    private int digFails;
    private int digStartY;
    private int digStartShort;
    private int digStartTunnel;
    public int digDrives;
    /** Falls into holes (soak diagnostics): how often the escape began and what it began on. */
    public int escapeStarts;
    public String escapeWhy = "";
    /** Where the last escape began, when, and how many in a row began within 3 blocks of it (a pit we keep going back to). */
    private BlockPos lastEscapeAt;
    private long lastEscapeTick = Long.MIN_VALUE / 2;
    private int escapeRepeat;
    /** Options that started and ended in the same tick with nothing to show for it, in a row (plan.md D-4). */
    private static final java.util.Set<Option> CHURN_OPTIONS = java.util.EnumSet.of(Option.MINE, Option.CRAFT, Option.QUARRY, Option.GATHER_WOOD,
            Option.STAIRS, Option.SHAFT, Option.FARM, Option.STORE, Option.FETCH, Option.LOOT, Option.DISCOVER, Option.ACHIEVE, Option.SALVAGE,
            Option.BREW, Option.ANIMALS, Option.FISH, Option.PORTAL);
    private final long[] optionCooldown = new long[Option.COUNT];
    @Nullable
    private Option churnOption;
    private int churnRun;
    public int churnCooldowns;

    public CombatAction action() {
        return action;
    }

    public Entity target() {
        return target;
    }

    private long now() {
        return self.level().getGameTime();
    }

    public long sinceEnemyAttack(Entity enemy) {
        long last = enemyLastAttack.get(enemy.getId());
        return last == Long.MIN_VALUE ? UNKNOWN : now() - last;
    }

    // ------------------------------------------------------------------ main loop

    public void tick() {
        long p0 = Prof.t();
        tickInner();
        if (Prof.on) {
            Prof.add(Prof.TOTAL, p0);
            Prof.cloneTicks++;
        }
    }

    /** Ticks this clone's mind has run (since it was summoned / respawned). */
    private long ticksLived;

    private void tickInner() {
        ticksLived++;
        long now = now();
        trackRewards();
        attacks.tick(now);
        if (((now + self.getId()) & 3) == 0 || !lookedAround) {
            lookedAround = true; // a first look around before the very first decision
            long p = Prof.t();
            perception.update(now);
            observeWorld(now);
            Prof.add(Prof.PERCEPTION, p);
            p = Prof.t();
            watcher.update(now);
            Prof.add(Prof.WATCHER, p);
        }
        if (self.isCreative()) {
            // creative: nothing to play for any more - only helping the others
            if (option != null) {
                finishOption(false);
            }
            creative.tick(now);
            motor.tick();
            return;
        }
        if (self.getVehicle() instanceof net.minecraft.world.entity.animal.horse.AbstractHorse && option != null && !ON_HORSEBACK.contains(option)) {
            self.stopRiding(); // this needs both feet on the ground
        }
        if ((now + self.getId()) % 40 == 0 && !self.isUsingItem() && option != Option.GATHER_WOOD && option != Option.MINE
                && option != Option.CRAFT && option != Option.STORE && option != Option.FETCH && option != Option.LOOT && option != Option.FARM
                && option != Option.QUARRY && option != Option.DISCOVER && option != Option.BREW
                && option != Option.ANIMALS && option != Option.FISH && option != Option.SALVAGE && option != Option.PORTAL
                && option != Option.STAIRS && option != Option.ACHIEVE && option != Option.SHAFT
                && !explosives.busy() && !travel.busy() && !perch.busy() && !boatTrap.busy() && !portals.busy()
                && cleanTarget == null && !consumables.busy() && self.containerMenu == self.inventoryMenu && !motor.isFlying()
                && (action == null || action == CombatAction.APPROACH || action == CombatAction.HOLD)) {
            Equipment.manage(self, true);
        }
        if (((now + self.getId()) % 10) == 0) {
            checkAlarms(now);
        }
        waterMoves();
        if (self.isInWater() && !self.isPassenger()) {
            swimTicks = Math.min(3000, swimTicks + 1);
        } else if (swimTicks > 0 && (now & 7) == 0) {
            swimTicks--;
        }
        if (((now + self.getId()) % 20) == 0) {
            discovery.watchInventory();
            if (farming.needDirt) {
                farming.needDirt = false;
                itemAid.need(Items.DIRT, 4); // water to farm by, nothing to make soil from
            }
            if (brain() != null && !brain().hasFlag("had_bucket") && Crafting.hasBucket(self)) {
                brain().setFlag("had_bucket"); // the first bucket: from now on one is enough
            }
        }
        if (((now + self.getId()) % 100) == 50) {
            discovery.share(now);
        }
        if (((now + self.getId()) % 20) == 7) {
            foodAid.tick(now);
        }
        boolean threatened = !Senses.threats(perception, self, now, 12).isEmpty();
        if (threatened) {
            lastThreatSeen = now;
        }
        long pr = Prof.t();
        watchTells(now);
        boolean cloudBusy = !escaping && effects.tick(now, threatened); // effects on us learned; bad lingering clouds left
        boolean itemBusy = buriedReflex(now) || doorBreath.tick(now) || cloudBusy || tellReflex(now) || consumables.tick(now, threatened, option == Option.FIGHT ? target : null);
        if (!itemBusy && !escaping && option != Option.ANIMALS) {
            itemBusy = explosives.tick(now, option == Option.FIGHT ? target : null);
        }
        if (!itemBusy && option == Option.FIGHT && (target != null || boatTrap.busy())) {
            itemBusy = boatTrap.tick(target, now); // a boat at its feet: most mobs sit down in it
        }
        if (!itemBusy && perch.busy()) {
            itemBusy = perch.tick(now); // up on a little pillar out of the chasers' reach
        }
        if (!itemBusy) {
            itemBusy = portals.travelTick(now, threatened); // out of the portal we came through / home from the Nether
        }
        if (!itemBusy && !escaping && option != Option.FIGHT && option != Option.FISH) {
            travel.threatened = threatened;
            itemBusy = travel.tick(now); // bridging a gap / pearling across
        }
        if (!itemBusy && !escaping && option != Option.FIGHT && option != Option.FLEE && ((now + self.getId()) % 20) == 7 && !self.isUsingItem()) {
            itemBusy = lighting.tick(); // a torch where monsters could spawn
        }
        Prof.add(Prof.REFLEXES, pr);
        long pt = Prof.t();
        // look once a second, but only while standing (a hop out of a stuck walk must not hide the hole we are in)
        boolean trapCheck = !escaping && !itemBusy && now - lastTrapCheck >= 20 && self.onGround();
        if (trapCheck) {
            lastTrapCheck = now;
        }
        if (trapCheck && now - escapeFailedAt > 600 && !motor.isFlying() && !hostileWithin(3.5, now) && option != Option.SHAFT && option != Option.STAIRS
                && forcedOption != Option.SHAFT && forcedOption != Option.STAIRS && escape.isTrapped()) {
            // reflex, like a player who notices he fell into a hole: get out before doing anything else
            if (option != null) {
                finishOption(false);
            }
            if (Equipment.pillarBlockSlot(self) < 0 && wallsHard() && consumables.pearlOutOfPit()) {
                // nothing to build with and walls we cannot dig through: an ender pearl over the edge
            } else {
                escaping = true;
                escapeStarts++;
                escapeWhy = self.level().getBlockState(self.blockPosition().below()).getBlock() + " y=" + self.getBlockY();
                escape.start(motor.recentGoal(), onEscapeStart(now));
            }
        }
        Prof.add(Prof.TRAP, pt);
        long ph = Prof.t();
        hazardTick(now);
        Prof.add(Prof.HAZARD, ph);
        long ps = Prof.t();
        idleWhyTick = now;
        if (itemBusy) {
            // drinking / scooping water / waiting for a pearl: nothing else this tick
            idleWhy = "item";
        } else if (escaping) {
            idleWhy = "escape";
            Escape.Status st = escape.tick();
            if (st != Escape.Status.WORKING) {
                escaping = false;
                if (st == Escape.Status.FAILED) {
                    escapeFailedAt = now;
                    consumables.pearlOutOfPit();
                }
            }
        } else if (cleanTarget != null) {
            idleWhy = "clean";
            cleanUp();
        } else {
            idleWhy = "strategy";
            runStrategy(now);
        }
        Prof.add(Prof.STRATEGY, ps);
        if (lookBackTicks > 0) {
            lookBackTicks--;
            if (!motor.hasLookIntent() && lookBack != null) {
                motor.lookAt(lookBack);
            }
        }
        long pm = Prof.t();
        motor.tick();
        Prof.add(Prof.MOTOR, pm);
    }

    /**
     * Hearing. Receives exactly the sounds a human client would get. Creature sounds are matched to the creature
     * making them (its voice identifies the kind); the clone then knows something is there even out of sight,
     * remembers it and turns towards the noise like a player would.
     */
    public void hear(SoundEvent sound, double x, double y, double z, int entityId) {
        ServerLevel level = self.serverLevel();
        String path = sound.getLocation().getPath();
        fishing.onSound(path, x, y, z);
        Entity source;
        Vec3 pos;
        if (entityId >= 0) {
            source = level.getEntity(entityId);
            if (source == null) {
                return;
            }
            pos = source.position();
        } else {
            if (!path.startsWith("entity.")) {
                return;
            }
            pos = new Vec3(x, y, z);
            String[] parts = path.split("\\.");
            String kind = parts.length > 1 ? parts[1] : "";
            source = null;
            double best = Double.MAX_VALUE;
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(pos, pos).inflate(2.5), e -> e != self && e.isAlive())) {
                double d = e.position().distanceToSqr(pos) - (Perception.typeId(e).endsWith(":" + kind) ? 100 : 0);
                if (d < best) {
                    best = d;
                    source = e;
                }
            }
        }
        if (source == null || source == self || perception.isVisible(source)) {
            return;
        }
        long now = now();
        Perception.Seen s = perception.hear(source, pos, now);
        heardSounds++;
        if (source instanceof Creeper && path.contains("primed") && s.swellStart < 0) {
            s.swellStart = now;
        }
        boolean hostile = Senses.isHostileTo(source, self) || source instanceof net.minecraft.world.entity.monster.Enemy;
        if (hostile || Senses.isAgent(source)) {
            if (hostile || lookBackTicks <= 0) {
                lookBack = pos.add(0, source.getBbHeight() * 0.8, 0);
                lookBackTicks = 12;
            }
        }
    }

    public long heardSounds;
    public int quarried;
    public String harvestDebug = "";
    /** Not skipped for now, and - for planks / logs of buildings - not part of somebody's base. */
    private boolean harvestable(Perception.BlockKind kind, BlockPos p) {
        return !skipBlocks.containsKey(p) && (kind != Perception.BlockKind.WOOD
                || com.rlclones.clone.Bases.get(self.getServer()).nearest(self.level().dimension(), Vec3.atCenterOf(p), 12) == null);
    }

    /** Blocks found not to be taken (a base's, unsafe, out of reach): left alone for a minute even though seen again. */
    private final java.util.Map<BlockPos, Long> skipBlocks = new java.util.HashMap<>();
    /** Recent harvest events (diagnostics, tests). */
    public final StringBuilder harvestTrace = new StringBuilder();

    private void traceHarvest(String what) {
        if (harvestTrace.length() < 600) {
            harvestTrace.append(' ').append(what).append('@').append(optionTicks);
        }
    }

    /**
     * Safe to dig out like a careful player: never the block right under our own feet with a drop below it, nothing
     * deeper than one below the feet, and nothing touching lava (or opening into a drop).
     */
    public boolean safeToDig(BlockPos p) {
        ServerLevel level = self.serverLevel();
        BlockPos feet = self.blockPosition();
        if (p.getY() < feet.getY() - 1) {
            return false;
        }
        // the ground we walk on: only take it where it leaves a shallow dip, never a hole into the dark (or under our feet)
        BlockPos below = p.below();
        if (p.getY() < feet.getY() && (level.getBlockState(below).getCollisionShape(level, below).isEmpty() || !level.getFluidState(below).isEmpty())) {
            return false;
        }
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            if (level.getFluidState(p.relative(d)).is(net.minecraft.tags.FluidTags.LAVA)) {
                return false;
            }
        }
        return true;
    }
    public final java.util.List<String> optionLog = new java.util.ArrayList<>();

    /** The walls around the feet cannot be dug through quickly (obsidian, bedrock, no fitting tool...). */
    private boolean wallsHard() {
        ServerLevel level = self.serverLevel();
        BlockPos head = self.blockPosition().above();
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos p = head.relative(d);
            BlockState st = level.getBlockState(p);
            if (st.getCollisionShape(level, p).isEmpty()) {
                continue;
            }
            float hardness = st.getDestroySpeed(level, p);
            if (hardness >= 0 && hardness < 20 && (!st.requiresCorrectToolForDrops() || Equipment.canHarvest(self, st))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ looking around

    private void beginLook() {
        long now = now();
        lookCtx = (Senses.isDark(self) ? 1 : 0) + (now - lastThreatSeen < 200 ? 2 : 0);
        lookArm = brain().chooseLook(lookCtx, Config.get(Config.EXPLORATION, 0.35));
        lookStart = now;
        lookBase = perception.discoveries;
        lookHistory.add(Brain.LOOK_SPEEDS[lookArm]);
        if (lookHistory.size() > 50) {
            lookHistory.remove(0);
        }
    }

    /** Turn at the speed chosen for this look-around, glancing up and down a little. */
    private void lookTick() {
        if (lookArm < 0) {
            beginLook();
        }
        float speed = Brain.LOOK_SPEEDS[lookArm];
        float pitch = (float) (Math.sin((now() - lookStart) * 0.08) * 25.0);
        motor.lookAngles(self.getYRot() + speed, pitch);
    }

    /** Score the look-around by how much was newly noticed per second, and learn from it. */
    private void endLook() {
        if (lookArm < 0) {
            return;
        }
        long ticks = now() - lookStart;
        if (ticks >= 10) {
            brain().learnLook(lookCtx, lookArm, (perception.discoveries - lookBase) * 20f / ticks);
        }
        lookArm = -1;
    }

    // ------------------------------------------------------------------ chat: emergencies and calls for help

    public enum Alarm {
        LAVA("lava", true), FIRE("fire", true), DROWNING("drowning", true), EXPLOSION("explosion", true),
        LOW_HEALTH("low_health", true), TRAPPED("trapped", true), OUTNUMBERED("outnumbered", false),
        OUTMATCHED("outmatched", false), STARVING("starving", false);

        public final String key;
        public final boolean urgent;

        Alarm(String key, boolean urgent) {
            this.key = key;
            this.urgent = urgent;
        }
    }

    private boolean hostileWithin(double radius, long now) {
        for (Perception.Seen s : Senses.threats(perception, self, now, radius + 2)) {
            if (Senses.gap(self, s.entity) <= radius) {
                return true;
            }
        }
        return false;
    }

    /** Look at the own situation; if it is an emergency / we are outnumbered, say where, why and how many should come. */
    private void checkAlarms(long now) {
        float hp = self.getHealth() / Math.max(1f, self.getMaxHealth());
        List<Perception.Seen> threats = Senses.threats(perception, self, now, 12);
        Alarm alarm = null;
        Component detail = Component.empty();
        String plainDetail = "";
        int need = 1;
        if (self.isInLava()) {
            alarm = Alarm.LAVA;
        } else if (self.isOnFire() && hp < 0.6f) {
            alarm = Alarm.FIRE;
        } else if (self.isInWater() && self.getAirSupply() < self.getMaxAirSupply() * 0.3) {
            alarm = Alarm.DROWNING;
        } else if (threats.stream().anyMatch(s -> s.entity instanceof Creeper c && c.getSwellDir() > 0 && s.entity.distanceTo(self) < 5)) {
            alarm = Alarm.EXPLOSION;
        } else if (threats.size() >= 3 || (!threats.isEmpty() && Senses.threatLevel(perception, self, self.getCloneBrain(), now) == 2 && hp > 0.3f)) {
            alarm = threats.size() >= 3 ? Alarm.OUTNUMBERED : Alarm.OUTMATCHED;
            it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap<net.minecraft.world.entity.EntityType<?>> counts = new it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap<>();
            float enemyHealth = 0;
            for (Perception.Seen s : threats) {
                counts.mergeInt(s.entity.getType(), 1, Integer::sum);
                if (s.entity instanceof LivingEntity le) {
                    enemyHealth += le.getHealth();
                }
            }
            net.minecraft.network.chat.MutableComponent list = Component.empty();
            StringBuilder plain = new StringBuilder();
            boolean first = true;
            for (var e : counts.object2IntEntrySet()) {
                if (!first) {
                    list.append(", ");
                    plain.append(',');
                }
                first = false;
                list.append(e.getKey().getDescription()).append("×" + e.getIntValue());
                plain.append(net.minecraft.world.entity.EntityType.getKey(e.getKey())).append('=').append(e.getIntValue());
            }
            detail = list;
            plainDetail = plain.toString();
            // one helper per extra enemy (more when hurt), or by how much stronger the enemy is than us
            need = alarm == Alarm.OUTNUMBERED ? Mth.clamp(threats.size() - 1 + (hp < 0.5f ? 1 : 0), 1, 6)
                    : Mth.clamp(Math.round(enemyHealth / Math.max(10f, self.getHealth() + (float) Equipment.attackDamage(self.getMainHandItem()) * 2)), 1, 4);
        } else if (hp <= 0.3f && !threats.isEmpty()) {
            alarm = Alarm.LOW_HEALTH;
            detail = threats.get(0).entity.getType().getDescription();
            plainDetail = threats.get(0).typeId;
            need = threats.size() >= 2 ? 2 : 1;
        } else if (now - escapeFailedAt < 200) {
            alarm = Alarm.TRAPPED;
        } else if (self.getFoodData().getFoodLevel() == 0 && Equipment.bestFoodSlot(self) < 0) {
            alarm = Alarm.STARVING;
        }
        if (alarm != null && need > 1) {
            // friends already standing next to us count
            int allies = 0;
            for (Perception.Seen s : perception.visible()) {
                if (s.entity != self && Senses.isAllyOf(s.entity, self) && s.entity.distanceTo(self) < 12) {
                    allies++;
                }
            }
            need = Math.max(1, need - allies);
        }
        // the emergency we called about is over: say so, so nobody comes for nothing
        if (openAlarm != null) {
            if (alarm == null) {
                if (++clearChecks >= 3) {
                    resolve(false);
                }
            } else {
                clearChecks = 0;
            }
        }
        if (alarm == null || now - lastAlarm < 100) {
            return;
        }
        // the same call again only after a while - unless it got worse (more enemies turned up: ask for more)
        boolean worse = alarm == lastAlarmSent && openAlarm != null && need > lastNeed;
        if (now - alarmTimes.getOrDefault(alarm, -100000L) < 600 && !worse) {
            return;
        }
        alarmTimes.put(alarm, now);
        lastAlarm = now;
        boolean urgent = alarm.urgent || (alarm == Alarm.OUTNUMBERED && hp < 0.5f);
        BlockPos p = self.blockPosition();
        String plain = (urgent ? "SOS " : "HELP ") + p.getX() + " " + p.getY() + " " + p.getZ() + " " + alarm.key
                + (plainDetail.isEmpty() ? "" : " " + plainDetail) + " need=" + need;
        Component reason = Component.translatable("rlclones.reason." + alarm.key, detail);
        Chat.say(self, Component.translatable(urgent ? "rlclones.chat.sos" : "rlclones.chat.help", p.getX(), p.getY(), p.getZ(), reason, need), plain);
        lastAlarmSent = alarm;
        lastNeed = need;
        openAlarm = alarm;
        openAlarmPos = p;
        clearChecks = 0;
    }

    public Alarm lastAlarmSent;

    /** Tell everyone the call for help is over (solved, or this clone died) and nobody needs to come any more. */
    private void resolve(boolean died) {
        if (openAlarm == null) {
            return;
        }
        BlockPos p = openAlarmPos;
        Chat.say(self, Component.translatable(died ? "rlclones.chat.cancel" : "rlclones.chat.resolved", p.getX(), p.getY(), p.getZ()),
                "RESOLVED " + p.getX() + " " + p.getY() + " " + p.getZ() + (died ? " died" : ""));
        alarmTimes.removeLong(openAlarm);
        openAlarm = null;
        clearChecks = 0;
        resolvedSent++;
    }

    /** Read a chat line (from a clone or a real player). */
    public void onChat(net.minecraft.server.level.ServerPlayer sender, String text) {
        if (sender.getUUID().equals(self.getUUID()) || Senses.rivals(sender, self)) {
            return; // the other side's calls and news are not for us
        }
        long now = now();
        String t = text.trim();
        String name = sender.getGameProfile().getName();
        if (t.startsWith("OMW ")) {
            responders.computeIfAbsent(t.substring(4).trim(), k -> new java.util.HashSet<>()).add(sender.getUUID());
            return;
        }
        if (t.startsWith("HAZARD ")) {
            String[] parts = t.split(" ");
            if (parts.length >= 3 && parts[2].startsWith("to=")
                    && java.util.Arrays.asList(parts[2].substring(3).split(",")).contains(self.getGameProfile().getName())) {
                if (!brain().isHarmful(parts[1]) && !brain().provenSafe(parts[1])) {
                    brain().learnHarmful(parts[1]);
                    hazardsReceived++;
                }
            }
            return;
        }
        if (t.startsWith("SAFE ")) {
            String id = t.substring(5).trim();
            if (brain().isHarmful(id)) {
                correctHarmful(id, false); // a friend stood on it for a long time unhurt
            }
            return;
        }
        if (expedition.onChat(sender, t, now)) {
            return;
        }
        if (itemAid.onChat(sender, t, now)) {
            return;
        }
        if (foodAid.onChat(sender, t, now)) {
            return;
        }
        if (discovery.onChat(t) || t.startsWith("DISCOVER ")) {
            return;
        }
        if (t.startsWith("DEPOSIT ") || t.startsWith("WITHDRAW ") || t.startsWith("LOOT ") || t.startsWith("BASE ") || t.startsWith("HAZARD_LEARNED")) {
            return; // bases and their contents are shared knowledge already
        }
        if (Chat.isResolved(t)) {
            requests.removeIf(q -> q.from().equals(sender.getUUID()));
            responders.remove(name);
            if (answering != null && answering.from().equals(sender.getUUID())) {
                answering = null;
                cancelledHelp++;
            }
            return;
        }
        Chat.Request r = Chat.parse(sender, t, now);
        if (r == null) {
            return;
        }
        requests.removeIf(q -> q.from().equals(r.from()));
        requests.add(r);
        while (requests.size() > 6) {
            requests.remove(0);
        }
    }

    public int cancelledHelp;

    public List<Chat.Request> requests() {
        return requests;
    }

    /** The call for help this clone should answer: nearest / most urgent one that does not have enough helpers yet. */
    public Chat.Request activeRequest() {
        long now = now();
        requests.removeIf(r -> now - r.tick() > 1200);
        Chat.Request best = null;
        double bestScore = Double.MAX_VALUE;
        for (Chat.Request r : requests) {
            if (r.dimension() != self.level().dimension()) {
                continue;
            }
            double d = r.pos().distanceTo(self.position());
            if (d > 160) {
                continue;
            }
            java.util.Set<java.util.UUID> coming = responders(r.fromName());
            if (!coming.contains(self.getUUID()) && coming.size() >= r.need()) {
                continue; // enough people are already on their way
            }
            double score = d - (r.urgent() ? 32 : 0);
            if (score < bestScore) {
                bestScore = score;
                best = r;
            }
        }
        return best;
    }

    public boolean hasHelpRequest() {
        return activeRequest() != null;
    }

    private boolean runHelp() {
        Chat.Request r = activeRequest();
        if (r == null) {
            answering = null;
            return true; // resolved (or enough others went): stop, do not show up for nothing
        }
        if (answering != r) {
            answering = r;
            java.util.Set<java.util.UUID> coming = responders.computeIfAbsent(r.fromName(), k -> new java.util.HashSet<>());
            if (coming.add(self.getUUID())) {
                Chat.say(self, Component.translatable("rlclones.chat.coming", r.fromName()), "OMW " + r.fromName());
            }
        }
        if (self.position().distanceTo(r.pos()) <= 5) {
            optionReward += 1.0f; // arrived to help: team reward
            requests.remove(r);
            answering = null;
            return true;
        }
        motor.navigate(r.pos(), 4.0, true);
        return motor.stuckCount() > 4;
    }

    // ------------------------------------------------------------------ harmful blocks

    private static final java.util.Set<net.minecraft.resources.ResourceKey<net.minecraft.world.damagesource.DamageType>> NOT_FROM_BLOCKS = java.util.Set.of(
            net.minecraft.world.damagesource.DamageTypes.FALL, net.minecraft.world.damagesource.DamageTypes.DROWN,
            net.minecraft.world.damagesource.DamageTypes.STARVE, net.minecraft.world.damagesource.DamageTypes.IN_WALL,
            net.minecraft.world.damagesource.DamageTypes.OUTSIDE_BORDER, net.minecraft.world.damagesource.DamageTypes.FELL_OUT_OF_WORLD,
            net.minecraft.world.damagesource.DamageTypes.GENERIC_KILL, net.minecraft.world.damagesource.DamageTypes.CRAMMING,
            net.minecraft.world.damagesource.DamageTypes.FLY_INTO_WALL, net.minecraft.world.damagesource.DamageTypes.WITHER,
            net.minecraft.world.damagesource.DamageTypes.DRY_OUT, net.minecraft.world.damagesource.DamageTypes.LIGHTNING_BOLT,
            net.minecraft.world.damagesource.DamageTypes.ON_FIRE, net.minecraft.world.damagesource.DamageTypes.LAVA,
            net.minecraft.world.damagesource.DamageTypes.EXPLOSION, net.minecraft.world.damagesource.DamageTypes.PLAYER_EXPLOSION,
            net.minecraft.world.damagesource.DamageTypes.FIREWORKS, net.minecraft.world.damagesource.DamageTypes.SONIC_BOOM,
            net.minecraft.world.damagesource.DamageTypes.INDIRECT_MAGIC, net.minecraft.world.damagesource.DamageTypes.THORNS,
            net.minecraft.world.damagesource.DamageTypes.BAD_RESPAWN_POINT, net.minecraft.world.damagesource.DamageTypes.FALLING_BLOCK,
            net.minecraft.world.damagesource.DamageTypes.FALLING_ANVIL, net.minecraft.world.damagesource.DamageTypes.FALLING_STALACTITE);

    /** Blocks the clone is standing on or touching right now. */
    private java.util.Set<BlockPos> touching() {
        java.util.Set<BlockPos> out = new java.util.LinkedHashSet<>();
        out.add(self.getOnPos().immutable());
        net.minecraft.world.phys.AABB box = self.getBoundingBox().inflate(0.05);
        BlockPos.betweenClosedStream(box).forEach(p -> {
            if (!self.level().getBlockState(p).isAir()) {
                out.add(p.immutable());
            }
        });
        return out;
    }

    /**
     * Took damage without an attacker. Known block damage (magma = hot floor, cactus, berry bush, campfire...) is pinned
     * on the block that caused it right away; unknown damage (e.g. infection blocks from mods) on the block that was
     * touched every time it happened and never while standing around unhurt.
     */
    public void onEnvironmentDamage(DamageSource source) {
        long now = now();
        lastEnvHurt = now;
        ServerLevel level = self.serverLevel();
        if (NOT_FROM_BLOCKS.stream().anyMatch(source::is)) {
            return;
        }
        java.util.Set<BlockPos> culprits = new java.util.LinkedHashSet<>();
        if (source.is(net.minecraft.world.damagesource.DamageTypes.HOT_FLOOR)) {
            culprits.add(self.getOnPos());
        } else if (source.is(net.minecraft.world.damagesource.DamageTypes.CACTUS) || source.is(net.minecraft.world.damagesource.DamageTypes.SWEET_BERRY_BUSH)
                || source.is(net.minecraft.world.damagesource.DamageTypes.IN_FIRE) || source.is(net.minecraft.world.damagesource.DamageTypes.FREEZE)
                || source.is(net.minecraft.world.damagesource.DamageTypes.STALAGMITE)) {
            for (BlockPos p : touching()) {
                BlockState st = level.getBlockState(p);
                boolean match = source.is(net.minecraft.world.damagesource.DamageTypes.CACTUS) ? st.getBlock() instanceof net.minecraft.world.level.block.CactusBlock
                        : source.is(net.minecraft.world.damagesource.DamageTypes.SWEET_BERRY_BUSH) ? st.getBlock() instanceof net.minecraft.world.level.block.SweetBerryBushBlock
                        : source.is(net.minecraft.world.damagesource.DamageTypes.FREEZE) ? st.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW)
                        : source.is(net.minecraft.world.damagesource.DamageTypes.STALAGMITE) ? st.getBlock() instanceof net.minecraft.world.level.block.PointedDripstoneBlock
                        : st.is(net.minecraft.tags.BlockTags.FIRE) || st.is(net.minecraft.tags.BlockTags.CAMPFIRES);
                if (match) {
                    culprits.add(p);
                }
            }
        } else {
            if ((source.is(net.minecraft.world.damagesource.DamageTypes.MAGIC) || source.is(net.minecraft.world.damagesource.DamageTypes.GENERIC))
                    && (self.hasEffect(net.minecraft.world.effect.MobEffects.POISON) || self.hasEffect(net.minecraft.world.effect.MobEffects.HARM))) {
                return;
            }
            unknownHurts++;
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (BlockPos p : touching()) {
                String id = Perception.blockId(level.getBlockState(p));
                if (seen.add(id)) {
                    suspects.addTo(id, 1);
                }
            }
            for (var e : suspects.object2IntEntrySet()) {
                // present every time it hurt (at least 3 times) and hardly ever stood on safely
                if (e.getIntValue() >= 3 && e.getIntValue() >= unknownHurts - 1 && safeGround.getInt(e.getKey()) < 3) {
                    for (BlockPos p : touching()) {
                        if (Perception.blockId(level.getBlockState(p)).equals(e.getKey())) {
                            culprits.add(p);
                        }
                    }
                }
            }
        }
        for (BlockPos p : culprits) {
            BlockState st = level.getBlockState(p);
            if (st.isAir()) {
                continue;
            }
            String id = Perception.blockId(st);
            boolean known = brain().isHarmful(id);
            brain().learnHarmful(id);
            perception.noteBlock(p);
            if (!known) {
                hazardsLearned++;
                Chat.say(self, Component.translatable("rlclones.chat.hazard_learned", st.getBlock().getName()), "HAZARD_LEARNED " + id);
            }
        }
    }

    private boolean safeToRemove(BlockPos p) {
        ServerLevel level = self.serverLevel();
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            if (level.getFluidState(p.relative(d)).is(net.minecraft.tags.FluidTags.LAVA)) {
                return false;
            }
        }
        BlockState st = level.getBlockState(p);
        if (st.getDestroySpeed(level, p) < 0 || !self.mayInteract(level, p)) {
            return false;
        }
        // do not dig the floor away above a drop or lava
        BlockPos below = p.below();
        return !level.getBlockState(below).isAir() || !self.getBoundingBox().inflate(0.3, 1, 0.3).intersects(new net.minecraft.world.phys.AABB(p));
    }

    /** Seconds of touching a block believed harmful without getting hurt (block id -> samples). */
    private final it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<String> harmlessContact = new it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<>();
    /** Harmful-block beliefs corrected after long harmless contact (diagnostics, tests). */
    public int hazardsCorrected;
    private static final int HARMLESS_SAMPLES = 6;

    /**
     * A block thought harmful that the clone keeps standing on / touching without ever getting hurt was a mistake:
     * after a few seconds of harmless contact the belief is dropped (and the others are told).
     */
    private void judgeHarmfulContact(long now) {
        if (ticksLived < 100 || self.isCreative() || self.isSpectator() || self.hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE)
                || self.hasEffect(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE) && self.getEffect(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE).getAmplifier() >= 4) {
            return; // protected: no harm proves nothing
        }
        if (now - lastEnvHurt <= 40) {
            harmlessContact.clear(); // it did hurt
            return;
        }
        ServerLevel level = self.serverLevel();
        java.util.Set<String> touched = new java.util.HashSet<>();
        BlockPos floor = self.getOnPos();
        for (BlockPos p : touching()) {
            if (p.equals(floor) && (self.isSteppingCarefully() || !self.onGround())) {
                continue; // sneaking over it (magma does nothing then) / not really on it
            }
            String id = Perception.blockId(level.getBlockState(p));
            if (brain().isHarmful(id)) {
                touched.add(id);
            }
        }
        for (String id : touched) {
            if (harmlessContact.addTo(id, 1) + 1 >= HARMLESS_SAMPLES) {
                harmlessContact.removeInt(id);
                correctHarmful(id, true);
            }
        }
    }

    private void correctHarmful(String id, boolean tell) {
        brain().unlearnHarmful(id);
        hazardsCorrected++;
        java.util.List<BlockPos> stale = new java.util.ArrayList<>();
        for (var e : perception.blocks().entrySet()) {
            if (e.getValue() == Perception.BlockKind.HARMFUL && Perception.blockId(self.level().getBlockState(e.getKey())).equals(id)) {
                stale.add(e.getKey());
            }
        }
        stale.forEach(perception::forgetBlock);
        if (cleanTarget != null && Perception.blockId(self.level().getBlockState(cleanTarget)).equals(id)) {
            cleanTarget = null;
        }
        if (tell) {
            net.minecraft.world.level.block.Block block = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(new net.minecraft.resources.ResourceLocation(id));
            Chat.say(self, Component.translatable("rlclones.chat.hazard_safe", block == null ? Component.literal(id) : block.getName()), "SAFE " + id);
        }
    }

    /** Harmful-block housekeeping: remember safe ground, remove hazards in sight, tell nearby clones what hurts. */
    private void hazardTick(long now) {
        long phase = now + self.getId();
        if (phase % 20 == 10 && !brain().harmfulBlocks().isEmpty()) {
            judgeHarmfulContact(now);
        }
        if (phase % 20 == 0 && self.onGround() && now - lastEnvHurt > 60 && safeGround.size() < 512) {
            safeGround.addTo(Perception.blockId(self.level().getBlockState(self.getOnPos())), 1);
        }
        if (phase % 100 == 0) {
            shareHazards();
        }
        if (phase % 20 == 0 && !brain().harmfulBlocks().isEmpty()) {
            perception.scanForHarmful(8);
        }
        if (now - cleanWindowStart > 1200) {
            cleanWindowStart = now;
            cleanedInWindow = 0;
        }
        if (cleanTarget != null || escaping || phase % 20 != 0 || cleanedInWindow >= 16 || !Config.get(Config.ALLOW_BLOCK_BREAKING, true)
                || self.containerMenu != self.inventoryMenu || option == Option.FIGHT || option == Option.FLEE || motor.isFlying()
                || self.isInLava() || hostileWithin(8, now) || brain().harmfulBlocks().isEmpty()) {
            return;
        }
        BlockPos best = null;
        double bestD = 10 * 10;
        for (var e : perception.blocks().entrySet()) {
            if (e.getValue() != Perception.BlockKind.HARMFUL) {
                continue;
            }
            double d = e.getKey().distToCenterSqr(self.position());
            if (d < bestD && brain().isHarmful(Perception.blockId(self.level().getBlockState(e.getKey()))) && safeToRemove(e.getKey())) {
                bestD = d;
                best = e.getKey();
            }
        }
        if (best != null) {
            cleanTarget = best;
            cleanTicks = 0;
            motor.resetStuck();
            cleanLog("start" + best.toShortString());
        }
    }

    private final StringBuilder cleanLog = new StringBuilder();

    private void cleanLog(String event) {
        if (cleanLog.length() < 600) {
            cleanLog.append(now()).append(':').append(event).append(' ');
        }
    }

    /** Diagnostics of the harmful-block clean-up (for tests). */
    public String hazardDebug() {
        return "clean=" + cleanTarget + " ticks=" + cleanTicks + " escaping=" + escaping + " option=" + option + " log=" + cleanLog
                + " seen=" + perception.blocks().entrySet().stream().filter(e -> e.getValue() == Perception.BlockKind.HARMFUL)
                .map(e -> e.getKey().toShortString()).toList() + " pos=" + self.position() + " pitch=" + self.getXRot();
    }

    private void cleanUp() {
        ServerLevel level = self.serverLevel();
        BlockState st = level.getBlockState(cleanTarget);
        if (st.isAir() || !brain().isHarmful(Perception.blockId(st))) {
            cleanLog("gone");
            perception.forgetBlock(cleanTarget);
            cleanTarget = null;
            return;
        }
        if (++cleanTicks > 400 || hostileWithin(6, now())) {
            cleanLog(cleanTicks > 400 ? "timeout" : "hostile");
            perception.forgetBlock(cleanTarget);
            cleanTarget = null;
            motor.resetMining();
            return;
        }
        Vec3 c = Vec3.atCenterOf(cleanTarget);
        if (self.getEyePosition().distanceTo(c) > Motor.BLOCK_REACH - 0.5) {
            // walk to where one can stand next to / on it (a path into the solid block itself does not exist)
            motor.navigate(motor.approachPoint(cleanTarget), 1.5, false);
            if (motor.stuckCount() > 6) {
                cleanLog("stuck");
                perception.forgetBlock(cleanTarget);
                cleanTarget = null;
            }
            return;
        }
        motor.stop();
        Equipment.select(self, Equipment.bestToolSlot(self, st));
        if (motor.mine(cleanTarget)) {
            hazardsCleaned++;
            cleanedInWindow++;
            stepReward += 0.5f;
            perception.forgetBlock(cleanTarget);
            cleanTarget = null;
        }
    }

    /** Clones standing nearby that do not know a block is harmful get told (in chat, by name). */
    private void shareHazards() {
        Brain mine = brain();
        if (mine.harmfulBlocks().isEmpty()) {
            return;
        }
        java.util.Map<String, List<String>> lacking = new java.util.LinkedHashMap<>();
        long now = self.level().getGameTime();
        for (Perception.Seen s : perception.remembered()) {
            // seen in the last few seconds: still around even if we just looked away
            if (now - s.lastSeen > 200 || !(s.entity instanceof ClonePlayer other) || other == self || !other.isAlive() || other.distanceTo(self) > 16
                    || other.getCloneBrain() == null || other.getCloneBrain() == mine) {
                continue;
            }
            for (String id : mine.harmfulBlocks()) {
                if (!other.getCloneBrain().isHarmful(id)) {
                    lacking.computeIfAbsent(id, k -> new java.util.ArrayList<>()).add(other.getGameProfile().getName());
                }
            }
        }
        lacking.forEach((id, names) -> {
            net.minecraft.world.level.block.Block block = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(new net.minecraft.resources.ResourceLocation(id));
            Component blockName = block == null ? Component.literal(id) : block.getName();
            Chat.say(self, Component.translatable("rlclones.chat.hazard", blockName, String.join(", ", names)), "HAZARD " + id + " to=" + String.join(",", names));
            hazardsShared += names.size();
        });
    }

    // ------------------------------------------------------------------ extra options (chests, farming, expeditions)

    /**
     * An escape from a pit begins here. Begun three times within 3 blocks in 2400 ticks it is a loop (out, back for the ore
     * or the stairs, in again): the ores about are left alone for a while, the stairs here are given up, and getting out now
     * means getting well away. Returns how far away (0 = out of the cell is enough).
     */
    public int onEscapeStart(long now) {
        BlockPos at = self.blockPosition();
        if (lastEscapeAt != null && lastEscapeAt.distManhattan(at) <= 3 && now - lastEscapeTick < 2400) {
            escapeRepeat++;
        } else {
            escapeRepeat = 1;
        }
        lastEscapeAt = at;
        lastEscapeTick = now;
        if (escapeRepeat < 3) {
            return 0;
        }
        stairs.avoidNear(at, 10, now + 6000);
        for (java.util.Map.Entry<BlockPos, Perception.BlockKind> e : perception.blocks().entrySet()) {
            if (e.getValue() == Perception.BlockKind.ORE && e.getKey().distManhattan(at) <= 8) {
                skipBlocks.put(e.getKey().immutable(), now + 1200); // (forgotten after 1200 more ticks)
            }
        }
        return 6;
    }

    public boolean skipped(BlockPos p) {
        return skipBlocks.containsKey(p);
    }

    /** An option has ended (D-4): three in a row that came to nothing in a tick are left out for 600 ticks. */
    public void noteOptionEnd(Option o, int ticks, float reward, boolean died, long now) {
        if (!CHURN_OPTIONS.contains(o)) {
            return;
        }
        if (!died && ticks <= 1 && Math.abs(reward) < 0.01f) {
            if (churnOption == o) {
                churnRun++;
            } else {
                churnOption = o;
                churnRun = 1;
            }
            if (churnRun >= 3) {
                optionCooldown[o.ordinal()] = now + 600;
                churnRun = 0;
                churnCooldowns++;
            }
        } else if (ticks > 5) {
            churnOption = null;
            churnRun = 0;
        }
    }

    public boolean cooledDown(Option o, long now) {
        return now < optionCooldown[o.ordinal()];
    }

    /** Hungry and nothing to eat: no trips, no digging, food first. */
    private boolean hungryNoFood() {
        return !self.isCreative() && self.getFoodData().getFoodLevel() < 8 && FoodAid.foodItems(self) == 0 && !Senses.hasFood(self);
    }

    /** Iron / diamonds are still to be found, there is depth left to dig for them, and the last digs did not all get nowhere. */
    private boolean digPending(long now) {
        return now >= digBackoffUntil && Progression.digWanted(self) && stairs.pending();
    }

    private void markDigStart() {
        digStartY = self.getBlockY();
        digStartShort = Progression.ironShort(self) + Progression.diamondShort(self);
        digStartTunnel = stairs.tunnelDug;
    }

    private void noteDigEnd(long now, boolean died) {
        lastDigEnd = now;
        if (died) {
            return;
        }
        boolean progress = digStartY - self.getBlockY() >= 4 || stairs.tunnelDug - digStartTunnel >= 8
                || digStartShort - (Progression.ironShort(self) + Progression.diamondShort(self)) > 0;
        if (progress) {
            digFails = 0;
        } else if (++digFails >= 3) {
            digFails = 0;
            digBackoffUntil = now + 6000; // three digs in a row got nowhere: other things for a while
        }
    }

    /**
     * Iron or diamonds are what is missing and there is depth left: go and dig (a staircase, or a shaft), unless it is
     * not the time (hurt, hungry, no wood for a table): then what is missing first. Null = nothing to pull.
     */
    @Nullable
    private Option digDrive(long now, int mask) {
        boolean stairsOk = (mask & Option.STAIRS.bit()) != 0;
        boolean shaftOk = (mask & Option.SHAFT.bit()) != 0;
        if (!(stairsOk || shaftOk) || !digPending(now) || now - lastDigEnd < 1200 || self.getHealth() < self.getMaxHealth() * 0.7f) {
            return null;
        }
        if (self.getFoodData().getFoodLevel() < 18 && FoodAid.foodItems(self) < 6) { // enough for a long way down: food first
            if ((mask & Option.HUNT.bit()) != 0) {
                return Option.HUNT;
            }
            return (mask & Option.FARM.bit()) != 0 ? Option.FARM : null;
        }
        if (Crafting.woodUnits(self) < 12 && (mask & Option.GATHER_WOOD.bit()) != 0) { // a spare pickaxe's worth and a table
            return Option.GATHER_WOOD; // a table, sticks, ladders: from wood
        }
        digDrives++;
        return stairsOk ? Option.STAIRS : Option.SHAFT;
    }

    private boolean othersOnline() {
        for (net.minecraft.server.level.ServerPlayer p : self.getServer().getPlayerList().getPlayers()) {
            if (p != self && p.isAlive() && p.level() == self.level() && !p.isSpectator()) {
                return true;
            }
        }
        return false;
    }

    /** Real work: while any of these is possible, the clone is not "out of things to do". */
    private static final int BUSY_OPTIONS = Option.HELP.bit() | Option.STORE.bit() | Option.FETCH.bit() | Option.LOOT.bit() | Option.FARM.bit()
            | Option.EXPEDITION.bit() | Option.JOIN.bit() | Option.QUARRY.bit() | Option.BREW.bit() | Option.ANIMALS.bit() | Option.SALVAGE.bit()
            | Option.PORTAL.bit() | Option.FEED.bit() | Option.BREED.bit() | Option.FIGHT.bit() | Option.FLEE.bit() | Option.EAT.bit()
            | Option.GATHER_WOOD.bit() | Option.MINE.bit() | Option.CRAFT.bit();

    /** Options only a clone knows about itself (read from chat / its own plans). */
    private long extraTick = Long.MIN_VALUE;
    private int extraInv = -1;
    private long extraSeen = Long.MIN_VALUE;
    private int extraCached;

    /**
     * Options only this clone knows about. Asked by the clone itself and by every clone watching it (imitation), so
     * the answer is kept for the rest of the tick unless the bag or what it sees changed meanwhile.
     */
    public int extraOptions(long now) {
        int inv = self.getInventory().getTimesChanged();
        if (now == extraTick && inv == extraInv && perception.lastUpdate() == extraSeen) {
            return extraCached;
        }
        extraCached = computeExtraOptions(now);
        extraTick = now;
        extraInv = inv;
        extraSeen = perception.lastUpdate();
        return extraCached;
    }

    private int computeExtraOptions(long now) {
        int mask = 0;
        if (hasHelpRequest()) {
            mask |= Option.HELP.bit();
        }
        if (self.containerMenu == self.inventoryMenu) {
            if (storage.canStore()) {
                mask |= Option.STORE.bit();
            }
            if (storage.canFetch()) {
                mask |= Option.FETCH.bit();
            }
            if (storage.canLoot()) {
                mask |= Option.LOOT.bit();
            }
        }
        if (farming.hasWork() || water.hasWork()) {
            mask |= Option.FARM.bit(); // a field to work, or water to bring home for one
        }
        // iron / diamonds to dig for, a stone pickaxe still to make, or no food for a trip: no new trips
        boolean homeBody = digPending(now) || hungryNoFood() || Progression.need(self) == Progression.Need.STONE;
        if (expedition.isLeading() || (!homeBody && expedition.canLead(now) && othersOnline())) {
            mask |= Option.EXPEDITION.bit();
        }
        if (expedition.joinedOffer() != null || (!homeBody && expedition.canJoin(now))) {
            mask |= Option.JOIN.bit();
        }
        if (Config.get(Config.ALLOW_BLOCK_BREAKING, true) && Crafting.stoneNeeded(self) > 0
                && Senses.nearestBlock(perception, self, Perception.BlockKind.STONE, 16) != null) {
            mask |= Option.QUARRY.bit();
        }
        if (discovery.canDiscover()) {
            mask |= Option.DISCOVER.bit();
        }
        if (self.containerMenu == self.inventoryMenu && brewing.hasWork(now)) {
            mask |= Option.BREW.bit();
        }
        if (self.onGround() && animals.hasWork()) {
            mask |= Option.ANIMALS.bit();
        }
        if (fishing.canFish()) {
            mask |= Option.FISH.bit();
        }
        if (needWood() && Senses.nearestBlock(perception, self, Perception.BlockKind.WOOD, 24, p -> harvestable(Perception.BlockKind.WOOD, p)) != null) {
            mask |= Option.SALVAGE.bit();
        }
        if (self.onGround() && portals.hasWork()) {
            mask |= Option.PORTAL.bit();
        }
        if (foodAid.canHelp() || itemAid.canHelp()) {
            mask |= Option.FEED.bit(); // food for the starving, or what a friend asked for
        }
        if (breeding.canStart()) {
            mask |= Option.BREED.bit();
        }
        if (!hungryNoFood() && stairs.wanted()) {
            mask |= Option.STAIRS.bit(); // (when idle, or while iron / diamonds are to be had: see startOption)
        }
        if (!hungryNoFood() && shafts.wanted()) {
            mask |= Option.SHAFT.bit(); // the other way down (same)
        }
        if (achievements.hasGoal(now)) {
            mask |= Option.ACHIEVE.bit();
        }
        Player fav = foodAid.favourite(64);
        if (fav != null && fav.distanceTo(self) > 5) {
            mask |= Option.FOLLOW.bit();
        }
        return mask;
    }

    /** No wood at all, and no tree in sight. */
    private boolean needWood() {
        int wood = 0;
        for (ItemStack s : self.getInventory().items) {
            if (s.is(net.minecraft.tags.ItemTags.PLANKS) || s.is(net.minecraft.tags.ItemTags.LOGS)) {
                wood += s.getCount();
            }
        }
        return wood == 0 && Config.get(Config.ALLOW_BLOCK_BREAKING, true) && Senses.nearestBlock(perception, self, Perception.BlockKind.LOG, 32) == null;
    }

    private boolean runStorage() {
        int before = storage.deposits + storage.withdrawals + storage.lootings;
        Storage.Status st = storage.tick();
        optionReward += (storage.deposits + storage.withdrawals + storage.lootings - before) * 1.5f;
        return st != Storage.Status.WORKING;
    }

    private boolean runFarm() {
        if (farmWater) {
            WaterSource.Status ws = water.tick();
            if (ws == WaterSource.Status.DONE) {
                optionReward += 5f; // water by the base for good
            }
            return ws != WaterSource.Status.WORKING;
        }
        int before = farming.tilled + farming.planted + farming.harvested + farming.soilMade + farming.torchesPlaced;
        Farming.Status st = farming.tick();
        optionReward += (farming.tilled + farming.planted + farming.harvested + farming.soilMade + farming.torchesPlaced - before) * 0.5f;
        if (farming.needTorch) {
            farming.needTorch = false;
            if (Crafting.torchMakeable(self)) {
                crafting.forcedTarget = Items.TORCH; // too dark for crops: a torch first
                nextOption = Option.CRAFT;
                return true;
            }
            itemAid.need(Items.TORCH, 4);
        }
        return st != Farming.Status.WORKING;
    }

    /** Only perceive and learn from others (used when the clone's own AI is switched off). */
    public void passiveTick() {
        long now = now();
        if (option != null) {
            finishOption(false);
        }
        if (((now + self.getId()) & 3) == 0) {
            perception.update(now);
            observeWorld(now);
            watcher.update(now);
        }
    }

    private void trackRewards() {
        float hp = self.getHealth();
        if (lastHealth >= 0) {
            float dh = hp - lastHealth;
            if (dh < 0) {
                stepReward += dh * 1.5f;
                optionReward += dh;
                LivingEntity attacker = self.getLastHurtByMob();
                if (attacker != null && !perception.isVisible(attacker) && attacker.distanceTo(self) < 32) {
                    // feel where the hit came from and turn around, like a player reacting to the damage tilt
                    lookBack = attacker.getEyePosition();
                    lookBackTicks = 10;
                }
            } else if (dh > 0) {
                optionReward += dh * 0.3f;
            }
        }
        lastHealth = hp;
        int food = self.getFoodData().getFoodLevel();
        if (lastFood >= 0 && food > lastFood) {
            optionReward += (food - lastFood) * (lastFood <= 14 ? 1.0f : 0.2f);
        }
        lastFood = food;
        long chunk = ChunkPos.asLong(self.getBlockX() >> 4, self.getBlockZ() >> 4);
        if (visitedChunks.size() < 4096 && visitedChunks.add(chunk)) {
            optionReward += 0.3f;
        }
    }

    // ------------------------------------------------------------------ learning from sightings

    private void observeWorld(long now) {
        ServerLevel level = self.serverLevel();
        for (Perception.Seen s : perception.visible()) {
            if (!(s.entity instanceof Mob mob)) {
                continue;
            }
            EnemyKnowledge k = brain().knowledge(s.typeId);
            if (s.firstSeen == now) {
                k.sightings++;
            }
            double v = s.horizontalSpeed();
            if (v > 0.02 && v < 2.0 && mob.getTarget() != null) {
                k.speed.add(v);
            }
            long dt = s.lastSeen - s.prevSeen;
            if (dt > 0 && dt <= 8 && s.pos.distanceTo(s.prevPos) > 6.0) {
                k.trait(EnemyKnowledge.TELEPORTS);
            }
            if (!s.flaggedBurn && mob.isOnFire() && level.isDay() && level.canSeeSky(mob.blockPosition()) && !mob.isInLava()) {
                s.flaggedBurn = true;
                k.trait(EnemyKnowledge.BURNS_IN_DAYLIGHT);
            }
            if (!s.flaggedClimb && mob.onClimbable() && !mob.onGround() && mob.horizontalCollision) {
                s.flaggedClimb = true;
                k.trait(EnemyKnowledge.CLIMBS);
            }
            if (!mob.onGround() && !mob.isInWater() && !mob.onClimbable() && Math.abs(mob.getDeltaMovement().y) < 0.03) {
                if (++s.airborneStreak == 5) {
                    k.trait(EnemyKnowledge.FLIES);
                }
            } else {
                s.airborneStreak = 0;
            }
            if (mob instanceof Creeper c) {
                if ((c.getSwellDir() > 0 || c.isIgnited()) && s.swellStart < 0) {
                    s.swellStart = now;
                } else if (c.getSwellDir() < 0 && !c.isIgnited()) {
                    s.swellStart = -1;
                }
            }
        }
    }

    /** A visible mob hit someone in melee. */
    public void observeMelee(Mob attacker, LivingEntity victim, float amount) {
        long now = now();
        EnemyKnowledge k = brain().knowledge(Perception.typeId(attacker));
        k.meleeRange.add(Senses.gap(attacker, victim));
        k.meleeDamage.add(amount);
        k.trait(EnemyKnowledge.MELEE);
        long last = enemyLastAttack.get(attacker.getId());
        if (last != Long.MIN_VALUE && now - last > 2 && now - last <= 200) {
            k.meleeCooldown.add(now - last);
        }
        enemyLastAttack.put(attacker.getId(), now);
        if (Senses.isAgent(victim) && attacker.getLastHurtByMob() == null) {
            k.trait(EnemyKnowledge.AGGRESSIVE);
        }
    }

    /** A visible mob's projectile hit someone. */
    public void observeProjectileHit(Mob owner, LivingEntity victim, float amount) {
        EnemyKnowledge k = brain().knowledge(Perception.typeId(owner));
        k.rangedRange.add(Senses.gap(owner, victim));
        k.rangedDamage.add(amount);
        k.trait(EnemyKnowledge.RANGED);
    }

    /** A visible mob fired a projectile. */
    public void observeShot(Mob shooter) {
        long now = now();
        EnemyKnowledge k = brain().knowledge(Perception.typeId(shooter));
        k.trait(EnemyKnowledge.RANGED);
        long last = enemyLastShot.get(shooter.getId());
        if (last != Long.MIN_VALUE && now - last > 2 && now - last <= 300) {
            k.rangedCooldown.add(now - last);
        }
        enemyLastShot.put(shooter.getId(), now);
        enemyLastAttack.put(shooter.getId(), now);
        LivingEntity t = shooter.getTarget();
        if (t != null) {
            k.rangedRange.add(Senses.gap(shooter, t));
        }
    }

    /** A visible entity exploded; {@code center} is the blast centre. */
    public void observeExplosion(Entity exploder) {
        EnemyKnowledge k = brain().knowledge(Perception.typeId(exploder));
        k.trait(EnemyKnowledge.EXPLOSIVE);
        Perception.Seen s = perception.get(exploder);
        if (s != null && s.swellStart >= 0) {
            k.fuseTicks.add(now() - s.swellStart);
        }
    }

    public void observeExplosionDamage(Entity exploder, double distance, float amount) {
        EnemyKnowledge k = brain().knowledge(Perception.typeId(exploder));
        k.trait(EnemyKnowledge.EXPLOSIVE);
        k.blastRadius.add(distance);
        k.explosionDamage.add(amount);
    }

    public void observeTargeting(Mob mob, LivingEntity target) {
        EnemyKnowledge k = brain().knowledge(Perception.typeId(mob));
        k.aggroRange.add(Senses.gap(mob, target));
        if (Senses.isAgent(target) && mob.getLastHurtByMob() != target) {
            k.trait(EnemyKnowledge.AGGRESSIVE);
        }
    }

    public void observeEffect(Entity source, String effectId) {
        brain().knowledge(Perception.typeId(source)).effects.mergeInt(effectId, 1, Integer::sum);
    }

    public void observeDeath(LivingEntity victim, float damageTaken, boolean killedByAgent) {
        EnemyKnowledge k = brain().knowledge(Perception.typeId(victim));
        if (damageTaken > 0) {
            k.health.add(damageTaken);
        }
        if (killedByAgent) {
            k.killedByClones++;
        }
    }

    public void observeAgentKilled(Entity killer) {
        brain().knowledge(Perception.typeId(killer)).agentsKilled++;
    }

    public void observeShieldDisabled(Entity attacker) {
        brain().knowledge(Perception.typeId(attacker)).trait(EnemyKnowledge.DISABLES_SHIELD);
    }

    // ------------------------------------------------------------------ own reward signals

    public void onDealtDamage(LivingEntity victim, float amount) {
        attacks.onDealt(victim, amount);
        if (victim == target || Senses.isHostileTo(victim, self) || Senses.isFoodAnimal(victim)) {
            stepReward += amount;
            if (Senses.isHostileTo(victim, self)) {
                optionReward += amount * 0.2f;
            }
        }
    }

    public void onKill(LivingEntity victim, boolean hostile) {
        if (hostile) {
            stepReward += 8f;
            optionReward += 6f;
            brain().kills++;
        } else if (Senses.isFoodAnimal(victim)) {
            stepReward += 4f;
            optionReward += 2f;
        }
    }

    public void onPickup(int count) {
        optionReward += Math.min(count, 5) * 0.2f;
    }

    public void onBlockBroken(BlockState state, BlockPos pos) {
        discovery.onBroken(state, pos);
        brain().learnBlock(Perception.blockId(state));
        Perception.BlockKind kind = Perception.classify(state);
        if (kind == Perception.BlockKind.LOG) {
            optionReward += 0.5f;
        } else if (kind == Perception.BlockKind.ORE) {
            optionReward += 0.8f;
        }
    }

    public void onCrafted(net.minecraft.world.item.ItemStack stack) {
        net.minecraft.world.item.Item item = stack.getItem();
        boolean gear = item instanceof net.minecraft.world.item.TieredItem || item instanceof net.minecraft.world.item.ArmorItem
                || item instanceof net.minecraft.world.item.ShieldItem;
        optionReward += gear ? 1.5f : 0.2f;
    }

    public void onSmelted() {
        optionReward += 0.5f;
    }

    public void onShieldBlock(float blocked) {
        stepReward += blocked * 0.5f;
        if (tells.blocking()) {
            tells.tellBlocks++; // raised because we saw it coming
        }
    }

    private double waterEnterY = Double.NaN;
    private long lastInWater = Long.MIN_VALUE / 2;
    private double airborneFromY = Double.NaN;
    private boolean wasInWater;

    /**
     * Water used to change height: up a waterfall / water column (in at the bottom, out on the ground 2.5+ blocks
     * higher), or a jump from a height that ended in water instead of on the ground.
     */
    private void waterMoves() {
        boolean inWater = self.isInWater();
        long now = now();
        // up: the lowest point of a swim (in the water, or just out of it) and later on the ground 2.5+ blocks higher
        if (inWater) {
            lastInWater = now;
            if (Double.isNaN(waterEnterY) || self.getY() < waterEnterY) {
                waterEnterY = self.getY();
            }
        } else if (now - lastInWater > 20) {
            waterEnterY = Double.NaN;
        }
        if (!Double.isNaN(waterEnterY) && self.onGround() && self.getY() - waterEnterY >= 2.5) {
            travel.waterClimbs++;
            waterEnterY = inWater ? self.getY() : Double.NaN;
        }
        // down: a jump from a height that ended in the water
        if (self.onGround() && !inWater) {
            airborneFromY = Double.NaN;
        } else if (!self.onGround() && !inWater && Double.isNaN(airborneFromY)) {
            airborneFromY = self.getY();
        }
        if (inWater && !wasInWater) {
            if (!Double.isNaN(airborneFromY) && airborneFromY - self.getY() >= 3.0) {
                travel.waterDrops++;
            }
            airborneFromY = Double.NaN;
        }
        wasInWater = inWater;
    }

    /** Watch the enemies in view for the cues that announce their attacks (every other tick). */
    private void watchTells(long now) {
        if ((now & 1) != 0 || brain() == null) {
            return;
        }
        for (Perception.Seen s : perception.visible()) {
            Entity e = s.entity;
            boolean harmless = e instanceof net.minecraft.world.entity.animal.Animal && !(e instanceof net.minecraft.world.entity.NeutralMob)
                    || e instanceof net.minecraft.world.entity.npc.AbstractVillager;
            if ((e instanceof Mob || e instanceof Player) && !harmless && e.isAlive() && e.distanceTo(self) < 32) {
                tells.watch(e, now, brain(), Senses.isHostileTo(e, self));
            }
        }
        if (now % 400 == 0) {
            tells.prune();
        }
    }

    /** A known tell is showing and the blow is due: shield up, facing it (a reflex, like a player who sees the bow drawn). */
    /** Times the clone got itself out after being buried by falling gravel / sand (tests). */
    public int digOuts;
    private boolean buried;
    private int buriedTicks;

    private boolean suffocates(BlockPos p) {
        ServerLevel level = self.serverLevel();
        BlockState st = level.getBlockState(p);
        return !st.isAir() && st.isSuffocating(level, p) && st.isCollisionShapeFullBlock(level, p);
    }

    /**
     * Buried (gravel or sand came down on us): like a player - step out sideways where there is room, otherwise dig
     * the block out of the face, then the one at the feet, again as more comes down, until the head is free.
     */
    private boolean buriedReflex(long now) {
        if (self.isCreative() || self.isSpectator() || self.isPassenger()) {
            return false;
        }
        BlockPos feet = self.blockPosition();
        BlockPos head = BlockPos.containing(self.getEyePosition());
        boolean inHead = suffocates(head);
        boolean inFeet = !head.equals(feet) && suffocates(feet);
        if (!inHead && !inFeet) {
            if (buried) {
                buried = false;
                digOuts++;
                motor.resetMining();
            }
            return false;
        }
        if (!buried) {
            buried = true;
            buriedTicks = 0;
            if (option != null) {
                finishOption(false);
            }
        }
        buriedTicks++;
        ServerLevel level = self.serverLevel();
        // room beside us (both blocks free, something to stand on): just walk out
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos f = feet.relative(d);
            if (level.getBlockState(f).getCollisionShape(level, f).isEmpty() && level.getBlockState(f.above()).getCollisionShape(level, f.above()).isEmpty()
                    && !level.getBlockState(f.below()).getCollisionShape(level, f.below()).isEmpty()) {
                motor.moveToward(Vec3.atBottomCenterOf(f));
                return true;
            }
        }
        BlockPos dig = inHead ? head : feet;
        if (!Config.get(Config.ALLOW_BLOCK_BREAKING, true) || level.getBlockState(dig).getDestroySpeed(level, dig) < 0) {
            return false;
        }
        if ((buriedTicks & 7) == 1) {
            Equipment.select(self, Equipment.bestToolSlot(self, level.getBlockState(dig)));
        }
        motor.stop();
        motor.mine(dig);
        return true;
    }

    private boolean tellReflex(long now) {
        Entity due = Equipment.hasShield(self) ? tells.blockNow(now) : null;
        if (due != null) {
            motor.lookAt(due);
            boolean shieldUp = self.isUsingItem() && self.getUseItem().getItem() instanceof net.minecraft.world.item.ShieldItem;
            if (!shieldUp) {
                if (self.isUsingItem()) {
                    self.stopUsingItem();
                }
                motor.useHeldItem(InteractionHand.OFF_HAND);
            }
            tells.setBlocking(true);
            return true;
        }
        if (tells.blocking()) {
            tells.setBlocking(false);
            if (action != CombatAction.BLOCK && self.isUsingItem() && self.getUseItem().getItem() instanceof net.minecraft.world.item.ShieldItem) {
                self.stopUsingItem();
            }
        }
        return false;
    }

    private long lastTrapCheck = Long.MIN_VALUE / 2;

    /** How the clone last died (for diagnostics). */
    public String lastDeath = "";

    public void onDeath(DamageSource source) {
        lastDeath = source.getMsgId() + "@" + self.blockPosition().toShortString() + " option=" + option + " food=" + self.getFoodData().getFoodLevel();
        com.rlclones.RLClones.LOGGER.info("CLONE-DEATH {} {} t={}", self.getGameProfile().getName(), lastDeath, self.level().getGameTime());
        if (option != null) {
            finishOption(true);
        }
        resolve(true);
        expedition.abandon();
        storage.reset();
        perch.reset();
        boatTrap.reset();
        portals.reset();
        cleanTarget = null;
        brain().deaths++;
        motor.resetMining();
    }

    // ------------------------------------------------------------------ strategy layer

    private void runStrategy(long now) {
        if (option == null) {
            long pd = Prof.t();
            startOption(now);
            Prof.add(Prof.DECIDE, pd);
            if (option == null) {
                return;
            }
        }
        if ((option == Option.STAIRS || option == Option.SHAFT) && !hasPickFor(null) && toolUp(null)) {
            finishOption(false); // digging down by hand: a pickaxe first
            return;
        }
        if (ORE_REFLEX.contains(option) && oreReflex(now)) {
            optionTicks++;
            return; // an ore right in front of us comes first
        }
        boolean done = switch (option) {
            case FIGHT -> runFight(now, false);
            case HUNT -> runFight(now, true);
            case FLEE -> runFlee(now);
            case EAT -> runEat();
            case COLLECT -> runCollect(now);
            case FOLLOW -> runFollow(now);
            case EXPLORE -> runExplore();
            case GATHER_WOOD -> runHarvest(Perception.BlockKind.LOG);
            case MINE -> runHarvest(Perception.BlockKind.ORE);
            case REST -> runRest();
            case CRAFT -> crafting.tick() == Crafting.Status.DONE;
            case HELP -> runHelp();
            case STORE, FETCH, LOOT -> runStorage();
            case FARM -> runFarm();
            case EXPEDITION -> expedition.leadTick() != Expedition.Status.WORKING;
            case JOIN -> expedition.followTick() != Expedition.Status.WORKING;
            case QUARRY -> runHarvest(Perception.BlockKind.STONE) || Crafting.stoneNeeded(self) == 0;
            case DISCOVER -> discovery.tick();
            case BREW -> brewing.tick(now) != Brewing.Status.WORKING;
            case ANIMALS -> animals.tick() != Animals.Status.WORKING;
            case FISH -> fishing.tick() != Fishing.Status.WORKING;
            case SALVAGE -> runHarvest(Perception.BlockKind.WOOD) || !needWood() && blocksDone >= 2;
            case PORTAL -> portals.tick() != Portals.Status.WORKING;
            case FEED -> feedingItems ? itemAid.helpTick() != ItemAid.Status.WORKING : foodAid.helpTick() != FoodAid.Status.WORKING;
            case BREED -> breeding.tick() != Breeding.Status.WORKING;
            case ACHIEVE -> achievements.tick() != Achievements.Status.WORKING;
            case STAIRS -> stairs.tick() != StairMining.Status.WORKING;
            case SHAFT -> shafts.tick() != ShaftMining.Status.WORKING;
        };
        optionTicks++;
        optionReward -= 0.005f;
        if (option != null && (done || optionTicks >= option.maxTicks || shouldInterrupt(now))) {
            finishOption(false);
        }
    }

    /**
     * Strong pulls that win over the learned policy most of the time: a friend starving (bring food), an animal
     * that could be tamed, a partner for a child, and - the more they helped us - staying with our helpers.
     */
    @Nullable
    private Option drive(long now, int mask) {
        if (!Senses.threats(perception, self, now, 16).isEmpty()) {
            return null;
        }
        if ((mask & Option.FEED.bit()) != 0 && random.nextFloat() < 0.85f) {
            return Option.FEED;
        }
        if ((mask & Option.BREED.bit()) != 0 && random.nextFloat() < 0.7f) {
            return Option.BREED;
        }
        if ((mask & Option.ANIMALS.bit()) != 0 && (animals.tameCandidate() != null || animals.livestockPending()) && random.nextFloat() < 0.7f) {
            return Option.ANIMALS; // it could be ours: tame it / pen it
        }
        wantWater = false;
        if ((mask & Option.FARM.bit()) != 0 && water.hasWork() && random.nextFloat() < 0.8f) {
            wantWater = true;
            return Option.FARM; // a bucket and no water at home: make a spring there for the fields
        }
        if ((mask & Option.CRAFT.bit()) != 0 && crafting.urgent()) {
            urgentCrafts++;
            return Option.CRAFT; // a furnace for the raw food / ore, the first bucket
        }
        if ((mask & Option.MINE.bit()) != 0 && Config.get(Config.ALLOW_BLOCK_BREAKING, true)
                && Senses.nearestBlock(perception, self, Perception.BlockKind.ORE, 24, p -> harvestable(Perception.BlockKind.ORE, p)
                && (hasPickFor(self.level().getBlockState(p)) || pickMakeableFor(self.level().getBlockState(p)))) != null) {
            orePulls++;
            return Option.MINE; // ore in sight: nothing else comes close (a pickaxe is made first if need be)
        }
        Option dig = digDrive(now, mask);
        if (dig != null) {
            return dig;
        }
        if ((mask & Option.QUARRY.bit()) != 0 && Progression.need(self) == Progression.Need.STONE) {
            return Option.QUARRY; // a wooden pickaxe and stone in sight: the stone pickaxe is next
        }
        if ((mask & Option.HUNT.bit()) != 0 && FoodAid.foodItems(self) < 16 && fishInSight(16) != null && random.nextFloat() < 0.85f) {
            huntFish = true;
            return Option.HUNT; // fish swimming about: food for the taking
        }
        Player fav = foodAid.favourite(64);
        if ((mask & Option.FOLLOW.bit()) != 0 && fav != null && fav.distanceTo(self) > 6
                && random.nextFloat() < Math.min(0.6f, 0.12f * foodAid.gratitude(fav.getUUID()))) {
            followDrives++;
            return Option.FOLLOW;
        }
        return null;
    }

    /** FOLLOW options started because of gratitude (diagnostics, tests). */
    public int followDrives;
    /** Urgent crafting / ore / fish pulls taken (diagnostics, tests). */
    public int urgentCrafts;
    public int orePulls;
    public int fishHunts;
    private boolean huntFish;
    private boolean feedingItems;

    /** The nearest fish we could catch for food, seen lately. */
    @Nullable
    private Entity fishInSight(double radius) {
        long now = now();
        Entity best = null;
        double bestD = radius;
        for (Perception.Seen s : perception.remembered()) {
            if (s.entity instanceof net.minecraft.world.entity.animal.AbstractFish && now - s.lastSeen <= 40 && Senses.isFoodAnimal(s.entity)) {
                double d = s.entity.distanceTo(self);
                if (d < bestD) {
                    bestD = d;
                    best = s.entity;
                }
            }
        }
        return best;
    }
    @Nullable
    private Entity followTarget;
    /** Who the last FOLLOW went after (diagnostics, tests). */
    public String followDebug = "";

    /** Whom to follow: the helper we owe most if one is around, otherwise the nearest friend. */
    @Nullable
    private Entity pickFollowTarget(long now) {
        Player fav = foodAid.favourite(64);
        if (fav != null) {
            followDebug = fav.getGameProfile().getName() + " (helped us)";
            return fav;
        }
        Perception.Seen ally = Senses.nearestAlly(perception, self, self, now, 64);
        followDebug = ally == null ? "" : ally.entity.getName().getString();
        return ally == null ? null : ally.entity;
    }

    private void startOption(long now) {
        int s = Senses.strategyState(perception, self, self, brain(), now);
        int mask = Senses.strategyMask(perception, self, self, now);
        if ((mask & BUSY_OPTIONS) != 0) {
            int idle = Option.STAIRS.bit() | Option.ACHIEVE.bit() | Option.SHAFT.bit();
            if (digPending(now)) {
                idle &= ~(Option.STAIRS.bit() | Option.SHAFT.bit()); // iron / diamonds are still to be had: digging is real work
            }
            if (forcedOption != null) {
                idle &= ~forcedOption.bit();
            }
            mask &= ~idle; // those are for when there is nothing else to do
        }
        for (Option co : CHURN_OPTIONS) {
            if (now < optionCooldown[co.ordinal()] && co != forcedOption) {
                mask &= ~co.bit(); // it keeps ending at once: not now
            }
        }
        int o;
        Option committed = expedition.isLeading() ? Option.EXPEDITION : expedition.joinedOffer() != null ? Option.JOIN : null;
        Option planned = nextOption;
        nextOption = null;
        huntFish = false;
        if (planned != null && (mask & planned.bit()) != 0 && Senses.threats(perception, self, now, 16).isEmpty()) {
            o = planned.ordinal(); // our own plan: the tool before the job
        } else if (forcedOption != null && (mask & forcedOption.bit()) != 0) {
            o = forcedOption.ordinal();
        } else if (now < forceFightUntil && (mask & Option.FIGHT.bit()) != 0) {
            o = Option.FIGHT.ordinal(); // no way out: fight
        } else if (committed != null && (mask & committed.bit()) != 0 && Senses.threats(perception, self, now, 16).isEmpty()) {
            o = committed.ordinal(); // a promise: keep going with the group until the trip is over
        } else {
            Option pull = drive(now, mask);
            o = pull != null ? pull.ordinal() : brain().chooseStrategy(s, mask);
        }
        if (o < 0) {
            idleWhy = mask == 0 ? "choose:mask0" : "choose:none";
            return;
        }
        option = Option.VALUES[o];
        String why = "";
        if (option == Option.FIGHT || option == Option.FLEE) {
            List<Perception.Seen> th = Senses.threats(perception, self, now, 16);
            if (!th.isEmpty()) {
                why = ":" + th.get(0).typeId + "@" + (int) th.get(0).pos.distanceTo(self.position());
            }
        } else if (optionLog.size() < 3) {
            why = "(v" + perception.visible().size() + " r" + perception.remembered().size() + ")"; // what it knew when it chose (diagnostics)
        }
        optionLog.add(option.name() + why);
        if (optionLog.size() > 30) {
            optionLog.remove(0);
        }
        optionState = s;
        optionTicks = 0;
        optionReward = 0;
        goal = null;
        fleeStuck = 0;
        lastFleeStuck = 0;
        fleeTurn = 0;
        lastFleeDist = -1;
        cakeAt = null;
        cakeTicks = 0;
        goalTimer = 0;
        calmTicks = 0;
        blockTarget = null;
        blockTicks = 0;
        blocksDone = 0;
        eatStarted = false;
        closeTicks = 0;
        switch (option) {
            case STORE -> storage.begin(Storage.Mode.STORE);
            case FETCH -> storage.begin(Storage.Mode.FETCH);
            case LOOT -> storage.begin(Storage.Mode.LOOT);
            case FARM -> {
                farming.reset();
                water.reset();
                farmWater = water.hasWork() && (wantWater || !farming.hasWork());
            }
            case DISCOVER -> discovery.begin();
            case BREW -> brewing.begin();
            case ANIMALS -> animals.begin();
            case FISH -> fishing.begin();
            case PORTAL -> portals.begin();
            case FEED -> {
                feedingItems = !foodAid.canHelp() && itemAid.canHelp();
                if (feedingItems) {
                    itemAid.begin();
                } else {
                    foodAid.begin();
                }
            }
            case BREED -> breeding.begin();
            case ACHIEVE -> achievements.begin();
            case STAIRS -> {
                markDigStart();
                stairs.begin();
            }
            case SHAFT -> {
                markDigStart();
                shafts.begin();
            }
            case FOLLOW -> followTarget = pickFollowTarget(now);
            case EXPEDITION -> {
                if (!expedition.isLeading()) {
                    Object[] hunt = huntTarget(now);
                    if (hunt != null) {
                        Entity foe = (Entity) hunt[0];
                        expedition.leadHunt(foe.blockPosition(), (Integer) hunt[1], net.minecraft.world.entity.EntityType.getKey(foe.getType()).toString());
                    } else {
                        BlockPos t = expedition.pickTarget(visitedChunks::contains);
                        if (t != null) {
                            expedition.lead(t, expedition.companionsNeeded(t, brain()));
                        }
                    }
                }
            }
            case JOIN -> {
                if (expedition.joinedOffer() == null) {
                    expedition.join();
                }
            }
            default -> {
            }
        }
    }

    private void finishOption(boolean died) {
        if (option == null) {
            return;
        }
        endedOption = option.name();
        endedAfter = optionTicks;
        if (option == Option.FIGHT || option == Option.HUNT) {
            closeCombatStep(died, died);
            target = null;
            dropVantage();
        }
        if (option == Option.SHAFT && (crafting.forcedTarget == Items.LADDER || crafting.forcedTarget == Items.STICK)) {
            crafting.forcedTarget = null;
        }
        long now = now();
        if (option == Option.STAIRS || option == Option.SHAFT) {
            noteDigEnd(now, died);
        }
        noteOptionEnd(option, optionTicks, optionReward, died, now);
        if (died) {
            optionReward -= 30f;
        }
        if (expedition.takeFinished()) {
            optionReward += 5f;
        }
        int s2 = died ? 0 : Senses.strategyState(perception, self, self, brain(), now);
        int mask2 = died ? 0 : Senses.strategyMask(perception, self, self, now);
        float gamma = (float) Math.pow(0.995, Math.max(1, optionTicks));
        brain().learn(Brain.STRATEGY, optionState, option.ordinal(), optionReward, s2, died, gamma, mask2, 1f, false);
        if (option == Option.CRAFT) {
            crafting.reset();
            if (crafting.forcedTarget instanceof net.minecraft.world.item.PickaxeItem) {
                crafting.forcedTarget = null; // made (or not: the mining will ask again)
            }
        }
        if (option == Option.STORE || option == Option.FETCH || option == Option.LOOT) {
            storage.reset();
        }
        if (option == Option.FARM) {
            farming.reset();
            water.reset();
        }
        if (option == Option.CRAFT && crafting.forcedTarget == Items.TORCH) {
            crafting.forcedTarget = null;
        }
        if (option == Option.BREW) {
            brewing.reset();
        }
        if (option == Option.FISH) {
            fishing.reset();
        }
        endLook();
        if (self.isUsingItem() && !died) {
            self.stopUsingItem();
        }
        motor.resetMining();
        motor.clearPath();
        option = null;
    }

    private boolean shouldInterrupt(long now) {
        if (optionTicks < 10) {
            return false;
        }
        if (option == Option.FIGHT) {
            return optionTicks > 60 && self.getHealth() <= self.getMaxHealth() * 0.35f && (action == null || actionDone);
        }
        if (option == Option.FLEE) {
            return false;
        }
        for (Perception.Seen s : perception.visible()) {
            if (s.entity instanceof Mob m && Senses.isHostileTo(m, self) && Senses.gap(self, m) < 8
                    && (m.getTarget() == self || Senses.isWindingUp(m))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ fighting (per enemy type Q-table)

    /** Jump attacks done with an axe / sweeps done with a sword (diagnostics, tests). */
    public int axeCrits;
    public int swordSweeps;

    /** Hostiles (the target included) standing within {@code radius} of the target. */
    private int enemiesAround(Entity t, double radius) {
        int n = 0;
        long now = now();
        for (Perception.Seen s : perception.remembered()) {
            if (now - s.lastSeen <= 40 && s.alive() && Senses.isHostileTo(s.entity, self) && s.entity.distanceTo(t) <= radius) {
                n++;
            }
        }
        return n;
    }

    private boolean validTarget(Entity e, boolean hunt, long now) {
        if (e == null || !e.isAlive() || e.isRemoved() || e.level() != self.level() || e.distanceTo(self) > 32) {
            return false;
        }
        Perception.Seen s = perception.get(e);
        if (s == null || now - s.lastSeen > (e == target && (vantage != null || vantageBase != null) ? 200 : 60)) {
            return false;
        }
        return hunt ? Senses.isFoodAnimal(e) : Senses.isHostileTo(e, self);
    }

    private Entity pickHostile(long now) {
        Entity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Perception.Seen s : perception.remembered()) {
            if (now - s.lastSeen > 60 || !s.alive() || !Senses.isHostileTo(s.entity, self)) {
                continue;
            }
            double score = Senses.gap(self, s.entity);
            if (score > 24) {
                continue;
            }
            if (s.entity instanceof Mob m && m.getTarget() == self) {
                score -= 6;
            } else if (s.entity instanceof Mob m && m.getTarget() != null) {
                score -= 2;
            }
            if (!s.visible) {
                score += 4;
            }
            if (score < bestScore) {
                bestScore = score;
                best = s.entity;
            }
        }
        return best;
    }

    private boolean runFight(long now, boolean hunt) {
        if (target != null && !validTarget(target, hunt, now)) {
            closeCombatStep(true, false);
            target = null;
            dropVantage();
        }
        if (target == null) {
            Perception.Seen animal = hunt ? Senses.nearestAnimal(perception, self, now, 24) : null;
            Entity fish = hunt && huntFish ? fishInSight(24) : null;
            target = hunt ? (fish != null ? fish : animal == null ? null : animal.entity) : pickHostile(now);
            if (fish != null && target == fish) {
                fishHunts++;
            }
            if (target == null) {
                return true;
            }
            targetType = Perception.typeId(target);
            combatState = -1;
            action = null;
            Equipment.manage(self, true); // weapon in hand, shield in the off hand before the fight starts
            if (!hunt) {
                brain().knowledge(targetType).encounters++;
            }
        }
        combatTick();
        return false;
    }

    /** Consecutive HOLD decisions / times a long HOLD was broken off (diagnostics, tests). */
    private int holdStreak;
    public int holdBreaks;

    private void combatTick() {
        if (action == null || actionDone || actionTicks >= action.duration) {
            EnemyKnowledge k = brain().knowledge(targetType);
            int s = Senses.combatState(self, target, k, sinceEnemyAttack(target), Senses.crowd(perception, self));
            int mask = Senses.combatMask(self) & ~uselessActions();
            holdStreak = action == CombatAction.HOLD ? holdStreak + 1 : 0;
            if (holdStreak >= 2 && forcedAction != CombatAction.HOLD && (mask & ~CombatAction.HOLD.bit()) != 0) {
                mask &= ~CombatAction.HOLD.bit(); // looked on long enough: do something (strike, step, shoot, back off)
                holdBreaks++;
            }
            if (action != null && combatState >= 0) {
                brain().learn(targetType, combatState, action.ordinal(), stepReward, s, false,
                        (float) Config.get(Config.DISCOUNT, 0.9), mask, 1f, false);
            }
            CombatAction prev = action;
            int a = forcedAction != null && (mask & forcedAction.bit()) != 0 ? forcedAction.ordinal() : brain().chooseCombat(targetType, s, mask);
            if (a < 0) {
                return;
            }
            action = CombatAction.VALUES[a];
            combatState = s;
            stepReward = 0;
            actionTicks = 0;
            actionDone = false;
            beginAction(prev);
        }
        executeAction();
        actionTicks++;
        stepReward -= 0.01f;
    }

    /** Ends the running combat decision: terminal when the fight is over (enemy gone / clone died). */
    private void closeCombatStep(boolean terminal, boolean died) {
        if (action != null && combatState >= 0 && targetType != null) {
            if (died) {
                stepReward -= 25f;
            }
            if (terminal || target == null || !target.isAlive()) {
                brain().learn(targetType, combatState, action.ordinal(), stepReward, 0, true, 0f, 0, 1f, false);
            } else {
                int s = Senses.combatState(self, target, brain().knowledge(targetType), sinceEnemyAttack(target), Senses.crowd(perception, self));
                brain().learn(targetType, combatState, action.ordinal(), stepReward, s, false,
                        (float) Config.get(Config.DISCOUNT, 0.9), Senses.combatMask(self), 1f, false);
            }
        }
        if (!died && self.isUsingItem() && (action == CombatAction.BLOCK || action == CombatAction.SHOOT || action == CombatAction.USE_ITEM)) {
            if ((action == CombatAction.SHOOT || action == CombatAction.USE_ITEM) && !drawnBow()) {
                self.releaseUsingItem();
            } else {
                self.stopUsingItem(); // a half-aimed bow is lowered, not loosed (it might be pointing at a friend)
            }
        }
        if (!died && (action == CombatAction.SHOOT || action == CombatAction.PILLAR || action == CombatAction.USE_ITEM)) {
            Equipment.manage(self, true);
        }
        action = null;
        combatState = -1;
        stepReward = 0;
    }

    private boolean drawnBow() {
        return Equipment.rangedKind(self.getUseItem()) == Equipment.RangedKind.BOW;
    }

    private void beginAction(CombatAction prev) {
        if (prev == CombatAction.BLOCK && action != CombatAction.BLOCK && self.isUsingItem()) {
            self.stopUsingItem();
        }
        if ((prev == CombatAction.SHOOT || prev == CombatAction.USE_ITEM) && action != prev && self.isUsingItem()) {
            if (drawnBow()) {
                self.stopUsingItem();
            } else {
                self.releaseUsingItem();
            }
        }
        if ((prev == CombatAction.SHOOT || prev == CombatAction.PILLAR || prev == CombatAction.USE_ITEM) && action != prev) {
            Equipment.manage(self, true);
        }
        switch (action) {
            case CRIT_ATTACK -> {
                // a jump attack lands hardest with an axe
                int axe = Equipment.bestOfKind(self, net.minecraft.world.item.AxeItem.class);
                if (axe >= 0) {
                    Equipment.select(self, axe);
                }
            }
            case ATTACK -> {
                // several enemies side by side: the sword's sweep hits them all
                if (target != null && enemiesAround(target, 2.5) >= 2) {
                    int sword = Equipment.bestOfKind(self, net.minecraft.world.item.SwordItem.class);
                    if (sword >= 0) {
                        Equipment.select(self, sword);
                    }
                }
            }
            case PILLAR -> {
                pillarBase = self.blockPosition();
                pillarPlaced = false;
                Equipment.select(self, Equipment.pillarBlockSlot(self));
            }
            case SHOOT -> {
                if (!Equipment.canFire(self, self.getMainHandItem()) && !Equipment.offhandRanged(self)) {
                    Equipment.select(self, Equipment.rangedSlot(self));
                }
            }
            case USE_ITEM -> useMode = -1;
            default -> {
            }
        }
    }

    private void executeAction() {
        Entity t = target;
        double gap = Senses.gap(self, t);
        switch (action) {
            case APPROACH -> {
                motor.lookAt(t);
                if (gap > 1.5) {
                    motor.navigate(t.position(), 1.0, gap > 3.5);
                } else {
                    motor.moveToward(t.position());
                }
            }
            case ATTACK -> {
                motor.lookAt(t);
                if (motor.canHit(t)) {
                    if (self.getMainHandItem().getItem() instanceof net.minecraft.world.item.SwordItem && self.onGround() && !self.isSprinting()
                            && enemiesAround(t, 2.5) >= 2) {
                        swordSweeps++;
                    }
                    motor.attack(t);
                    actionDone = true;
                } else if (!motor.withinReach(t)) {
                    motor.moveToward(t.position());
                }
            }
            case CRIT_ATTACK -> {
                motor.lookAt(t);
                int axe = Equipment.bestOfKind(self, net.minecraft.world.item.AxeItem.class);
                if (axe >= 0 && !(self.getMainHandItem().getItem() instanceof net.minecraft.world.item.AxeItem) && !self.isUsingItem()) {
                    Equipment.select(self, axe); // the axe stays in hand for the whole jump
                }
                critDebug = "t=" + actionTicks + " ground=" + self.onGround() + " vy=" + String.format("%.2f", self.getDeltaMovement().y) + " hit="
                        + motor.canHit(t) + " gap=" + String.format("%.2f", gap) + " hand=" + self.getMainHandItem();
                if (actionTicks == 0 && self.onGround()) {
                    motor.jump();
                }
                if (!motor.withinReach(t)) {
                    motor.moveToward(t.position());
                }
                if (!self.onGround() && self.getDeltaMovement().y < 0 && motor.canHit(t)) {
                    if (self.getMainHandItem().getItem() instanceof net.minecraft.world.item.AxeItem) {
                        axeCrits++;
                    }
                    motor.attack(t);
                    actionDone = true;
                } else if (actionTicks > 3 && self.onGround()) {
                    actionDone = true;
                }
            }
            case SPRINT_ATTACK -> {
                motor.lookAt(t);
                motor.moveToward(t.position());
                motor.sprint(true);
                if (motor.canHit(t)) {
                    motor.attack(t);
                    actionDone = true;
                }
            }
            case RETREAT -> {
                motor.lookAt(t);
                motor.moveAwayFrom(t.position());
            }
            case STRAFE_LEFT -> {
                motor.lookAt(t);
                motor.strafe(1f);
            }
            case STRAFE_RIGHT -> {
                motor.lookAt(t);
                motor.strafe(-1f);
            }
            case BLOCK -> {
                motor.lookAt(t);
                if (gap > Senses.REACH_GAP) {
                    motor.moveToward(t.position()); // advance behind the raised shield
                }
                if (!self.isUsingItem()) {
                    if (!Equipment.hasShield(self) || !motor.useHeldItem(InteractionHand.OFF_HAND)) {
                        actionDone = true;
                    }
                }
            }
            case HOLD -> motor.lookAt(t);
            case SHOOT -> {
                if (!seekVantage(t)) {
                    shoot(t, gap);
                }
            }
            case USE_ITEM -> useLearned(t);
            case PILLAR -> pillar();
        }
    }

    /** Fire whatever ranged weapon we carry, operated the way that weapon type is operated by a player. */
    private void shoot(Entity t, double gap) {
        ItemStack held = self.getMainHandItem();
        InteractionHand hand = InteractionHand.MAIN_HAND;
        boolean drawingOff = self.isUsingItem() && self.getUsedItemHand() == InteractionHand.OFF_HAND
                && Equipment.rangedKind(self.getUseItem()) != Equipment.RangedKind.NONE;
        if (!Equipment.canFire(self, held) && (drawingOff || Equipment.offhandRanged(self))) {
            hand = InteractionHand.OFF_HAND; // the sword stays in the main hand: shoot with the off hand
            held = self.getOffhandItem();
        } else if (!Equipment.canFire(self, held)) {
            int slot = Equipment.rangedSlot(self);
            if (slot < 0) {
                actionDone = true;
                return;
            }
            if (self.isUsingItem()) {
                self.stopUsingItem();
            }
            Equipment.select(self, slot);
            held = self.getMainHandItem();
        }
        Entity friend = allyInLine(t);
        if (friend != null && Equipment.rangedKind(held) == Equipment.RangedKind.CROSSBOW) {
            // a crossbow bolt flies straight into the friend: take the bow, which can lob over him
            int bow = Equipment.bowSlot(self);
            if (bow < 0) {
                actionDone = true;
                motor.strafe(self.getRandom().nextBoolean() ? 1f : -1f); // step aside for a clear line instead
                return;
            }
            if (self.isUsingItem()) {
                self.stopUsingItem();
            }
            Equipment.select(self, bow);
            held = self.getMainHandItem();
            hand = InteractionHand.MAIN_HAND;
            bowSwitches++;
        }
        if (held.getItem() instanceof CrossbowItem && !CrossbowItem.isCharged(held) && !self.isUsingItem()) {
            readyAmmo(hand, t); // fireworks or arrows into the crossbow
        }
        if (friend != null && Equipment.rangedKind(held) == Equipment.RangedKind.THROWN) {
            actionDone = true; // no throwing past a friend's head
            return;
        }
        if (actionTicks == 0 || !self.isUsingItem() || friend != null && lobDraw == 0) {
            int was = lobDraw;
            lobDraw = friend != null && Equipment.rangedKind(held) == Equipment.RangedKind.BOW ? lobDrawTicks(t) : 0;
            if (was == 0 && lobDraw > 0 && self.isUsingItem()) {
                self.stopUsingItem(); // drawn for a straight shot, then a friend is seen in the line: start over up the arc
            }
        }
        shootDebug = "friend=" + (friend != null) + " kind=" + Equipment.rangedKind(held) + " lob=" + lobDraw + " pitch=" + (int) self.getXRot()
                + "/" + (int) lobPitch + " using=" + self.isUsingItem() + " t=" + actionTicks;
        if (lobDraw > 0) {
            aimLob(t, self.isUsingItem() ? Math.max(lobDraw, self.getTicksUsingItem() + 1) : lobDraw);
        } else {
            aimProjectile(t, Equipment.projectileSpeed(held));
        }
        boolean canSee = perception.canSee(t);
        switch (Equipment.rangedKind(held)) {
            case CROSSBOW -> {
                if (held.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(held)) {
                    if (actionTicks >= 2 && canSee) {
                        boolean rocket = CrossbowItem.containsChargedProjectile(held, Items.FIREWORK_ROCKET);
                        if (motor.useHeldItem(hand)) { // loaded: right-click fires
                            rocketShots += rocket ? 1 : 0;
                            offhandShots += hand == InteractionHand.OFF_HAND ? 1 : 0;
                        }
                        actionDone = true;
                    }
                    return;
                }
                if (!self.isUsingItem()) {
                    if (actionTicks < 3) {
                        motor.useHeldItem(hand); // start winding
                    } else {
                        actionDone = true;
                    }
                    return;
                }
                int charge = held.getItem() instanceof CrossbowItem ? CrossbowItem.getChargeDuration(held) : 25;
                if (self.getTicksUsingItem() >= charge + 1) {
                    self.releaseUsingItem(); // loaded; fired on a following tick
                }
            }
            case THROWN -> {
                if (!self.isUsingItem()) {
                    if (actionTicks < 3) {
                        motor.useHeldItem(hand);
                    } else {
                        actionDone = true;
                    }
                    return;
                }
                if (self.getTicksUsingItem() >= 12 && canSee) {
                    self.releaseUsingItem();
                    actionDone = true;
                }
            }
            default -> {
                if (!self.isUsingItem()) {
                    // a lob: start drawing only once the bow points up the arc (a short draw leaves no time to turn)
                    boolean ready = lobDraw == 0 || Math.abs(self.getXRot() - lobPitch) < 4;
                    if (actionTicks < (lobDraw > 0 ? 12 : 3)) {
                        if (ready) {
                            motor.useHeldItem(hand);
                        }
                    } else {
                        actionDone = true;
                    }
                    return;
                }
                if (lobDraw > 0) {
                    int drawn = self.getTicksUsingItem();
                    if (drawn >= lobDraw) {
                        if (arrowClearsFriends(drawn)) {
                            self.releaseUsingItem(); // a high arc over the friend's head
                            arcShots++;
                            actionDone = true;
                        } else if (drawn > lobDraw + 30) {
                            self.stopUsingItem(); // the aim never came right: lower the bow
                            actionDone = true;
                        } else {
                            heldShots++; // not pointing up the arc yet (looked away?): hold the arrow
                        }
                    }
                } else if (friend != null) {
                    self.stopUsingItem(); // no arc to be had: never loose straight through a friend
                    actionDone = true;
                } else if (gap < 1.5 || (self.getTicksUsingItem() >= 20 && canSee)) {
                    if (arrowClearsFriends(self.getTicksUsingItem())) {
                        offhandShots += self.getUsedItemHand() == InteractionHand.OFF_HAND ? 1 : 0;
                        arrowsLoosed++;
                        if (vantageFromY > -1e9 && self.getY() > vantageFromY + 0.5 && vantageDebug.length() < 400) {
                            vantageDebug += " shot@" + String.format(java.util.Locale.ROOT, "%.1f/%d", self.getY() - vantageFromY, (int) self.getXRot());
                        }
                        self.releaseUsingItem();
                    } else {
                        self.stopUsingItem();
                    }
                    actionDone = true;
                }
            }
        }
    }

    /** Times the clone climbed up somewhere to get a shot at an enemy hidden behind something (tests). */
    public int vantageClimbs;
    public int arrowsLoosed;
    @Nullable
    private Vec3 vantage;
    private double vantageFromY = -1e10;
    private int vantageTicks;
    private int vantagePillars;
    @Nullable
    private BlockPos vantageBase;
    public String vantageDebug = "";

    /** From {@code eye}, would an arrow fly clear to {@code at}? */
    private boolean clearShot(Vec3 eye, Vec3 at) {
        return self.level().clip(new ClipContext(eye, at, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, self)).getType() == HitResult.Type.MISS;
    }

    /** A higher spot close by (a step, a ledge) from which where the enemy was last seen is in plain view. */
    @Nullable
    private Vec3 findVantage(Vec3 at) {
        ServerLevel level = self.serverLevel();
        BlockPos feet = self.blockPosition();
        List<BlockPos> spots = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(feet.offset(-5, 1, -5), feet.offset(5, 3, 5))) {
            BlockPos below = p.below();
            if (level.getBlockState(below).getCollisionShape(level, below).isEmpty() || !level.getBlockState(p).getCollisionShape(level, p).isEmpty()
                    || !level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
                    || level.getBlockState(below).getBlock() instanceof net.minecraft.world.level.block.FenceBlock) {
                continue;
            }
            Vec3 eye = new Vec3(p.getX() + 0.5, p.getY() + self.getEyeHeight(), p.getZ() + 0.5);
            if (clearShot(eye, at) && clearShot(eye.add(0, -0.1, 0), at.add(0, -0.4, 0))) {
                spots.add(p.immutable()); // (with room to spare: the arrow leaves a little below the eye)
            }
        }
        spots.sort(java.util.Comparator.comparingDouble(p -> p.distSqr(feet)));
        for (int i = 0; i < Math.min(3, spots.size()); i++) {
            Vec3 v = Vec3.atBottomCenterOf(spots.get(i));
            net.minecraft.world.level.pathfinder.Path path = motor.pathTo(v);
            if (path != null && path.canReach()) {
                return v;
            }
        }
        return null;
    }

    /**
     * The enemy we mean to shoot has slipped out of sight behind something: rather than stand there with the bow drawn,
     * climb up a step or a ledge nearby that looks over the cover - or put a block or two under our feet - and look again.
     * Returns true while busy getting up there.
     */
    private boolean seekVantage(Entity t) {
        boolean ranged = Equipment.rangedKind(self.getMainHandItem()) != Equipment.RangedKind.NONE || Equipment.offhandRanged(self)
                || Equipment.rangedSlot(self) >= 0;
        if (!ranged) {
            return false;
        }
        Vec3 mid = t.getBoundingBox().getCenter(); // where the arrow is aimed
        Vec3 from = self.getEyePosition().add(0, -0.1, 0);
        boolean shotClear = clearShot(from, mid) && clearShot(from, mid.add(0, -0.3, 0));
        boolean seeNow = perception.canSee(t) && shotClear;
        Perception.Seen seen = perception.get(t);
        Vec3 last = (seen == null ? t.position() : seen.pos).add(0, t.getBbHeight() * 0.5, 0);
        if (vantage != null) {
            boolean arrived = Motor.horizontalDistance(self.position(), vantage) < 0.3 && Math.abs(self.getY() - vantage.y) < 0.6 && self.onGround();
            if (!arrived) {
                if (self.isUsingItem()) {
                    self.stopUsingItem(); // bow down while climbing
                }
                actionTicks = Math.min(actionTicks, 10); // keep at it across decisions
                motor.lookAt(last);
                motor.navigate(vantage, 0.15, false);
                if (Motor.horizontalDistance(self.position(), vantage) < 0.8 && Math.abs(self.getY() - vantage.y) < 0.6) {
                    motor.sneak(true); // the last step onto a narrow ledge: carefully
                }
                if (motor.stuckCount() > 4 || ++vantageTicks > 200) {
                    vantage = null;
                    vantageTicks = 1000;
                }
                return true;
            }
            // up there: stay put, crouched (no slipping off a narrow ledge), and shoot from here
            motor.stop();
            motor.sneak(true);
            if (seeNow) {
                countVantage();
                vantageWait = 0;
                return false;
            }
            if (perception.canSee(t)) {
                // seen from here, but an arrow would catch the edge: somewhere higher still
                Vec3 higher = findVantage(last);
                vantageDebug += " edge" + (higher == null ? "" : "->" + BlockPos.containing(higher).toShortString());
                if (higher != null) {
                    vantage = higher;
                    return true;
                }
                return false;
            }
            if (++vantageWait > 60) {
                vantage = null; // it moved out of view from here: look for another way
                vantageWait = 0;
            }
            return false;
        }
        if (seeNow) {
            if (vantageBase != null) {
                countVantage(); // built up high enough
            }
            vantageBase = null;
            vantagePillars = 0;
            vantageTicks = 0;
            return false;
        }
        if (actionTicks < 4 && vantageBase == null) {
            return false; // a moment for it to show itself again
        }
        if (++vantageTicks > 160) {
            return false; // nothing better to be had
        }
        actionTicks = Math.min(actionTicks, 10); // keep at it across decisions
        if (vantageBase == null) {
            vantageFromY = self.getY();
            vantageCounted = false;
            vantage = findVantage(last);
            if (vantageDebug.length() < 400) {
                vantageDebug += " find=" + (vantage == null ? "none" : BlockPos.containing(vantage).toShortString());
            }
            if (vantage != null) {
                return true;
            }
            if (Config.get(Config.ALLOW_BLOCK_PLACING, true) && Equipment.pillarBlockSlot(self) >= 0 && self.onGround()) {
                vantageBase = self.blockPosition(); // no ledge about: build one under our feet
            } else {
                vantageTicks = 1000;
                return false;
            }
        }
        if (self.isUsingItem()) {
            self.stopUsingItem();
        }
        // pillar: jump and set a block under our feet, up to two high
        if (vantagePillars >= 2) {
            return false;
        }
        motor.lookAngles(self.getYRot(), 90f);
        if (self.onGround() && self.getY() < vantageBase.getY() + 0.5) {
            motor.jump();
        } else if (self.getY() >= vantageBase.getY() + 1.0 && self.level().getBlockState(vantageBase).canBeReplaced()) {
            if (!Equipment.isPillarBlock(self.getMainHandItem())) {
                Equipment.select(self, Equipment.pillarBlockSlot(self));
            }
            if (motor.useOnTopFace(vantageBase.below())) {
                vantagePillars++;
                vantageBase = vantageBase.above();
            }
        }
        return true;
    }

    private int vantageWait;
    private boolean vantageCounted;

    /** It is in plain view (and in reach of an arrow) from up here: one climb that paid off. */
    private void countVantage() {
        if (!vantageCounted && self.getY() > vantageFromY + 0.5) {
            vantageCounted = true;
            vantageClimbs++;
            vantageDebug += " seen from +" + String.format(java.util.Locale.ROOT, "%.1f", self.getY() - vantageFromY);
        }
    }

    /** The fight is over / a new target: no more climbing for the old one. */
    private void dropVantage() {
        vantage = null;
        vantageBase = null;
        vantagePillars = 0;
        vantageTicks = 0;
        vantageWait = 0;
    }

    /** Shots fired with a firework loaded in the crossbow / with the weapon held in the off hand (tests). */
    public int rocketShots;
    public int offhandShots;

    /**
     * Before winding the crossbow: a firework rocket (with stars) is loaded from the other hand - when there are no
     * arrows, or a bunch of enemies stands together and no friend is near them. Otherwise arrows.
     */
    private void readyAmmo(InteractionHand hand, Entity t) {
        boolean arrows = false;
        for (ItemStack st : self.getInventory().items) {
            arrows |= st.getItem() instanceof net.minecraft.world.item.ArrowItem;
        }
        boolean friendNear = !self.level().getEntitiesOfClass(LivingEntity.class, t.getBoundingBox().inflate(5), e -> e != self && e != t && e.isAlive()
                && (e instanceof Player p && !p.isSpectator() && !Senses.rivals(p, self)
                || e instanceof net.minecraft.world.entity.TamableAnimal ta && ta.isTame())).isEmpty();
        boolean rockets = Equipment.hasWarRockets(self) && !friendNear && t.distanceTo(self) > 5 && (!arrows || enemiesAround(t, 3.5) >= 2);
        if (hand == InteractionHand.MAIN_HAND) {
            boolean offRocket = self.getOffhandItem().getItem() instanceof FireworkRocketItem;
            if (rockets && !Equipment.isWarRocket(self.getOffhandItem())) {
                Equipment.toOffhand(self, Equipment.warRocketSlot(self)); // the crossbow takes what is in the other hand first
            } else if (!rockets && offRocket && arrows) {
                Equipment.stowOffhand(self);
            }
        } else if (rockets && !Equipment.isWarRocket(self.getMainHandItem())) {
            Equipment.select(self, Equipment.warRocketSlot(self)); // crossbow in the off hand: the rocket in the main hand
        } else if (!rockets && self.getMainHandItem().getItem() instanceof FireworkRocketItem) {
            Equipment.select(self, Equipment.bestWeaponSlot(self));
        }
    }

    // ---------------------------------------------------------------- items used the way experience says works

    private int useMode = -1;
    private String useItem = "";
    private boolean useStarted;
    private double useDealtBefore;

    /** Use an item on the enemy: swing it / right click it / hold and let go - untried ways first, then the best one. */
    private void useLearned(Entity t) {
        if (!(t instanceof LivingEntity lt)) {
            actionDone = true;
            return;
        }
        if (actionTicks == 0 || useMode < 0) {
            EnemyKnowledge k = brain().knowledgeIfPresent(targetType);
            int slot = Equipment.usableSlot(self, st -> !AttackLearning.useless(k, AttackLearning.rangedMethod(st)));
            if (slot < 0) {
                actionDone = true;
                return;
            }
            if (self.isUsingItem()) {
                self.stopUsingItem();
            }
            Equipment.select(self, slot);
            useItem = AttackLearning.itemId(self.getMainHandItem());
            useMode = brain().chooseItemUse(useItem, self.getRandom());
            useStarted = false;
            useDealtBefore = attacks.dealtTo(lt);
        }
        if (!AttackLearning.itemId(self.getMainHandItem()).equals(useItem)) {
            useMode = -1;
            actionDone = true; // used up or put away
            return;
        }
        if (useMode == AttackLearning.SWING) {
            motor.lookAt(t);
            if (!motor.withinReach(t)) {
                motor.moveToward(t.position());
            }
            if (motor.canHit(t) && self.getAttackStrengthScale(0.5f) >= 0.9f) {
                motor.attack(t);
                finishUse(lt, 2);
            } else if (actionTicks > 40) {
                useMode = -1;
                actionDone = true;
            }
            return;
        }
        if (Senses.gap(self, t) < 2.5) {
            motor.lookAt(t);
        } else {
            double speed = Equipment.projectileSpeed(self.getMainHandItem());
            aimProjectile(t, speed > 0.1 ? speed : 1.5);
        }
        if (!useStarted) {
            if (actionTicks >= 2) {
                motor.useHeldItem(InteractionHand.MAIN_HAND);
                useStarted = true;
                if (!self.isUsingItem()) {
                    self.swing(InteractionHand.MAIN_HAND);
                    finishUse(lt, 40); // an instant use: a throw, a zap...
                }
            }
            return;
        }
        if (!self.isUsingItem()) {
            finishUse(lt, 40);
            return;
        }
        if (self.getTicksUsingItem() >= AttackLearning.HOLD_TICKS[useMode]) {
            self.releaseUsingItem();
            finishUse(lt, 40);
        }
    }

    private void finishUse(LivingEntity t, int wait) {
        attacks.trial(useItem, useMode, t, useDealtBefore, now() + wait);
        useMode = -1;
        actionDone = true;
    }

    /** Attacks found to do nothing to this kind of enemy are not chosen any more. */
    private int uselessActions() {
        EnemyKnowledge k = brain().knowledgeIfPresent(targetType);
        if (k == null) {
            return 0;
        }
        int m = 0;
        if (AttackLearning.useless(k, "melee")) {
            m |= CombatAction.ATTACK.bit() | CombatAction.CRIT_ATTACK.bit() | CombatAction.SPRINT_ATTACK.bit();
        }
        int r = Equipment.rangedSlot(self);
        ItemStack rangedItem = Equipment.offhandRanged(self) ? self.getOffhandItem() : r >= 0 ? self.getInventory().getItem(r) : ItemStack.EMPTY;
        if (!rangedItem.isEmpty() && AttackLearning.useless(k, AttackLearning.rangedMethod(rangedItem))) {
            m |= CombatAction.SHOOT.bit();
        }
        if (Equipment.usableSlot(self, st -> !AttackLearning.useless(k, AttackLearning.rangedMethod(st))) < 0) {
            m |= CombatAction.USE_ITEM.bit();
        }
        return m;
    }

    /** A swing of ours connected (AttackEntityEvent): judged a few ticks later (did it take any health?). */
    public void onMeleeHit(Entity target) {
        if (target instanceof LivingEntity lt) {
            attacks.attempt(lt, "melee", now());
            if (action != CombatAction.USE_ITEM && !self.getMainHandItem().isEmpty()) {
                attacks.trial(AttackLearning.itemId(self.getMainHandItem()), AttackLearning.SWING, lt, attacks.dealtTo(lt), now() + 2);
            }
        }
    }

    /** One of our projectiles struck something. */
    public void onProjectileHit(LivingEntity target, Entity projectile) {
        attacks.attempt(target, AttackLearning.projectileMethod(projectile), now());
    }

    public AttackLearning attacks() {
        return attacks;
    }

    /** Arrows lobbed over a friend / bows taken instead of a crossbow because of one (diagnostics, tests). */
    public int arcShots;
    public int heldShots;
    public String critDebug = "";
    public int bowSwitches;
    public String shootDebug = "";
    private int lobDraw;

    /** Friends seen in the last minute: we know where they stand even while looking elsewhere (up a lob, say). */
    private final java.util.Map<Entity, Long> recentAllies = new java.util.HashMap<>();

    /** A player / clone of ours standing in the straight line of fire. */
    private Entity allyInLine(Entity t) {
        Vec3 eye = self.getEyePosition();
        Vec3 aim = t.getBoundingBox().getCenter();
        double dist = eye.distanceTo(aim);
        long now = now();
        for (Perception.Seen s : perception.visible()) {
            if (s.entity != self && Senses.isAllyOf(s.entity, self)) {
                recentAllies.put(s.entity, now);
            }
        }
        recentAllies.entrySet().removeIf(e -> now - e.getValue() > 1200 || !e.getKey().isAlive() || e.getKey().level() != self.level());
        for (Entity a : recentAllies.keySet()) {
            if (a == t || !Senses.isAllyOf(a, self)) {
                continue;
            }
            if (a.distanceTo(self) < dist && a.getBoundingBox().inflate(0.6).clip(eye, aim).isPresent()) {
                return a;
            }
        }
        return null;
    }

    /** Follows the arrow a bow drawn {@code drawn} ticks would loose right now: true if it flies past every friend. */
    private boolean arrowClearsFriends(int drawn) {
        float f = Math.min(1f, drawn / 20f);
        double speed = Math.min(1.0, (f * f + f * 2) / 3) * 3.0;
        Vec3 pos = self.getEyePosition().subtract(0, 0.1, 0);
        Vec3 vel = self.getViewVector(1f).scale(speed);
        java.util.List<AABB> friends = new java.util.ArrayList<>();
        for (Entity a : recentAllies.keySet()) {
            if (a != self && a.isAlive() && a.level() == self.level() && a.distanceTo(self) < 64) {
                friends.add(a.getBoundingBox().inflate(0.5));
            }
        }
        if (friends.isEmpty()) {
            return true;
        }
        double floor = self.getY() - 8;
        for (int i = 0; i < 100 && pos.y > floor; i++) {
            Vec3 next = pos.add(vel);
            for (AABB bb : friends) {
                if (bb.contains(pos) || bb.clip(pos, next).isPresent()) {
                    return false;
                }
            }
            if (self.level().clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, self)).getType() != HitResult.Type.MISS) {
                return true; // stuck in a block before reaching anyone
            }
            pos = next;
            vel = vel.scale(0.99).add(0, -0.05, 0);
        }
        return true;
    }

    private static final double LOB_ANGLE = Math.toRadians(55);

    /** How long to draw the bow so that a steep shot comes down on {@code t} (a weaker draw = a shorter, higher arc). */
    private int lobDrawTicks(Entity t) {
        Vec3 eye = self.getEyePosition();
        Vec3 aim = t.getBoundingBox().getCenter();
        double x = Math.sqrt((aim.x - eye.x) * (aim.x - eye.x) + (aim.z - eye.z) * (aim.z - eye.z));
        double dy = aim.y - eye.y;
        double denom = 2 * Math.cos(LOB_ANGLE) * Math.cos(LOB_ANGLE) * (x * Math.tan(LOB_ANGLE) - dy);
        if (denom <= 0) {
            return 20;
        }
        double v = Math.sqrt(0.05 * x * x / denom) * 1.04; // a little extra for the air drag
        double power = Mth.clamp(v / 3.0, 0.12, 1.0);
        double f = -1 + Math.sqrt(1 + 3 * power);
        return Mth.clamp((int) Math.ceil(f * 20), 3, 20);
    }

    /** Aim the steep (high) solution for the arrow speed a draw of {@code draw} ticks gives. */
    private void aimLob(Entity t, int draw) {
        float f = Math.min(1f, draw / 20f);
        double v = Math.min(1.0, (f * f + f * 2) / 3) * 3.0;
        Vec3 eye = self.getEyePosition();
        Vec3 aim = t.getBoundingBox().getCenter();
        double dx = aim.x - eye.x;
        double dz = aim.z - eye.z;
        double x = Math.sqrt(dx * dx + dz * dz);
        double dy = aim.y - eye.y;
        double g = 0.05;
        double v2 = v * v;
        double root = v2 * v2 - g * (g * x * x + 2 * dy * v2);
        double angle = root < 0 ? Math.PI / 4 : Math.atan((v2 + Math.sqrt(root)) / (g * Math.max(0.1, x)));
        float yaw = (float) Math.toDegrees(Mth.atan2(dz, dx)) - 90.0F;
        lobPitch = (float) -Math.toDegrees(angle);
        motor.lookAngles(yaw, lobPitch);
    }

    private float lobPitch;

    private void aimProjectile(Entity t, double speed) {
        Vec3 eye = self.getEyePosition();
        Vec3 aim = t.getBoundingBox().getCenter();
        double dx = aim.x - eye.x;
        double dz = aim.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double flight = horiz / speed;
        aim = aim.add(t.getDeltaMovement().multiply(flight, 0, flight));
        dx = aim.x - eye.x;
        dz = aim.z - eye.z;
        horiz = Math.sqrt(dx * dx + dz * dz) * 1.05;
        double dy = aim.y - eye.y;
        double v = speed;
        double g = 0.05;
        double v2 = v * v;
        double root = v2 * v2 - g * (g * horiz * horiz + 2 * dy * v2);
        double angle = root < 0 ? Math.PI / 4 : Math.atan((v2 - Math.sqrt(root)) / (g * Math.max(0.1, horiz)));
        float yaw = (float) Math.toDegrees(Mth.atan2(dz, dx)) - 90.0F;
        motor.lookAngles(yaw, (float) -Math.toDegrees(angle));
    }

    private void pillar() {
        motor.lookAngles(self.getYRot(), 90f);
        if (!pillarPlaced) {
            if (actionTicks <= 1 && self.onGround()) {
                motor.jump();
            }
            if (self.getY() >= pillarBase.getY() + 1.0 && self.level().getBlockState(pillarBase).canBeReplaced()) {
                if (!Equipment.isPillarBlock(self.getMainHandItem())) {
                    Equipment.select(self, Equipment.pillarBlockSlot(self));
                }
                pillarPlaced = motor.useOnTopFace(pillarBase.below());
                if (!pillarPlaced) {
                    actionDone = true;
                }
            } else if (actionTicks > 10) {
                actionDone = true;
            }
        } else if (self.onGround()) {
            actionDone = true;
        }
    }

    // ------------------------------------------------------------------ other options

    private boolean runFlee(long now) {
        List<Perception.Seen> threats = Senses.threats(perception, self, now, 24);
        if (threats.isEmpty()) {
            if (++calmTicks > 40) {
                return true;
            }
        } else {
            calmTicks = 0;
        }
        if (!threats.isEmpty() && portals.escape()) {
            return false; // a portal close by: through it, away from all of them
        }
        if (goal == null || --goalTimer <= 0) {
            Vec3 away = Vec3.ZERO;
            for (Perception.Seen s : threats) {
                Vec3 d = self.position().subtract(s.pos);
                double len = Math.max(1.0, d.length());
                away = away.add(d.normalize().scale(1.0 / len));
            }
            Perception.Seen ally = Senses.nearestAlly(perception, self, self, now, 64);
            if (away.lengthSqr() < 1e-6) {
                away = self.getLookAngle().scale(-1);
            }
            away = new Vec3(away.x, 0, away.z).normalize();
            if (fleeTurn != 0) {
                away = new Vec3(-away.z * fleeTurn, 0, away.x * fleeTurn); // that way was a wall: try sideways
            }
            if (ally != null && ally.pos.distanceTo(self.position()) > 6) {
                Vec3 toAlly = ally.pos.subtract(self.position());
                away = away.add(new Vec3(toAlly.x, 0, toAlly.z).normalize().scale(0.7)).normalize();
            }
            Vec3 cover = threats.isEmpty() ? null : findCover(threats, away);
            if (cover != null) {
                if (coverGoal == null || cover.distanceTo(coverGoal) > 0.5) {
                    coverReached = false;
                }
                goal = cover; // out of their sight behind something solid
                coverGoal = cover;
                goalTimer = 40;
            } else {
                goal = self.position().add(away.scale(14));
                coverGoal = null;
                goalTimer = 20;
            }
            boolean cornered = threats.stream().anyMatch(t -> Senses.gap(self, t.entity) < 4);
            if (cornered && self.getHealth() <= self.getMaxHealth() * 0.6f) {
                consumables.pearlAway(away); // only if the 5 damage of the pearl leaves us alive
            }
        }
        // (running away we look ahead, not back: what was right behind us a few seconds ago still is)
        boolean close = perception.remembered().stream().anyMatch(t -> now - t.lastSeen < 200 && t.alive() && Senses.isHostileTo(t.entity, self)
                && Senses.gap(self, t.entity) < 4);
        if (close && perch.canStart(now) && (fleeStuck > 0 || self.getHealth() < self.getMaxHealth() * 0.5f || self.getRandom().nextInt(40) == 0)) {
            perch.start(); // two blocks up, out of reach
            return true;
        }
        Perception.Seen nearest = null;
        for (Perception.Seen t : perception.remembered()) {
            if (now - t.lastSeen < 200 && t.alive() && Senses.isHostileTo(t.entity, self)
                    && (nearest == null || t.entity.distanceTo(self) < nearest.entity.distanceTo(self))) {
                nearest = t;
            }
        }
        if (nearest != null && (now % 20) == 0) {
            double d = nearest.entity.distanceTo(self);
            if (close && lastFleeDist >= 0 && d < lastFleeDist - 0.3) {
                fleeStuck++; // the only way on leads back past it
            }
            lastFleeDist = d;
        }
        if (motor.stuckCount() == 0) {
            lastFleeStuck = 0;
        } else {
            motor.holdJumps(); // hopping at the wall again will not get us out
            if (motor.stuckCount() != lastFleeStuck) {
                lastFleeStuck = motor.stuckCount();
                fleeStuck++;
                fleeTurn = self.getRandom().nextBoolean() ? 1 : -1;
                goalTimer = 0; // try another way first
            }
        }
        if (fleeStuck >= 2 && close) {
            // nowhere left to run: turn round and fight
            corneredFights++;
            forceFightUntil = now + 200;
            fleeStuck = 0;
            return true;
        }
        if (coverGoal != null && goal == coverGoal) {
            if (Motor.horizontalDistance(self.position(), coverGoal) < 0.7 && Math.abs(self.getY() - coverGoal.y) < 1.2) {
                motor.stop();
                if (hiddenAt(threats, self.position())) {
                    if (!coverReached) {
                        coverReached = true;
                        coverTaken++;
                    }
                    goalTimer = Math.max(goalTimer, 10); // stay hidden
                } else {
                    goalTimer = 0; // they can see us here after all: somewhere else
                }
                return false;
            }
            if (motor.stuckCount() > 3) {
                badCover.add(BlockPos.containing(coverGoal));
                coverGoal = null;
                goalTimer = 0;
            }
            motor.navigate(goal, 0.4, true);
            return false;
        }
        motor.navigate(goal, 1.5, true);
        return false;
    }

    /** Times the clone ran into cover (out of the threats' sight) while fleeing. */
    public int coverTaken;
    @Nullable
    private Vec3 coverGoal;
    private boolean coverReached;
    private final java.util.Set<BlockPos> badCover = new java.util.HashSet<>();

    private boolean hazardBlock(net.minecraft.world.level.block.state.BlockState st) {
        if (st.isAir()) {
            return false;
        }
        var b = st.getBlock();
        return b instanceof net.minecraft.world.level.block.MagmaBlock || b instanceof net.minecraft.world.level.block.CactusBlock
                || b instanceof net.minecraft.world.level.block.SweetBerryBushBlock || b instanceof net.minecraft.world.level.block.WebBlock
                || st.is(net.minecraft.tags.BlockTags.FIRE) || st.is(net.minecraft.tags.BlockTags.CAMPFIRES)
                || brain() != null && brain().isHarmful(net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(b).toString());
    }

    /** None of the threats has a line of sight to someone standing at {@code at}. */
    private boolean hiddenAt(List<Perception.Seen> threats, Vec3 at) {
        for (Perception.Seen t : threats) {
            Vec3 eye = t.pos.add(0, t.entity.getEyeHeight(), 0);
            if (perception.clearLine(eye, at.add(0, 1.5, 0)) || perception.clearLine(eye, at.add(0, 0.4, 0))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Somewhere close (3 to 10 blocks) where none of the threats can see us - behind a wall, a tree, a hill - preferably
     * snug among solid blocks and away from them (never closer to them than we are now).
     */
    @Nullable
    private Vec3 findCover(List<Perception.Seen> threats, Vec3 away) {
        ServerLevel level = self.serverLevel();
        BlockPos me = self.blockPosition();
        double nearestNow = Double.MAX_VALUE;
        for (Perception.Seen t : threats) {
            nearestNow = Math.min(nearestNow, t.pos.distanceTo(self.position()));
        }
        List<Vec3> spots = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (int dx = -10; dx <= 10; dx++) {
            for (int dz = -10; dz <= 10; dz++) {
                double r = Math.sqrt(dx * dx + dz * dz);
                if (r < 2.5 || r > 10.5) {
                    continue;
                }
                for (int dy = 2; dy >= -2; dy--) {
                    BlockPos p = me.offset(dx, dy, dz);
                    if (badCover.contains(p) || !level.getFluidState(p).isEmpty() || !level.getBlockState(p).getCollisionShape(level, p).isEmpty()
                            || !level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
                            || level.getBlockState(p.below()).getCollisionShape(level, p.below()).isEmpty() || hazardBlock(level.getBlockState(p.below()))
                            || hazardBlock(level.getBlockState(p))) {
                        continue;
                    }
                    Vec3 spot = Vec3.atBottomCenterOf(p);
                    double nearest = Double.MAX_VALUE;
                    for (Perception.Seen t : threats) {
                        nearest = Math.min(nearest, t.pos.distanceTo(spot));
                    }
                    if (nearest < 4 || nearest < nearestNow - 0.5 || !hiddenAt(threats, spot)) {
                        break;
                    }
                    int solid = 0;
                    for (BlockPos q : BlockPos.betweenClosed(p.offset(-1, 0, -1), p.offset(1, 1, 1))) {
                        if (!level.getBlockState(q).getCollisionShape(level, q).isEmpty()) {
                            solid++;
                        }
                    }
                    Vec3 dir = spot.subtract(self.position());
                    spots.add(spot);
                    scores.add(solid + 2.0 * away.dot(new Vec3(dir.x, 0, dir.z).normalize()) - 0.3 * r + 0.2 * nearest);
                    break;
                }
            }
        }
        // the best few that can actually be walked to without passing them
        for (int tries = 0; tries < 4 && !spots.isEmpty(); tries++) {
            int bi = 0;
            for (int i = 1; i < spots.size(); i++) {
                if (scores.get(i) > scores.get(bi)) {
                    bi = i;
                }
            }
            Vec3 spot = spots.remove(bi);
            scores.remove(bi);
            net.minecraft.world.level.pathfinder.Path path = motor.pathTo(spot);
            if (path == null || path.getNodeCount() > 30) {
                badCover.add(BlockPos.containing(spot));
                continue;
            }
            boolean pastThem = false;
            for (int i = 0; i < path.getNodeCount() && !pastThem; i++) {
                Vec3 n = Vec3.atBottomCenterOf(path.getNodePos(i));
                for (Perception.Seen t : threats) {
                    pastThem |= n.distanceTo(t.pos) < 2.0;
                }
            }
            if (!pastThem) {
                return spot;
            }
        }
        return null;
    }

    private int fleeStuck;
    private int lastFleeStuck;
    private int fleeTurn;
    private double lastFleeDist = -1;

    public int cakeBites;
    private BlockPos cakeAt;
    private int cakeTicks;

    /** No ordinary food: a cake - put it down (or use one standing nearby) and eat slices off it. */
    private boolean eatCake() {
        ServerLevel level = self.serverLevel();
        if (!self.getFoodData().needsFood() || ++cakeTicks > 300) {
            return true;
        }
        if (cakeAt == null || !(level.getBlockState(cakeAt).getBlock() instanceof net.minecraft.world.level.block.CakeBlock)) {
            cakeAt = Senses.cakeNearby(self, 5);
        }
        if (cakeAt == null) {
            int slot = -1;
            for (int i = 0; i < self.getInventory().items.size(); i++) {
                if (self.getInventory().items.get(i).is(Items.CAKE)) {
                    slot = i;
                }
            }
            if (slot < 0) {
                return true;
            }
            BlockPos feet = self.blockPosition();
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                BlockPos spot = feet.relative(d);
                if (level.getBlockState(spot).canBeReplaced() && level.getBlockState(spot.below()).isFaceSturdy(level, spot.below(), net.minecraft.core.Direction.UP)) {
                    Equipment.select(self, slot);
                    if (motor.placeBlockAt(spot)) {
                        cakeAt = spot;
                        break;
                    }
                }
            }
            return false;
        }
        Vec3 top = Vec3.atBottomCenterOf(cakeAt).add(0, 0.5, 0);
        if (self.getEyePosition().distanceTo(top) > Motor.BLOCK_REACH - 0.5) {
            motor.navigate(top, 1.5, false);
            return motor.stuckCount() > 4;
        }
        motor.stop();
        motor.lookAt(top);
        if (cakeTicks % 4 == 0) {
            int food = self.getFoodData().getFoodLevel();
            self.gameMode.useItemOn(self, level, self.getMainHandItem(), InteractionHand.MAIN_HAND,
                    new net.minecraft.world.phys.BlockHitResult(top, net.minecraft.core.Direction.UP, cakeAt, false));
            if (self.getFoodData().getFoodLevel() > food) {
                cakeBites++;
            }
        }
        return false;
    }

    /** Meals eaten straight from the off hand (no switching). */
    public int offhandMeals;

    private boolean runEat() {
        if (!eatStarted && FoodAid.goodFood(self.getOffhandItem()) && self.canEat(false)) {
            eatStarted = motor.useHeldItem(InteractionHand.OFF_HAND) && self.isUsingItem(); // food at the ready in the other hand
            offhandMeals += eatStarted ? 1 : 0;
            if (eatStarted) {
                return false;
            }
        }
        if (!eatStarted) {
            int slot = Equipment.bestFoodSlot(self);
            if (slot < 0) {
                return eatCake();
            }
            Equipment.select(self, slot);
            eatStarted = motor.useHeldItem(InteractionHand.MAIN_HAND) && self.isUsingItem();
            return !eatStarted;
        }
        if (!self.isUsingItem()) {
            Equipment.manage(self, true);
            return true;
        }
        return false;
    }

    private boolean runCollect(long now) {
        Entity item = Senses.nearestItem(perception, self, now, 16);
        if (item == null) {
            return true;
        }
        if (self.distanceTo(item) < 1.5) {
            motor.moveToward(item.position());
        } else {
            motor.navigate(item.position(), 0.5, false);
        }
        return motor.stuckCount() > 4;
    }

    private boolean runFollow(long now) {
        Entity who = followTarget;
        if (who == null || !who.isAlive() || who.level() != self.level()) {
            Perception.Seen ally = Senses.nearestAlly(perception, self, self, now, 64);
            who = ally == null ? null : ally.entity;
        }
        if (who == null) {
            return true;
        }
        double d = who.position().distanceTo(self.position());
        if (d > 4) {
            motor.navigate(who.position(), 3.0, d > 10);
            closeTicks = 0;
        } else {
            motor.lookAt(who);
            if (++closeTicks > 40) {
                return true;
            }
        }
        return motor.stuckCount() > 4;
    }

    private boolean runExplore() {
        if (goal == null) {
            goal = pickExploreGoal();
        }
        boolean arrived = motor.navigate(goal, 1.5, false);
        int phase = optionTicks % 50;
        if (phase >= 35) {
            lookTick(); // stop now and then and look around
        } else if (lookArm >= 0) {
            endLook();
        }
        return arrived || motor.stuckCount() > 3;
    }

    private Vec3 pickExploreGoal() {
        ServerLevel level = self.serverLevel();
        Vec3 best = null;
        double bestScore = -1;
        for (int i = 0; i < 6; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = 12 + random.nextDouble() * 12;
            double x = self.getX() + Math.cos(angle) * dist;
            double z = self.getZ() + Math.sin(angle) * dist;
            int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
            double y = Math.abs(surface - self.getY()) > 12 ? self.getY() : surface;
            long chunk = ChunkPos.asLong(Mth.floor(x) >> 4, Mth.floor(z) >> 4);
            double score = (visitedChunks.contains(chunk) ? 0 : 1) + random.nextDouble() * 0.5;
            if (score > bestScore) {
                bestScore = score;
                best = new Vec3(x, y, z);
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ tools for the job, ores first

    /** A decision the clone's own plans took for it (a pickaxe before the mining, wood before the pickaxe...): taken next. */
    @Nullable
    private Option nextOption;
    /** Times the clone found it had no pickaxe for the mining it was about to do and went to make / get one (tests). */
    public int toolUps;
    public String toolUpDebug = "";
    /** Ores mined on the spot because they were right in front of the clone while it did something else (tests). */
    public int oreReflexes;
    /** Ores taken in place of the plain stone that was being quarried (tests). */
    public int oresBeforeStone;
    @Nullable
    private BlockPos reflexOre;
    private int reflexTicks;
    @Nullable
    private Perception.BlockKind harvestKind;
    private static final java.util.Set<Option> ORE_REFLEX = java.util.EnumSet.of(Option.STAIRS, Option.SHAFT, Option.QUARRY, Option.EXPLORE,
            Option.GATHER_WOOD, Option.SALVAGE, Option.ACHIEVE, Option.DISCOVER);
    private static final net.minecraft.world.item.Item[] PICK_ORDER = {Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE};

    private long lastMaterialPickup = Long.MIN_VALUE / 2;
    private long toolBlockedUntil = Long.MIN_VALUE;

    /**
     * No pickaxe to be had for a while: ores it cannot take are no option meanwhile. Away from the overworld (no trees
     * to fetch wood from) only when there is wood in the bag or a tree in sight.
     */
    public boolean toolBlocked() {
        if (now() < toolBlockedUntil) {
            return true;
        }
        if (self.level().dimension() == net.minecraft.world.level.Level.OVERWORLD) {
            return false;
        }
        for (ItemStack s : self.getInventory().items) {
            if (s.is(net.minecraft.tags.ItemTags.LOGS) || s.is(net.minecraft.tags.ItemTags.PLANKS) || s.getItem() instanceof net.minecraft.world.item.PickaxeItem) {
                return false;
            }
        }
        return Senses.nearestBlock(perception, self, Perception.BlockKind.LOG, 32) == null;
    }

    /** A pickaxe that takes {@code st} can be made right now from what is in the bag. */
    private boolean pickMakeableFor(BlockState st) {
        for (net.minecraft.world.item.Item pick : PICK_ORDER) {
            if (new ItemStack(pick).isCorrectToolForDrops(st) && Crafting.makeable(self, pick)) {
                return true;
            }
        }
        return false;
    }

    /** A pickaxe in the bag that breaks {@code st} so that it drops (any pickaxe for null). */
    private boolean hasPickFor(@Nullable BlockState st) {
        for (ItemStack s : self.getInventory().items) {
            if (s.getItem() instanceof net.minecraft.world.item.PickaxeItem && (st == null || s.isCorrectToolForDrops(st))) {
                return true;
            }
        }
        return false;
    }

    /**
     * About to mine without a pickaxe that can do it: make one first (at the crafting table nearby, or one put down
     * right here), or - with nothing to make it from - go for the materials (wood, stone for a stone pick) and ask the
     * friends for one. Returns true when the current option should give way (the next one is already chosen).
     */
    private boolean toolUp(@Nullable BlockState st) {
        if (self.isCreative() || hasPickFor(st) || !Config.get(Config.ALLOW_BLOCK_BREAKING, true)) {
            return false;
        }
        net.minecraft.world.item.Item goal = null;
        net.minecraft.world.item.Item make = null;
        for (net.minecraft.world.item.Item pick : PICK_ORDER) {
            if (st != null && !new ItemStack(pick).isCorrectToolForDrops(st)) {
                continue;
            }
            if (goal == null) {
                goal = pick;
            }
            if (Crafting.makeable(self, pick)) {
                make = pick;
                break;
            }
        }
        if (goal == null) {
            return false; // no pickaxe does it
        }
        if (make == null && !hasPickFor(null) && goal != Items.WOODEN_PICKAXE && Crafting.makeable(self, Items.WOODEN_PICKAXE)) {
            make = Items.WOODEN_PICKAXE; // the first step up
        }
        toolUps++;
        if (make != null) {
            crafting.forcedTarget = make;
            if (crafting.hasWork()) {
                nextOption = Option.CRAFT;
                toolUpDebug = "craft " + ItemAid.key(make);
                return true;
            }
            crafting.forcedTarget = null; // it could be made, but not here and now (no room for a table...): as if it could not
            toolUpDebug = "cannot craft " + ItemAid.key(make) + " now";
        }
        itemAid.need(goal, 1);
        toolBlockedUntil = now() + 600; // no pickaxe to be had right now: the ore does not pull us back for a while
        Entity drop = Senses.nearestItem(perception, self, now(), 16);
        if (drop instanceof ItemEntity ie && now() - lastMaterialPickup > 200 && (ie.getItem().is(net.minecraft.tags.ItemTags.LOGS)
                || ie.getItem().is(net.minecraft.tags.ItemTags.PLANKS) || ie.getItem().is(Items.STICK)
                || ie.getItem().is(net.minecraft.tags.ItemTags.STONE_TOOL_MATERIALS) || ie.getItem().getItem() instanceof net.minecraft.world.item.PickaxeItem)) {
            lastMaterialPickup = now();
            nextOption = Option.COLLECT; // the wood (stone, pickaxe) lying right there first
            toolUpDebug = "pick up " + ie.getItem().getItem();
        } else if (itemAid.helpComing(goal)) {
            nextOption = Option.REST; // a friend is bringing one: wait here
            toolUpDebug = "waiting for " + ItemAid.key(goal);
        } else if (goal == Items.STONE_PICKAXE && hasPickFor(null) && Senses.nearestBlock(perception, self, Perception.BlockKind.STONE, 16) != null) {
            nextOption = Option.QUARRY;
            toolUpDebug = "stone for " + ItemAid.key(goal);
        } else if (goal == Items.WOODEN_PICKAXE || !hasPickFor(null)) {
            nextOption = Senses.nearestBlock(perception, self, Perception.BlockKind.LOG, 32) != null ? Option.GATHER_WOOD
                    : needWood() && Senses.nearestBlock(perception, self, Perception.BlockKind.WOOD, 24, p -> harvestable(Perception.BlockKind.WOOD, p)) != null
                    ? Option.SALVAGE : Option.EXPLORE;
            toolUpDebug = "wood: " + nextOption;
        } else {
            toolUpDebug = "cannot make " + ItemAid.key(goal);
            return false;
        }
        return true;
    }

    @Nullable
    private BlockPos reflexCollect;
    private int reflexCollectTicks;

    /** An ore within reach while doing something else: mined on the spot (and what it dropped picked up). Returns true while at it. */
    private boolean oreReflex(long now) {
        ServerLevel level = self.serverLevel();
        if (reflexCollect != null) {
            Vec3 at = Vec3.atBottomCenterOf(reflexCollect);
            boolean dropThere = !level.getEntitiesOfClass(ItemEntity.class, new AABB(reflexCollect).inflate(1.5)).isEmpty();
            if (--reflexCollectTicks <= 0 || !dropThere || motor.stuckCount() > 3) {
                reflexCollect = null;
                return false;
            }
            motor.navigate(at, 0.3, false);
            return true;
        }
        if (reflexOre == null) {
            if (((now + self.getId()) & 7) != 0 || self.onClimbable() || !self.onGround() || self.isInWater() || !Config.get(Config.ALLOW_BLOCK_BREAKING, true)) {
                return false;
            }
            Vec3 eye = self.getEyePosition();
            double bestD = (Motor.BLOCK_REACH - 0.3) * (Motor.BLOCK_REACH - 0.3);
            for (var e : perception.blocks().entrySet()) {
                if (e.getValue() != Perception.BlockKind.ORE) {
                    continue;
                }
                double d = Vec3.atCenterOf(e.getKey()).distanceToSqr(eye);
                BlockState st = level.getBlockState(e.getKey());
                if (d < bestD && !skipBlocks.containsKey(e.getKey()) && Perception.classify(st) == Perception.BlockKind.ORE && hasPickFor(st)
                        && safeToDig(e.getKey())) {
                    bestD = d;
                    reflexOre = e.getKey();
                }
            }
            if (reflexOre == null) {
                return false;
            }
            reflexTicks = 0;
            oreReflexes++;
            traceHarvest("reflex" + reflexOre.toShortString());
        }
        BlockState st = level.getBlockState(reflexOre);
        if (Perception.classify(st) != Perception.BlockKind.ORE || ++reflexTicks > 160) {
            if (reflexTicks > 160) {
                skipBlocks.put(reflexOre.immutable(), now);
            }
            perception.forgetBlock(reflexOre);
            reflexOre = null;
            motor.resetMining();
            return false;
        }
        if ((reflexTicks & 7) == 1) {
            Equipment.select(self, Equipment.bestToolSlot(self, st));
        }
        motor.stop();
        if (motor.mine(reflexOre)) {
            BlockPos done = reflexOre;
            perception.forgetBlock(done);
            reflexOre = null;
            reflexCollect = done;
            reflexCollectTicks = 40;
            for (BlockPos p : BlockPos.betweenClosed(done.offset(-1, -1, -1), done.offset(1, 1, 1))) {
                if (Perception.classify(level.getBlockState(p)) == Perception.BlockKind.ORE) {
                    perception.noteBlock(p); // the rest of the vein
                }
            }
        }
        return true;
    }

    private boolean runHarvest(Perception.BlockKind kind) {
        ServerLevel level = self.serverLevel();
        if (blockTarget != null && perception.kindAt(blockTarget) != (harvestKind != null ? harvestKind : kind)) {
            perception.forgetBlock(blockTarget);
            blockTarget = null;
        }
        if (blockTarget == null) {
            long at = now();
            skipBlocks.values().removeIf(t -> at - t > 1200);
            harvestKind = kind;
            blockTarget = null;
            if (kind == Perception.BlockKind.STONE) {
                // quarrying: an ore in sight is worth far more than plain stone - it goes first
                blockTarget = Senses.nearestBlock(perception, self, Perception.BlockKind.ORE, 16,
                        p -> harvestable(Perception.BlockKind.ORE, p) && hasPickFor(level.getBlockState(p)) && safeToDig(p));
                if (blockTarget != null) {
                    harvestKind = Perception.BlockKind.ORE;
                    oresBeforeStone++;
                }
            }
            if (blockTarget == null) {
                blockTarget = Senses.nearestBlock(perception, self, kind, kind == Perception.BlockKind.LOG ? 32 : 24, p -> harvestable(kind, p));
            }
            blockTicks = 0;
            if (blockTarget == null) {
                return true;
            }
            if ((kind == Perception.BlockKind.ORE || kind == Perception.BlockKind.STONE) && !hasPickFor(level.getBlockState(blockTarget))) {
                if (toolUp(level.getBlockState(blockTarget))) {
                    blockTarget = null;
                    return true; // the pickaxe first (or the wood for it)
                }
                harvestDebug = "no tool " + blockTarget.toShortString();
                skipBlocks.put(blockTarget.immutable(), now()); // nothing we can get that breaks this one
                perception.forgetBlock(blockTarget);
                blockTarget = null;
                return false;
            }
            if (kind == Perception.BlockKind.WOOD
                    && com.rlclones.clone.Bases.get(self.getServer()).nearest(self.level().dimension(), Vec3.atCenterOf(blockTarget), 12) != null) {
                harvestDebug = "base " + blockTarget.toShortString();
                skipBlocks.put(blockTarget.immutable(), now()); // never take a base apart (seen again at once: skip it a while)
                perception.forgetBlock(blockTarget);
                blockTarget = null;
                return false;
            }
            if (kind != Perception.BlockKind.LOG && (!safeToDig(blockTarget) || kind == Perception.BlockKind.STONE && blockTarget.getY() > self.getBlockY() + 3)) {
                harvestDebug = "unsafe " + blockTarget.toShortString();
                perception.forgetBlock(blockTarget); // (often only from where we stand right now: no long skip)
                blockTarget = null;
                return false;
            }
            harvestDebug = "target " + blockTarget.toShortString();
            traceHarvest("t" + blockTarget.toShortString());
            if (perception.kindAt(blockTarget) != harvestKind) {
                perception.forgetBlock(blockTarget);
                blockTarget = null;
                return false;
            }
            Equipment.select(self, Equipment.bestToolSlot(self, level.getBlockState(blockTarget)));
        }
        blockTicks++;
        if ((blockTicks & 7) == 1) {
            Equipment.select(self, Equipment.bestToolSlot(self, level.getBlockState(blockTarget))); // the right tool for the job
        }
        if (blockTicks > 240) {
            traceHarvest("timeout d=" + (int) self.getEyePosition().distanceTo(Vec3.atCenterOf(blockTarget)) + " " + motor.mineDebug);
            skipBlocks.put(blockTarget.immutable(), now());
            perception.forgetBlock(blockTarget);
            blockTarget = null;
            return false;
        }
        Vec3 center = Vec3.atCenterOf(blockTarget);
        if (self.getEyePosition().distanceTo(center) > Motor.BLOCK_REACH - 0.3) {
            motor.navigate(kind == Perception.BlockKind.LOG ? center : motor.approachPoint(blockTarget), kind == Perception.BlockKind.LOG ? 2.5 : 1.5, false);
            if (motor.stuckCount() > 3) {
                traceHarvest("stuck " + self.blockPosition().toShortString());
                skipBlocks.put(blockTarget.immutable(), now());
                perception.forgetBlock(blockTarget);
                blockTarget = null;
            }
            return false;
        }
        if (motor.mine(blockTarget)) {
            traceHarvest("mined");
            blocksDone++;
            if (kind == Perception.BlockKind.STONE && harvestKind == Perception.BlockKind.STONE) {
                quarried++;
            }
            BlockPos done = blockTarget;
            perception.forgetBlock(done);
            blockTarget = null;
            for (BlockPos p : BlockPos.betweenClosed(done.offset(-1, -1, -1), done.offset(1, 2, 1))) {
                if (Perception.classify(level.getBlockState(p)) == harvestKind) {
                    perception.noteBlock(p);
                }
            }
            return blocksDone >= 8;
        }
        return false;
    }

    private boolean runRest() {
        if ((optionTicks & 7) == 0 && itemAid.deliveryNear()) {
            nextOption = Option.COLLECT; // what a friend brought us lies right here
            return true;
        }
        motor.stop();
        lookTick();
        return optionTicks >= 60;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(option == null ? "idle" : option.key());
        if (target != null && (option == Option.FIGHT || option == Option.HUNT)) {
            sb.append(" vs ").append(targetType);
            if (action != null) {
                sb.append(" [").append(action.key()).append("]");
            }
        }
        return sb.toString();
    }
}
