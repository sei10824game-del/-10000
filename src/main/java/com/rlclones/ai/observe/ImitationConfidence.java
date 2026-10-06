package com.rlclones.ai.observe;

/** Outcome-aware multiplier for observational learning; poor demonstrations remain learnable, but count less. */
public final class ImitationConfidence {
    private ImitationConfidence() {
    }

    /**
     * Confidence uses the later result of the observed interval, not just whether an action could be inferred.
     * It combines reward, health/material progress, survival and expedition return outcome, then bounds the
     * multiplier so neither one lucky event nor one failure can dominate a clone's own experience.
     */
    public static float weight(float baseWeight, float reward, float healthDelta, float materialDelta,
                               boolean survived, boolean returnedSafely, boolean failedReturn) {
        if (!Float.isFinite(baseWeight) || baseWeight <= 0f) {
            return 0f;
        }
        float quality = 0.65f
                + 0.35f * unit(reward / 20f)
                + 0.30f * unit(healthDelta / 8f)
                + 0.20f * unit(materialDelta / 5f)
                + (survived ? 0.12f : -0.45f)
                + (returnedSafely ? 0.25f : 0f)
                - (failedReturn ? 0.22f : 0f);
        float confidence = Math.max(0.2f, Math.min(1.45f, quality));
        return baseWeight * confidence;
    }

    private static float unit(float value) {
        if (!Float.isFinite(value)) {
            return 0f;
        }
        return Math.max(-1f, Math.min(1f, value));
    }
}
