package io.nzsmp.folia.generation;

import java.util.List;
import java.util.Objects;


/**
 * Encoded section fast path. Section payloads may be zero-copy slices of one owned
 * response buffer. The server decodes directly to its packed storage, without NBT or
 * per-block Bukkit conversion. The exact format is described by RegistryLayout.
 *
 * @param encoding    encoding and numeric registry mapping
 * @param sections    owned section payloads
 * @param biomePolicy source of biome data
 * @param extras      optional block entities and heightmaps
 */
public record EncodedSectionChunkData(SectionEncoding encoding, List<EncodedSection> sections,
                                      BiomePolicy biomePolicy, ChunkExtras extras) implements GeneratedChunk {
    
    /**
     * Validates and constructs this value.
     *
     * @param encoding    encoding and numeric registry mapping
     * @param sections    owned section payloads
     * @param biomePolicy source of biome data
     * @param extras      optional block entities and heightmaps
     */
    public EncodedSectionChunkData {
        Objects.requireNonNull(encoding, "encoding");
        sections = List.copyOf(sections);
        Objects.requireNonNull(biomePolicy, "biomePolicy");
        Objects.requireNonNull(extras, "extras");
    }
}
