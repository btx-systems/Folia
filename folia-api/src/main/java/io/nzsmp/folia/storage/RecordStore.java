package io.nzsmp.folia.storage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;


/**
 * Asynchronous persistence of logical records. Methods must return promptly and
 * may be called concurrently. Missing is Optional.empty(); failures are exceptional.
 * A successful write/delete means an atomic durable update of that key. Batch
 * operations are not cross-record transactions. The server serializes operations
 * per key and calls flush before close. Provider-owned resources must survive the
 * plugin's onDisable and are released only by close. Do not schedule work on tick
 * threads from these callbacks, including when a synchronous compatibility caller waits.
 *
 * @param <K> logical key type
 */
public interface RecordStore<K> {
    
    /**
     * Reads a record; an empty result means it does not exist.
     *
     * @param key logical record key
     * @return the operation result
     */
    CompletionStage<Optional<StorageRecord>> read(K key);

    /**
     * Atomically persists one record.
     *
     * @param key    logical record key
     * @param record canonical record to write
     * @return the operation result
     */
    CompletionStage<Void> write(K key, StorageRecord record);

    /**
     * Durably deletes one record, if present.
     *
     * @param key logical record key
     * @return the operation result
     */
    CompletionStage<Void> delete(K key);

    /**
     * Lists a bounded page of logical keys.
     *
     * @param query pagination options
     * @return the operation result
     */
    CompletionStage<RecordPage<K>> list(RecordQuery query);

    /**
     * Default parallel read; providers can override to issue native batches.
     *
     * @param keys logical record keys
     * @return the operation result
     */
    default CompletionStage<Map<K, Optional<StorageRecord>>> readBatch(final List<K> keys) {
        final Map<K, CompletableFuture<Optional<StorageRecord>>> reads = new LinkedHashMap<>();
        keys.forEach(key -> reads.put(key, this.read(key).toCompletableFuture()));
        return CompletableFuture.allOf(reads.values().toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            final Map<K, Optional<StorageRecord>> result = new LinkedHashMap<>();
            reads.forEach((key, future) -> result.put(key, future.join()));
            return Map.copyOf(result);
        });
    }

    /**
     * Default parallel write; providers can override to issue native batches.
     *
     * @param records records to write
     * @return the operation result
     */
    default CompletionStage<Void> writeBatch(final Map<K, StorageRecord> records) {
        return CompletableFuture.allOf(records.entrySet().stream()
            .map(entry -> this.write(entry.getKey(), entry.getValue()).toCompletableFuture())
            .toArray(CompletableFuture[]::new));
    }

    /**
     * Waits for preceding durable writes.
     *
     * @return the operation result
     */
    CompletionStage<Void> flush();

    /**
     * Closes provider resources after the final flush.
     *
     * @return the operation result
     */
    CompletionStage<Void> close();
}
