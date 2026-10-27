package io.nzsmp.folia;

import io.nzsmp.folia.data.BinaryData;
import io.nzsmp.folia.storage.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManagedStoreTest {
    private static StorageRecord record(int value) {
        return new StorageRecord(new RecordMetadata("test", -1, 0), BinaryData.copyOf(new byte[] {(byte) value}));
    }

    private static class Memory implements RecordStore<String> {
        final Map<String, StorageRecord> values = new ConcurrentHashMap<>();
        final AtomicInteger reads = new AtomicInteger();
        CompletableFuture<Void> durable = CompletableFuture.completedFuture(null);
        @Override public CompletionStage<Optional<StorageRecord>> read(String key) { reads.incrementAndGet(); return CompletableFuture.completedFuture(Optional.ofNullable(values.get(key))); }
        @Override public CompletionStage<Void> write(String key, StorageRecord record) { return durable.thenRun(() -> values.put(key, record)); }
        @Override public CompletionStage<Void> delete(String key) { values.remove(key); return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<RecordPage<String>> list(RecordQuery query) { return CompletableFuture.completedFuture(new RecordPage<>(List.copyOf(values.keySet()), Optional.empty())); }
        @Override public CompletionStage<Void> flush() { return durable; }
        @Override public CompletionStage<Void> close() { return CompletableFuture.completedFuture(null); }
    }

    @Test
    void reservationsPreventSerializationAndReadReorderingAndFlushWaitsForDurability() {
        var backend = new Memory();
        var store = new ManagedStore<>(backend, Runnable::run, Duration.ofSeconds(1), 2, 20, 1024, error -> fail(error));
        var first = new CompletableFuture<StorageRecord>();
        var second = new CompletableFuture<StorageRecord>();
        store.writePending("chunk", first);
        store.writePending("chunk", second);
        var read = store.read("chunk").toCompletableFuture();
        var flush = store.flush().toCompletableFuture();
        second.complete(record(2));
        assertEquals(0, backend.reads.get());
        assertFalse(flush.isDone());
        backend.durable = new CompletableFuture<>();
        first.complete(record(1));
        assertFalse(read.isDone());
        backend.durable.complete(null);
        assertEquals(2, read.join().orElseThrow().payload().toByteArray()[0]);
        assertTrue(flush.isDone());
    }

    @Test
    void nativeBatchesRespectEarlierSerializationReservations() {
        var calls = new AtomicInteger();
        var backend = new Memory() {
            @Override public CompletionStage<Map<String, Optional<StorageRecord>>> readBatch(List<String> keys) {
                calls.incrementAndGet();
                return super.readBatch(keys);
            }
        };
        var store = new ManagedStore<>(backend, Runnable::run, Duration.ofSeconds(1), 2, 20, 1024, error -> fail(error));
        var pending = new CompletableFuture<StorageRecord>();
        store.writePending("a", pending);
        var batch = store.readBatch(List.of("a", "b")).toCompletableFuture();
        assertEquals(0, calls.get());
        pending.complete(record(7));
        assertEquals(1, calls.get());
        assertEquals(7, batch.join().get("a").orElseThrow().payload().toByteArray()[0]);
        assertTrue(batch.join().get("b").isEmpty());
    }

    @Test
    void missingIsDistinctFromFailureAndFailedStoreIsPoisoned() {
        var failures = new AtomicInteger();
        var store = new ManagedStore<>(new Memory(), Runnable::run, Duration.ofSeconds(1), 2, 20, 1024, error -> failures.incrementAndGet());
        assertTrue(store.read("absent").toCompletableFuture().join().isEmpty());
        store.writePending("bad", CompletableFuture.failedFuture(new IllegalArgumentException("corrupt")));
        assertTrue(store.read("other").toCompletableFuture().isCompletedExceptionally());
        assertTrue(store.flush().toCompletableFuture().isCompletedExceptionally());
        assertEquals(1, failures.get());
    }

    @Test
    void uncertainTimeoutDoesNotRetryOrPermitSubsequentWrites() throws Exception {
        var backend = new Memory();
        backend.durable = new CompletableFuture<>();
        var failure = new CompletableFuture<Throwable>();
        var store = new ManagedStore<>(backend, Runnable::run, Duration.ofMillis(20), 1, 20, 1024, failure::complete);
        var write = store.write("key", record(1)).toCompletableFuture();
        assertThrows(java.util.concurrent.ExecutionException.class, () -> write.get(3, java.util.concurrent.TimeUnit.SECONDS));
        assertNotNull(failure.get(3, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(store.write("key", record(2)).toCompletableFuture().isCompletedExceptionally());
        assertFalse(backend.durable.isDone());
    }
}
