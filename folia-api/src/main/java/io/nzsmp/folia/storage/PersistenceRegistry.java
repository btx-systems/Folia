package io.nzsmp.folia.storage;

import net.kyori.adventure.key.Key;


/**
 * Bootstrap-only registration. Configuration selects providers explicitly.
 */
public interface PersistenceRegistry {

    /**
     * Registers a world storage factory during bootstrap.
     *
     * @param id       logical resource key
     * @param provider provider factory
     */
    void registerWorldStorage(Key id, WorldStorageProvider provider);

    /**
     * Registers a player storage factory during bootstrap.
     *
     * @param id       logical resource key
     * @param provider provider factory
     */
    void registerPlayerStorage(Key id, PlayerStorageProvider provider);
}
