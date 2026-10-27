package io.nzsmp.folia.storage;

import io.nzsmp.folia.concurrent.AsyncWorkQueue;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Per-key ordering, bounded admission, retries and observable durable barriers. */
public final class ManagedStore<K> implements RecordStore<K> {
    private final RecordStore<K> delegate;
    private final Map<K, CompletableFuture<Void>> tails = new HashMap<>();
    private final AsyncWorkQueue queue;
    private final Executor executor;
    private final long timeoutMillis;
    private final long byteLimit;
    private final int operationLimit;
    private final Consumer<Throwable> fatal;
    private long pendingBytes;
    private int pendingOperations;
    private boolean closing;
    private volatile Throwable failed;

    public ManagedStore(final RecordStore<K> delegate, final Executor executor, final Duration timeout,
                        final int concurrency, final int queued, final long byteLimit, final Consumer<Throwable> fatal) {
        this.delegate = Objects.requireNonNull(delegate);
        this.executor = executor;
        this.timeoutMillis = timeout.toMillis();
        if (this.timeoutMillis < 1 || byteLimit < 1) throw new IllegalArgumentException("Invalid storage limits");
        this.byteLimit = byteLimit;
        this.operationLimit = Math.addExact(concurrency, queued);
        this.fatal = fatal;
        this.queue = new AsyncWorkQueue(concurrency, queued, executor);
    }

    private <T> CompletableFuture<T> ordered(final K key, final Supplier<CompletionStage<T>> work) {
        final CompletableFuture<Void> previous;
        final CompletableFuture<Void> tail = new CompletableFuture<>();
        synchronized (this) {
            if (this.closing) return CompletableFuture.failedFuture(new IllegalStateException("Store is closing"));
            if (this.failed != null) return CompletableFuture.failedFuture(this.failed);
            if (this.pendingOperations >= this.operationLimit) {
                final IllegalStateException error = new IllegalStateException("Too many pending storage keys");
                this.fail(error);
                return CompletableFuture.failedFuture(error);
            }
            ++this.pendingOperations;
            previous = this.tails.put(key, tail);
        }
        final CompletableFuture<T> result = (previous == null ? CompletableFuture.<Void>completedFuture(null) : previous)
            .thenCompose(ignored -> this.queue.submit(() -> 0, work));
        result.whenComplete((value, error) -> {
            if (error != null) {
                this.fail(error);
                tail.completeExceptionally(error);
            } else tail.complete(null);
            synchronized (this) { this.tails.remove(key, tail); --this.pendingOperations; }
        });
        return result;
    }

    private <T> CompletionStage<T> orderedBatch(final java.util.List<K> keys, final Supplier<CompletionStage<T>> work) {
        final var unique = java.util.List.copyOf(new java.util.LinkedHashSet<>(keys));
        final CompletableFuture<Void> tail = new CompletableFuture<>();
        final java.util.List<CompletableFuture<Void>> previous = new java.util.ArrayList<>();
        synchronized (this) {
            if (this.closing) return CompletableFuture.failedFuture(new IllegalStateException("Store is closing"));
            if (this.failed != null) return CompletableFuture.failedFuture(this.failed);
            if (this.pendingOperations + unique.size() > this.operationLimit) {
                final IllegalStateException error = new IllegalStateException("Too many pending storage operations");
                this.fail(error);
                return CompletableFuture.failedFuture(error);
            }
            this.pendingOperations += unique.size();
            for (final K key : unique) {
                final CompletableFuture<Void> before = this.tails.put(key, tail);
                if (before != null) previous.add(before);
            }
        }
        final CompletionStage<T> result = CompletableFuture.allOf(previous.toArray(CompletableFuture[]::new))
            .thenCompose(ignored -> this.queue.submit(() -> 0, work));
        result.whenComplete((value, error) -> {
            if (error == null) tail.complete(null);
            else { this.fail(error); tail.completeExceptionally(error); }
            synchronized (this) {
                for (final K key : unique) this.tails.remove(key, tail);
                this.pendingOperations -= unique.size();
            }
        });
        return result;
    }

    @Override
    public CompletionStage<Map<K, Optional<StorageRecord>>> readBatch(final java.util.List<K> keys) {
        if (keys.isEmpty()) return CompletableFuture.completedFuture(Map.of());
        final var unique = java.util.List.copyOf(new java.util.LinkedHashSet<>(keys));
        return this.orderedBatch(unique, () -> this.attempt(() -> this.delegate.readBatch(unique), 2).thenApply(records -> {
            if (records == null || !records.keySet().equals(new java.util.HashSet<>(unique))
                || records.values().stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Incomplete batch read result");
            return Map.copyOf(records);
        }));
    }

    @Override
    public CompletionStage<Void> writeBatch(final Map<K, StorageRecord> records) {
        if (records.isEmpty()) return CompletableFuture.completedFuture(null);
        final Map<K, StorageRecord> snapshot = Map.copyOf(records);
        final long size = snapshot.values().stream().mapToLong(record -> record.payload().size()).sum();
        synchronized (this) {
            if (size > this.byteLimit - this.pendingBytes) {
                final IllegalStateException error = new IllegalStateException("Pending storage byte limit exceeded");
                this.fail(error);
                return CompletableFuture.failedFuture(error);
            }
            this.pendingBytes += size;
        }
        return this.orderedBatch(java.util.List.copyOf(snapshot.keySet()), () -> this.attempt(() -> this.delegate.writeBatch(snapshot), 2))
            .whenComplete((ignored, error) -> { synchronized (this) { this.pendingBytes -= size; } });
    }

    private <T> CompletionStage<T> attempt(final Supplier<CompletionStage<T>> action, final int remaining) {
        final CompletableFuture<T> result;
        try {
            result = Objects.requireNonNull(action.get(), "Null storage stage").toCompletableFuture();
        } catch (Throwable error) {
            return this.retry(action, remaining, error);
        }
        // Do not mutate the provider's future. A timeout has an uncertain outcome and
        // MUST poison the key/store rather than permit a later stale write to race a new one.
        return result.copy().orTimeout(this.timeoutMillis, TimeUnit.MILLISECONDS).handle((value, error) -> {
            if (error == null) return CompletableFuture.completedFuture(value);
            return this.<T>retry(action, remaining, error);
        }).thenCompose(stage -> stage);
    }

    private <T> CompletionStage<T> retry(final Supplier<CompletionStage<T>> action, final int remaining, final Throwable error) {
        Throwable cause = error;
        while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) cause = cause.getCause();
        if (remaining == 0 || cause instanceof TimeoutException || cause instanceof IllegalArgumentException) {
            return CompletableFuture.failedFuture(cause);
        }
        return CompletableFuture.supplyAsync(() -> null,
            CompletableFuture.delayedExecutor(100L << (2 - remaining), TimeUnit.MILLISECONDS, this.executor))
            .thenCompose(ignored -> this.attempt(action, remaining - 1));
    }

    public void worldReady(final ChunkRecordCodec codec) {
        if (this.delegate instanceof WorldStore world) world.onWorldReady(codec);
    }

    public void fail(final Throwable error) {
        boolean notify = false;
        synchronized (this) {
            if (this.failed == null) { this.failed = error; notify = true; }
        }
        if (notify) this.executor.execute(() -> this.fatal.accept(error));
    }

    @Override
    public CompletionStage<Optional<StorageRecord>> read(final K key) {
        return this.ordered(key, () -> this.attempt(() -> this.delegate.read(key), 2)
            .thenApply(value -> Objects.requireNonNull(value, "Null read result")));
    }

    @Override
    public CompletionStage<Void> write(final K key, final StorageRecord record) {
        return this.writePending(key, CompletableFuture.completedFuture(record));
    }

    /** Reserves order BEFORE serialization completes. A null record represents deletion. */
    public CompletionStage<Void> writePending(final K key, final CompletionStage<StorageRecord> snapshot) {
        return this.writePending(key, snapshot, ignored -> {});
    }

    public CompletionStage<Void> writePending(final K key, final CompletionStage<StorageRecord> snapshot,
                                               final Consumer<StorageRecord> accepted) {
        final java.util.concurrent.atomic.AtomicLong charged = new java.util.concurrent.atomic.AtomicLong();
        // Charge queued snapshots too, not just active backend calls.
        final CompletionStage<StorageRecord> tracked = snapshot.toCompletableFuture().copy().orTimeout(this.timeoutMillis, TimeUnit.MILLISECONDS).thenApply(record -> {
            final long size = record == null ? 0 : record.payload().size();
            synchronized (this) {
                if (size > this.byteLimit - this.pendingBytes) throw new IllegalStateException("Pending storage byte limit exceeded");
                this.pendingBytes += size;
                charged.set(size);
            }
            return record;
        });
        final CompletionStage<Void> result = this.ordered(key, () -> tracked.thenCompose(record -> {
            accepted.accept(record); // runs in key order, including when serialization completed out of order
            return this.attempt(() -> record == null ? this.delegate.delete(key) : this.delegate.write(key, record), 2);
        }));
        result.whenComplete((ignored, error) -> tracked.whenComplete((record, snapshotError) -> {
            synchronized (this) { this.pendingBytes -= charged.getAndSet(0L); }
        }));
        return result;
    }

    @Override public CompletionStage<Void> delete(final K key) { return this.writePending(key, CompletableFuture.completedFuture(null)); }

    @Override
    public CompletionStage<RecordPage<K>> list(final RecordQuery query) {
        return this.barrier().thenCompose(ignored -> this.queue.submit(() -> 0, () -> this.attempt(() -> this.delegate.list(query), 2)))
            .whenComplete((value, error) -> { if (error != null) this.fail(error); });
    }

    private CompletableFuture<Void> barrier() {
        synchronized (this) {
            if (this.failed != null) return CompletableFuture.failedFuture(this.failed);
            return CompletableFuture.allOf(this.tails.values().toArray(CompletableFuture[]::new));
        }
    }

    @Override
    public CompletionStage<Void> flush() {
        return this.barrier().thenCompose(ignored -> this.queue.submit(() -> 0, () -> this.attempt(this.delegate::flush, 2)))
            .whenComplete((value, error) -> { if (error != null) this.fail(error); });
    }

    @Override
    public CompletionStage<Void> close() {
        synchronized (this) {
            if (this.closing) return CompletableFuture.failedFuture(new IllegalStateException("Store already closing"));
            this.closing = true;
        }
        return this.flush().handle((ignored, error) -> error).thenCompose(flushError ->
            this.attempt(this.delegate::close, 0).handle((ignored, closeError) -> {
                if (flushError != null) throw new java.util.concurrent.CompletionException(flushError);
                if (closeError != null) throw new java.util.concurrent.CompletionException(closeError);
                return (Void) null;
            }));
    }
}
