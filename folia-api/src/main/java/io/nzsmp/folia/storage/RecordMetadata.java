package io.nzsmp.folia.storage;

import java.util.Objects;


/**
 * Server payload format, Minecraft DataVersion (-1 for unversioned assets), and epoch milliseconds.
 *
 * @param mediaType          canonical payload content type
 * @param dataVersion        Minecraft DataVersion, or -1 for unversioned data
 * @param modifiedTimeMillis last modification time in epoch milliseconds
 */
public record RecordMetadata(String mediaType, int dataVersion, long modifiedTimeMillis) {

    /**
     * Creates and validates this value.
     *
     * @param mediaType          canonical payload content type
     * @param dataVersion        Minecraft DataVersion, or -1 for unversioned data
     * @param modifiedTimeMillis last modification time in epoch milliseconds
     */
    public RecordMetadata {
        Objects.requireNonNull(mediaType, "mediaType");
    }
}
