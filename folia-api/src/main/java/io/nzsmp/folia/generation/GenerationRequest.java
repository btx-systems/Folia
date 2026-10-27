package io.nzsmp.folia.generation;

/**
 * Coordinates are chunk coordinates, never block coordinates.
 *
 * @param world      immutable world information
 * @param chunkX     chunk X coordinate
 * @param chunkZ     chunk Z coordinate
 * @param registries runtime numeric registry layout
 * @param control    cancellation and priority control
 */
public record GenerationRequest(GenerationWorld world, int chunkX, int chunkZ,
                                RegistryLayout registries, RequestControl control) {
}
