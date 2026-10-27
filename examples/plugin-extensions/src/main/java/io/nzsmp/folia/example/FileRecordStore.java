package io.nzsmp.folia.example;

import io.nzsmp.folia.data.BinaryData;
import io.nzsmp.folia.storage.*;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/** Minimal durable reference backend. Production plugins choose their own layout. */
public final class FileRecordStore<K> implements RecordStore<K> {
    private static final int MAGIC = 0x4E5A5331;
    private final Path directory;
    private final Function<K, String> encodeKey;
    private final Function<String, K> decodeKey;
    private final ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();

    public FileRecordStore(Path directory, Function<K, String> encodeKey, Function<String, K> decodeKey) throws IOException {
        this.directory = directory;
        this.encodeKey = encodeKey;
        this.decodeKey = decodeKey;
        Files.createDirectories(directory);
    }

    private Path path(K key) {
        return this.directory.resolve(Base64.getUrlEncoder().withoutPadding().encodeToString(this.encodeKey.apply(key).getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".record");
    }

    private <T> CompletionStage<T> execute(IOCall<T> call) {
        return CompletableFuture.supplyAsync(() -> {
            try { return call.run(); } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
        }, this.io);
    }

    @Override
    public CompletionStage<Optional<StorageRecord>> read(K key) {
        return this.execute(() -> {
            Path file = this.path(key);
            if (!Files.exists(file)) return Optional.empty();
            try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
                if (in.readInt() != MAGIC) throw new IOException("Invalid record header");
                RecordMetadata metadata = new RecordMetadata(in.readUTF(), in.readInt(), in.readLong());
                int size = in.readInt();
                if (size < 0 || size > 64 * 1024 * 1024) throw new IOException("Invalid record size");
                byte[] data = in.readNBytes(size);
                if (data.length != size || in.read() != -1) throw new IOException("Truncated or oversized record");
                return Optional.of(new StorageRecord(metadata, BinaryData.takeOwnership(java.nio.ByteBuffer.wrap(data))));
            }
        });
    }

    @Override
    public CompletionStage<Void> write(K key, StorageRecord record) {
        return this.execute(() -> {
            Path temporary = Files.createTempFile(this.directory, ".pending-", ".tmp");
            try {
                try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE);
                     DataOutputStream out = new DataOutputStream(java.nio.channels.Channels.newOutputStream(channel))) {
                    out.writeInt(MAGIC);
                    out.writeUTF(record.metadata().mediaType());
                    out.writeInt(record.metadata().dataVersion());
                    out.writeLong(record.metadata().modifiedTimeMillis());
                    out.writeInt(record.payload().size());
                    out.write(record.payload().toByteArray());
                    out.flush();
                    channel.force(true);
                }
                Files.move(temporary, this.path(key), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                this.syncDirectory();
            } finally { Files.deleteIfExists(temporary); }
            return null;
        });
    }

    @Override
    public CompletionStage<Void> delete(K key) {
        return this.execute(() -> {
            Files.deleteIfExists(this.path(key));
            this.syncDirectory();
            return null;
        });
    }

    private void syncDirectory() throws IOException {
        try (FileChannel channel = FileChannel.open(this.directory, StandardOpenOption.READ)) { channel.force(true); }
    }

    @Override
    public CompletionStage<RecordPage<K>> list(RecordQuery query) {
        return this.execute(() -> {
            List<String> names;
            try (var paths = Files.list(this.directory)) {
                names = paths.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".record"))
                    .filter(name -> query.cursor().isEmpty() || name.compareTo(query.cursor().get()) > 0)
                    .sorted().limit((long) query.limit() + 1).toList();
            }
            List<String> page = names.stream().limit(query.limit()).toList();
            List<K> keys = page.stream().map(name -> this.decodeKey.apply(new String(Base64.getUrlDecoder().decode(name.substring(0, name.length() - 7)), java.nio.charset.StandardCharsets.UTF_8))).toList();
            return new RecordPage<>(keys, names.size() > query.limit() ? Optional.of(page.getLast()) : Optional.empty());
        });
    }

    @Override public CompletionStage<Void> flush() { return CompletableFuture.completedFuture(null); }
    @Override public CompletionStage<Void> close() { this.io.shutdown(); return CompletableFuture.completedFuture(null); }
    @FunctionalInterface private interface IOCall<T> { T run() throws IOException; }
}
