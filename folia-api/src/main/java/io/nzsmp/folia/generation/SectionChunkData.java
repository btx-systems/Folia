package io.nzsmp.folia.generation;

import java.util.List;
import java.util.Objects;


/**
 * Complete terrain. Omitted sections are air; their biomes come from the world's biome source.
 *
 * @param sections    owned section payloads
 * @param biomePolicy source of biome data
 * @param extras      optional block entities and heightmaps
 */
public record SectionChunkData(List<ChunkSectionData> sections, BiomePolicy biomePolicy, ChunkExtras extras)
    implements GeneratedChunk {
    
    /**
     * Validates and constructs this value.
     *
     * @param sections    owned section payloads
     * @param biomePolicy source of biome data
     * @param extras      optional block entities and heightmaps
     */
    public SectionChunkData {
        sections = List.copyOf(sections);
        Objects.requireNonNull(biomePolicy, "biomePolicy");
        Objects.requireNonNull(extras, "extras");
    }
}
