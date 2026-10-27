package io.nzsmp.folia.generation;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;


/**
 * Local palette with Minecraft's padded-long packing: entries never cross long boundaries,
 * least-significant entry first, index x + z*width + y*width*width.
 * Blocks contain 4096 entries; biomes contain 64. A uniform palette uses zero bits.
 * Palette values must not be mutated after submission (clone reusable BlockData first).
 *
 * @param <T> palette value type
 */
public final class PackedPalette<T> {
    private final List<T> palette;
    private final int bitsPerEntry;
    private final AtomicReference<long[]> storage;

    private PackedPalette(final List<T> palette, final int bits, final long[] storage) {
        this.palette = List.copyOf(palette);
        if (this.palette.isEmpty() || bits < 0 || bits > 31
            || (bits == 0 && this.palette.size() != 1)
            || (bits > 0 && this.palette.size() > (1L << bits))) {
            throw new IllegalArgumentException("Invalid palette size or bit width");
        }
        this.bitsPerEntry = bits;
        this.storage = new AtomicReference<>(Objects.requireNonNull(storage, "storage"));
    }

    /**
     * Creates a palette containing one repeated value.
     *
     * @param <T>   palette value type
     * @param value uniform palette value
     * @return the operation result
     */
    public static <T> PackedPalette<T> uniform(final T value) {
        return new PackedPalette<>(List.of(value), 0, new long[0]);
    }

    /**
     * Creates an isolated copy of the supplied data.
     *
     * @param <T>     palette value type
     * @param palette local palette entries
     * @param bits    bits per entry
     * @param storage padded packed-long storage
     * @return the operation result
     */
    public static <T> PackedPalette<T> copyOf(final List<T> palette, final int bits, final long[] storage) {
        return new PackedPalette<>(palette, bits, storage.clone());
    }

    /**
     * Transfers ownership; the caller must never modify or reuse the supplied array.
     *
     * @param <T>     palette value type
     * @param palette local palette entries
     * @param bits    bits per entry
     * @param storage padded packed-long storage
     * @return the operation result
     */
    public static <T> PackedPalette<T> takeOwnership(final List<T> palette, final int bits, final long[] storage) {
        return new PackedPalette<>(palette, bits, storage);
    }

    /**
     * Returns the local palette entries.
     *
     * @return the operation result
     */
    public List<T> palette() {
        return this.palette;
    }

    /**
     * Returns the number of bits per packed entry.
     *
     * @return the operation result
     */
    public int bitsPerEntry() {
        return this.bitsPerEntry;
    }

    /**
     * Transfers the array to a consumer exactly once. Called by the server on submission.
     * Results cannot be reused for another chunk; create a new payload instead.
     *
     * @return the operation result
     * @throws IllegalStateException if the supplied data or lifecycle state is invalid
     */
    public long[] takeStorage() {
        final long[] result = this.storage.getAndSet(null);
        if (result == null) {
            
            throw new IllegalStateException("Palette storage already consumed");
        }
        return result;
    }
}
