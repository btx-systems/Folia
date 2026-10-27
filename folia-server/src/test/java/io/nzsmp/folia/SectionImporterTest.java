package io.nzsmp.folia;

import io.nzsmp.folia.data.BinaryData;
import io.nzsmp.folia.generation.PackedPalette;
import io.nzsmp.folia.generation.RegistryLayouts;
import io.nzsmp.folia.generation.SectionImporter;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.Legacy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@Legacy
class SectionImporterTest {
    @Test
    void encodedAndPackedImportsMatchBlocksAndServerBookkeeping() {
        var factory = PalettedContainerFactory.create(RegistryHelper.registryAccess());
        var values = List.of(Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(),
            Blocks.WATER.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState());
        var words = new SimpleBitStorage(4, 4096);
        for (int i = 0; i < 4096; ++i) words.set(i, i % values.size());
        var packed = SectionImporter.packed(PackedPalette.takeOwnership(values, 4, words.getRaw()),
            factory.blockStatesStrategy(), Blocks.AIR.defaultBlockState(), null, value -> value);
        assertSame(words.getRaw(), packed.data.storage().getRaw());
        var expected = new LevelChunkSection(packed, factory.createForBiomes());
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            expected.write(buffer, null, 0);
            buffer.readShort();
            buffer.readShort();
            var decodedBlocks = SectionImporter.encoded(buffer, factory.blockStatesStrategy(), Blocks.AIR.defaultBlockState(), null);
            var decodedBiomes = SectionImporter.encoded(buffer, factory.biomeStrategy(), factory.defaultBiome(), null);
            var actual = new LevelChunkSection(decodedBlocks, decodedBiomes);
            for (int i = 0; i < 4096; ++i) assertSame(values.get(i % values.size()), actual.getStates().get(i));
            assertFalse(actual.hasOnlyAir());
            assertEquals(expected.isRandomlyTickingBlocks(), actual.isRandomlyTickingBlocks());
            assertEquals(expected.isRandomlyTickingFluids(), actual.isRandomlyTickingFluids());
            assertEquals(expected.moonrise$getTickingBlockList().size(), actual.moonrise$getTickingBlockList().size());
            assertEquals(expected.moonrise$hasSpecialCollidingBlocks(), actual.moonrise$hasSpecialCollidingBlocks());
            assertFalse(buffer.isReadable());
        } finally { buffer.release(); }
    }

    @Test
    void invalidPackingAndNetworkWidthsFailInsteadOfBecomingAir() {
        var factory = PalettedContainerFactory.create(RegistryHelper.registryAccess());
        var words = new SimpleBitStorage(4, 4096);
        words.set(0, 9);
        assertThrows(IllegalArgumentException.class, () -> SectionImporter.packed(
            PackedPalette.takeOwnership(List.of(Blocks.STONE.defaultBlockState()), 4, words.getRaw()),
            factory.blockStatesStrategy(), Blocks.AIR.defaultBlockState(), null, value -> value));
        var buffer = SectionImporter.buffer(BinaryData.copyOf(new byte[] {(byte) 255}));
        try {
            assertThrows(IllegalArgumentException.class, () -> SectionImporter.encoded(buffer, factory.blockStatesStrategy(), Blocks.AIR.defaultBlockState(), null));
        } finally { buffer.release(); }
        assertThrows(IllegalArgumentException.class, () -> SectionImporter.packed(
            PackedPalette.takeOwnership(List.of(Blocks.STONE.defaultBlockState()), 4, new long[1]),
            factory.blockStatesStrategy(), Blocks.AIR.defaultBlockState(), null, value -> value));
    }

    @Test
    void malformedSerializedPalettesCannotBePromotedToAirForPluginRecords() {
        var factory = PalettedContainerFactory.create(RegistryHelper.registryAccess());
        var invalidState = new net.minecraft.nbt.CompoundTag();
        invalidState.putString("Name", "minecraft:stone"); // obsolete shape falsely labelled as current-version data
        var palette = new net.minecraft.nbt.ListTag(); palette.add(invalidState);
        var container = new net.minecraft.nbt.CompoundTag(); container.put("palette", palette);
        var parsed = factory.blockStatesContainerCodec().parse(net.minecraft.nbt.NbtOps.INSTANCE, container);
        assertTrue(parsed.error().isPresent());
        assertThrows(net.minecraft.world.level.chunk.storage.SerializableChunkData.ChunkReadException.class, () ->
            net.minecraft.world.level.chunk.storage.SerializableChunkData.resolvePaletteResult(parsed, new net.minecraft.world.level.ChunkPos(0, 0), -4, true));
    }

    @Test
    void importedBlockEntitiesRequireMatchingPositionsAndTypes() {
        var factory = PalettedContainerFactory.create(RegistryHelper.registryAccess());
        var chunk = new net.minecraft.world.level.chunk.ProtoChunk(new net.minecraft.world.level.ChunkPos(-2, 3), net.minecraft.world.level.chunk.UpgradeData.EMPTY,
            net.minecraft.world.level.LevelHeightAccessor.create(-64, 384), factory, null);
        chunk.getSections()[0].setBlockState(1, 0, 1, Blocks.CHEST.defaultBlockState());
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putInt("x", -31); tag.putInt("y", -64); tag.putInt("z", 49); tag.putString("id", "minecraft:chest");
        var seen = new java.util.HashSet<net.minecraft.core.BlockPos>();
        io.nzsmp.folia.generation.ChunkImporter.validateBlockEntity(chunk, tag, seen);
        assertThrows(IllegalArgumentException.class, () -> io.nzsmp.folia.generation.ChunkImporter.validateBlockEntity(chunk, tag, seen));
        tag.putString("id", "minecraft:furnace");
        assertThrows(IllegalArgumentException.class, () -> io.nzsmp.folia.generation.ChunkImporter.validateBlockEntity(chunk, tag, new java.util.HashSet<>()));
        tag.putString("id", "DUMMY");
        io.nzsmp.folia.generation.ChunkImporter.validateBlockEntity(chunk, tag, new java.util.HashSet<>());
        tag.putInt("x", 0);
        assertThrows(IllegalArgumentException.class, () -> io.nzsmp.folia.generation.ChunkImporter.validateBlockEntity(chunk, tag, new java.util.HashSet<>()));
    }

    @Test
    void registryDescriptorsAreStableAndExposeNumericMappings() {
        var biomes = RegistryHelper.registryAccess().lookupOrThrow(Registries.BIOME);
        var first = RegistryLayouts.create(biomes);
        assertEquals(first.sectionEncoding(), RegistryLayouts.create(biomes).sectionEncoding());
        assertEquals(64, first.sectionEncoding().registryFingerprint().length());
        assertTrue(first.blockStates().contains("minecraft:stone"));
        assertEquals(biomes.size(), first.biomes().size());
    }
}
