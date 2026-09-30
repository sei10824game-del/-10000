package com.rlclones.ai.combat;

import java.util.Locale;

/**
 * Discrete combat situation, always expressed relative to what was learned about the enemy type:
 * distance is binned against the learned enemy reach, enemy readiness against the learned attack cooldown.
 */
public final class CombatState {
    public static final int DIST = 5;   // 0 deep inside enemy reach, 1 at enemy reach, 2 only I can hit, 3 near, 4 far
    public static final int OWN = 3;    // own attack charge: low / mid / full
    public static final int ENEMY = 3;  // enemy attack: just used / recharging / ready
    public static final int HP = 3;     // own health: low / mid / high
    public static final int WINDUP = 2; // enemy is charging something (creeper fuse, bow draw)
    public static final int CROWD = 3;  // hostiles around me: 1 / 2-3 / 4+
    public static final int ELEV = 3;   // enemy below / level / above
    public static final int SIZE = DIST * OWN * ENEMY * HP * WINDUP * CROWD * ELEV;

    public static final String[] DIST_NAMES = {"deep", "enemy_reach", "my_reach", "near", "far"};

    private CombatState() {
    }

    public static int encode(int dist, int own, int enemy, int hp, int windup, int crowd, int elev) {
        int k = clamp(dist, DIST);
        k = k * OWN + clamp(own, OWN);
        k = k * ENEMY + clamp(enemy, ENEMY);
        k = k * HP + clamp(hp, HP);
        k = k * WINDUP + clamp(windup, WINDUP);
        k = k * CROWD + clamp(crowd, CROWD);
        k = k * ELEV + clamp(elev, ELEV);
        return k;
    }

    /** Returns {dist, own, enemy, hp, windup, crowd, elev}. */
    public static int[] decode(int key) {
        int[] v = new int[7];
        v[6] = key % ELEV;
        key /= ELEV;
        v[5] = key % CROWD;
        key /= CROWD;
        v[4] = key % WINDUP;
        key /= WINDUP;
        v[3] = key % HP;
        key /= HP;
        v[2] = key % ENEMY;
        key /= ENEMY;
        v[1] = key % OWN;
        key /= OWN;
        v[0] = key;
        return v;
    }

    private static int clamp(int v, int size) {
        return Math.max(0, Math.min(size - 1, v));
    }

    public static String describe(int key) {
        int[] v = decode(key);
        return String.format(Locale.ROOT, "dist=%s own=%d enemy=%d hp=%d windup=%d crowd=%d elev=%d",
                DIST_NAMES[v[0]], v[1], v[2], v[3], v[4], v[5], v[6]);
    }

    /**
     * Initial Q values: weak common-sense priors so a brand-new clone is not completely helpless.
     * They are small compared to real rewards, so experience quickly overrides them.
     */
    public static void prior(int key, boolean explosive, boolean ranged, float[] q) {
        int[] v = decode(key);
        int dist = v[0];
        int own = v[1];
        int enemy = v[2];
        int hp = v[3];
        boolean windup = v[4] == 1;
        int crowd = v[5];
        boolean inMyReach = dist <= 2;
        boolean far = dist >= 3;

        q[CombatAction.ATTACK.ordinal()] = inMyReach ? (own == 2 ? 1.0f : own == 1 ? 0.3f : -0.2f) : -0.3f;
        q[CombatAction.CRIT_ATTACK.ordinal()] = inMyReach && own == 2 ? 0.8f : (dist == 3 ? 0.1f : -0.2f);
        q[CombatAction.SPRINT_ATTACK.ordinal()] = own == 2 ? (dist == 3 ? 0.6f : inMyReach ? 0.5f : 0f) : -0.1f;
        q[CombatAction.APPROACH.ordinal()] = far ? 0.5f : -0.1f;
        q[CombatAction.RETREAT.ordinal()] = dist <= 1 && own < 2 ? 0.4f : (far ? -0.2f : 0f);
        q[CombatAction.STRAFE_LEFT.ordinal()] = ranged && (windup || far) ? 0.3f : 0f;
        q[CombatAction.STRAFE_RIGHT.ordinal()] = ranged && (windup || far) ? 0.3f : 0f;
        // Raise the shield when the enemy is able to hit us and we cannot answer yet, or against charged shots.
        q[CombatAction.BLOCK.ordinal()] = ranged && windup ? 1.0f : (dist <= 1 && enemy == 2 ? (own < 2 ? 1.2f : 0.6f) : (dist <= 1 ? 0.2f : 0f));
        q[CombatAction.HOLD.ordinal()] = dist == 2 && own < 2 ? 0.3f : -0.05f;
        q[CombatAction.SHOOT.ordinal()] = far ? (explosive ? 0.8f : 0.4f) : -0.3f;
        q[CombatAction.USE_ITEM.ordinal()] = own < 2 ? 0.25f : 0.1f;
        q[CombatAction.PILLAR.ordinal()] = crowd >= 1 && hp == 0 ? 0.3f : -0.1f;

        if (hp == 0) {
            q[CombatAction.APPROACH.ordinal()] -= 0.3f;
            q[CombatAction.RETREAT.ordinal()] += 0.4f;
        }
        if (explosive && windup) {
            q[CombatAction.APPROACH.ordinal()] = -1.5f;
            q[CombatAction.ATTACK.ordinal()] -= 0.5f;
            q[CombatAction.RETREAT.ordinal()] = dist <= 3 ? 1.5f : 0.2f;
        }
    }
}
