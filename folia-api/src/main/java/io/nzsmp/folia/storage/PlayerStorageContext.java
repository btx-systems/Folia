package io.nzsmp.folia.storage;

/**
 * Stable configured namespace for players; not their current world UUID.
 *
 * @param scopeId configured player namespace
 */
public record PlayerStorageContext(String scopeId) {
}
