package sh.btx.nzsmp.folia.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.key.Key;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import sh.btx.nzsmp.folia.generation.ChunkImporter;
import sh.btx.nzsmp.folia.generation.ChunkSectionData;
import sh.btx.nzsmp.folia.generation.PackedPalette;
import sh.btx.nzsmp.folia.generation.SectionImporter;

/** Section transforms preserve unrecognized metadata and never instantiate live entities/chunks. */
public final class PluginChunkRecordCodec implements ChunkRecordCodec {
    private final PalettedContainerFactory factory;
    private final net.minecraft.core.Registry<Biome> biomes;
    private final java.util.function.UnaryOperator<CompoundTag> upgrade;

    public PluginChunkRecordCodec(final ServerLevel world) {
        this.factory = world.palettedContainerFactory();
        this.biomes = world.registryAccess().lookupOrThrow(Registries.BIOME);
        this.upgrade = tag -> world.getChunkSource().chunkMap.upgradeChunkTag(tag);
    }

    // Test constructor avoids requiring a live world for codec correctness tests.
    public PluginChunkRecordCodec(final PalettedContainerFactory factory, final net.minecraft.core.Registry<Biome> biomes) {
        this.factory = factory;
        this.biomes = biomes;
        this.upgrade = tag -> tag;
    }

    @Override
    public ChunkRecordSnapshot decode(final StorageRecord record) {
        try {
            final CompoundTag tag = this.upgrade.apply(ChunkImporter.readNbt(record.payload()));
            final List<ChunkSectionData> sections = new ArrayList<>();
            for (final var value : tag.getListOrEmpty("sections")) {
                if (!(value instanceof CompoundTag section)) throw new IllegalArgumentException("Invalid section compound");
                if (!section.contains("block_states")) continue; // preserve light-only sections in the original
                final PalettedContainer<BlockState> blocks = this.factory.blockStatesContainerCodec().parse(NbtOps.INSTANCE, section.get("block_states")).getOrThrow();
                final PalettedContainerRO<Holder<Biome>> biomes = this.factory.biomeContainerCodec().parse(NbtOps.INSTANCE, section.get("biomes")).getOrThrow();
                final var blockData = blocks.pack(this.factory.blockStatesStrategy());
                final var biomeData = biomes.pack(this.factory.biomeStrategy());
                final List<BlockData> blockPalette = blockData.paletteEntries().stream().map(state -> (BlockData) CraftBlockData.createData(state)).toList();
                final List<Key> biomePalette = biomeData.paletteEntries().stream().map(holder -> Key.key(this.biomes.getKey(holder.value()).toString())).toList();
                sections.add(new ChunkSectionData(section.getByteOr("Y", (byte) 0),
                    PackedPalette.takeOwnership(blockPalette, blockData.bitsPerEntry(), blockData.storage().map(java.util.stream.LongStream::toArray).orElseGet(() -> new long[0])),
                    PackedPalette.takeOwnership(biomePalette, biomeData.bitsPerEntry(), biomeData.storage().map(java.util.stream.LongStream::toArray).orElseGet(() -> new long[0]))));
            }
            // Retain the upgraded tag as the lossless base; arrays above are independently owned.
            return new ChunkRecordSnapshot(new StorageRecord(record.metadata(), PluginPersistence.encode(tag).payload()), sections);
        } catch (Exception error) {
            throw new IllegalArgumentException("Cannot decode chunk record", error);
        }
    }

    @Override
    public StorageRecord encode(final ChunkRecordSnapshot snapshot, final DerivedDataPolicy policy) {
        try {
            final CompoundTag tag = ChunkImporter.readNbt(snapshot.original().payload());
            final Map<Integer, CompoundTag> oldSections = new HashMap<>();
            for (final var value : tag.getListOrEmpty("sections")) {
                if (!(value instanceof CompoundTag section)) throw new IllegalArgumentException("Invalid section compound");
                oldSections.put((int) section.getByteOr("Y", (byte) 0), section.copy());
            }
            final ListTag sections = new ListTag();
            final HashSet<Integer> seen = new HashSet<>();
            for (final ChunkSectionData section : snapshot.sections()) {
                if (section.sectionY() < -128 || section.sectionY() > 127 || !seen.add(section.sectionY())) throw new IllegalArgumentException("Invalid section Y");
                final CompoundTag data = oldSections.remove(section.sectionY());
                final CompoundTag output = data == null ? new CompoundTag() : data;
                output.putByte("Y", (byte) section.sectionY());
                final var blocks = SectionImporter.packed(section.blocks(), this.factory.blockStatesStrategy(), Blocks.AIR.defaultBlockState(), null, value -> ((CraftBlockData) value).getState());
                final var biomes = SectionImporter.packed(section.biomes(), this.factory.biomeStrategy(), this.factory.defaultBiome(), null,
                    value -> this.biomes.getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.parse(value.asString()))));
                output.put("block_states", this.factory.blockStatesContainerCodec().encodeStart(NbtOps.INSTANCE, blocks).getOrThrow());
                output.put("biomes", this.factory.biomeContainerRWCodec().encodeStart(NbtOps.INSTANCE, biomes).getOrThrow());
                if (policy == DerivedDataPolicy.RECOMPUTE) clearLight(output);
                sections.add(output);
            }
            for (final CompoundTag leftover : oldSections.values()) {
                leftover.remove("block_states");
                leftover.remove("biomes");
                if (policy == DerivedDataPolicy.RECOMPUTE) clearLight(leftover);
                sections.add(leftover); // retain unknown per-section and light-only metadata
            }
            tag.put("sections", sections);
            if (policy == DerivedDataPolicy.RECOMPUTE) {
                tag.remove("Heightmaps");
                tag.putBoolean("isLightOn", false);
                tag.remove(ca.spottedleaf.moonrise.patches.starlight.util.SaveUtil.STARLIGHT_VERSION_TAG);
            }
            final StorageRecord encoded = PluginPersistence.encode(tag);
            return new StorageRecord(new RecordMetadata(snapshot.original().metadata().mediaType(), encoded.metadata().dataVersion(), snapshot.original().metadata().modifiedTimeMillis()), encoded.payload());
        } catch (Exception error) {
            throw new IllegalArgumentException("Cannot encode chunk record", error);
        }
    }

    private static void clearLight(final CompoundTag section) {
        section.remove("BlockLight");
        section.remove("SkyLight");
        section.remove("starlight.blocklight_state");
        section.remove("starlight.skylight_state");
    }
}
