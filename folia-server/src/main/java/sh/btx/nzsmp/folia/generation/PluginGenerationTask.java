package sh.btx.nzsmp.folia.generation;

import ca.spottedleaf.concurrentutil.executor.PrioritisedExecutor;
import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.task.ChunkProgressionTask;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.key.Key;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import sh.btx.nzsmp.folia.concurrent.AsyncWorkQueue;

/** Suspends the first generation step until a provider supplies complete terrain. */
public final class PluginGenerationTask extends ChunkProgressionTask {
    private static final Executor PROVIDERS = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("plugin-worldgen-", 0).factory());
    private static final Map<ServerLevel, AsyncWorkQueue> QUEUES = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final AsyncChunkGenerator generator;
    private final ChunkAccess target;
    private final GenerationControl control;
    private final PrioritisedExecutor.PrioritisedTask importTask;
    private volatile boolean scheduled;
    private boolean done;
    private boolean importing;
    private volatile boolean cancelled;
    private volatile CompletableFuture<GeneratedChunk> pending;
    private volatile GeneratedChunk result;
    private volatile Throwable failure;
    private volatile RegistryLayout layout;

    public PluginGenerationTask(final ChunkTaskScheduler scheduler, final ServerLevel world, final int x, final int z,
                                final ChunkAccess target, final AsyncChunkGenerator generator, final Priority priority) {
        super(scheduler, world, x, z);
        this.generator = generator;
        this.target = target;
        this.control = new GenerationControl(apiPriority(priority));
        this.importTask = scheduler.parallelGenExecutor.createTask(this::importResult, priority);
    }

    @Override public ChunkStatus getTargetStatus() { return ChunkStatus.STRUCTURE_STARTS; }
    @Override public boolean isScheduled() { return this.scheduled; }

    @Override
    public void schedule() {
        synchronized (this) {
            if (this.scheduled) throw new IllegalStateException("Already scheduled");
            this.scheduled = true;
            if (this.done) return;
        }
        final AsyncWorkQueue queue = QUEUES.computeIfAbsent(this.world, ignored -> new AsyncWorkQueue(
            Integer.getInteger("folia.generation.max-inflight", 64), Integer.getInteger("folia.generation.max-queued", 4096), PROVIDERS));
        this.pending = queue.submit(() -> this.control.priority().ordinal(), () -> {
            if (this.cancelled) return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException());
            this.layout = RegistryLayouts.forWorld(this.world);
            final GenerationWorld info = new GenerationWorld(this.world.getWorld().getKey(), Key.key(this.world.dimension().identifier().toString()),
                this.world.getSeed(), this.world.getMinY(), this.world.getMaxY() + 1);
            return this.generator.generate(new GenerationRequest(info, this.chunkX, this.chunkZ, this.layout, this.control))
                .toCompletableFuture().copy().orTimeout(Long.getLong("folia.generation.timeout-seconds", 30L), TimeUnit.SECONDS);
        });
        if (this.cancelled) this.pending.cancel(false);
        this.pending.whenComplete((value, error) -> {
            synchronized (this) {
                if (this.done) return;
                this.result = value;
                this.failure = error;
            }
            this.importTask.queue();
        });
    }

    private void importResult() {
        synchronized (this) {
            if (this.done) return;
            this.importing = true;
        }
        ChunkAccess imported = null;
        Throwable error = this.failure;
        try {
            if (!this.cancelled && error == null) {
                imported = ChunkImporter.apply(this.world, this.target, java.util.Objects.requireNonNull(this.result, "Null generation result"), this.layout);
            }
        } catch (Throwable failure) {
            error = failure;
        }
        synchronized (this) {
            this.importing = false;
            this.done = true;
        }
        this.result = null;
        this.complete(this.cancelled ? null : imported, this.cancelled ? null : error);
    }

    @Override
    public void cancel() {
        final boolean defer;
        synchronized (this) {
            if (this.done) return;
            this.cancelled = true;
            defer = this.importing;
            if (!defer) this.done = true;
        }
        // Never invoke plugin listeners while holding the chunk scheduling lock.
        PROVIDERS.execute(this.control::cancel);
        if (defer) return; // importer must finish before releasing chunk ownership
        this.importTask.cancel();
        final CompletableFuture<GeneratedChunk> future = this.pending;
        if (future != null) future.cancel(false);
        this.complete(null, null);
    }

    private static RequestPriority apiPriority(final Priority priority) {
        return switch (priority.name()) {
            case "BLOCKING" -> RequestPriority.BLOCKING;
            case "HIGHEST" -> RequestPriority.HIGHEST;
            case "HIGH", "HIGHER" -> RequestPriority.HIGH;
            case "LOW", "LOWER" -> RequestPriority.LOW;
            case "LOWEST" -> RequestPriority.LOWEST;
            case "IDLE" -> RequestPriority.IDLE;
            default -> RequestPriority.NORMAL;
        };
    }

    @Override public Priority getPriority() { return this.importTask.getPriority(); }
    @Override public void lowerPriority(final Priority priority) { if (this.importTask.lowerPriority(priority)) this.changedPriority(); }
    @Override public void setPriority(final Priority priority) { if (this.importTask.setPriority(priority)) this.changedPriority(); }
    @Override public void raisePriority(final Priority priority) { if (this.importTask.raisePriority(priority)) this.changedPriority(); }
    private void changedPriority() { PROVIDERS.execute(() -> this.control.priority(apiPriority(this.getPriority()))); }
}
