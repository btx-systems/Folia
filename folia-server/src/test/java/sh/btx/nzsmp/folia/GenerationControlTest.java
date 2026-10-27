package sh.btx.nzsmp.folia;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import sh.btx.nzsmp.folia.generation.GenerationControl;
import sh.btx.nzsmp.folia.generation.RequestPriority;
import static org.junit.jupiter.api.Assertions.*;

class GenerationControlTest {
    @Test
    void concurrentAndLateCancellationSubscriptionsFireOnce() {
        for (int i = 0; i < 250; ++i) {
            var control = new GenerationControl(RequestPriority.NORMAL);
            var count = new AtomicInteger();
            var subscribe = CompletableFuture.runAsync(() -> control.onCancellation(count::incrementAndGet));
            var cancel = CompletableFuture.runAsync(control::cancel);
            CompletableFuture.allOf(subscribe, cancel).join();
            control.cancel();
            assertEquals(1, count.get());
            control.onCancellation(count::incrementAndGet);
            assertEquals(2, count.get());
        }
    }

    @Test
    void subscriptionsCanBeRemovedAndPriorityChangesAreVisible() {
        var control = new GenerationControl(RequestPriority.NORMAL);
        var cancellations = new AtomicInteger();
        control.onCancellation(cancellations::incrementAndGet).close();
        var changes = new AtomicInteger();
        var subscription = control.onPriorityChange(value -> changes.incrementAndGet());
        control.priority(RequestPriority.HIGH);
        subscription.close();
        control.priority(RequestPriority.LOW);
        control.cancel();
        assertEquals(0, cancellations.get());
        assertEquals(2, changes.get());
        assertEquals(RequestPriority.LOW, control.priority());
    }
}
