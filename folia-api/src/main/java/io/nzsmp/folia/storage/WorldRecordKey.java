package io.nzsmp.folia.storage;

import java.util.Arrays;
import java.util.Objects;
import net.kyori.adventure.key.Key;


/**
 * Logical keys inside one world/dimension store, independent of physical layout.
 */
public sealed interface WorldRecordKey permits WorldRecordKey.Chunk, WorldRecordKey.SavedData, WorldRecordKey.Metadata, WorldRecordKey.Asset {

    /**
     * Logical ChunkKind contract.
     */
    enum ChunkKind {

        /**
         * terrain category.
         */
        TERRAIN,

        /**
         * entities category.
         */
        ENTITIES,

        /**
         * poi category.
         */
        POI
    }

    /**
     * Logical Chunk contract.
     *
     * @param kind   record category
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     */
    record Chunk(ChunkKind kind, int chunkX, int chunkZ) implements WorldRecordKey {

        /**
         * Creates and validates this value.
         *
         * @param kind   record category
         * @param chunkX chunk X coordinate
         * @param chunkZ chunk Z coordinate
         */
        public Chunk {
            Objects.requireNonNull(kind, "kind");
        }
    }

    /**
     * Logical SavedData contract.
     *
     * @param id logical resource key
     */
    record SavedData(Key id) implements WorldRecordKey {

        /**
         * Creates and validates this value.
         *
         * @param id logical resource key
         */
        public SavedData {
            Objects.requireNonNull(id, "id");
        }
    }

    /**
     * Logical Metadata contract.
     *
     * @param name normalized metadata name
     */
    record Metadata(String name) implements WorldRecordKey {

        /**
         * Creates and validates this value.
         *
         * @param name normalized metadata name
         */
        public Metadata {
            validatePath(name);
        }
    }

    /**
     * Logical Asset contract.
     *
     * @param relativePath normalized relative asset path
     */
    record Asset(String relativePath) implements WorldRecordKey {

        /**
         * Creates and validates this value.
         *
         * @param relativePath normalized relative asset path
         */
        public Asset {
            validatePath(relativePath);
        }
    }

    private static void validatePath(final String value) {
        if (value == null || value.isBlank() || value.startsWith("/") || value.indexOf('\\') >= 0
            || Arrays.asList(value.split("/", -1)).stream().anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals(".."))) {
            throw new IllegalArgumentException("Expected a normalized relative path");
        }
    }
}
