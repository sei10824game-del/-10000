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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

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
        this.watcher = new AgentWatcher(self, perception, self::getBrain, this::sinceEnemyAttack);
        enemyLastAttack.defaultReturnValue(Long.MIN_VALUE);
        enemyLastShot.defaultReturnValue(Long.MIN_VALUE);
    }

    private Brain brain() {
        return self.getBrain();
    }

    public Perception perception() {
        return perception;
    }

    public Motor motor() {
        return motor;
    }

    public AgentWatcher watcher() {
        return watcher;
    }

    public Option option() {
        return option;
    }

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
        long now = now();
        trackRewards();
        if (((now + self.getId()) & 3) == 0) {
            perception.update(now);
            observeWorld(now);
            watcher.update(now);
        }
        if ((now + self.getId()) % 40 == 0 && !self.isUsingItem() && option != Option.GATHER_WOOD && option != Option.MINE
                && (action == null || action == CombatAction.APPROACH || action == CombatAction.HOLD)) {
            Equipment.manage(self, true);
        }
        runStrategy(now);
        if (lookBackTicks > 0) {
            lookBackTicks--;
            if (!motor.hasLookIntent() && lookBack != null) {
                motor.lookAt(lookBack);
            }
        }
        motor.tick();
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

    public void onBlockBroken(BlockState state) {
        Perception.BlockKind kind = Perception.classify(state);
        if (kind == Perception.BlockKind.LOG) {
            optionReward += 0.5f;
        } else if (kind == Perception.BlockKind.ORE) {
            optionReward += 0.8f;
        }
    }

    public void onShieldBlock(float blocked) {
        stepReward += blocked * 0.5f;
    }

    public void onDeath(DamageSource source) {
        if (option != null) {
            finishOption(true);
        }
        brain().deaths++;
        motor.resetMining();
    }

    // ------------------------------------------------------------------ strategy layer

    private void runStrategy(long now) {
        if (option == null) {
            startOption(now);
            if (option == null) {
                return;
            }
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
        };
        optionTicks++;
        optionReward -= 0.005f;
        if (option != null && (done || optionTicks >= option.maxTicks || shouldInterrupt(now))) {
            finishOption(false);
        }
    }

    private void startOption(long now) {
        int s = Senses.strategyState(perception, self, self, brain(), now);
        int mask = Senses.strategyMask(perception, self, self, now);
        int o = brain().chooseStrategy(s, mask);
        if (o < 0) {
            return;
        }
        option = Option.VALUES[o];
        optionState = s;
        optionTicks = 0;
        optionReward = 0;
        goal = null;
        goalTimer = 0;
        calmTicks = 0;
        blockTarget = null;
        blockTicks = 0;
        blocksDone = 0;
        eatStarted = false;
        closeTicks = 0;
    }

    private void finishOption(boolean died) {
        if (option == null) {
            return;
        }
        if (option == Option.FIGHT || option == Option.HUNT) {
            closeCombatStep(died, died);
            target = null;
        }
        long now = now();
        if (died) {
            optionReward -= 30f;
        }
        int s2 = died ? 0 : Senses.strategyState(perception, self, self, brain(), now);
        int mask2 = died ? 0 : Senses.strategyMask(perception, self, self, now);
        float gamma = (float) Math.pow(0.995, Math.max(1, optionTicks));
        brain().learn(Brain.STRATEGY, optionState, option.ordinal(), optionReward, s2, died, gamma, mask2, 1f, false);
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

    private boolean validTarget(Entity e, boolean hunt, long now) {
        if (e == null || !e.isAlive() || e.isRemoved() || e.level() != self.level() || e.distanceTo(self) > 32) {
            return false;
        }
        Perception.Seen s = perception.get(e);
        if (s == null || now - s.lastSeen > 60) {
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
        }
        if (target == null) {
            Perception.Seen animal = hunt ? Senses.nearestAnimal(perception, self, now, 24) : null;
            target = hunt ? (animal == null ? null : animal.entity) : pickHostile(now);
            if (target == null) {
                return true;
            }
            targetType = Perception.typeId(target);
            combatState = -1;
            action = null;
            if (!hunt) {
                brain().knowledge(targetType).encounters++;
            }
        }
        combatTick();
        return false;
    }

    private void combatTick() {
        if (action == null || actionDone || actionTicks >= action.duration) {
            EnemyKnowledge k = brain().knowledge(targetType);
            int s = Senses.combatState(self, target, k, sinceEnemyAttack(target), Senses.crowd(perception, self));
            int mask = Senses.combatMask(self);
            if (action != null && combatState >= 0) {
                brain().learn(targetType, combatState, action.ordinal(), stepReward, s, false,
                        (float) Config.get(Config.DISCOUNT, 0.9), mask, 1f, false);
            }
            CombatAction prev = action;
            int a = brain().chooseCombat(targetType, s, mask);
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
        if (!died && self.isUsingItem() && (action == CombatAction.BLOCK || action == CombatAction.SHOOT)) {
            if (action == CombatAction.SHOOT) {
                self.releaseUsingItem();
            } else {
                self.stopUsingItem();
            }
        }
        if (!died && (action == CombatAction.SHOOT || action == CombatAction.PILLAR)) {
            Equipment.manage(self, true);
        }
        action = null;
        combatState = -1;
        stepReward = 0;
    }

    private void beginAction(CombatAction prev) {
        if (prev == CombatAction.BLOCK && action != CombatAction.BLOCK && self.isUsingItem()) {
            self.stopUsingItem();
        }
        if (prev == CombatAction.SHOOT && action != CombatAction.SHOOT && self.isUsingItem()) {
            self.releaseUsingItem();
        }
        if ((prev == CombatAction.SHOOT || prev == CombatAction.PILLAR) && action != prev) {
            Equipment.manage(self, true);
        }
        switch (action) {
            case PILLAR -> {
                pillarBase = self.blockPosition();
                pillarPlaced = false;
                Equipment.select(self, Equipment.pillarBlockSlot(self));
            }
            case SHOOT -> Equipment.select(self, Equipment.bowSlot(self));
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
                    motor.attack(t);
                    actionDone = true;
                } else if (!motor.withinReach(t)) {
                    motor.moveToward(t.position());
                }
            }
            case CRIT_ATTACK -> {
                motor.lookAt(t);
                if (actionTicks == 0 && self.onGround()) {
                    motor.jump();
                }
                if (!motor.withinReach(t)) {
                    motor.moveToward(t.position());
                }
                if (!self.onGround() && self.getDeltaMovement().y < 0 && motor.canHit(t)) {
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
                if (!self.isUsingItem()) {
                    if (!Equipment.hasShield(self) || !motor.useHeldItem(InteractionHand.OFF_HAND)) {
                        actionDone = true;
                    }
                }
            }
            case HOLD -> motor.lookAt(t);
            case SHOOT -> shoot(t, gap);
            case PILLAR -> pillar();
        }
    }

    private void shoot(Entity t, double gap) {
        if (!(self.getMainHandItem().getItem() instanceof BowItem)) {
            int bow = Equipment.bowSlot(self);
            if (bow < 0) {
                actionDone = true;
                return;
            }
            Equipment.select(self, bow);
        }
        aimBow(t);
        if (!self.isUsingItem()) {
            if (actionTicks < 3) {
                motor.useHeldItem(InteractionHand.MAIN_HAND);
            } else {
                actionDone = true;
            }
            return;
        }
        if (gap < 1.5 || (self.getTicksUsingItem() >= 20 && perception.canSee(t))) {
            self.releaseUsingItem();
            actionDone = true;
        }
    }

    private void aimBow(Entity t) {
        Vec3 eye = self.getEyePosition();
        Vec3 aim = t.getBoundingBox().getCenter();
        double dx = aim.x - eye.x;
        double dz = aim.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double flight = horiz / 3.0;
        aim = aim.add(t.getDeltaMovement().multiply(flight, 0, flight));
        dx = aim.x - eye.x;
        dz = aim.z - eye.z;
        horiz = Math.sqrt(dx * dx + dz * dz) * 1.05;
        double dy = aim.y - eye.y;
        double v = 3.0;
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
            if (ally != null && ally.pos.distanceTo(self.position()) > 6) {
                Vec3 toAlly = ally.pos.subtract(self.position());
                away = away.add(new Vec3(toAlly.x, 0, toAlly.z).normalize().scale(0.7)).normalize();
            }
            goal = self.position().add(away.scale(14));
            goalTimer = 20;
        }
        motor.navigate(goal, 1.5, true);
        return false;
    }

    private boolean runEat() {
        if (!eatStarted) {
            int slot = Equipment.bestFoodSlot(self);
            if (slot < 0) {
                return true;
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
        ItemEntity item = Senses.nearestItem(perception, self, now, 16);
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
        Perception.Seen ally = Senses.nearestAlly(perception, self, self, now, 64);
        if (ally == null) {
            return true;
        }
        double d = ally.pos.distanceTo(self.position());
        if (d > 4) {
            motor.navigate(ally.pos, 3.0, d > 10);
            closeTicks = 0;
        } else {
            motor.lookAt(ally.entity);
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
            float sweep = (phase - 42) * 12f;
            motor.lookAngles(self.getYRot() + sweep * 0.3f, 0f);
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

    private boolean runHarvest(Perception.BlockKind kind) {
        ServerLevel level = self.serverLevel();
        if (blockTarget != null && Perception.classify(level.getBlockState(blockTarget)) != kind) {
            perception.forgetBlock(blockTarget);
            blockTarget = null;
        }
        if (blockTarget == null) {
            blockTarget = Senses.nearestBlock(perception, self, kind, kind == Perception.BlockKind.LOG ? 32 : 24);
            blockTicks = 0;
            if (blockTarget == null) {
                return true;
            }
            if (Perception.classify(level.getBlockState(blockTarget)) != kind) {
                perception.forgetBlock(blockTarget);
                blockTarget = null;
                return false;
            }
            Equipment.select(self, Equipment.bestToolSlot(self, level.getBlockState(blockTarget)));
        }
        blockTicks++;
        if (blockTicks > 240) {
            perception.forgetBlock(blockTarget);
            blockTarget = null;
            return false;
        }
        Vec3 center = Vec3.atCenterOf(blockTarget);
        if (self.getEyePosition().distanceTo(center) > Motor.BLOCK_REACH - 0.3) {
            motor.navigate(center, 2.5, false);
            if (motor.stuckCount() > 3) {
                perception.forgetBlock(blockTarget);
                blockTarget = null;
            }
            return false;
        }
        if (motor.mine(blockTarget)) {
            blocksDone++;
            BlockPos done = blockTarget;
            perception.forgetBlock(done);
            blockTarget = null;
            for (BlockPos p : BlockPos.betweenClosed(done.offset(-1, -1, -1), done.offset(1, 2, 1))) {
                if (Perception.classify(level.getBlockState(p)) == kind) {
                    perception.noteBlock(p);
                }
            }
            return blocksDone >= 8;
        }
        return false;
    }

    private boolean runRest() {
        motor.stop();
        motor.lookAngles(self.getYRot() + 8f, 0f);
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
