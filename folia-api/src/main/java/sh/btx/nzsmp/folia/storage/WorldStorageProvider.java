package sh.btx.nzsmp.folia.storage;

import java.util.concurrent.CompletionStage;


/**
 * Opens independent root-metadata and dimension storage sessions for a logical world.
 */
@FunctionalInterface
public interface WorldStorageProvider {

    /**
     * Opens a server-owned storage session.
     *
     * @param context logical world identity and optional dimension
     * @return a stage supplying the opened store
     */
    CompletionStage<WorldStore> open(WorldStorageContext context);
}
