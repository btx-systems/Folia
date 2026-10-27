package sh.btx.nzsmp.folia.generation;

import net.kyori.adventure.key.Key;


/**
 * Immutable world context; heights use an inclusive minimum and exclusive maximum.
 *
 * @param worldKey     world key
 * @param dimensionKey dimension key
 * @param seed         world seed
 * @param minHeight    inclusive minimum block Y
 * @param maxHeight    exclusive maximum block Y
 */
public record GenerationWorld(Key worldKey, Key dimensionKey, long seed, int minHeight, int maxHeight) {
}
