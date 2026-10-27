package io.nzsmp.folia.generation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import net.kyori.adventure.key.Key;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.craftbukkit.block.data.CraftBlockData;

public final class RegistryLayouts {
    private static final java.util.Map<ServerLevel, RegistryLayout> CACHE = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private RegistryLayouts() {}

    public static RegistryLayout forWorld(final ServerLevel world) {
        return CACHE.computeIfAbsent(world, level -> create(level.registryAccess().lookupOrThrow(Registries.BIOME)));
    }

    public static RegistryLayout create(final Registry<Biome> biomes) {
        final List<String> states = new ArrayList<>();
        for (int i = 0; i < Block.BLOCK_STATE_REGISTRY.size(); ++i) {
            final BlockState state = Block.BLOCK_STATE_REGISTRY.byIdOrThrow(i);
            states.add(CraftBlockData.createData(state).getAsString());
        }
        final List<Key> biomeKeys = new ArrayList<>();
        for (int i = 0; i < biomes.size(); ++i) biomeKeys.add(Key.key(biomes.getKey(biomes.byIdOrThrow(i)).toString()));
        try {
            final MessageDigest hash = MessageDigest.getInstance("SHA-256");
            // NUL separates entries; neither block-state strings nor keys contain NUL.
            states.forEach(value -> hash.update((value + "\0").getBytes(StandardCharsets.UTF_8)));
            hash.update((byte) 255);
            biomeKeys.forEach(value -> hash.update((value.asString() + "\0").getBytes(StandardCharsets.UTF_8)));
            return new RegistryLayout(new SectionEncoding("minecraft:network-section/26.3", SharedConstants.getCurrentVersion().dataVersion().version(),
                HexFormat.of().formatHex(hash.digest())), states, biomeKeys);
        } catch (NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }
}
