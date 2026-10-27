package sh.btx.nzsmp.folia.generation;

import java.util.function.Consumer;

/** Thread-safe request lifecycle. Listener callbacks must not block or throw. */
public interface RequestControl {
    /**
     * Returns the current request priority.
     *
     * @return current priority
     */
    RequestPriority priority();

    /**
     * Returns whether cancellation has been requested.
     *
     * @return whether the request was cancelled
     */
    boolean isCancelled();

    /**
     * Registers a listener, including when cancellation has already happened.
     *
     * @param callback nonblocking cancellation listener
     * @return a subscription that removes the listener
     */
    Subscription onCancellation(Runnable callback);

    /**
     * Registers a listener and immediately reports the current priority.
     *
     * @param callback nonblocking priority listener
     * @return a subscription that removes the listener
     */
    Subscription onPriorityChange(Consumer<RequestPriority> callback);

    /** Removes a listener. Closing more than once is safe. */
    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
