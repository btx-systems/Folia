package io.nzsmp.folia.storage;

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
     * Logical Kind contract.
     */
    public enum Kind {

        /**
         * player data category.
         */
        PLAYER_DATA,

        /**
         * statistics category.
         */
        STATISTICS,

        /**
         * advancements category.
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
