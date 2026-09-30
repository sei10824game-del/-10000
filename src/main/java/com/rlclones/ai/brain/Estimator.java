package com.rlclones.ai.brain;

import net.minecraft.nbt.CompoundTag;

/**
 * Online estimator of one learned quantity (attack range, cooldown, damage...).
 * MEAN tracks the running average, UPPER tracks an upper envelope (e.g. the furthest distance an enemy
 * has been seen hitting from) and LOWER a lower envelope (e.g. the shortest interval between two attacks).
 */
public final class Estimator {
    public enum Mode {MEAN, UPPER, LOWER}

    private final Mode mode;
    private final double prior;
    private double value;
    private int count;

    public Estimator(Mode mode, double prior) {
        this.mode = mode;
        this.prior = prior;
        this.value = prior;
    }

    public static Estimator mean(double prior) {
        return new Estimator(Mode.MEAN, prior);
    }

    public static Estimator upper(double prior) {
        return new Estimator(Mode.UPPER, prior);
    }

    public static Estimator lower(double prior) {
        return new Estimator(Mode.LOWER, prior);
    }

    public void add(double x) {
        if (!Double.isFinite(x)) {
            return;
        }
        if (count == 0) {
            value = x;
        } else {
            switch (mode) {
                case MEAN -> value += (x - value) * Math.max(0.05, 1.0 / (count + 1));
                case UPPER -> value += (x - value) * (x > value ? 0.5 : 0.03);
                case LOWER -> value += (x - value) * (x < value ? 0.5 : 0.03);
            }
        }
        if (count < Integer.MAX_VALUE) {
            count++;
        }
    }

    public double get() {
        return count > 0 ? value : prior;
    }

    public double getOr(double fallback) {
        return count > 0 ? value : fallback;
    }

    public boolean known() {
        return count > 0;
    }

    public int count() {
        return count;
    }

    public void merge(Estimator other) {
        if (other.count == 0) {
            return;
        }
        if (count == 0) {
            value = other.value;
            count = other.count;
            return;
        }
        double total = (double) count + other.count;
        value = (value * count + other.value * other.count) / total;
        count = (int) Math.min(Integer.MAX_VALUE, total);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("v", value);
        tag.putInt("n", count);
        return tag;
    }

    public void load(CompoundTag tag) {
        count = tag.getInt("n");
        value = count > 0 ? tag.getDouble("v") : prior;
    }
}
