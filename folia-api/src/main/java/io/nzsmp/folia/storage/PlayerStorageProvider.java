package io.nzsmp.folia.storage;

import java.util.concurrent.CompletionStage;


/**
 * Logical PlayerStorageProvider contract.
 */
@FunctionalInterface
public interface PlayerStorageProvider {

    /**
     * Opens a server-owned storage session.
     *
     * @param context context value
     * @return the operation result
     */
    CompletionStage<PlayerStore> open(PlayerStorageContext context);
}
