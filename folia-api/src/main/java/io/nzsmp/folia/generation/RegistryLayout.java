package io.nzsmp.folia.generation;

import java.util.List;
import net.kyori.adventure.key.Key;


/**
 * Runtime numeric-ID mappings. Array positions are IDs. Obtain this from a request,
 * not from hard-coded tables. Biome mappings can change with datapacks.
 *
 * @param sectionEncoding network section compatibility descriptor
 * @param blockStates     block-state strings indexed by runtime ID
 * @param biomes          biome keys or palettes
 */
public record RegistryLayout(SectionEncoding sectionEncoding, List<String> blockStates, List<Key> biomes) {
    
    /**
     * Validates and constructs this value.
     *
     * @param sectionEncoding network section compatibility descriptor
     * @param blockStates     block-state strings indexed by runtime ID
     * @param biomes          biome keys or palettes
     */
    public RegistryLayout {
        blockStates = List.copyOf(blockStates);
        biomes = List.copyOf(biomes);
    }
}
