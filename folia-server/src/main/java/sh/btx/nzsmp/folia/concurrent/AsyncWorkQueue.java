package sh.btx.nzsmp.folia.concurrent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Bounded asynchronous admission. Pending stages never occupy an executor thread. */
public final class AsyncWorkQueue {
    private final int parallelism;
    private final int capacity;
    private final Executor executor;
    private final ArrayList<Job<?>> queued = new ArrayList<>();
    private int active;

    public AsyncWorkQueue(final int parallelism, final int capacity, final Executor executor) {
        if (parallelism < 1 || capacity < 0) throw new IllegalArgumentException("Invalid queue limits");
        this.parallelism = parallelism;
        this.capacity = capacity;
        this.executor = executor;
    }

    public <T> CompletableFuture<T> submit(final IntSupplier priority, final Supplier<CompletionStage<T>> work) {
        final Job<T> job = new Job<>(priority, work);
        boolean start;
        synchronized (this) {
            start = this.active < this.parallelism;
            if (start) ++this.active;
            else if (this.queued.size() < this.capacity) this.queued.add(job);
            else return CompletableFuture.failedFuture(new RejectedExecutionException("Plugin operation queue is full"));
        }
        if (start) this.dispatch(job);
        return job.result;
    }

    private void dispatch(final Job<?> job) {
        try {
            this.executor.execute(() -> job.run(this::finished));
        } catch (Throwable failure) {
            job.result.completeExceptionally(failure);
            this.finished();
        }
    }

    private void finished() {
        final Job<?> next;
        synchronized (this) {
            next = this.queued.stream().min(Comparator.comparingInt(job -> job.priority.getAsInt())).orElse(null);
            if (next == null) --this.active;
            else this.queued.remove(next);
        }
        if (next != null) this.dispatch(next);
    }

    private static final class Job<T> {
        private final IntSupplier priority;
        private final Supplier<CompletionStage<T>> work;
        private final CompletableFuture<T> result = new CompletableFuture<>();

        private Job(final IntSupplier priority, final Supplier<CompletionStage<T>> work) {
            this.priority = priority;
            this.work = work;
        }

        private void run(final Runnable finished) {
            if (this.result.isCancelled()) {
                finished.run();
                return;
            }
            try {
                Objects.requireNonNull(this.work.get(), "Provider returned null stage").whenComplete((value, error) -> {
                    if (error == null) this.result.complete(value);
                    else this.result.completeExceptionally(error);
                    finished.run();
                });
            } catch (Throwable failure) {
                this.result.completeExceptionally(failure);
                finished.run();
            }
        }
    }
}
