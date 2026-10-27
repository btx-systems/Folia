package sh.btx.nzsmp.folia.data;

import java.nio.ByteBuffer;
import java.util.Objects;


/**
 * An owned, read-only binary payload. Views do not copy the backing storage.
 */
public final class BinaryData {
    private final ByteBuffer bytes;

    private BinaryData(final ByteBuffer bytes) {
        this.bytes = bytes.slice().asReadOnlyBuffer();
    }

    /**
     * Copies the remaining bytes; the caller retains ownership of the source.
     *
     * @param bytes binary payload
     * @return the operation result
     */
    public static BinaryData copyOf(final ByteBuffer bytes) {
        final ByteBuffer copy = ByteBuffer.allocate(bytes.remaining());
        copy.put(bytes.duplicate()).flip();
        return new BinaryData(copy);
    }

    /**
     * Copies an array; subsequent changes to the array are harmless.
     *
     * @param bytes binary payload
     * @return the operation result
     */
    public static BinaryData copyOf(final byte[] bytes) {
        return copyOf(ByteBuffer.wrap(bytes));
    }

    /**
     * Transfers exclusive ownership of the remaining bytes without copying.
     * The caller MUST NOT subsequently mutate, reuse, or release their backing storage.
     * Pooled buffers must be copied before transfer. Views share the same ownership.
     *
     * @param bytes binary payload
     * @return the operation result
     */
    public static BinaryData takeOwnership(final ByteBuffer bytes) {
        return new BinaryData(Objects.requireNonNull(bytes, "bytes"));
    }

    /**
     * Returns an independently positioned read-only view.
     *
     * @return the operation result
     */
    public ByteBuffer buffer() {
        return this.bytes.asReadOnlyBuffer();
    }

    /**
     * Returns a view of a range, without copying.
     *
     * @param offset byte offset
     * @param length byte length
     * @return the operation result
     */
    public BinaryData slice(final int offset, final int length) {
        return new BinaryData(this.bytes.slice(offset, length));
    }

    /**
     * Returns the payload size in bytes.
     *
     * @return the operation result
     */
    public int size() {
        return this.bytes.remaining();
    }

    /**
     * Copies the payload to a new array.
     *
     * @return the operation result
     */
    public byte[] toByteArray() {
        final byte[] copy = new byte[this.size()];
        this.bytes.duplicate().get(copy);
        return copy;
    }
}
