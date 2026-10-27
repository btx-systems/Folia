package io.nzsmp.folia.generation;

import io.nzsmp.folia.data.BinaryData;
import java.util.Objects;


/**
 * Uncompressed, named-root compound chunk NBT, including coordinates and DataVersion.
 *
 * @param dataVersion Minecraft DataVersion, or -1 for unversioned data
 * @param nbt         uncompressed named-root chunk NBT
 */
public record SerializedChunkData(int dataVersion, BinaryData nbt) implements GeneratedChunk {
    
    /**
     * Validates and constructs this value.
     *
     * @param dataVersion Minecraft DataVersion, or -1 for unversioned data
     * @param nbt         uncompressed named-root chunk NBT
     */
    public SerializedChunkData {
        Objects.requireNonNull(nbt, "nbt");
    }
}
