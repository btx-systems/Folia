package io.nzsmp.folia;

import io.nzsmp.folia.concurrent.AsyncWorkQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AsyncWorkQueueTest {
    @Test
    void waitingStagesReleaseExecutorAndAdmissionIsBounded() {
        var queue = new AsyncWorkQueue(1, 2, Runnable::run);
        var pending = new CompletableFuture<Integer>();
        var order = new ArrayList<Integer>();
        var first = queue.submit(() -> 3, () -> pending);
        var low = queue.submit(() -> 5, () -> { order.add(5); return CompletableFuture.completedFuture(5); });
        var high = queue.submit(() -> 1, () -> { order.add(1); return CompletableFuture.completedFuture(1); });
        assertTrue(queue.submit(() -> 0, () -> CompletableFuture.completedFuture(0)).isCompletedExceptionally());
        assertFalse(first.isDone());
        assertFalse(low.isDone());
        pending.complete(3);
        assertEquals(List.of(1, 5), order);
        assertEquals(5, low.join());
        assertEquals(1, high.join());
    }

    @Test
    void cancelledQueuedRequestsDoNotInvokeProviders() {
        var queue = new AsyncWorkQueue(1, 1, Runnable::run);
        var first = new CompletableFuture<Void>();
        queue.submit(() -> 0, () -> first);
        var calls = new AtomicInteger();
        var next = queue.submit(() -> 0, () -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(null); });
        next.cancel(false);
        first.complete(null);
        assertEquals(0, calls.get());
        assertTrue(queue.submit(() -> 0, () -> CompletableFuture.completedFuture(1)).isDone());
    }
}
