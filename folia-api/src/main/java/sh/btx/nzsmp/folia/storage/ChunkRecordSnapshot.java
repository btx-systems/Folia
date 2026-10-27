package sh.btx.nzsmp.folia.storage;

import java.util.List;
import java.util.Objects;
import sh.btx.nzsmp.folia.generation.ChunkSectionData;

/**
 * Detached chunk sections and the lossless original record. To reconstruct a delta,
 * supply the reconstructed complete section list with the original metadata record.
 *
 * @param original original canonical record, including non-terrain metadata
 * @param sections complete terrain sections with owned palette storage
 */
public record ChunkRecordSnapshot(StorageRecord original, List<ChunkSectionData> sections) {
    /**
     * Creates a detached snapshot.
     *
     * @param original original canonical record
     * @param sections complete terrain sections
     */
    public ChunkRecordSnapshot {
        Objects.requireNonNull(original, "original");
        sections = List.copyOf(sections);
    }
}
