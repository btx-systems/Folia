package io.nzsmp.folia.generation;

import java.util.concurrent.CompletionStage;
import org.bukkit.generator.ChunkGenerator;


/**
 * Supplies complete terrain asynchronously, using normal Bukkit generator selection.
 * Calls may run concurrently on server generation workers. Return promptly: never
 * wait for network I/O, acquire blocking permits, or access live world/chunk state.
 * The server handles lighting, POI reconciliation, heightmaps and chunk activation.
 * Vanilla terrain, decorations, structures and initial mob generation are bypassed.
 */
public abstract class AsyncChunkGenerator extends ChunkGenerator {
    
    /**
     * Returns a non-null stage and non-null result; completion may occur on any thread.
     *
     * @param request generation request
     * @return the operation result
     */
    public abstract CompletionStage<GeneratedChunk> generate(GenerationRequest request);
}
