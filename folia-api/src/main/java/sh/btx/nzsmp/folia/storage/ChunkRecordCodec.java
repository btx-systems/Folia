package sh.btx.nzsmp.folia.storage;

/**
 * Lossless access to a dimension's canonical chunk records. This is CPU work;
 * call it on a storage worker, never a tick thread. Unknown metadata remains in
 * the original record, allowing providers to compress or delta-code just sections.
 */
public interface ChunkRecordCodec {
    /**
     * Decodes detached section palettes while retaining the original record.
     *
     * @param record canonical chunk NBT
     * @return a detached snapshot with owned packed arrays
     */
    ChunkRecordSnapshot decode(StorageRecord record);

    /**
     * Reconstructs canonical chunk NBT, preserving fields outside section terrain.
     * The snapshot's palette storage is consumed once.
     *
     * @param snapshot detached section data and original metadata
     * @param policy   whether reconstructed terrain is identical to the saved terrain
     * @return the reconstructed canonical record
     */
    StorageRecord encode(ChunkRecordSnapshot snapshot, DerivedDataPolicy policy);

    /** Controls reuse of lighting and heightmaps after replacing sections. */
    enum DerivedDataPolicy {
        /** Rebuild derived data; use when changing terrain rather than reconstructing it. */
        RECOMPUTE,
        /** Preserve saved derived data; only valid for exact terrain reconstruction. */
        PRESERVE
    }
}
