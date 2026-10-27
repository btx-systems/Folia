package io.nzsmp.folia.storage;

import java.util.concurrent.CompletionStage;


/**
 * Opens player storage for a configured namespace independent of dimensions.
 */
@FunctionalInterface
public interface PlayerStorageProvider {

    /**
     * Opens a server-owned storage session.
     *
     * @param context logical player namespace
     * @return a stage supplying the opened store
     */
    CompletionStage<PlayerStore> open(PlayerStorageContext context);
}
