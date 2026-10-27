package io.nzsmp.folia.generation;

/**
 * Source of biomes for a result.
 */
public enum BiomePolicy {
    
    /**
     * Use supplied palettes or encoded biome containers.
     */
    PROVIDED,
    
    /**
     * Sample the world's configured biome source; discard supplied biome data.
     */
    SERVER_GENERATED
}
