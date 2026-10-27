package sh.btx.nzsmp.folia.storage;

import java.util.Optional;
import net.kyori.adventure.key.Key;


/**
 * Stable configured world ID. Empty dimension identifies world-root metadata and assets.
 *
 * @param worldId   configured logical world identifier
 * @param dimension dimension key, or empty for the world root
 */
public record WorldStorageContext(String worldId, Optional<Key> dimension) {
}
