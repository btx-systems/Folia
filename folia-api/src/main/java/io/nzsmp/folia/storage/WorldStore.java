package io.nzsmp.folia.storage;

/**
 * Storage for the root world metadata or one dimension.
 */
public interface WorldStore extends RecordStore<WorldRecordKey> {
    /**
     * Supplies dimension-specific codec helpers before its first chunk I/O.
     * Called once during world startup; return promptly. Root metadata stores do
     * not receive this callback. The codec exposes detached records, never live chunks.
     *
     * @param codec lossless chunk-record codec
     */
    default void onWorldReady(final ChunkRecordCodec codec) {
    }
}
