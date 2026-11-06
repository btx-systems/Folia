package sh.btx.nzsmp.folia.generation;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.bukkit.HeightMap;
import sh.btx.nzsmp.folia.data.BinaryData;


/**
 * Optional block-entity NBT, heightmaps and fluid ticks. Each heightmap has 256 entries
 * indexed x + z*16, storing the first available Y relative to the world minimum
 * (0..height). Values must be semantically correct; bounds are checked and missing maps
 * are calculated.
 *
 * @param blockEntities uncompressed block-entity NBT compounds
 * @param heightmaps    optional first-available-height values
 * @param fluidTicks    positions whose fluid the server ticks once the chunk is loaded
 */
public record ChunkExtras(List<BinaryData> blockEntities, Map<HeightMap, List<Integer>> heightmaps,
                          List<FluidTick> fluidTicks) {

    /**
     * No supplied block entities, heightmaps or fluid ticks.
     */
    public static final ChunkExtras EMPTY = new ChunkExtras(List.of(), Map.of(), List.of());

    /**
     * Validates and constructs this value.
     *
     * @param blockEntities uncompressed block-entity NBT compounds
     * @param heightmaps    optional first-available-height values
     * @param fluidTicks    positions whose fluid the server ticks once the chunk is loaded
     */
    public ChunkExtras {
        blockEntities = List.copyOf(blockEntities);
        final Map<HeightMap, List<Integer>> copy = new EnumMap<>(HeightMap.class);
        heightmaps.forEach((type, values) -> copy.put(type, List.copyOf(values)));
        heightmaps = Map.copyOf(copy);
        fluidTicks = List.copyOf(fluidTicks);
    }

    /**
     * Constructs extras without fluid ticks.
     *
     * @param blockEntities uncompressed block-entity NBT compounds
     * @param heightmaps    optional first-available-height values
     */
    public ChunkExtras(final List<BinaryData> blockEntities, final Map<HeightMap, List<Integer>> heightmaps) {
        this(blockEntities, heightmaps, List.of());
    }

    /**
     * A fluid the generator could not settle, for example water that may flow into a
     * neighbouring chunk or cave. The server schedules a fluid tick here, which runs once
     * the chunk and its neighbours are ticking; positions without fluid are ignored.
     *
     * @param x local X, 0..15
     * @param y absolute Y within the world's height range
     * @param z local Z, 0..15
     */
    public record FluidTick(int x, int y, int z) {

        /**
         * Validates and constructs this value.
         *
         * @param x local X, 0..15
         * @param y absolute Y within the world's height range
         * @param z local Z, 0..15
         */
        public FluidTick {
            if (x < 0 || x > 15 || z < 0 || z > 15) {
                throw new IllegalArgumentException("Fluid tick X and Z must be local (0..15)");
            }
        }
    }
}
