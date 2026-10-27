package io.nzsmp.folia;

import com.sun.management.ThreadMXBean;
import io.nzsmp.folia.data.BinaryData;
import io.nzsmp.folia.generation.PackedPalette;
import io.nzsmp.folia.generation.SectionImporter;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.craftbukkit.generator.OldCraftChunkData;
import org.bukkit.support.RegistryHelper;

/** Reproducible import microbenchmark, not a claim about whole-server throughput. */
public final class GenerationFastPathBenchmark {
    private static volatile long blackhole;
    private static List<BlockState> STATES;
    private static List<BlockData> BUKKIT;
    private static PalettedContainerFactory factory;
    private record Fixture(List<BlockState> palette, int bits, long[] words, int[] indices, BinaryData encoded) {}

    public static void main(String[] args) {
        RegistryHelper.setup(FeatureFlags.VANILLA_SET);
        factory = PalettedContainerFactory.create(RegistryHelper.registryAccess());
        STATES = List.of(Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(), Blocks.WATER.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState());
        BUKKIT = STATES.stream().map(state -> (BlockData) CraftBlockData.createData(state)).toList();
        ThreadMXBean allocations = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        allocations.setThreadAllocatedMemoryEnabled(true);
        int warmups = Integer.getInteger("benchmark.warmups", 5);
        int samples = Integer.getInteger("benchmark.samples", 9);
        System.out.println("IMPORT_BENCHMARK java=" + System.getProperty("java.version") + " arch=" + System.getProperty("os.arch")
            + " warmups=" + warmups + " samples=" + samples);
        System.out.println("BENCH_CSV,sections,profile,path,median_ms,p95_ms,median_alloc_bytes,checksum");
        for (int sections : new int[] {24, 254}) for (String profile : List.of("mixed", "stratified")) {
            List<Fixture> fixtures = new ArrayList<>();
            for (int y = 0; y < sections; ++y) {
                boolean mixed = profile.equals("mixed") || y % 4 == 2;
                fixtures.add(fixture(mixed ? STATES : List.of(y % 4 == 3 ? STATES.getFirst() : STATES.get(1))));
            }
            List<String> paths = List.of("bukkit-setBlock", "packed-copy", "packed-owned", "encoded-sections");
            for (int i = 0; i < warmups; ++i) for (String path : paths) run(path, fixtures, null);
            long[][] times = new long[4][samples];
            long[][] bytes = new long[4][samples];
            long[] checksums = new long[4];
            for (int sample = 0; sample < samples; ++sample) for (int offset = 0; offset < paths.size(); ++offset) {
                int index = (offset + sample) % paths.size(); // rotate order to reduce systematic GC/JIT bias
                String path = paths.get(index);
                // Ownership-transfer benchmark starts with independently owned incoming arrays.
                // Their production belongs to the provider and is deliberately outside import timing.
                long[][] owned = path.equals("packed-owned") ? fixtures.stream().map(f -> f.words.clone()).toArray(long[][]::new) : null;
                long allocated = allocations.getThreadAllocatedBytes(Thread.currentThread().threadId());
                long start = System.nanoTime();
                long checksum = run(path, fixtures, owned);
                times[index][sample] = System.nanoTime() - start;
                bytes[index][sample] = allocations.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated;
                checksums[index] = checksum;
                blackhole = checksum;
            }
            for (int path = 0; path < paths.size(); ++path) {
                Arrays.sort(times[path]); Arrays.sort(bytes[path]);
                if (checksums[path] != checksums[0]) throw new AssertionError("Output mismatch");
                System.out.printf(java.util.Locale.ROOT, "BENCH_CSV,%d,%s,%s,%.3f,%.3f,%d,%d%n", sections, profile, paths.get(path),
                    times[path][samples / 2] / 1e6, times[path][Math.min(samples - 1, (int) Math.ceil(samples * .95) - 1)] / 1e6,
                    bytes[path][samples / 2], checksums[path]);
            }
        }
    }

    private static Fixture fixture(List<BlockState> palette) {
        int bits = palette.size() == 1 ? 0 : 4;
        var storage = bits == 0 ? null : new SimpleBitStorage(bits, 4096);
        int[] indices = new int[4096];
        for (int i = 0; i < indices.length; ++i) { indices[i] = palette.size() == 1 ? 0 : ((i * 13) ^ (i >> 4)) & 3; if (storage != null) storage.set(i, indices[i]); }
        long[] words = storage == null ? new long[0] : storage.getRaw();
        var blocks = SectionImporter.packed(PackedPalette.copyOf(palette, bits, words), factory.blockStatesStrategy(), Blocks.AIR.defaultBlockState(), null, value -> value);
        var section = new LevelChunkSection(blocks, factory.createForBiomes());
        var buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            section.write(buffer, null, 0);
            byte[] payload = new byte[buffer.readableBytes()]; buffer.getBytes(0, payload);
            return new Fixture(palette, bits, words, indices, BinaryData.takeOwnership(java.nio.ByteBuffer.wrap(payload)));
        } finally { buffer.release(); }
    }

    private static long run(String path, List<Fixture> fixtures, long[][] owned) {
        long checksum = 0;
        if (path.equals("bukkit-setBlock")) {
            var chunk = new OldCraftChunkData(0, fixtures.size() * 16, factory);
            for (int y = 0; y < fixtures.size(); ++y) {
                Fixture fixture = fixtures.get(y);
                List<BlockData> values = fixture.palette.stream().map(state -> BUKKIT.get(STATES.indexOf(state))).toList();
                for (int i = 0; i < 4096; ++i) chunk.setBlock(i & 15, (y << 4) + (i >> 8), (i >> 4) & 15, values.get(fixture.indices[i]));
            }
            for (int y = 0; y < fixtures.size(); ++y) {
                checksum += checksum(((CraftBlockData) chunk.getBlockData(1, (y << 4) + 7, 2)).getState());
            }
        } else {
            for (int y = 0; y < fixtures.size(); ++y) {
                Fixture fixture = fixtures.get(y);
                PalettedContainer<BlockState> blocks;
                PalettedContainer<Holder<Biome>> biomes;
                if (path.equals("encoded-sections")) {
                    FriendlyByteBuf buffer = SectionImporter.buffer(fixture.encoded);
                    try {
                        buffer.readShort(); buffer.readShort();
                        blocks = SectionImporter.encoded(buffer, factory.blockStatesStrategy(), Blocks.AIR.defaultBlockState(), null);
                        biomes = SectionImporter.encoded(buffer, factory.biomeStrategy(), factory.defaultBiome(), null);
                    } finally { buffer.release(); }
                } else {
                    var input = path.equals("packed-copy") || owned == null
                        ? PackedPalette.copyOf(fixture.palette, fixture.bits, fixture.words)
                        : PackedPalette.takeOwnership(fixture.palette, fixture.bits, owned[y]);
                    blocks = SectionImporter.packed(input, factory.blockStatesStrategy(), Blocks.AIR.defaultBlockState(), null, value -> value);
                    biomes = factory.createForBiomes();
                }
                checksum += checksum(new LevelChunkSection(blocks, biomes).getBlockState(1, 7, 2));
            }
        }
        return checksum;
    }

    private static long checksum(BlockState state) { return net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.getId(state); }
}
