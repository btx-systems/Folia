package sh.btx.nzsmp.folia.storage;

import java.util.Objects;
import java.util.UUID;


/**
 * Player identity is independent of the current dimension.
 *
 * @param playerId player UUID
 * @param kind     record category
 */
public record PlayerRecordKey(UUID playerId, Kind kind) {

    /**
     * Independently persisted records belonging to one player.
     */
    public enum Kind {

        /**
         * Player NBT, including inventory, position and persistent data.
         */
        PLAYER_DATA,

        /**
         * Player statistics as UTF-8 JSON.
         */
        STATISTICS,

        /**
         * Player advancement progress as UTF-8 JSON.
         */
        ADVANCEMENTS
    }

    /**
     * Creates and validates this value.
     *
     * @param playerId player UUID
     * @param kind     record category
     */
    public PlayerRecordKey {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(kind, "kind");
    }
}
