package sh.btx.nzsmp.folia.storage;

import java.util.Objects;
import sh.btx.nzsmp.folia.data.BinaryData;


/**
 * Canonical server data; providers may encode it differently in their own storage.
 *
 * @param metadata record format and version information
 * @param payload  canonical record contents
 */
public record StorageRecord(RecordMetadata metadata, BinaryData payload) {

    /**
     * Creates and validates this value.
     *
     * @param metadata record format and version information
     * @param payload  canonical record contents
     */
    public StorageRecord {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(payload, "payload");
    }
}
