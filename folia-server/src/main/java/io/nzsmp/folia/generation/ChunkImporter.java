package io.nzsmp.folia.generation;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import org.bukkit.craftbukkit.CraftHeightMap;
import org.bukkit.craftbukkit.block.data.CraftBlockData;

public final class ChunkImporter {
    private ChunkImporter() {}

    public static ChunkAccess apply(final ServerLevel world, final ChunkAccess target, final GeneratedChunk result,
                                    final RegistryLayout layout) throws java.io.IOException {
        if (!(target instanceof ProtoChunk)) throw new IllegalArgumentException("Generation target is not a protochunk");
        if (result instanceof SerializedChunkData serialized) {
            final CompoundTag tag = readNbt(serialized.nbt());
            if (tag.getIntOr("DataVersion", -1) != serialized.dataVersion() || serialized.dataVersion() < 0 || serialized.dataVersion() > layout.sectionEncoding().dataVersion()) {
                throw new IllegalArgumentException("Unsupported or inconsistent chunk DataVersion");
            }
            if (!SerializableChunkData.getChunkCoordinate(tag).equals(target.getPos())) throw new IllegalArgumentException("Chunk coordinate mismatch");
            final CompoundTag upgraded = world.getChunkSource().chunkMap.upgradeChunkTag(tag);
            final SerializableChunkData data = SerializableChunkData.parse(world, world.palettedContainerFactory(), upgraded, true);
            if (data == null || !data.chunkStatus().isOrAfter(ChunkStatus.FEATURES)) throw new IllegalArgumentException("Serialized terrain must be complete through FEATURES");
            final ProtoChunk chunk = data.read(world, world.getPoiManager(), world.getChunkSource().chunkMap.storageInfo(), target.getPos());
            final Set<BlockPos> blockEntityPositions = new HashSet<>();
            for (final CompoundTag blockEntity : data.blockEntities()) validateBlockEntity(chunk, blockEntity, blockEntityPositions);
            chunk.setPersistedStatus(ChunkStatus.SPAWN);
            chunk.setLightCorrect(false); // validate light by recomputing; do not trust foreign engine metadata
            chunk.markUnsaved();
            return chunk;
        }
        final BiomePolicy policy;
        final ChunkExtras extras;
        final Set<Integer> seen = new HashSet<>();
        if (result instanceof SectionChunkData sections) {
            policy = sections.biomePolicy();
            extras = sections.extras();
            for (final ChunkSectionData section : sections.sections()) {
                checkSection(world, seen, section.sectionY());
                final BlockState[] presets = world.chunkPacketBlockController.getPresetBlockStates(world, target.getPos(), section.sectionY());
                final PalettedContainer<BlockState> blocks = SectionImporter.packed(section.blocks(), world.palettedContainerFactory().blockStatesStrategy(),
                    Blocks.AIR.defaultBlockState(), presets, value -> ((CraftBlockData) value).getState());
                final PalettedContainer<Holder<Biome>> biomes = policy == BiomePolicy.SERVER_GENERATED
                    ? world.palettedContainerFactory().createForBiomes()
                    : SectionImporter.packed(section.biomes(), world.palettedContainerFactory().biomeStrategy(), world.palettedContainerFactory().defaultBiome(), null,
                        value -> world.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.parse(value.asString()))));
                target.getSections()[section.sectionY() - world.getMinSectionY()] = new LevelChunkSection(blocks, biomes);
            }
        } else if (result instanceof EncodedSectionChunkData sections) {
            if (!layout.sectionEncoding().equals(sections.encoding())) throw new IllegalArgumentException("Section encoding or registry mapping mismatch");
            policy = sections.biomePolicy();
            extras = sections.extras();
            if (sections.sections().stream().mapToLong(section -> section.bytes().size()).sum() > 64L * 1024 * 1024) {
                throw new IllegalArgumentException("Encoded chunk exceeds 64 MiB");
            }
            for (final EncodedSection section : sections.sections()) {
                checkSection(world, seen, section.sectionY());
                if (section.bytes().size() > 1024 * 1024) throw new IllegalArgumentException("Oversized encoded section");
                final FriendlyByteBuf buffer = SectionImporter.buffer(section.bytes());
                try {
                    final int blockCount = buffer.readUnsignedShort();
                    final int fluidCount = buffer.readUnsignedShort();
                    if (blockCount > 4096 || fluidCount > 4096) throw new IllegalArgumentException("Invalid section counts");
                    final BlockState[] presets = world.chunkPacketBlockController.getPresetBlockStates(world, target.getPos(), section.sectionY());
                    final PalettedContainer<BlockState> blocks = SectionImporter.encoded(buffer, world.palettedContainerFactory().blockStatesStrategy(), Blocks.AIR.defaultBlockState(), presets);
                    final PalettedContainer<Holder<Biome>> biomes;
                    if (policy == BiomePolicy.SERVER_GENERATED) {
                        SectionImporter.skipEncoded(buffer, world.palettedContainerFactory().biomeStrategy());
                        biomes = world.palettedContainerFactory().createForBiomes();
                    } else {
                        biomes = SectionImporter.encoded(buffer, world.palettedContainerFactory().biomeStrategy(), world.palettedContainerFactory().defaultBiome(), null);
                    }
                    if (buffer.isReadable()) throw new IllegalArgumentException("Trailing section data");
                    // Construct SERVER sections once; never call the client packet reader.
                    target.getSections()[section.sectionY() - world.getMinSectionY()] = new LevelChunkSection(blocks, biomes);
                } finally {
                    buffer.release();
                }
            }
        } else {
            throw new IllegalArgumentException("Unknown generation result");
        }
        // Sampling does not require neighbour chunks and handles omitted air sections.
        final BiomeResolver resolver = world.getChunkSource().getGenerator().getBiomeSource()
            .createResolver(world.getChunkSource().randomState().createClimateSampler(SamplerContext.EMPTY_UNCACHED));
        for (int i = 0; i < target.getSections().length; ++i) {
            final int sectionY = i + world.getMinSectionY();
            if (policy == BiomePolicy.SERVER_GENERATED || !seen.contains(sectionY)) {
                final LevelChunkSection section = target.getSections()[i];
                for (int y = 0; y < 4; ++y) for (int z = 0; z < 4; ++z) for (int x = 0; x < 4; ++x) {
                    section.setNoiseBiome(x, y, z, resolver.getNoiseBiome((target.getPos().x() << 2) + x, (sectionY << 2) + y, (target.getPos().z() << 2) + z));
                }
            }
        }
        final Set<BlockPos> blockEntityPositions = new HashSet<>();
        for (final io.nzsmp.folia.data.BinaryData bytes : extras.blockEntities()) {
            final CompoundTag tag = readNbt(bytes);
            validateBlockEntity(target, tag, blockEntityPositions);
            target.setBlockEntityNbt(tag);
        }
        final Set<Heightmap.Types> missing = EnumSet.copyOf(ChunkStatus.SPAWN.heightmapsAfter());
        extras.heightmaps().forEach((type, values) -> {
            if (values.size() != 256 || values.stream().anyMatch(value -> value < 0 || value > world.getHeight())) throw new IllegalArgumentException("Invalid heightmap");
            final Heightmap.Types internal = CraftHeightMap.toNMS(type);
            target.setHeightmap(internal, new SimpleBitStorage(Mth.ceillog2(world.getHeight() + 1), 256, values.stream().mapToInt(Integer::intValue).toArray()).getRaw());
            missing.remove(internal);
        });
        Heightmap.primeHeightmaps(target, missing);
        ((ProtoChunk) target).setPersistedStatus(ChunkStatus.SPAWN);
        target.setLightCorrect(false);
        target.markUnsaved();
        return target;
    }

    public static void validateBlockEntity(final ChunkAccess chunk, final CompoundTag tag, final Set<BlockPos> seen) {
        if (tag.getInt("x").isEmpty() || tag.getInt("y").isEmpty() || tag.getInt("z").isEmpty() || tag.getString("id").isEmpty()) {
            throw new IllegalArgumentException("Block entities require id and absolute x/y/z");
        }
        final BlockPos pos = new BlockPos(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0));
        final BlockState state = chunk.getBlockState(pos);
        if ((pos.getX() >> 4) != chunk.getPos().x() || (pos.getZ() >> 4) != chunk.getPos().z()
            || chunk.isOutsideBuildHeight(pos) || !state.hasBlockEntity() || !seen.add(pos)) {
            throw new IllegalArgumentException("Duplicate or invalid block entity position/state");
        }
        final String name = tag.getStringOr("id", "");
        if (!name.equals("DUMMY")) { // vanilla protochunk placeholder creates the state's default block entity
            final Identifier id = Identifier.tryParse(name);
            if (id == null || !net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(id)
                || !net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getValue(id).isValid(state)) {
                throw new IllegalArgumentException("Block entity type does not match its block state");
            }
        }
    }

    private static void checkSection(final ServerLevel world, final Set<Integer> seen, final int sectionY) {
        if (sectionY < world.getMinSectionY() || sectionY > world.getMaxSectionY() || !seen.add(sectionY)) {
            throw new IllegalArgumentException("Duplicate or out-of-bounds section Y: " + sectionY);
        }
    }

    public static CompoundTag readNbt(final io.nzsmp.folia.data.BinaryData data) throws java.io.IOException {
        if (data.size() > 64 * 1024 * 1024) throw new IllegalArgumentException("NBT exceeds 64 MiB");
        final FriendlyByteBuf buffer = SectionImporter.buffer(data);
        try {
            final CompoundTag tag = NbtIo.read((java.io.DataInput) new io.netty.buffer.ByteBufInputStream(buffer), NbtAccounter.create(64L * 1024 * 1024));
            if (buffer.isReadable()) throw new IllegalArgumentException("Trailing NBT data");
            return tag;
        } finally {
            buffer.release();
        }
    }
}
