package sh.btx.nzsmp.folia.generation;

/**
 * Owned generation result. Implementations contain no live server objects.
 */
public sealed interface GeneratedChunk permits SectionChunkData, EncodedSectionChunkData, SerializedChunkData {
}
