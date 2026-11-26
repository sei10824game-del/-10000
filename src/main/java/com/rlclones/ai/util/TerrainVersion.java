package com.rlclones.ai.util;
/** Attached to each palette. Versions follow the actual block storage, including direct palette edits. */
public interface TerrainVersion {
    long rlclones$terrainVersion();
    void rlclones$watchTerrain();
}
