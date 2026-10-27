package sh.btx.nzsmp.folia.generation;

import java.util.Objects;


/**
 * Identifies the exact network section encoding and block-state/biome ID mapping.
 * A descriptor is a compatibility assertion, not a converter. Providers must encode
 * using its associated RegistryLayout; assigning a new descriptor to old bytes is invalid.
 *
 * @param format              versioned encoding identifier
 * @param dataVersion         Minecraft DataVersion, or -1 for unversioned data
 * @param registryFingerprint digest of the ordered registry mappings
 */
public record SectionEncoding(String format, int dataVersion, String registryFingerprint) {
    
    /**
     * Validates and constructs this value.
     *
     * @param format              versioned encoding identifier
     * @param dataVersion         Minecraft DataVersion, or -1 for unversioned data
     * @param registryFingerprint digest of the ordered registry mappings
     */
    public SectionEncoding {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(registryFingerprint, "registryFingerprint");
    }
}
