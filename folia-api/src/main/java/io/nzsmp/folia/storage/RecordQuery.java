package io.nzsmp.folia.storage;

import java.util.Optional;


/**
 * Paginated enumeration. Cursors are opaque and returned by the provider.
 *
 * @param cursor opaque continuation cursor
 * @param limit  maximum number of keys to return
 */
public record RecordQuery(Optional<String> cursor, int limit) {

    /**
     * Creates and validates this value.
     *
     * @param cursor opaque continuation cursor
     * @param limit  maximum number of keys to return
     */
    public RecordQuery {
        if (cursor == null || limit < 1 || limit > 10000) {
            throw new IllegalArgumentException("Invalid record query");
        }
    }
}
