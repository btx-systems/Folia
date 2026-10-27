package sh.btx.nzsmp.folia.generation;

import net.kyori.adventure.key.Key;
import org.bukkit.block.data.BlockData;


/**
 * One absolute section Y, with 4096 block entries and 64 biome entries.
 *
 * @param sectionY absolute section Y coordinate
 * @param blocks   block-state palette
 * @param biomes   biome keys or palettes
 */
public record ChunkSectionData(int sectionY, PackedPalette<BlockData> blocks, PackedPalette<Key> biomes) {
}
