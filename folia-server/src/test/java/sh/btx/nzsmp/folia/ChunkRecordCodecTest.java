package sh.btx.nzsmp.folia;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.Legacy;
import org.junit.jupiter.api.Test;
import sh.btx.nzsmp.folia.generation.ChunkImporter;
import sh.btx.nzsmp.folia.storage.ChunkRecordCodec;
import sh.btx.nzsmp.folia.storage.PluginChunkRecordCodec;
import sh.btx.nzsmp.folia.storage.PluginPersistence;
import static org.junit.jupiter.api.Assertions.*;

@Legacy
class ChunkRecordCodecTest {
    @Test
    void sectionRoundTripPreservesUnknownMetadataAndCanInvalidateDerivedData() throws Exception {
        var factory = PalettedContainerFactory.create(RegistryHelper.registryAccess());
        var blocks = factory.createForBlockStates();
        for (int i = 0; i < 4096; ++i) blocks.set(i, (i & 1) == 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        var section = new CompoundTag();
        section.putByte("Y", (byte) -4);
        section.put("block_states", factory.blockStatesContainerCodec().encodeStart(NbtOps.INSTANCE, blocks).getOrThrow());
        section.put("biomes", factory.biomeContainerRWCodec().encodeStart(NbtOps.INSTANCE, factory.createForBiomes()).getOrThrow());
        section.putString("unknown-section-field", "preserved");
        section.putByteArray("BlockLight", new byte[2048]);
        var lightOnly = new CompoundTag();
        lightOnly.putByte("Y", (byte) -5);
        lightOnly.putString("unknown-light-field", "preserved-too");
        var sections = new ListTag(); sections.add(section); sections.add(lightOnly);
        var nbt = new CompoundTag();
        nbt.putInt("DataVersion", net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());
        nbt.put("sections", sections);
        nbt.putString("unknown-root-field", "keep");
        nbt.put("Heightmaps", new CompoundTag());
        nbt.putBoolean("isLightOn", false);
        nbt.putInt("starlight.light_version", 10);
        var codec = new PluginChunkRecordCodec(factory, RegistryHelper.registryAccess().lookupOrThrow(Registries.BIOME));
        var snapshot = codec.decode(PluginPersistence.encode(nbt));
        assertEquals(1, snapshot.sections().size());
        var preserved = codec.encode(snapshot, ChunkRecordCodec.DerivedDataPolicy.PRESERVE);
        var result = ChunkImporter.readNbt(preserved.payload());
        assertEquals("keep", result.getStringOr("unknown-root-field", ""));
        var resultSection = result.getListOrEmpty("sections").getCompound(0).orElseThrow();
        assertEquals("preserved", resultSection.getStringOr("unknown-section-field", ""));
        assertTrue(resultSection.contains("BlockLight"));
        assertTrue(result.contains("Heightmaps"));
        assertEquals(2, result.getListOrEmpty("sections").size());
        var decodedAgain = codec.decode(preserved);
        var data = decodedAgain.sections().getFirst().blocks();
        var internal = sh.btx.nzsmp.folia.generation.SectionImporter.packed(data, factory.blockStatesStrategy(), Blocks.AIR.defaultBlockState(), null,
            value -> ((CraftBlockData) value).getState());
        for (int i = 0; i < 4096; ++i) assertSame(blocks.get(i), internal.get(i));
        var recomputed = ChunkImporter.readNbt(codec.encode(codec.decode(preserved), ChunkRecordCodec.DerivedDataPolicy.RECOMPUTE).payload());
        assertFalse(recomputed.contains("Heightmaps"));
        assertFalse(recomputed.contains("starlight.light_version"));
        assertFalse(recomputed.getListOrEmpty("sections").getCompound(0).orElseThrow().contains("BlockLight"));
    }
}
