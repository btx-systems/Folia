package io.nzsmp.folia.example;

import io.nzsmp.folia.storage.*;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.key.Key;

public final class ExampleBootstrap implements PluginBootstrap {
    @Override
    public void bootstrap(BootstrapContext context) {
        Path base = context.getDataDirectory().resolve("records");
        context.getPersistenceRegistry().registerWorldStorage(Key.key("example:files"), info -> {
            try {
                String scope = encode(info.worldId() + "\0" + info.dimension().map(Key::asString).orElse("root"));
                FileRecordStore<WorldRecordKey> store = new FileRecordStore<>(base.resolve("worlds").resolve(scope), ExampleBootstrap::worldKey, ExampleBootstrap::parseWorldKey);
                return CompletableFuture.completedFuture(new WorldStore() {
                    @Override public java.util.concurrent.CompletionStage<java.util.Optional<StorageRecord>> read(WorldRecordKey key) { return store.read(key); }
                    @Override public java.util.concurrent.CompletionStage<Void> write(WorldRecordKey key, StorageRecord record) { return store.write(key, record); }
                    @Override public java.util.concurrent.CompletionStage<Void> delete(WorldRecordKey key) { return store.delete(key); }
                    @Override public java.util.concurrent.CompletionStage<RecordPage<WorldRecordKey>> list(RecordQuery query) { return store.list(query); }
                    @Override public java.util.concurrent.CompletionStage<Void> flush() { return store.flush(); }
                    @Override public java.util.concurrent.CompletionStage<Void> close() { return store.close(); }
                });
            } catch (Exception error) { return CompletableFuture.failedFuture(error); }
        });
        context.getPersistenceRegistry().registerPlayerStorage(Key.key("example:files"), info -> {
            try {
                FileRecordStore<PlayerRecordKey> store = new FileRecordStore<>(base.resolve("players").resolve(encode(info.scopeId())),
                    key -> key.playerId() + "/" + key.kind().name(),
                    value -> { String[] parts = value.split("/", 2); return new PlayerRecordKey(UUID.fromString(parts[0]), PlayerRecordKey.Kind.valueOf(parts[1])); });
                return CompletableFuture.completedFuture(new PlayerStore() {
                    @Override public java.util.concurrent.CompletionStage<java.util.Optional<StorageRecord>> read(PlayerRecordKey key) { return store.read(key); }
                    @Override public java.util.concurrent.CompletionStage<Void> write(PlayerRecordKey key, StorageRecord record) { return store.write(key, record); }
                    @Override public java.util.concurrent.CompletionStage<Void> delete(PlayerRecordKey key) { return store.delete(key); }
                    @Override public java.util.concurrent.CompletionStage<RecordPage<PlayerRecordKey>> list(RecordQuery query) { return store.list(query); }
                    @Override public java.util.concurrent.CompletionStage<Void> flush() { return store.flush(); }
                    @Override public java.util.concurrent.CompletionStage<Void> close() { return store.close(); }
                });
            } catch (Exception error) { return CompletableFuture.failedFuture(error); }
        });
    }

    private static String encode(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static String worldKey(WorldRecordKey key) {
        return switch (key) {
            case WorldRecordKey.Chunk c -> "chunk/" + c.kind().name() + "/" + c.chunkX() + "/" + c.chunkZ();
            case WorldRecordKey.SavedData d -> "data/" + d.id().asString();
            case WorldRecordKey.Metadata m -> "metadata/" + m.name();
            case WorldRecordKey.Asset a -> "asset/" + a.relativePath();
        };
    }
    private static WorldRecordKey parseWorldKey(String value) {
        String[] parts = value.split("/", 2);
        return switch (parts[0]) {
            case "chunk" -> { String[] c = parts[1].split("/"); yield new WorldRecordKey.Chunk(WorldRecordKey.ChunkKind.valueOf(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2])); }
            case "data" -> new WorldRecordKey.SavedData(Key.key(parts[1]));
            case "metadata" -> new WorldRecordKey.Metadata(parts[1]);
            case "asset" -> new WorldRecordKey.Asset(parts[1]);
            default -> throw new IllegalArgumentException("Unknown key kind");
        };
    }
}
