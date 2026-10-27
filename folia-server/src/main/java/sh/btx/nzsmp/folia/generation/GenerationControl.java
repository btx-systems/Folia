package sh.btx.nzsmp.folia.generation;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Server-owned request lifecycle, including listener races and late subscribers. */
public final class GenerationControl implements RequestControl {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CopyOnWriteArrayList<Runnable> cancellation = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<RequestPriority>> priorities = new CopyOnWriteArrayList<>();
    private volatile RequestPriority priority;

    public GenerationControl(final RequestPriority priority) {
        this.priority = Objects.requireNonNull(priority);
    }

    @Override
    public RequestPriority priority() { return this.priority; }

    @Override
    public boolean isCancelled() { return this.cancelled.get(); }

    @Override
    public Subscription onCancellation(final Runnable callback) {
        final AtomicBoolean delivered = new AtomicBoolean();
        final Runnable once = () -> { if (delivered.compareAndSet(false, true)) callback.run(); };
        this.cancellation.add(once);
        if (this.cancelled.get()) {
            this.cancellation.remove(once);
            safely(once);
        }
        return () -> this.cancellation.remove(once);
    }

    @Override
    public Subscription onPriorityChange(final Consumer<RequestPriority> callback) {
        this.priorities.add(callback);
        safely(() -> callback.accept(this.priority));
        return () -> this.priorities.remove(callback);
    }

    public void cancel() {
        if (this.cancelled.compareAndSet(false, true)) {
            this.cancellation.forEach(GenerationControl::safely);
            this.cancellation.clear();
            this.priorities.clear();
        }
    }

    public void priority(final RequestPriority next) {
        this.priority = next;
        this.priorities.forEach(callback -> safely(() -> callback.accept(this.priority)));
    }

    private static void safely(final Runnable callback) {
        try { callback.run(); } catch (Throwable failure) {
            org.slf4j.LoggerFactory.getLogger(GenerationControl.class).warn("Generation listener failed", failure);
        }
    }
}
