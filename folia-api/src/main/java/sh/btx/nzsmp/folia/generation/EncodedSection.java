package sh.btx.nzsmp.folia.generation;

import sh.btx.nzsmp.folia.data.BinaryData;


/**
 * One complete, versioned network section: counts, block container, biome container.
 *
 * @param sectionY absolute section Y coordinate
 * @param bytes    binary payload
 */
public record EncodedSection(int sectionY, BinaryData bytes) {
}
