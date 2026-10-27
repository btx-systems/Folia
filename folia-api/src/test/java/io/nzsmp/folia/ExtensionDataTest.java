package io.nzsmp.folia;

import io.nzsmp.folia.data.BinaryData;
import io.nzsmp.folia.generation.PackedPalette;
import io.nzsmp.folia.storage.RecordQuery;
import io.nzsmp.folia.storage.WorldRecordKey;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExtensionDataTest {
    @Test
    void copiedBytesAreIsolatedAndViewsHaveIndependentPositions() {
        byte[] input = {1, 2, 3, 4};
        BinaryData data = BinaryData.copyOf(input);
        input[1] = 9;
        assertArrayEquals(new byte[] {2, 3}, data.slice(1, 2).toByteArray());
        assertThrows(ReadOnlyBufferException.class, () -> data.buffer().put((byte) 0));
        data.buffer().get();
        assertEquals(4, data.buffer().remaining());
    }

    @Test
    void ownedSlicesUseTheRequestedRemainingRegion() {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[] {9, 1, 2, 3, 9});
        buffer.position(1).limit(4);
        BinaryData data = BinaryData.takeOwnership(buffer);
        assertArrayEquals(new byte[] {1, 2, 3}, data.toByteArray());
        assertThrows(IndexOutOfBoundsException.class, () -> data.slice(2, 2));
    }

    @Test
    void paletteOwnershipCanBeConsumedExactlyOnce() {
        long[] original = {123L};
        PackedPalette<String> palette = PackedPalette.takeOwnership(List.of("a", "b"), 1, original);
        assertSame(original, palette.takeStorage());
        assertThrows(IllegalStateException.class, palette::takeStorage);
        assertThrows(IllegalArgumentException.class, () -> PackedPalette.copyOf(List.of("a", "b"), 0, original));
        assertThrows(IllegalArgumentException.class, () -> PackedPalette.copyOf(List.of("a", "b", "c"), 1, original));
    }

    @Test
    void keysCannotEscapeTheirNamespaceAndEnumerationIsBounded() {
        for (String path : List.of("/absolute", "../outside", "a/../b", "a//b", "a\\b", "a/./b")) {
            assertThrows(IllegalArgumentException.class, () -> new WorldRecordKey.Asset(path));
        }
        assertEquals("generated/a.nbt", new WorldRecordKey.Asset("generated/a.nbt").relativePath());
        assertThrows(IllegalArgumentException.class, () -> new RecordQuery(Optional.empty(), 0));
        assertThrows(IllegalArgumentException.class, () -> new RecordQuery(Optional.empty(), 10001));
    }
}
