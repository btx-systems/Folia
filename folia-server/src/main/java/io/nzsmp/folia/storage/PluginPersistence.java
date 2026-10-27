package io.nzsmp.folia.storage;

import ca.spottedleaf.concurrentutil.executor.Cancellable;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionFileType;
import io.nzsmp.folia.data.BinaryData;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Early bootstrap binding and adapters. No provider is used unless explicitly configured. */
public final class PluginPersistence implements PersistenceRegistry {
    public static final PluginPersistence INSTANCE = new PluginPersistence();
    private static final Logger LOGGER = LoggerFactory.getLogger(PluginPersistence.class);
    private static final Executor IO = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("plugin-storage-", 0).factory());
    private final Map<Key, WorldStorageProvider> worlds = new HashMap<>();
    private final Map<Key, PlayerStorageProvider> players = new HashMap<>();
    private final Map<Optional<Key>, ManagedStore<WorldRecordKey>> stores = new ConcurrentHashMap<>();
    private final Map<PlayerRecordKey, Optional<StorageRecord>> playerCache = new ConcurrentHashMap<>();
    private final Set<String> cachedAssets = ConcurrentHashMap.newKeySet();
    private volatile ManagedStore<PlayerRecordKey> playerStore;
    private volatile WorldStorageProvider worldProvider;
    private volatile Path root;
    private String worldId;
    private static Duration operationTimeout = Duration.ofSeconds(10);
    private static int concurrency = 64;
    private static int queueCapacity = 4096;
    private static long byteLimit = 256L * 1024 * 1024;
    private final Map<String, Key> dimensions = new ConcurrentHashMap<>();
    private final Map<String, String> assetStamps = new ConcurrentHashMap<>();
    private volatile boolean frozen;
    private volatile CompletableFuture<Void> assetScan = CompletableFuture.completedFuture(null);

    private PluginPersistence() {}

    @Override
    public synchronized void registerWorldStorage(final Key id, final WorldStorageProvider provider) {
        if (this.frozen || this.worlds.putIfAbsent(id, java.util.Objects.requireNonNull(provider)) != null) throw new IllegalStateException("Duplicate or late world provider: " + id);
    }

    @Override
    public synchronized void registerPlayerStorage(final Key id, final PlayerStorageProvider provider) {
        if (this.frozen || this.players.putIfAbsent(id, java.util.Objects.requireNonNull(provider)) != null) throw new IllegalStateException("Duplicate or late player provider: " + id);
    }

    public static void initialize(final Path worldRoot) throws IOException {
        final PluginPersistence self = INSTANCE;
        self.frozen = true;
        self.root = worldRoot.toAbsolutePath().normalize();
        final Path configFile = Path.of("config", "plugin-storage.yml");
        final YamlConfiguration config = loadConfiguration(configFile);
        operationTimeout = Duration.ofSeconds(config.getLong("limits.timeout-seconds", 10));
        concurrency = config.getInt("limits.max-inflight", 64);
        queueCapacity = config.getInt("limits.max-queued", 4096);
        byteLimit = config.getLong("limits.max-pending-bytes", 256L * 1024 * 1024);
        if (operationTimeout.isZero() || operationTimeout.isNegative() || concurrency < 1 || queueCapacity < 0 || byteLimit < 1) throw new IllegalArgumentException("Invalid plugin storage limits");
        Math.addExact(concurrency, queueCapacity);
        self.worldId = config.getString("world-id", worldRoot.getFileName().toString());
        final String world = config.getString("world-provider", "vanilla");
        final String player = config.getString("player-provider", "vanilla");
        if (world.equals("vanilla") && Files.exists(self.root.resolve(".plugin-storage-cache"))) {
            throw new IllegalStateException("Refusing vanilla storage for a plugin-owned world cache; restore its provider configuration or explicitly migrate the world");
        }
        if (player.equals("vanilla") && Files.exists(self.root.resolve(".plugin-player-cache"))) {
            throw new IllegalStateException("Refusing vanilla player storage for a plugin-owned player cache");
        }
        if (!world.equals("vanilla")) {
            self.worldProvider = self.worlds.get(Key.key(world));
            if (self.worldProvider == null) throw new IllegalStateException("Selected world storage provider did not register: " + world);
            // Never turn a real existing world into a disposable cache implicitly.
            final Path marker = self.root.resolve(".plugin-storage-cache");
            if (!Files.exists(marker) && Files.exists(self.root) && hasPersistentWorldFiles(self.root)) {
                throw new IllegalStateException("Custom storage requires a fresh cache directory; import the existing world explicitly first");
            }
            Files.createDirectories(self.root);
            final String identity = world + "\n" + self.worldId;
            if (Files.exists(marker) && !Files.readString(marker).equals(identity)) throw new IllegalStateException("Cache belongs to a different storage binding");
            Files.writeString(marker, identity);
            self.store(Optional.empty());
            self.hydrateAssets();
        }
        if (!player.equals("vanilla")) {
            final PlayerStorageProvider provider = self.players.get(Key.key(player));
            if (provider == null) throw new IllegalStateException("Selected player storage provider did not register: " + player);
            final String scope = config.getString("player-scope", self.worldId);
            final Path marker = self.root.resolve(".plugin-player-cache");
            final String identity = player + "\n" + scope;
            if (Files.exists(marker) && !Files.readString(marker).equals(identity)) throw new IllegalStateException("Player cache belongs to a different storage binding");
            self.playerStore = managed(await(provider.open(new PlayerStorageContext(scope))));
            Files.writeString(marker, identity);
        }
    }

    public static YamlConfiguration loadConfiguration(final Path file) throws IOException {
        final YamlConfiguration config = new YamlConfiguration();
        if (Files.exists(file)) {
            try { config.load(file.toFile()); }
            catch (org.bukkit.configuration.InvalidConfigurationException error) {
                throw new IOException("Malformed plugin storage configuration; refusing to fall back to vanilla", error);
            }
        }
        return config;
    }

    private static boolean hasPersistentWorldFiles(final Path root) throws IOException {
        try (var entries = Files.list(root)) {
            return entries.anyMatch(path -> !path.getFileName().toString().equals("session.lock"));
        }
    }

    private static <K> ManagedStore<K> managed(final RecordStore<K> store) {
        return new ManagedStore<>(store, IO, operationTimeout, concurrency, queueCapacity, byteLimit, PluginPersistence::fatal);
    }

    private ManagedStore<WorldRecordKey> store(final Optional<Key> dimension) {
        return this.stores.computeIfAbsent(dimension, key -> managed(await(this.worldProvider.open(new WorldStorageContext(this.worldId, key)))));
    }

    private static ManagedStore<WorldRecordKey> store(final ServerLevel world) {
        return INSTANCE.store(Optional.of(Key.key(world.dimension().identifier().toString())));
    }

    public static void declareDimensions(final java.util.Collection<Key> keys) {
        keys.forEach(key -> INSTANCE.dimensions.put("dimensions/" + key.namespace() + "/" + key.value(), key));
    }

    public static boolean worldEnabled() { return INSTANCE.worldProvider != null; }
    public static boolean playerEnabled() { return INSTANCE.playerStore != null; }

    public static boolean handles(final Path path) {
        return worldEnabled() && INSTANCE.root != null && path.toAbsolutePath().normalize().startsWith(INSTANCE.root);
    }

    private record Address(ManagedStore<WorldRecordKey> store, WorldRecordKey key) {}

    private static Address address(final Path path) {
        final String relative = INSTANCE.root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
        final String[] parts = relative.split("/");
        for (final var dimension : INSTANCE.dimensions.entrySet().stream().sorted(java.util.Comparator.<Map.Entry<String, Key>>comparingInt(entry -> entry.getKey().length()).reversed()).toList()) {
            final String prefix = dimension.getKey() + "/data/";
            if (!relative.startsWith(prefix)) continue;
            final String file = relative.substring(prefix.length()).replaceFirst("\\.dat$", "");
            final int slash = file.indexOf('/');
            final String id = slash < 0 ? "minecraft:" + file : file.substring(0, slash) + ":" + file.substring(slash + 1);
            return new Address(INSTANCE.store(Optional.of(dimension.getValue())), new WorldRecordKey.SavedData(Key.key(id)));
        }
        if (relative.startsWith("data/")) {
            final String id = relative.substring(5).replaceFirst("\\.dat$", "");
            final int slash = id.indexOf('/');
            return new Address(INSTANCE.store(Optional.empty()), new WorldRecordKey.SavedData(Key.key(slash < 0 ? "minecraft:" + id : id.substring(0, slash) + ":" + id.substring(slash + 1))));
        }
        return new Address(INSTANCE.store(Optional.empty()), new WorldRecordKey.Metadata(relative));
    }

    public static Optional<StorageRecord> read(final Path path) {
        final Address target = address(path);
        return await(target.store.read(target.key));
    }

    public static boolean exists(final Path path) {
        return handles(path) ? read(path).isPresent() : Files.exists(path);
    }

    public static CompoundTag readNbt(final Path path) throws IOException {
        final Optional<StorageRecord> record = read(path);
        if (record.isEmpty()) throw new java.io.FileNotFoundException(path.toString());
        return decode(record.get());
    }

    public static void writeNbt(final Path path, final CompoundTag tag) {
        final Address target = address(path);
        // Snapshot before asynchronous serialization; callers may reuse their tag.
        final CompoundTag snapshot = tag.copy();
        target.store.writePending(target.key, CompletableFuture.supplyAsync(() -> encode(snapshot), IO));
    }

    private static WorldRecordKey.Chunk chunkKey(final int x, final int z, final RegionFileType type) {
        return new WorldRecordKey.Chunk(switch (type) {
            case CHUNK_DATA -> WorldRecordKey.ChunkKind.TERRAIN;
            case ENTITY_DATA -> WorldRecordKey.ChunkKind.ENTITIES;
            case POI_DATA -> WorldRecordKey.ChunkKind.POI;
        }, x, z);
    }

    public static void saveChunk(final ServerLevel world, final int x, final int z, final RegionFileType type,
                                 final Consumer<BiConsumer<CompoundTag, Throwable>> serialize) {
        final CompletableFuture<StorageRecord> record = new CompletableFuture<>();
        store(world).writePending(chunkKey(x, z, type), record);
        // Reservation precedes serialization, preventing reads/reordered saves overtaking it.
        serialize.accept((tag, error) -> {
            if (error != null) record.completeExceptionally(error);
            else CompletableFuture.supplyAsync(() -> tag == null ? null : encode(tag), IO)
                .whenComplete((value, failure) -> { if (failure == null) record.complete(value); else record.completeExceptionally(failure); });
        });
    }

    public static Cancellable loadChunk(final ServerLevel world, final int x, final int z, final RegionFileType type,
                                        final BiConsumer<CompoundTag, Throwable> callback) {
        final CompletableFuture<CompoundTag> future = store(world).read(chunkKey(x, z, type)).thenApplyAsync(record -> {
            try {
                if (record.isEmpty()) return null;
                final CompoundTag tag = decode(record.get());
                if (type == RegionFileType.CHUNK_DATA && !net.minecraft.world.level.chunk.storage.SerializableChunkData.getChunkCoordinate(tag).equals(new net.minecraft.world.level.ChunkPos(x, z))) {
                    throw new IllegalArgumentException("Stored chunk coordinates do not match its key");
                }
                if (type == RegionFileType.ENTITY_DATA) {
                    final int[] pos = tag.getIntArray("Position").orElseThrow(() -> new IllegalArgumentException("Missing entity chunk coordinates"));
                    if (pos.length != 2 || pos[0] != x || pos[1] != z) throw new IllegalArgumentException("Stored entity coordinates do not match its key");
                }
                return tag;
            } catch (Throwable error) { throw fail(error); }
        }, IO).toCompletableFuture();
        future.whenComplete((tag, error) -> callback.accept(tag, error));
        return () -> future.cancel(false);
    }

    public static StorageRecord encode(final CompoundTag tag) {
        try {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.write(tag, new DataOutputStream(bytes));
            return new StorageRecord(new RecordMetadata("application/x-minecraft-nbt", tag.getIntOr("DataVersion", tag.getCompoundOrEmpty("Data").getIntOr("DataVersion", -1)), System.currentTimeMillis()),
                BinaryData.takeOwnership(java.nio.ByteBuffer.wrap(bytes.toByteArray())));
        } catch (IOException error) { throw fail(error); }
    }

    private static CompoundTag decode(final StorageRecord record) throws IOException {
        try {
            final CompoundTag tag = io.nzsmp.folia.generation.ChunkImporter.readNbt(record.payload());
            final int version = tag.getIntOr("DataVersion", tag.getCompoundOrEmpty("Data").getIntOr("DataVersion", -1));
            if (version > SharedConstants.getCurrentVersion().dataVersion().version()) throw new IllegalArgumentException("Record is from a newer Minecraft version");
            return tag;
        } catch (Throwable error) { throw fail(error); }
    }

    public static CompletableFuture<Void> preloadPlayer(final UUID id, final java.util.function.BooleanSupplier connected) {
        final List<PlayerRecordKey> keys = java.util.Arrays.stream(PlayerRecordKey.Kind.values()).map(kind -> new PlayerRecordKey(id, kind)).toList();
        return INSTANCE.playerStore.readBatch(keys).thenAccept(records -> {
            synchronized (INSTANCE.playerCache) {
                if (connected.getAsBoolean()) INSTANCE.playerCache.putAll(records);
            }
        }).toCompletableFuture();
    }

    public static Optional<StorageRecord> readPlayer(final UUID id, final PlayerRecordKey.Kind kind) {
        final PlayerRecordKey key = new PlayerRecordKey(id, kind);
        final Optional<StorageRecord> cached = INSTANCE.playerCache.get(key);
        return cached == null ? await(INSTANCE.playerStore.read(key)) : cached;
    }

    public static Optional<CompoundTag> readPlayerNbt(final UUID id) {
        return readPlayer(id, PlayerRecordKey.Kind.PLAYER_DATA).map(record -> {
            try { return decode(record); } catch (IOException error) { throw fail(error); }
        });
    }

    public static void writePlayerNbt(final UUID id, final CompoundTag tag) {
        final CompoundTag snapshot = tag.copy();
        writePlayerPending(id, PlayerRecordKey.Kind.PLAYER_DATA, CompletableFuture.supplyAsync(() -> encode(snapshot), IO));
    }

    private static void writePlayerPending(final UUID id, final PlayerRecordKey.Kind kind, final CompletionStage<StorageRecord> record) {
        final PlayerRecordKey key = new PlayerRecordKey(id, kind);
        INSTANCE.playerStore.writePending(key, record, value -> INSTANCE.playerCache.computeIfPresent(key, (k, previous) -> Optional.of(value)));
    }

    public static void releasePlayer(final UUID id) {
        synchronized (INSTANCE.playerCache) {
            for (final PlayerRecordKey.Kind kind : PlayerRecordKey.Kind.values()) INSTANCE.playerCache.remove(new PlayerRecordKey(id, kind));
        }
    }

    public static String readPlayerJson(final Path path, final PlayerRecordKey.Kind kind) {
        final UUID id = UUID.fromString(path.getFileName().toString().replaceFirst("\\.json$", ""));
        return readPlayer(id, kind).map(record -> java.nio.charset.StandardCharsets.UTF_8.decode(record.payload().buffer()).toString()).orElse(null);
    }

    public static void writePlayerJson(final Path path, final PlayerRecordKey.Kind kind, final String json) {
        final UUID id = UUID.fromString(path.getFileName().toString().replaceFirst("\\.json$", ""));
        final StorageRecord record = new StorageRecord(new RecordMetadata("application/json", SharedConstants.getCurrentVersion().dataVersion().version(), System.currentTimeMillis()),
            BinaryData.takeOwnership(java.nio.ByteBuffer.wrap(json.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        writePlayerPending(id, kind, CompletableFuture.completedFuture(record));
    }

    public static List<UUID> playerIds() {
        final Set<UUID> ids = new HashSet<>();
        Optional<String> cursor = Optional.empty();
        do {
            final RecordPage<PlayerRecordKey> page = await(INSTANCE.playerStore.list(new RecordQuery(cursor, 1000)));
            page.keys().stream().filter(key -> key.kind() == PlayerRecordKey.Kind.PLAYER_DATA).forEach(key -> ids.add(key.playerId()));
            cursor = page.nextCursor();
        } while (cursor.isPresent());
        return List.copyOf(ids);
    }

    public static long playerModified(final UUID id) {
        return readPlayer(id, PlayerRecordKey.Kind.PLAYER_DATA).map(value -> value.metadata().modifiedTimeMillis()).orElse(0L);
    }

    private void hydrateAssets() throws IOException {
        // Only known asset directories are caches. Never walk or materialize chunk records.
        for (final String name : List.of("datapacks", "generated", "resourcepacks", "icon.png", "paper-world.yml")) clearCache(this.root.resolve(name));
        final Path dimensions = this.root.resolve("dimensions");
        if (Files.isDirectory(dimensions)) {
            try (var paths = Files.walk(dimensions, 32)) {
                for (final Path generated : paths.filter(path -> (path.getFileName().toString().equals("generated") || path.getFileName().toString().equals("paper-world.yml"))).toList()) clearCache(generated);
            }
        }
        final ManagedStore<WorldRecordKey> rootStore = this.store(Optional.empty());
        Optional<String> cursor = Optional.empty();
        do {
            final RecordPage<WorldRecordKey> page = await(rootStore.list(new RecordQuery(cursor, 1000)));
            for (final WorldRecordKey key : page.keys()) if (key instanceof WorldRecordKey.Asset asset) {
                final Path file = this.assetPath(asset.relativePath());
                final StorageRecord record = await(rootStore.read(key)).orElseThrow();
                Files.createDirectories(file.getParent());
                Files.write(file, record.payload().toByteArray());
                Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(record.metadata().modifiedTimeMillis()));
                this.cachedAssets.add(asset.relativePath());
                this.assetStamps.put(asset.relativePath(), Files.size(file) + ":" + Files.getLastModifiedTime(file).toMillis());
            }
            cursor = page.nextCursor();
        } while (cursor.isPresent());
    }

    private static void clearCache(final Path path) throws IOException {
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(path)) throw new IOException("Asset cache must not contain symlinks: " + path);
        try (var files = Files.walk(path)) {
            for (final Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (Files.isSymbolicLink(file)) throw new IOException("Asset cache must not contain symlinks: " + file);
                Files.delete(file);
            }
        }
    }

    private Path assetPath(final String name) throws IOException {
        if (!isAsset(name)) throw new IOException("Invalid server asset path: " + name);
        final Path file = this.root.resolve(name).normalize();
        if (!file.startsWith(this.root)) throw new IOException("Asset path escapes world cache");
        for (Path part = file; part != null && part.startsWith(this.root); part = part.getParent()) {
            if (Files.isSymbolicLink(part)) throw new IOException("Asset cache contains symlink");
        }
        return file;
    }

    private static boolean isAsset(final String relative) {
        return relative.equals("icon.png") || relative.equals("paper-world.yml") || relative.startsWith("datapacks/") || relative.startsWith("resourcepacks/")
            || relative.startsWith("generated/") || (relative.matches("dimensions/[^/]+/.+/generated/.+") || relative.matches("dimensions/[^/]+/.+/paper-world\\.yml"));
    }

    public static void saveAsset(final Path file) throws IOException {
        if (!handles(file)) return;
        final String relative = INSTANCE.root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
        INSTANCE.assetPath(relative);
        if (Files.size(file) > 64L * 1024 * 1024) throw new IOException("Asset exceeds 64 MiB");
        final StorageRecord record = new StorageRecord(new RecordMetadata("application/octet-stream", -1, Files.getLastModifiedTime(file).toMillis()),
            BinaryData.takeOwnership(java.nio.ByteBuffer.wrap(Files.readAllBytes(file))));
        await(INSTANCE.store(Optional.empty()).write(new WorldRecordKey.Asset(relative), record));
        INSTANCE.cachedAssets.add(relative);
        INSTANCE.assetStamps.put(relative, Files.size(file) + ":" + Files.getLastModifiedTime(file).toMillis());
    }

    public static synchronized void scheduleAssetSave() {
        if (!worldEnabled() || !INSTANCE.assetScan.isDone()) return;
        INSTANCE.assetScan = CompletableFuture.runAsync(INSTANCE::saveAssets, IO);
    }

    private void saveAssets() {
        try {
            final Set<String> current = new HashSet<>();
            final List<Path> roots = new ArrayList<>();
            for (final String name : List.of("datapacks", "generated", "resourcepacks", "icon.png", "paper-world.yml")) roots.add(this.root.resolve(name));
            final Path dimensions = this.root.resolve("dimensions");
            if (Files.isDirectory(dimensions)) {
                try (var paths = Files.walk(dimensions, 32)) {
                    paths.filter(path -> (path.getFileName().toString().equals("generated") || path.getFileName().toString().equals("paper-world.yml"))).forEach(roots::add);
                }
            }
            final ManagedStore<WorldRecordKey> store = this.store(Optional.empty());
            for (final Path base : roots) {
                if (!Files.exists(base)) continue;
                try (var files = Files.walk(base)) {
                    for (final Path file : files.filter(path -> !Files.isDirectory(path)).toList()) {
                        final String relative = this.root.relativize(file).toString().replace('\\', '/');
                        this.assetPath(relative);
                        if (Files.size(file) > 64L * 1024 * 1024) throw new IOException("Asset exceeds 64 MiB: " + relative);
                        current.add(relative);
                        final String stamp = Files.size(file) + ":" + Files.getLastModifiedTime(file).toMillis();
                        if (stamp.equals(this.assetStamps.get(relative))) continue;
                        final StorageRecord record = new StorageRecord(new RecordMetadata("application/octet-stream", -1, Files.getLastModifiedTime(file).toMillis()), BinaryData.takeOwnership(java.nio.ByteBuffer.wrap(Files.readAllBytes(file))));
                        await(store.write(new WorldRecordKey.Asset(relative), record));
                        this.assetStamps.put(relative, stamp);
                    }
                }
            }
            for (final String deleted : this.cachedAssets) if (!current.contains(deleted)) await(store.delete(new WorldRecordKey.Asset(deleted)));
            this.assetStamps.keySet().retainAll(current);
            this.cachedAssets.clear();
            this.cachedAssets.addAll(current);
        } catch (Throwable error) { throw fail(error); }
    }

    public static void worldReady(final ServerLevel world) {
        if (worldEnabled()) store(world).worldReady(new PluginChunkRecordCodec(world));
    }

    public static void flushWorld(final ServerLevel world) {
        if (worldEnabled()) await(store(world).flush());
    }

    public static void flushAll() {
        if (worldEnabled()) { await(INSTANCE.assetScan); scheduleAssetSave(); await(INSTANCE.assetScan); }
        INSTANCE.stores.values().forEach(store -> await(store.flush()));
        if (playerEnabled()) await(INSTANCE.playerStore.flush());
    }

    public static void closeAll() {
        boolean failed = false;
        try { flushAll(); }
        catch (Throwable error) { LOGGER.error("Final plugin storage flush failed", error); failed = true; }
        final java.util.List<ManagedStore<?>> closing = new ArrayList<>(INSTANCE.stores.values());
        if (playerEnabled()) closing.add(INSTANCE.playerStore);
        for (final ManagedStore<?> store : closing) {
            try { await(store.close()); }
            catch (Throwable error) { LOGGER.error("Plugin storage closed with unacknowledged operations", error); failed = true; }
        }
        if (failed) LOGGER.error("SERVER STORAGE SHUTDOWN FAILED: unacknowledged records may not have reached durable storage.");
    }

    public static <T> T await(final CompletionStage<T> stage) {
        try { return stage.toCompletableFuture().get(60, TimeUnit.SECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw fail(error); }
        catch (Exception error) { throw fail(error); }
    }

    public static IllegalStateException fail(final Throwable error) {
        fatal(error);
        return new IllegalStateException("Plugin persistence failed; operation must not be treated as missing data", error);
    }

    private static void fatal(final Throwable error) {
        LOGGER.error("Plugin persistence failed. Unsaved records are not acknowledged; stopping the server.", error);
        final MinecraftServer server = MinecraftServer.getServer();
        if (server != null && !server.hasStopped()) server.halt(false);
    }
}
