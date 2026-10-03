package com.rlclones.ai;

import com.rlclones.clone.Bases;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.FlyingMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.FlyingAnimal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * What a clone in creative mode gets up to besides handing out gifts - it can fly and take anything from the creative
 * menu, so: TNT into a crowd of monsters, splash / lingering potions that hurt that kind (healing on the undead, mod
 * potions too - what does nothing to a kind is learned), monsters dropped off cliffs (the ground broken from under them,
 * or yanked over the edge with a fishing rod), monsters hooked and flung by flying up high and reeling in, TNT on
 * patches full of harmful blocks - and, for the others: a Nether portal, an End portal, an enchanting table with all
 * its bookshelves, a farm by the base, farm animals and an iron golem.
 */
public final class CreativePlay {
    public enum Job {NONE, TNT_CROWD, POTION, CLIFF, ROD_LAUNCH, TNT_HAZARD, ENCHANTING, NETHER_PORTAL, END_PORTAL, FARM, MOBS, GOLEM}

    private final ClonePlayer self;
    private final Motor motor;
    private final Perception perception;
    private final CreativeHelper helper;

    private Job job = Job.NONE;
    private int stage;
    private int ticks;
    private int idx;
    private int tries;
    @Nullable
    private LivingEntity foe;
    private Vec3 spot = Vec3.ZERO;
    private BlockPos site = BlockPos.ZERO;
    private final List<BlockPos> cells = new ArrayList<>();
    private Direction axis = Direction.EAST;
    private double startY;
    private float startHealth;
    private String potionId = "";
    private String foeType = "";
    private long lastTnt = Long.MIN_VALUE / 2;
    private long lastPotion = Long.MIN_VALUE / 2;
    private long lastCliff = Long.MIN_VALUE / 2;
    private long lastRod = Long.MIN_VALUE / 2;
    private long lastHazard = Long.MIN_VALUE / 2;
    private long lastBuild = Long.MIN_VALUE / 2;
    private long lastThink = Long.MIN_VALUE / 2;

    public int tntPlaced;
    public int tntLit;
    public int potionsThrown;
    public int cliffDrops;
    public int rodLaunches;
    public int hazardBlasts;
    public int portalsBuilt;
    public int endPortals;
    public int enchantRooms;
    public int farmsMade;
    public int mobsSpawned;
    public int golemsBuilt;
    public String lastPotionThrown = "";
    public String debug = "";
    public String log = "";

    public CreativePlay(ClonePlayer self, Motor motor, Perception perception, CreativeHelper helper) {
        this.self = self;
        this.motor = motor;
        this.perception = perception;
        this.helper = helper;
    }

    public Job job() {
        return job;
    }

    private void note(String s) {
        debug = job + " s" + stage + " " + s;
        if (log.length() < 600) {
            log += " " + job + ":" + s;
        }
    }

    private ServerLevel level() {
        return self.serverLevel();
    }

    // ------------------------------------------------------------------ hands, flight, blocks

    /** Get {@code stack} into the main hand: from the bag if we have it, else out of the creative menu. */
    private boolean hold(ItemStack stack) {
        var inv = self.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (ItemStack.isSameItemSameTags(s, stack)) {
                Equipment.select(self, i);
                return true;
            }
        }
        return helper.takeFromMenu(stack);
    }

    private boolean hold(Item item) {
        return hold(new ItemStack(item, item.getMaxStackSize()));
    }

    private void lookNow(Vec3 p) {
        Vec3 eye = self.getEyePosition();
        double dx = p.x - eye.x;
        double dy = p.y - eye.y;
        double dz = p.z - eye.z;
        float yaw = (float) Math.toDegrees(Mth.atan2(dz, dx)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        self.setYRot(yaw);
        self.setYHeadRot(yaw);
        self.setXRot(Mth.clamp(pitch, -90f, 90f));
    }

    /** Fly to {@code p}; true once there. */
    private boolean flyTo(Vec3 p, double arrive) {
        if (self.position().distanceTo(p) <= arrive) {
            motor.fly(self.position()); // hover
            return true;
        }
        motor.fly(p);
        return false;
    }

    /** Within reach of {@code target} (flying over beside it if not). */
    private boolean reach(Vec3 target) {
        if (self.getEyePosition().distanceTo(target) <= 4.2) {
            motor.fly(self.position());
            return true;
        }
        Vec3 away = Consumables.horizontal(self.position().subtract(target));
        motor.fly(new Vec3(target.x + away.x * 2.2, target.y + 0.6, target.z + away.z * 2.2));
        return false;
    }

    private boolean solid(BlockPos p) {
        return !level().getBlockState(p).getCollisionShape(level(), p).isEmpty() && level().getFluidState(p).isEmpty();
    }

    private boolean free(BlockPos p) {
        return level().getBlockState(p).canBeReplaced() && level().getFluidState(p).isEmpty();
    }

    private boolean occupied(BlockPos p) {
        return !level().getEntities((Entity) null, new AABB(p), e -> e instanceof LivingEntity && e.isAlive()).isEmpty();
    }

    /** Put {@code item} down at {@code p} (right-click on a neighbour face, as a player builds). True once it is there. */
    private boolean place(BlockPos p, Item item, Block expect) {
        if (level().getBlockState(p).is(expect)) {
            return true;
        }
        if (!reach(Vec3.atCenterOf(p))) {
            return false;
        }
        if (self.getBoundingBox().intersects(new AABB(p))) {
            motor.fly(self.position().add(0, 1.5, 0)); // standing in the way
            return false;
        }
        if (!level().getBlockState(p).canBeReplaced()) {
            breakBlock(p);
            return false;
        }
        if (hold(item)) {
            motor.placeBlockAt(p);
        }
        return level().getBlockState(p).is(expect);
    }

    /** Creative left click (not with a sword - that cannot break blocks in creative): the block is gone at once. */
    private void breakBlock(BlockPos p) {
        hold(Items.DIAMOND_PICKAXE);
        lookNow(Vec3.atCenterOf(p));
        self.gameMode.destroyBlock(p);
        self.swing(InteractionHand.MAIN_HAND);
    }

    /** Right-click the top face of {@code ground} with the held item, looking at it. */
    private boolean useOnTop(BlockPos ground) {
        lookNow(new Vec3(ground.getX() + 0.5, ground.getY() + 1.0, ground.getZ() + 0.5));
        return motor.useOnTopFace(ground);
    }

    // ------------------------------------------------------------------ who is who

    private static boolean flies(Entity e) {
        return e instanceof FlyingMob || e instanceof FlyingAnimal || e.isNoGravity() || e instanceof net.minecraft.world.entity.monster.Blaze
                || e instanceof net.minecraft.world.entity.monster.Vex || e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon
                || e instanceof net.minecraft.world.entity.boss.wither.WitherBoss;
    }

    private boolean enemy(Entity e) {
        return e instanceof LivingEntity && e.isAlive() && e != self && (e instanceof Enemy || Senses.isHostileTo(e, self))
                && !(e instanceof Player p && !Senses.rivals(p, self));
    }

    /** Players and clones on our side, pets, villagers, golems, farm animals: kept out of any blast. */
    private boolean friendly(Entity e) {
        if (e == self || !e.isAlive()) {
            return false;
        }
        return e instanceof Player p && !p.isSpectator() && !Senses.rivals(p, self) || e instanceof TamableAnimal t && t.isTame()
                || e instanceof AbstractVillager || e instanceof IronGolem || e instanceof Animal;
    }

    private List<LivingEntity> enemies(double radius) {
        List<LivingEntity> out = new ArrayList<>();
        long now = level().getGameTime();
        for (Perception.Seen s : perception.remembered()) {
            if (now - s.lastSeen <= 60 && s.entity instanceof LivingEntity le && enemy(le) && le.distanceTo(self) <= radius) {
                out.add(le);
            }
        }
        return out;
    }

    /** The biggest bunch of enemies standing together (within 4 blocks of one of them). */
    private List<LivingEntity> crowd() {
        List<LivingEntity> all = enemies(32);
        List<LivingEntity> best = List.of();
        for (LivingEntity a : all) {
            List<LivingEntity> group = new ArrayList<>();
            for (LivingEntity b : all) {
                if (b.distanceTo(a) <= 4) {
                    group.add(b);
                }
            }
            if (group.size() > best.size()) {
                best = group;
            }
        }
        return best;
    }

    private static Vec3 centre(List<LivingEntity> group) {
        Vec3 c = Vec3.ZERO;
        for (LivingEntity e : group) {
            c = c.add(e.position());
        }
        return c.scale(1.0 / Math.max(1, group.size()));
    }

    /** Nobody friendly within 7 blocks, no base within 16. */
    private boolean blastSafe(Vec3 at) {
        if (!level().getEntities((Entity) null, new AABB(at, at).inflate(7), this::friendly).isEmpty()) {
            return false;
        }
        return Bases.get(self.getServer()).nearest(level().dimension(), at, 16) == null;
    }

    // ------------------------------------------------------------------ the loop

    /**
     * Returns true while it has the clone busy this tick.
     *
     * @param friendInNeed someone on our side needs a gift / help right now (building waits)
     */
    public boolean tick(long now, boolean friendInNeed) {
        if (job != Job.NONE) {
            ticks++;
            boolean more = switch (job) {
                case TNT_CROWD -> tntCrowdTick(now);
                case POTION -> potionTick(now);
                case CLIFF -> cliffTick();
                case ROD_LAUNCH -> rodTick();
                case TNT_HAZARD -> tntHazardTick(now);
                case ENCHANTING -> enchantTick();
                case NETHER_PORTAL -> netherPortalTick();
                case END_PORTAL -> endPortalTick();
                case FARM -> farmTick();
                case MOBS -> mobsTick();
                case GOLEM -> golemTick();
                default -> false;
            };
            if (!more || ticks > 1600) {
                note(more ? "timeout" : "done");
                job = Job.NONE;
                lastThink = now;
                motor.land();
            }
            return true;
        }
        if (now - lastThink < 10) {
            return false;
        }
        lastThink = now;
        return choose(now, friendInNeed);
    }

    private Vec3 origin = Vec3.ZERO;

    private void start(Job j) {
        origin = self.position();
        attempts.merge(j, 1, Integer::sum);
        job = j;
        stage = 0;
        ticks = 0;
        idx = 0;
        tries = 0;
        cells.clear();
        note("start");
    }

    private final java.util.Map<Job, Integer> attempts = new java.util.EnumMap<>(Job.class);
    private int lastEnemies = -1;

    private boolean choose(long now, boolean friendInNeed) {
        List<LivingEntity> crowd = crowd();
        int seen = enemies(24).size();
        if (seen != lastEnemies && log.length() < 600) {
            log += " [enemies " + seen + "]";
            lastEnemies = seen;
        }
        if (crowd.size() >= 4 && now - lastTnt > 300) {
            Vec3 c = centre(crowd);
            if (blastSafe(c)) {
                spot = c;
                start(Job.TNT_CROWD);
                return true;
            }
        }
        if (crowd.size() >= 3 && now - lastPotion > 160) {
            ItemStack potion = potionFor(crowd);
            if (!potion.isEmpty()) {
                spot = centre(crowd);
                foe = crowd.get(0);
                startHealth = 0;
                for (LivingEntity e : crowd) {
                    startHealth += e.getHealth();
                }
                start(Job.POTION);
                return true;
            }
        }
        if (now - lastCliff > 200) {
            for (LivingEntity e : enemies(24)) {
                if (!flies(e) && e.onGround() && (dropUnder(e) >= 4 || cliffSide(e) != null)) {
                    foe = e;
                    startY = e.getY();
                    start(Job.CLIFF);
                    return true;
                }
            }
        }
        if (now - lastRod > 300) {
            for (LivingEntity e : enemies(20)) {
                if (!flies(e) && e.onGround() && !e.isInWater() && e.getBbHeight() < 3) {
                    foe = e;
                    startY = e.getY();
                    startHealth = e.getHealth();
                    start(Job.ROD_LAUNCH);
                    return true;
                }
            }
        }
        if (now - lastHazard > 600) {
            BlockPos h = hazardPatch();
            if (h != null && blastSafe(Vec3.atCenterOf(h))) {
                site = h;
                start(Job.TNT_HAZARD);
                return true;
            }
        }
        if (friendInNeed || !enemies(16).isEmpty() || now - lastBuild < 100) {
            return false;
        }
        return chooseProject(now);
    }

    // ------------------------------------------------------------------ TNT into a crowd

    @Nullable
    private BlockPos groundUnder(Vec3 at) {
        BlockPos p = BlockPos.containing(at.x, at.y + 1, at.z);
        for (int i = 0; i < 6; i++) {
            if (solid(p.below()) && free(p)) {
                return p;
            }
            p = p.below();
        }
        return null;
    }

    /** A free cell next to the middle of the crowd (TNT cannot go where a mob stands). */
    @Nullable
    private BlockPos tntCell(Vec3 c) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos p = groundUnder(c.add(dx, 0, dz));
                if (p != null && !occupied(p) && Vec3.atBottomCenterOf(p).distanceTo(c) < bestD) {
                    bestD = Vec3.atBottomCenterOf(p).distanceTo(c);
                    best = p;
                }
            }
        }
        return best;
    }

    private boolean tntCrowdTick(long now) {
        switch (stage) {
            case 0 -> {
                BlockPos p = tntCell(spot);
                if (p == null) {
                    note("no cell");
                    return false;
                }
                cells.add(p);
                stage = 1;
            }
            case 1 -> {
                BlockPos p = cells.get(0);
                if (level().getBlockState(p).is(Blocks.TNT)) {
                    tntPlaced++;
                    stage = 2;
                    return true;
                }
                if (!reach(Vec3.atCenterOf(p)) || !hold(Items.TNT)) {
                    return ticks < 300;
                }
                useOnTop(p.below());
                if (++tries > 20) {
                    return false;
                }
            }
            case 2 -> {
                BlockPos p = cells.get(0);
                if (!level().getBlockState(p).is(Blocks.TNT)) {
                    if (!level().getEntitiesOfClass(PrimedTnt.class, new AABB(p).inflate(1)).isEmpty()) {
                        tntLit++;
                        lastTnt = now;
                        note("lit");
                    }
                    stage = 3;
                    idx = 0;
                    return true;
                }
                if (reach(Vec3.atCenterOf(p)) && hold(Items.FLINT_AND_STEEL)) {
                    lookNow(Vec3.atCenterOf(p).add(0, 0.5, 0));
                    motor.useOnFace(p, Direction.UP);
                }
            }
            default -> {
                // back where we came from and up, away from the blast
                flyTo(origin.add(0, 4, 0), 1.0);
                return ++idx < 80;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ potions

    private static String potionId(Potion p) {
        var k = ForgeRegistries.POTIONS.getKey(p);
        return k == null ? "?" : k.toString();
    }

    /** How much a potion would hurt this kind (healing harms the undead, poison does nothing to them...). */
    public static double potionScore(Potion p, boolean undead) {
        double score = 0;
        for (MobEffectInstance in : p.getEffects()) {
            MobEffect e = in.getEffect();
            int lvl = in.getAmplifier() + 1;
            if (undead) {
                if (e == MobEffects.HEAL) {
                    score += 10 * lvl;
                } else if (e == MobEffects.HARM || e == MobEffects.REGENERATION) {
                    score -= 10;
                } else if (e == MobEffects.POISON) {
                    score += 0;
                } else if (e.getCategory() == MobEffectCategory.HARMFUL) {
                    score += 2;
                }
            } else {
                if (e == MobEffects.HARM) {
                    score += 10 * lvl;
                } else if (e == MobEffects.POISON || e == MobEffects.WITHER) {
                    score += 6 * lvl;
                } else if (e.getCategory() == MobEffectCategory.HARMFUL) {
                    score += 2;
                } else if (e.getCategory() == MobEffectCategory.BENEFICIAL) {
                    score -= 3;
                }
            }
        }
        return score;
    }

    /** The potion (any registered one, modded included) that should hurt this crowd most; empty if none. */
    private ItemStack potionFor(List<LivingEntity> crowd) {
        LivingEntity first = crowd.get(0);
        boolean undead = first.getMobType() == MobType.UNDEAD;
        String type = Perception.typeId(first);
        var k = self.getCloneBrain() == null ? null : self.getCloneBrain().knowledgeIfPresent(type);
        Potion best = null;
        double bestScore = 0;
        for (Potion p : ForgeRegistries.POTIONS.getValues()) {
            if (p.getEffects().isEmpty()) {
                continue;
            }
            String id = potionId(p);
            if (k != null && AttackLearning.useless(k, "potion:" + id)) {
                continue; // tried on this kind: nothing happened
            }
            double score = potionScore(p, undead);
            if (k != null && k.traits.getInt("effect:potion:" + id) > 0) {
                score += 1; // known to work
            }
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        if (best == null) {
            return ItemStack.EMPTY;
        }
        potionId = potionId(best);
        foeType = type;
        return PotionUtils.setPotion(new ItemStack(crowd.size() >= 5 ? Items.LINGERING_POTION : Items.SPLASH_POTION), best);
    }

    private boolean potionTick(long now) {
        switch (stage) {
            case 0 -> {
                // over the middle of them, a few blocks up
                BlockPos g = groundUnder(spot);
                double y = (g == null ? spot.y : g.getY()) + 4.5;
                Vec3 facing = Consumables.horizontal(spot.subtract(self.position()));
                if (!flyTo(new Vec3(spot.x - facing.x * 1.2, y, spot.z - facing.z * 1.2), 0.7)) {
                    return ticks < 300;
                }
                List<LivingEntity> crowd = crowd();
                ItemStack potion = crowd.isEmpty() ? ItemStack.EMPTY : potionFor(crowd);
                if (potion.isEmpty() || !hold(potion)) {
                    return false;
                }
                lookNow(spot);
                self.setXRot(80f);
                if (motor.useHeldItem(InteractionHand.MAIN_HAND)) {
                    potionsThrown++;
                    lastPotion = now;
                    lastPotionThrown = potionId;
                    note("threw " + potionId);
                    stage = 1;
                    idx = 0;
                }
            }
            default -> {
                motor.fly(self.position());
                if (++idx < 50) {
                    return true;
                }
                // did it do anything to them?
                float health = 0;
                for (LivingEntity e : level().getEntitiesOfClass(LivingEntity.class, new AABB(spot, spot).inflate(6), e -> Perception.typeId(e).equals(foeType))) {
                    health += e.getHealth();
                }
                boolean worked = health < startHealth - 0.5f || level().getEntitiesOfClass(LivingEntity.class, new AABB(spot, spot).inflate(6),
                        e -> Perception.typeId(e).equals(foeType) && !e.getActiveEffects().isEmpty()).size() > 0;
                if (self.getCloneBrain() != null) {
                    self.getCloneBrain().knowledge(foeType).traits.mergeInt((worked ? "effect:" : "noeffect:") + "potion:" + potionId, 1, Integer::sum);
                }
                note(worked ? "it worked" : "no effect");
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ over the edge

    /** How far the ground under the mob's feet falls away below the block it stands on (a ledge, a bridge: not open ground). */
    private int dropUnder(Entity e) {
        BlockPos support = e.blockPosition().below();
        if (!solid(support)) {
            return 0;
        }
        int open = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            open += solid(support.relative(d)) ? 0 : 1;
        }
        if (open < 2) {
            return 0; // part of the ground all round: breaking it only makes a hole
        }
        int d = 0;
        for (BlockPos p = support.below(); d < 24 && !solid(p) && level().getFluidState(p).isEmpty(); p = p.below()) {
            d++;
        }
        return d;
    }

    /** A side of the mob that falls away 5+ blocks (a cliff edge right beside it). */
    @Nullable
    private Direction cliffSide(Entity e) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = e.blockPosition().relative(d);
            if (!free(n) || !free(n.above())) {
                continue;
            }
            int depth = 0;
            for (BlockPos p = n.below(); depth < 24 && !solid(p) && level().getFluidState(p).isEmpty(); p = p.below()) {
                depth++;
            }
            if (depth >= 5) {
                return d;
            }
        }
        return null;
    }

    private boolean cliffTick() {
        LivingEntity t = foe;
        if (t == null || !t.isAlive()) {
            return false;
        }
        if (t.getY() < startY - 2.5) {
            cliffDrops++;
            lastCliff = level().getGameTime();
            note("dropped " + Perception.typeId(t));
            return false;
        }
        if (stage >= 10) {
            return ++idx < 60 || !t.onGround(); // watching it fall
        }
        if (dropUnder(t) >= 4) {
            // break the ground from under it (every block its feet stand on)
            AABB bb = t.getBoundingBox();
            List<BlockPos> under = new ArrayList<>();
            for (double x : new double[]{bb.minX + 0.01, bb.maxX - 0.01}) {
                for (double z : new double[]{bb.minZ + 0.01, bb.maxZ - 0.01}) {
                    BlockPos p = BlockPos.containing(x, bb.minY - 0.5, z);
                    if (solid(p) && !under.contains(p)) {
                        under.add(p);
                    }
                }
            }
            if (under.isEmpty()) {
                stage = 10;
                return true;
            }
            BlockPos p = under.get(0);
            if (reach(Vec3.atCenterOf(p))) {
                breakBlock(p);
                stage = under.size() == 1 ? 10 : stage;
                idx = 0;
            }
            return ticks < 400;
        }
        Direction side = cliffSide(t);
        if (side == null) {
            return stage > 0 && ticks < 200;
        }
        // hover out over the drop beyond the edge and yank it towards us with a rod
        Vec3 over = t.position().add(side.getStepX() * 3.5, 1.2, side.getStepZ() * 3.5);
        if (stage == 0) {
            if (flyTo(over, 0.8)) {
                stage = 1;
                idx = 0;
            }
            return ticks < 400;
        }
        return reel(t, over, 10);
    }

    /** Cast at {@code t} from where we hover, wait for the hook to catch, then reel in. */
    private boolean reel(LivingEntity t, Vec3 hoverAt, int doneStage) {
        motor.fly(hoverAt);
        if (!hold(Items.FISHING_ROD)) {
            return false;
        }
        var hook = self.fishing;
        if (hook == null) {
            if (++tries > 8) {
                return false;
            }
            lookNow(t.position().add(0, t.getBbHeight() * 0.6 + 0.3, 0));
            motor.useHeldItem(InteractionHand.MAIN_HAND); // cast
            idx = 0;
            return true;
        }
        if (hook.getHookedIn() == t) {
            lookNow(t.position());
            motor.useHeldItem(InteractionHand.MAIN_HAND); // reel in: it comes flying our way
            stage = doneStage;
            idx = 0;
            return true;
        }
        if (++idx > 30) {
            motor.useHeldItem(InteractionHand.MAIN_HAND); // missed: wind in and cast again
            idx = 0;
        }
        return true;
    }

    // ------------------------------------------------------------------ hook it, fly up, reel in

    private boolean rodTick() {
        LivingEntity t = foe;
        if (t == null || !t.isAlive()) {
            if (stage >= 3) {
                rodLaunches++;
            }
            return false;
        }
        switch (stage) {
            case 0 -> {
                // close in a few blocks off, a little above it
                Vec3 from = Consumables.horizontal(self.position().subtract(t.position()));
                Vec3 at = t.position().add(from.scale(4)).add(0, 1.0, 0);
                if (flyTo(at, 0.8)) {
                    stage = 1;
                    idx = 0;
                }
                return ticks < 300;
            }
            case 1 -> {
                if (!hold(Items.FISHING_ROD)) {
                    return false;
                }
                var hook = self.fishing;
                motor.fly(self.position());
                if (hook == null) {
                    if (++tries > 8) {
                        note("could not hook it");
                        return false;
                    }
                    lookNow(t.position().add(0, t.getBbHeight() * 0.6 + 0.3, 0));
                    motor.useHeldItem(InteractionHand.MAIN_HAND);
                    idx = 0;
                } else if (hook.getHookedIn() == t) {
                    stage = 2; // caught: now up we go, the line paying out
                    startY = self.getY();
                    note("hooked");
                } else if (++idx > 30) {
                    motor.useHeldItem(InteractionHand.MAIN_HAND);
                    idx = 0;
                }
                return true;
            }
            case 2 -> {
                var hook = self.fishing;
                if (hook == null || hook.getHookedIn() != t) {
                    stage = 1;
                    return true;
                }
                if (self.getY() < t.getY() + 15) {
                    motor.fly(new Vec3(self.getX(), t.getY() + 16, self.getZ()));
                    return ticks < 400;
                }
                lookNow(t.position());
                motor.useHeldItem(InteractionHand.MAIN_HAND); // reel in from high up: it is flung into the air
                stage = 3;
                idx = 0;
                rodLaunches++;
                startHealth = t.getHealth();
                note("flung");
                return true;
            }
            default -> {
                motor.fly(self.position());
                return ++idx < 80;
            }
        }
    }

    // ------------------------------------------------------------------ TNT on harmful ground

    private boolean harmful(BlockState st) {
        if (st.isAir()) {
            return false;
        }
        Block b = st.getBlock();
        if (b instanceof net.minecraft.world.level.block.MagmaBlock || b instanceof net.minecraft.world.level.block.CactusBlock
                || b instanceof net.minecraft.world.level.block.SweetBerryBushBlock || b instanceof net.minecraft.world.level.block.WebBlock
                || b instanceof net.minecraft.world.level.block.WitherRoseBlock || b instanceof net.minecraft.world.level.block.PowderSnowBlock
                || b instanceof net.minecraft.world.level.block.PointedDripstoneBlock || st.is(BlockTags.FIRE) || st.is(BlockTags.CAMPFIRES)) {
            return true;
        }
        var k = ForgeRegistries.BLOCKS.getKey(b);
        return k != null && self.getCloneBrain() != null && self.getCloneBrain().isHarmful(k.toString());
    }

    /** The middle of a patch with 8+ harmful blocks close together (within 14 blocks). */
    @Nullable
    private BlockPos hazardPatch() {
        List<BlockPos> found = new ArrayList<>();
        BlockPos feet = self.blockPosition();
        Vec3 eye = self.getEyePosition();
        for (BlockPos p : BlockPos.betweenClosed(feet.offset(-14, -6, -14), feet.offset(14, 4, 14))) {
            if (harmful(level().getBlockState(p))) {
                var hit = level().clip(new net.minecraft.world.level.ClipContext(eye, Vec3.atCenterOf(p), net.minecraft.world.level.ClipContext.Block.OUTLINE,
                        net.minecraft.world.level.ClipContext.Fluid.NONE, self));
                if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS || hit.getBlockPos().distManhattan(p) <= 1) {
                    found.add(p.immutable()); // in sight (not behind a wall in somebody else's place)
                }
            }
        }
        BlockPos best = null;
        int bestN = 7;
        for (BlockPos a : found) {
            int n = 0;
            for (BlockPos b : found) {
                if (a.distManhattan(b) <= 4) {
                    n++;
                }
            }
            if (n > bestN) {
                bestN = n;
                best = a;
            }
        }
        return best;
    }

    private int harmfulAround(BlockPos c) {
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-4, -2, -4), c.offset(4, 2, 4))) {
            if (harmful(level().getBlockState(p))) {
                n++;
            }
        }
        return n;
    }

    private boolean tntHazardTick(long now) {
        if (stage == 0) {
            // a few charges spread over the patch, on top of whatever is free there
            for (BlockPos p : BlockPos.betweenClosed(site.offset(-3, -2, -3), site.offset(3, 2, 3))) {
                if (cells.size() < 3 && free(p) && !harmful(level().getBlockState(p)) && solid(p.below()) && !occupied(p)) {
                    boolean spaced = true;
                    for (BlockPos c : cells) {
                        spaced &= c.distManhattan(p) >= 3;
                    }
                    if (spaced) {
                        cells.add(p.immutable());
                    }
                }
            }
            if (cells.isEmpty()) {
                note("no room for TNT");
                return false;
            }
            startHealth = harmfulAround(site);
            stage = 1;
            idx = 0;
            return true;
        }
        if (stage == 1) {
            if (idx >= cells.size()) {
                stage = 2;
                idx = 0;
                return true;
            }
            BlockPos p = cells.get(idx);
            if (level().getBlockState(p).is(Blocks.TNT)) {
                tntPlaced++;
                idx++;
                return true;
            }
            if (reach(Vec3.atCenterOf(p)) && hold(Items.TNT)) {
                useOnTop(p.below());
                if (!level().getBlockState(p).is(Blocks.TNT) && ++tries > 30) {
                    idx++;
                }
            }
            return ticks < 600;
        }
        if (stage == 2) {
            // light them all, then away
            BlockPos lit = null;
            for (BlockPos p : cells) {
                if (level().getBlockState(p).is(Blocks.TNT)) {
                    lit = p;
                    break;
                }
            }
            if (lit == null) {
                hazardBlasts++;
                lastHazard = now;
                lastTnt = now;
                stage = 3;
                idx = 0;
                note("blast " + (int) startHealth + " harmful blocks");
                return true;
            }
            if (reach(Vec3.atCenterOf(lit)) && hold(Items.FLINT_AND_STEEL)) {
                lookNow(Vec3.atCenterOf(lit).add(0, 0.5, 0));
                motor.useOnFace(lit, Direction.UP);
                tntLit++;
            }
            return ticks < 900;
        }
        flyTo(origin.add(0, 4, 0), 1.0);
        return ++idx < 90;
    }

    // ------------------------------------------------------------------ building for everyone

    /** Where to build: by the nearest base, else here. */
    private BlockPos anchor() {
        Bases.Base b = Bases.get(self.getServer()).nearest(level().dimension(), self.position(), 64);
        return b != null ? b.center : self.blockPosition();
    }

    private boolean hasFriend() {
        long now = level().getGameTime();
        for (Perception.Seen s : perception.remembered()) {
            if (now - s.lastSeen < 400 && s.entity instanceof Player p && p != self && !p.isCreative() && !Senses.rivals(p, self) && p.isAlive()) {
                return true;
            }
        }
        return false;
    }

    /**
     * A flat, empty patch {@code w} x {@code d} (and {@code h} high) near {@code a}, at least {@code minDist} from it.
     * Returns the floor-level corner cell (where blocks stand), or null.
     */
    private static final List<int[]> RING = new ArrayList<>();

    static {
        for (int dx = -14; dx <= 14; dx++) {
            for (int dz = -14; dz <= 14; dz++) {
                RING.add(new int[]{dx, dz});
            }
        }
        RING.sort(java.util.Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
    }

    @Nullable
    private BlockPos findSite(BlockPos a, int w, int d, int h, int minDist) {
        for (int[] o : RING) {
            int dx = o[0];
            int dz = o[1];
            if (dx * dx + dz * dz < minDist * minDist) {
                continue;
            }
            for (int dy = -3; dy <= 3; dy++) {
                    BlockPos c = a.offset(dx, dy, dz);
                    if (!solid(c.below()) || !level().getBlockState(c).isAir()) {
                        continue;
                    }
                    boolean ok = true;
                    for (int x = -1; x <= w && ok; x++) {
                        for (int z = -1; z <= d && ok; z++) {
                            boolean inside = x >= 0 && x < w && z >= 0 && z < d;
                            BlockPos p = c.offset(x, 0, z);
                            if (inside && (!solid(p.below()) || occupied(p))) {
                                ok = false;
                            }
                            for (int y = 0; y < h && ok; y++) {
                                ok = level().getBlockState(p.above(y)).isAir();
                            }
                        }
                    }
                    if (ok && !overlapsBase(c, w, d)) {
                        return c;
                    }
            }
        }
        return null;
    }

    private boolean overlapsBase(BlockPos c, int w, int d) {
        for (Bases.Base b : Bases.get(self.getServer()).bases) {
            for (BlockPos ch : b.chests) {
                if (ch.getX() >= c.getX() - 2 && ch.getX() <= c.getX() + w + 1 && ch.getZ() >= c.getZ() - 2 && ch.getZ() <= c.getZ() + d + 1) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean chooseProject(long now) {
        if (!hasFriend()) {
            return false;
        }
        Bases bases = Bases.get(self.getServer());
        BlockPos a = anchor();
        var dim = level().dimension();
        boolean hasBase = bases.nearest(dim, self.position(), 64) != null;
        if (!bases.hasProject(dim, "enchanting", a, 48) && !tooMany(Job.ENCHANTING)) {
            BlockPos s = findSite(a, 5, 5, 3, 3);
            if (s != null) {
                site = s;
                start(Job.ENCHANTING);
                return true;
            }
        }
        if (hasBase && !bases.hasProject(dim, "farm", a, 48) && !farmlandNear(a, 12) && !tooMany(Job.FARM)) {
            BlockPos s = findSite(a, 5, 5, 2, 3);
            if (s != null) {
                site = s;
                start(Job.FARM);
                return true;
            }
        }
        if (!bases.hasProject(dim, "end_portal", a, 48) && !tooMany(Job.END_PORTAL)) {
            BlockPos s = findSite(a, 5, 5, 3, 6);
            if (s != null) {
                site = s;
                start(Job.END_PORTAL);
                return true;
            }
        }
        if (!bases.hasProject(dim, "nether_portal", a, 48) && bases.nearestPortal(dim, Vec3.atCenterOf(a), 48) == null && !tooMany(Job.NETHER_PORTAL)) {
            BlockPos s = findSite(a, 4, 3, 6, 4);
            if (s != null) {
                site = s.offset(0, 0, 1);
                start(Job.NETHER_PORTAL);
                return true;
            }
        }
        List<Animal> animals = level().getEntitiesOfClass(Animal.class, new AABB(a).inflate(16), Animal::isAlive);
        if (animals.size() < 6 && !bases.hasProject(dim, "mobs", a, 32) && !tooMany(Job.MOBS)) {
            site = a;
            start(Job.MOBS);
            return true;
        }
        if (level().getEntitiesOfClass(IronGolem.class, new AABB(a).inflate(32)).isEmpty() && !bases.hasProject(dim, "golem", a, 32) && !tooMany(Job.GOLEM)) {
            BlockPos s = findSite(a, 3, 1, 4, 2);
            if (s != null) {
                site = s;
                start(Job.GOLEM);
                return true;
            }
        }
        lastBuild = now;
        return false;
    }

    private boolean farmlandNear(BlockPos a, int r) {
        for (BlockPos p : BlockPos.betweenClosed(a.offset(-r, -3, -r), a.offset(r, 3, r))) {
            if (level().getBlockState(p).is(Blocks.FARMLAND)) {
                return true;
            }
        }
        return false;
    }

    /** A project that keeps failing here is left alone. */
    private boolean tooMany(Job j) {
        return attempts.getOrDefault(j, 0) >= 3;
    }

    private void finishProject(String kind, BlockPos at) {
        attempts.remove(job);
        Bases.get(self.getServer()).addProject(level().dimension(), kind, at);
        lastBuild = level().getGameTime();
    }

    /** Enchanting table in the middle of a 5x5, fifteen bookshelves around it (one gap to walk in). */
    private boolean enchantTick() {
        if (cells.isEmpty()) {
            for (int x = 0; x < 5; x++) {
                for (int z = 0; z < 5; z++) {
                    boolean ring = x == 0 || x == 4 || z == 0 || z == 4;
                    if (ring && !(x == 2 && z == 4)) {
                        cells.add(site.offset(x, 0, z));
                    }
                }
            }
            cells.add(site.offset(2, 0, 2));
        }
        if (idx >= cells.size()) {
            enchantRooms++;
            finishProject("enchanting", site.offset(2, 0, 2));
            note("enchanting room at " + site.offset(2, 0, 2).toShortString());
            return false;
        }
        BlockPos p = cells.get(idx);
        boolean table = idx == cells.size() - 1;
        if (place(p, table ? Items.ENCHANTING_TABLE : Items.BOOKSHELF, table ? Blocks.ENCHANTING_TABLE : Blocks.BOOKSHELF)) {
            idx++;
            tries = 0;
        } else if (++tries > 80) {
            idx++;
            tries = 0;
        }
        return true;
    }

    /** An obsidian frame 4 wide, 5 high, lit with flint and steel. */
    private boolean netherPortalTick() {
        if (cells.isEmpty()) {
            for (int x = 0; x < 4; x++) {
                cells.add(site.offset(x, 0, 0)); // the bottom row first, then up the sides, then the top
            }
            for (int y = 1; y < 5; y++) {
                cells.add(site.offset(0, y, 0));
                cells.add(site.offset(3, y, 0));
            }
            cells.add(site.offset(1, 4, 0));
            cells.add(site.offset(2, 4, 0));
        }
        if (idx < cells.size()) {
            BlockPos p = cells.get(idx);
            if (place(p, Items.OBSIDIAN, Blocks.OBSIDIAN)) {
                idx++;
                tries = 0;
            } else if (++tries > 80) {
                return false;
            }
            return true;
        }
        BlockPos inside = site.offset(1, 1, 0);
        if (level().getBlockState(inside).is(Blocks.NETHER_PORTAL)) {
            portalsBuilt++;
            Bases.get(self.getServer()).addPortal(level().dimension(), inside);
            finishProject("nether_portal", inside);
            note("portal lit at " + inside.toShortString());
            return false;
        }
        if (reach(Vec3.atCenterOf(inside)) && hold(Items.FLINT_AND_STEEL)) {
            lookNow(new Vec3(inside.getX() + 0.5, inside.getY(), inside.getZ() + 0.5));
            motor.useOnFace(inside.below(), Direction.UP);
        }
        return ++tries < 200;
    }

    /** Twelve end portal frames around a 3x3 (placed from the middle looking out: they face in), then the eyes. */
    private boolean endPortalTick() {
        if (cells.isEmpty()) {
            for (int i = 1; i <= 3; i++) {
                cells.add(site.offset(i, 0, 0));
                cells.add(site.offset(i, 0, 4));
                cells.add(site.offset(0, 0, i));
                cells.add(site.offset(4, 0, i));
            }
        }
        Vec3 middle = Vec3.atBottomCenterOf(site.offset(2, 0, 2));
        if (stage == 0) {
            if (idx >= cells.size()) {
                stage = 1;
                idx = 0;
                return true;
            }
            BlockPos p = cells.get(idx);
            if (level().getBlockState(p).is(Blocks.END_PORTAL_FRAME)) {
                idx++;
                tries = 0;
                return true;
            }
            // from over the middle, facing out towards each frame: it is placed facing us, the middle
            if (self.position().distanceTo(middle.add(0, 1.0, 0)) > 0.6) {
                motor.fly(middle.add(0, 1.0, 0));
                if (++tries > 300) {
                    note("could not get over the middle from " + self.blockPosition().toShortString());
                    return false;
                }
                return true;
            }
            motor.fly(middle.add(0, 1.0, 0));
            if (hold(Items.END_PORTAL_FRAME)) {
                Direction out = Direction.getNearest(p.getX() - site.getX() - 2, 0, p.getZ() - site.getZ() - 2);
                self.setYRot(out.toYRot());
                self.setYHeadRot(out.toYRot());
                self.setXRot(60f);
                motor.useOnTopFace(p.below());
            }
            if (++tries > 400) {
                note("frames would not go down");
                return false;
            }
            return true;
        }
        if (stage == 1) {
            // up out of the ring before the eyes go in (the portal opens under whoever stands there)
            if (flyTo(middle.add(0, 3, 0), 0.8)) {
                stage = 2;
            }
            return ticks < 600;
        }
        if (idx >= cells.size()) {
            if (level().getBlockState(site.offset(2, 0, 2)).is(Blocks.END_PORTAL)) {
                endPortals++;
                note("end portal open");
            }
            finishProject("end_portal", site.offset(2, 0, 2));
            return false;
        }
        BlockPos p = cells.get(idx);
        BlockState st = level().getBlockState(p);
        if (!st.is(Blocks.END_PORTAL_FRAME) || st.getValue(EndPortalFrameBlock.HAS_EYE)) {
            idx++;
            return true;
        }
        Vec3 hover = Vec3.atBottomCenterOf(p).add(0, 2.5, 0); // right over the frame, never over the middle
        if (flyTo(hover, 0.5) && hold(Items.ENDER_EYE)) {
            lookNow(Vec3.atCenterOf(p).add(0, 0.4, 0));
            motor.useOnFace(p, Direction.UP);
        }
        return ticks < 1400;
    }

    /** A 5x5 field: the middle dug out for water, the rest dirt (where it is not), tilled and sown. */
    private boolean farmTick() {
        BlockPos water = site.offset(2, -1, 2);
        if (stage == 0) {
            if (level().getFluidState(water).isSource()) {
                stage = 1;
                idx = 0;
                return true;
            }
            if (!reach(Vec3.atCenterOf(water))) {
                return ticks < 300;
            }
            if (!level().getBlockState(water).isAir() && !level().getBlockState(water).is(Blocks.WATER)) {
                breakBlock(water); // the ground broken open for the water
                return true;
            }
            if (hold(Items.WATER_BUCKET)) {
                lookNow(new Vec3(water.getX() + 0.5, water.getY() + 0.02, water.getZ() + 0.5));
                motor.useHeldItem(InteractionHand.MAIN_HAND);
            }
            return ++tries < 200;
        }
        if (cells.isEmpty()) {
            for (int x = 0; x < 5; x++) {
                for (int z = 0; z < 5; z++) {
                    if (x != 2 || z != 2) {
                        cells.add(site.offset(x, -1, z));
                    }
                }
            }
        }
        if (idx >= cells.size()) {
            int tilled = 0;
            for (BlockPos c : cells) {
                tilled += level().getBlockState(c).is(Blocks.FARMLAND) ? 1 : 0;
            }
            if (tilled >= 8) {
                farmsMade++;
            }
            finishProject("farm", site.offset(2, 0, 2));
            note("farm with " + tilled + " farmland");
            return false;
        }
        BlockPos g = cells.get(idx);
        BlockState st = level().getBlockState(g);
        if (!reach(Vec3.atCenterOf(g))) {
            return ticks < 1500;
        }
        if (++tries > 40) {
            idx++;
            tries = 0;
            return true;
        }
        if (st.is(Blocks.FARMLAND)) {
            if (level().getBlockState(g.above()).isAir()) {
                if (hold(Items.WHEAT_SEEDS)) {
                    useOnTop(g);
                }
            } else {
                idx++;
                tries = 0;
            }
            return true;
        }
        if (st.is(Blocks.DIRT) || st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.DIRT_PATH) || st.is(Blocks.COARSE_DIRT)) {
            if (!level().getBlockState(g.above()).isAir()) {
                breakBlock(g.above());
                return true;
            }
            if (hold(Items.DIAMOND_HOE)) {
                useOnTop(g);
            }
            return true;
        }
        if (!st.isAir()) {
            breakBlock(g); // stone and the like: broken out for dirt
            return true;
        }
        if (hold(Items.DIRT)) {
            motor.placeBlockAt(g);
        }
        return true;
    }

    private static final EntityType<?>[] USEFUL = {EntityType.COW, EntityType.SHEEP, EntityType.PIG, EntityType.CHICKEN, EntityType.COW,
            EntityType.SHEEP, EntityType.WOLF, EntityType.VILLAGER};

    /** Farm animals (and a wolf, a villager) let out of spawn eggs around the base. */
    private boolean mobsTick() {
        if (idx >= 6) {
            finishProject("mobs", site);
            return false;
        }
        EntityType<?> type = USEFUL[(idx + self.getRandom().nextInt(2)) % USEFUL.length];
        SpawnEggItem egg = SpawnEggItem.byId(type);
        if (egg == null) {
            idx++;
            return true;
        }
        BlockPos g = groundUnder(Vec3.atBottomCenterOf(site).add(self.getRandom().nextInt(7) - 3, 1, self.getRandom().nextInt(7) - 3));
        if (g == null || occupied(g)) {
            return ++tries < 60;
        }
        if (!reach(Vec3.atCenterOf(g))) {
            return ticks < 600;
        }
        if (hold(egg)) {
            int before = level().getEntitiesOfClass(Mob.class, new AABB(g).inflate(3)).size();
            useOnTop(g.below());
            if (level().getEntitiesOfClass(Mob.class, new AABB(g).inflate(3)).size() > before) {
                mobsSpawned++;
                idx++;
            } else if (++tries > 30) {
                idx++;
            }
        }
        return true;
    }

    /** Four iron blocks in a T and a carved pumpkin on top: an iron golem. */
    private boolean golemTick() {
        if (cells.isEmpty()) {
            cells.add(site.offset(1, 0, 0));
            cells.add(site.offset(1, 1, 0));
            cells.add(site.offset(0, 1, 0));
            cells.add(site.offset(2, 1, 0));
            startHealth = level().getEntitiesOfClass(IronGolem.class, new AABB(site).inflate(6)).size();
        }
        if (idx < cells.size()) {
            if (place(cells.get(idx), Items.IRON_BLOCK, Blocks.IRON_BLOCK)) {
                idx++;
                tries = 0;
            } else if (++tries > 80) {
                return false;
            }
            return true;
        }
        if (level().getEntitiesOfClass(IronGolem.class, new AABB(site).inflate(6)).size() > startHealth) {
            golemsBuilt++;
            finishProject("golem", site);
            note("iron golem made");
            return false;
        }
        BlockPos head = site.offset(1, 2, 0);
        if (reach(Vec3.atCenterOf(head)) && hold(Items.CARVED_PUMPKIN)) {
            if (self.getBoundingBox().intersects(new AABB(head))) {
                motor.fly(self.position().add(0, 0, 2));
            } else {
                motor.placeBlockAt(head);
            }
        }
        return ++tries < 200;
    }

    /** No longer creative (or gone): drop whatever was going on. */
    public void reset() {
        job = Job.NONE;
        motor.land();
    }

    /** The blast spot of the last crowd job (tests). */
    public Vec3 spot() {
        return spot;
    }

    public BlockPos site() {
        return site;
    }
}
