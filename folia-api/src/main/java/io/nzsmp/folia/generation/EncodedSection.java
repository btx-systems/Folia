package io.nzsmp.folia.generation;

import io.nzsmp.folia.data.BinaryData;


/**
 * One complete, versioned network section: counts, block container, biome container.
 *
 * @param sectionY absolute section Y coordinate
 * @param bytes    binary payload
 */
public record EncodedSection(int sectionY, BinaryData bytes) {
}
