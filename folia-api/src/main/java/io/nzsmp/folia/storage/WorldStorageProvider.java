package io.nzsmp.folia.storage;

import java.util.concurrent.CompletionStage;


/**
 * Logical WorldStorageProvider contract.
 */
@FunctionalInterface
public interface WorldStorageProvider {

    /**
     * Opens a server-owned storage session.
     *
     * @param context context value
     * @return the operation result
     */
    CompletionStage<WorldStore> open(WorldStorageContext context);
}
