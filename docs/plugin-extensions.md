# Plugin generation and persistence extensions

This fork adds transport-independent extension points under `sh.btx.nzsmp.folia`.
The APIs do not require a network service, database, or a particular compression
or delta-storage algorithm. Providers are responsible for their implementation.

## Development workflow

The implementation branch is based on Folia `8cffa90`, Minecraft 26.3 and the
Paper revision pinned in `gradle.properties`. Build with Java 25.

New API and implementation classes live in the tracked `folia-api/src` and
`folia-server/src` trees. Changes to inherited sources are committed in the
generated repositories and rebuilt into separate feature patches:

```sh
./gradlew rebuildPaperPatches
./gradlew :folia-server:rebuildAllServerPatches
./gradlew build :folia-server:createPaperclipJar
```

`./gradlew applyAllPatches` must reproduce the changes in a fresh checkout. Do
not commit generated source trees or build outputs. Preserve upstream licenses
and distinguish extension changes from upstream patches when resolving updates.

The implementation is split into public contracts, generation integration,
persistence integration, and validation/examples. Tests accompany each feature.

## Using a generator

Use a Paper plugin with `folia-supported: true` and `load: STARTUP`. Return an
`AsyncChunkGenerator` from `JavaPlugin#getDefaultWorldGenerator`. Select it through
Bukkit's existing world generator configuration; no transport is prescribed.

```java
@Override
public CompletionStage<GeneratedChunk> generate(GenerationRequest request) {
    return CompletableFuture.completedFuture(new SectionChunkData(
        List.of(new ChunkSectionData(
            request.world().minHeight() >> 4,
            PackedPalette.uniform(Material.STONE.createBlockData()),
            PackedPalette.uniform(Key.key("minecraft:plains"))
        )),
        BiomePolicy.PROVIDED,
        ChunkExtras.EMPTY
    ));
}
```

`GenerationRequest` contains chunk coordinates, immutable world information, a
runtime registry layout, and cancellation/priority controls. Calls can overlap.
Return promptly. A pending result occupies an admission slot but no Folia tick or
generation worker. Actual result import runs on a generation worker with normal
chunk ownership and completion bookkeeping. Providers may perform local CPU
work, schedule other workers, read a file, or return a future from any source.

A successful result supplies complete terrain. Vanilla terrain, structures,
decorations, and initial population are bypassed. Normal runtime spawning and
chunk activation remain server-managed. Saved complete chunks are loaded first;
load-only requests do not invoke the generator. Lighting is recomputed by Folia.
Heightmaps and POI consistency are handled by the server.

The result types are:

- `SectionChunkData`: block and biome palettes with padded packed-long indices.
  Each block section has 4096 indices and each biome section has 64. Index order
  is X, then Z, then Y. Entries never cross a long boundary. A uniform palette has
  zero bits and no storage. Arrays can be copied or transferred with
  `PackedPalette.takeOwnership`; submitted palette storage is consumed once.
  Matching native layouts adopt the array. Other layouts are repacked, including
  when global palettes or Anti-Xray presets require it.
- `EncodedSectionChunkData`: complete network-format section buffers, identified
  by an exact `SectionEncoding`. `BinaryData.slice` can share one owned response
  buffer across sections. Folia imports palettes directly into packed storage and
  constructs server sections with correct ticking, fluid and collision metadata.
- `SerializedChunkData`: uncompressed named-root compound chunk NBT with explicit
  `DataVersion`, coordinates, and terrain complete through `FEATURES`. Normal
  data converters handle supported older records. Future versions, invalid
  palettes, inconsistent coordinates and malformed sections fail explicitly.
  The record uses Minecraft's format for its declared version; a chunk packet
  is not chunk NBT.

`BinaryData.copyOf` isolates a source. `takeOwnership` transfers ownership without
copying: never subsequently mutate, reuse, or release the backing memory. Copy
pooled buffers before transfer. Read-only views do not protect against writes
through another alias. Palette values, including `BlockData`, must also remain
unchanged after submission; clone values you intend to reuse mutably.

Omitted sections are air with server-generated biomes. `BiomePolicy` selects
supplied biomes or the world's configured biome source. Encoded results always
contain a complete biome container even when the server will skip it and sample
its own biomes. `ChunkExtras` optionally supplies full block-entity NBT and
heightmaps. Block entities have absolute `x/y/z` and a matching registered `id`
(or the vanilla `DUMMY` protochunk placeholder). Heightmaps contain 256 values,
indexed X + Z*16, giving first-available height relative to the world's minimum.
Missing heightmaps are computed.

### Encoded section compatibility

Obtain the runtime mapping from `request.registries()`. Its block-state strings
and biome keys are indexed by numeric ID. The descriptor includes the format,
Minecraft DataVersion and a fingerprint of those ordered mappings. Providers
must verify or encode against that mapping. Never relabel older bytes with a new
descriptor: biome IDs can depend on datapacks as well as the Minecraft version.

`minecraft:network-section/26.3` contains:

1. Two big-endian unsigned shorts: non-air count and fluid count.
2. A block-state paletted container.
3. A biome paletted container.

A container starts with an unsigned-byte bit width. Zero bits means one VarInt
registry ID and no packed longs. Local palettes include a VarInt entry count and
VarInt registry IDs. Global palettes omit the local palette and pack runtime IDs.
The packed longs use big-endian network byte order and Minecraft padded packing;
this version has no additional long-array length prefix. The number of longs is
implied by the bit width and entry count. The server checks layout and IDs and
recomputes server-side counts. See `encodedFloor` in the example plugin for a
minimal version-checked encoder. HTTP/RPC framing and compression remain entirely
inside the plugin.

Generation admission defaults to 64 in-flight requests and 4096 queued requests
per world. Set `folia.generation.max-inflight`, `folia.generation.max-queued`, and
`folia.generation.timeout-seconds` as JVM properties; timeout defaults to 30 seconds.
Cancellation suppresses late installation. Listener callbacks must return quickly
and must not throw. A provider should observe cancellation to stop unnecessary
work; cancelling a future alone does not necessarily cancel its underlying I/O.

## Providing persistence

Register factories through `BootstrapContext#getPersistenceRegistry` in the
Paper bootstrapper. Registration is separate from selection:

```java
context.getPersistenceRegistry().registerWorldStorage(
    Key.key("example:files"), myWorldProvider);
context.getPersistenceRegistry().registerPlayerStorage(
    Key.key("example:files"), myPlayerProvider);
```

Create `config/plugin-storage.yml` to select registered providers:

```yaml
world-id: my-logical-world
world-provider: example:files
player-provider: example:files
player-scope: my-player-namespace
limits:
  timeout-seconds: 10
  max-inflight: 64
  max-queued: 4096
  max-pending-bytes: 268435456
```

Omitted provider selections default to `vanilla`. World and player providers are
independent. Malformed configuration, missing providers, conflicting registrations,
or a cache binding mismatch prevent startup. A plugin-owned cache cannot silently
be reopened with vanilla persistence if its provider configuration disappears.

World factories receive a configured logical world ID and an optional dimension
key. An empty dimension identifies world-root metadata/assets. This identity is
available before the saved world UUID is loaded. Player factories receive a
configured namespace independent of the player's current dimension. Bindings are
fixed for a server session. The initial contract has one writer process; it does
not provide distributed ownership, transactions or conflict resolution.

`RecordStore<K>` supplies asynchronous reads, writes, deletes, listing, batches,
flush and close. World keys distinguish terrain/entities/POI, namespaced saved
data, root metadata and assets. Player keys distinguish player NBT, statistics,
and advancements. The payload is canonical server data: uncompressed named-root
NBT for NBT records, UTF-8 JSON for stats/advancements, and original bytes for
assets. `RecordMetadata` identifies the format, Minecraft data version (or -1),
and modification time. Your backend can compress, pack, deduplicate, or delta-code
these records in any representation, reconstructing canonical records on read.

The server reserves writes before asynchronous serialization, preserves operation
order per key and makes reads wait for earlier writes. Providers can override
batch methods to use native backend batches. A batch is not a cross-record
transaction. Missing data is `Optional.empty`; errors must complete exceptionally.
A successful write/delete acknowledges an atomic, durable key update. `flush`
is a barrier for preceding writes. Provider callbacks must never block waiting
for a tick thread, including when a compatibility caller is synchronously waiting.

Known failed operations receive up to two retries after the first attempt, with
100 ms and 200 ms delays. An operation timeout has an uncertain outcome and is
not retried: the store is poisoned and the server stops, preventing a late old
write from racing a newer update. Exhausted failures, invalid records and queue
or byte-budget exhaustion also stop the server explicitly. Unacknowledged writes
cannot be promised durable during a permanent backend outage. Shutdown reports
failed persistence and still attempts to close resources.

Chunk I/O and player login prefetch are asynchronous. Player NBT, stats and
advancements are prefetched together before configuration proceeds, and the
login cache is released after placement or disconnect. Existing synchronous saved
world data and offline-player APIs can wait for uncached storage with a bounded
60-second compatibility wait. Their providers must make progress independently
of tick threads. Ordinary persistence writes serialize detached snapshots and
run outside tick threads.

### Lossless chunk transforms

Dimension stores receive `WorldStore#onWorldReady(ChunkRecordCodec)` before their
first chunk I/O. Root metadata stores do not receive this callback. The codec
exposes detached sections and retains the original record, including unknown
fields, so a storage plugin can reconstruct delta-coded terrain without NMS:

```java
ChunkRecordSnapshot snapshot = codec.decode(canonicalRecord);
// Compress or compare snapshot.sections() using a plugin-owned strategy.
StorageRecord reconstructed = codec.encode(
    new ChunkRecordSnapshot(snapshot.original(), reconstructedSections),
    ChunkRecordCodec.DerivedDataPolicy.PRESERVE
);
```

Use `PRESERVE` only when reconstructing exactly the saved terrain. `RECOMPUTE`
invalidates lighting/heightmaps when intentionally changing terrain. Encoding
consumes the submitted packed arrays. Run codecs on storage workers; these are
CPU operations and expose no live chunks. Generator baseline/version policy is
owned by the plugin, not the server API.

### Cache, assets and lifecycle

Custom world storage owns world metadata, saved data, chunk/entity/POI records,
world persistent data and server-managed assets. The world directory is a
rebuildable cache containing locks, binding markers and materialized assets.
Datapacks, generated structures, world resource packs/icons and per-world Paper
configuration are materialized before loading. Server structure saves write
through to the provider; other cached asset changes are captured on save/flush.
Server-wide configuration, plugin directories, bans, whitelist and the profile
cache remain server administration files.

For first activation, use a fresh cache directory and import existing records
through your backend's own tooling first. The server refuses to turn an existing
vanilla world directory into a disposable cache automatically. Switching back to
vanilla likewise requires an explicit export/migration. Filesystem-only legacy
world-layout migrations are rejected for custom stores; ordinary supported record
DataVersion conversions still run. No physical backend layout or migration
algorithm is imposed by these APIs.

Provider resources are server-owned through the final save and flush. They must
survive the plugin's `onDisable`; close them in `RecordStore#close`. The example
backend demonstrates atomic replacement and durable file writes. It is a small
reference implementation, not a prescribed storage layout for a large world.

## Examples, tests and benchmarks

The `extension-examples` module contains a startup plugin, file record providers,
and packed, encoded and serialized floor generators. Build it with:

```sh
./gradlew :extension-examples:jar
```

The plugin is inert unless selected as a generator/provider. Use
`ExtensionExamples`, `ExtensionExamples:encoded`, or `ExtensionExamples:serialized`
in Bukkit's generator configuration. Test-only smoke behavior is opt-in through
the `folia.extensions.smoke` JVM property.

Run unit/integration fixtures and the import benchmark:

```sh
./gradlew :folia-api:test :folia-api:checkstyleMain :folia-api:checkstyleTest
./gradlew :folia-server:test --tests sh.btx.nzsmp.folia.ExtensionTestSuite
./gradlew :folia-server:benchmarkChunkImports
```

The server suite is included in the regular server test task. Fixtures cover
buffer ownership, packed and network palettes, server counters, strict NBT,
block entities, cancellation races, bounded admission, storage ordering and
native batches, durable flushes, failure poisoning, timeouts, lossless codecs,
and configuration parsing.

For real server save/reload and player-login tests, supply an existing accepted
Minecraft `eula.txt`:

```sh
python3 tools/test-extension-server.py --eula-file=/path/to/eula.txt
```

The harness creates fresh temporary directories, binds only to localhost and
stops each server automatically. It tests all three generator formats, vanilla
persistence, and a real offline-mode player login/save/relogin with inventory,
PDC, stats and advancements. It deletes only its own disposable world caches
between runs, retaining the example backend outside those caches. Logs and a
JSON result file remain in the printed test directory. The harness does not
accept terms or change an existing server world.

The benchmark compares equivalent 24- and 254-section mixed/stratified terrain
through Bukkit setters, copied packed palettes, owned packed palettes and encoded
sections. It includes section decoding/construction and server counting, and
records timing and current-thread allocations. Provider generation, transport,
lighting, heightmaps, whole-world insertion and player throughput are excluded.
Owned arrays already exist at import time; producing those arrays is a provider
cost. See [the benchmark report](benchmarks/chunk-import-26.3.md) for repeated-fork
measurements and their limits.
