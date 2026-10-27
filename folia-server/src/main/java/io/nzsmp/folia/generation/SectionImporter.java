package io.nzsmp.folia.generation;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.function.Function;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.chunk.Configuration;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/** Validated bulk import shared by generation and benchmarks. */
public final class SectionImporter {
    private SectionImporter() {}

    public static <A, T> PalettedContainer<T> packed(final PackedPalette<A> input, final Strategy<T> strategy,
                                                   final T defaultValue, final T[] presets, final Function<A, T> resolve) {
        final List<T> palette = input.palette().stream().map(resolve).toList();
        if (palette.stream().distinct().count() != palette.size()) {
            throw new IllegalArgumentException("Duplicate palette entries");
        }
        final int bits = input.bitsPerEntry();
        final long[] words = input.takeStorage();
        final int expectedLength = bits == 0 ? 0 : (strategy.entryCount() + (64 / bits) - 1) / (64 / bits);
        if (words.length != expectedLength || palette.size() > strategy.entryCount()) {
            throw new IllegalArgumentException("Invalid packed storage length or palette size");
        }
        final BitStorage source = bits == 0 ? new ZeroBitStorage(strategy.entryCount())
            : new SimpleBitStorage(bits, strategy.entryCount(), words);
        if (bits == 0 && words.length != 0) throw new IllegalArgumentException("Uniform palette must have no storage");
        if (bits != 0) for (int i = 0; i < source.getSize(); ++i) {
            if (source.get(i) >= palette.size()) throw new IllegalArgumentException("Invalid palette index");
        }
        final Configuration config = strategy.extensionConfiguration(palette.size(), true);
        final Palette<T> targetPalette = config.createPalette(strategy, palette);
        final BitStorage target;
        if (config instanceof Configuration.Global) {
            target = new SimpleBitStorage(config.bitsInMemory(), strategy.entryCount());
            final int[] ids = palette.stream().mapToInt(value -> strategy.globalMap().getId(value)).toArray();
            for (int id : ids) if (id < 0) throw new IllegalArgumentException("Unregistered palette value");
            for (int i = 0; i < source.getSize(); ++i) target.set(i, ids[source.get(i)]);
        } else if (config.bitsInMemory() == bits) {
            target = source; // adopts transferred long[]; no stream materialization or copy
        } else {
            target = new SimpleBitStorage(config.bitsInMemory(), strategy.entryCount());
            for (int i = 0; i < source.getSize(); ++i) target.set(i, source.get(i));
        }
        return PalettedContainer.createForExtension(strategy, config, target, targetPalette, palette, defaultValue, presets);
    }

    public static <T> PalettedContainer<T> encoded(final FriendlyByteBuf buffer, final Strategy<T> strategy,
                                                  final T defaultValue, final T[] presets) {
        final int bits = buffer.readUnsignedByte();
        if (bits > 31) throw new IllegalArgumentException("Invalid palette bits: " + bits);
        final Configuration config = strategy.extensionConfiguration(bits, false);
        if (config.bitsInMemory() != bits) throw new IllegalArgumentException("Noncanonical network palette width: " + bits);
        final Palette<T> palette = config.createPalette(strategy, List.of());
        palette.read(buffer, strategy.globalMap());
        if (palette.getSize() < 1 || (bits == 0 && palette.getSize() != 1)) {
            throw new IllegalArgumentException("Empty or invalid network palette");
        }
        final BitStorage storage = bits == 0 ? new ZeroBitStorage(strategy.entryCount())
            : new SimpleBitStorage(bits, strategy.entryCount());
        buffer.readFixedSizeLongArray(storage.getRaw());
        if (bits == 0) {
            if (palette.valueFor(0) == null) throw new IllegalArgumentException("Unknown registry ID");
        } else for (int i = 0; i < storage.getSize(); ++i) {
            if (palette.valueFor(storage.get(i)) == null) throw new IllegalArgumentException("Unknown registry or palette ID");
        }
        final java.util.ArrayList<T> values = new java.util.ArrayList<>();
        // Global palettes have all registry entries; presets only require local palette entries.
        if (!(config instanceof Configuration.Global)) {
            for (int i = 0; i < palette.getSize(); ++i) values.add(palette.valueFor(i));
        }
        return PalettedContainer.createForExtension(strategy, config, storage, palette, values, defaultValue, presets);
    }

    public static <T> void skipEncoded(final FriendlyByteBuf buffer, final Strategy<T> strategy) {
        final int bits = buffer.readUnsignedByte();
        if (bits > 31 || strategy.extensionConfiguration(bits, false).bitsInMemory() != bits) {
            throw new IllegalArgumentException("Invalid network palette width");
        }
        final Configuration config = strategy.extensionConfiguration(bits, false);
        if (!(config instanceof Configuration.Global)) {
            final int count = bits == 0 ? 1 : buffer.readVarInt();
            if (count < 1 || count > strategy.entryCount() || count > (1L << bits)) throw new IllegalArgumentException("Invalid palette count");
            for (int i = 0; i < count; ++i) strategy.globalMap().byIdOrThrow(buffer.readVarInt());
        }
        if (bits != 0) buffer.skipBytes(((strategy.entryCount() + 64 / bits - 1) / (64 / bits)) * Long.BYTES);
    }

    public static FriendlyByteBuf buffer(final io.nzsmp.folia.data.BinaryData data) {
        return new FriendlyByteBuf(Unpooled.wrappedBuffer(data.buffer()));
    }
}
