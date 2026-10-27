package io.nzsmp.folia.generation;

import io.nzsmp.folia.data.BinaryData;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.bukkit.HeightMap;


/**
 * Optional block-entity NBT and heightmaps. Each heightmap has 256 entries indexed
 * x + z*16, storing the first available Y relative to the world minimum (0..height).
 * Values must be semantically correct; bounds are checked and missing maps are calculated.
 *
 * @param blockEntities uncompressed block-entity NBT compounds
 * @param heightmaps    optional first-available-height values
 */
public record ChunkExtras(List<BinaryData> blockEntities, Map<HeightMap, List<Integer>> heightmaps) {
    
    /**
     * No supplied block entities or heightmaps.
     */
    public static final ChunkExtras EMPTY = new ChunkExtras(List.of(), Map.of());

    /**
     * Validates and constructs this value.
     *
     * @param blockEntities uncompressed block-entity NBT compounds
     * @param heightmaps    optional first-available-height values
     */
    public ChunkExtras {
        blockEntities = List.copyOf(blockEntities);
        final Map<HeightMap, List<Integer>> copy = new EnumMap<>(HeightMap.class);
        heightmaps.forEach((type, values) -> copy.put(type, List.copyOf(values)));
        heightmaps = Map.copyOf(copy);
    }
}
