package io.nzsmp.folia.storage;

import java.util.List;
import java.util.Objects;
import java.util.Optional;


/**
 * A page of logical keys; empty nextCursor marks the end.
 *
 * @param <K>        logical key type
 * @param keys       logical record keys
 * @param nextCursor continuation cursor or empty at the end
 */
public record RecordPage<K>(List<K> keys, Optional<String> nextCursor) {

    /**
     * Creates and validates this value.
     *
     * @param keys       logical record keys
     * @param nextCursor continuation cursor or empty at the end
     */
    public RecordPage {
        keys = List.copyOf(keys);
        Objects.requireNonNull(nextCursor, "nextCursor");
    }
}
