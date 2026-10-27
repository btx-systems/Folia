# Chunk import benchmark

The encoded and packed import paths substantially reduce the cost of constructing
server sections in this fixture. At 254 sections, the median of three JVM-fork
medians was:

- Mixed terrain: Bukkit setters 22.080 ms, copied palettes 5.067 ms, owned palettes
  5.016 ms, encoded sections 5.659 ms. The encoded path used 7,548,880 allocated
  bytes versus 31,174,048 for setters, about 76% less.
- Stratified terrain: Bukkit setters 15.586 ms, copied palettes 1.307 ms, owned
  palettes 1.318 ms, encoded sections 1.478 ms. The encoded path used 2,038,960
  allocated bytes versus 26,983,432 for setters, about 92% less.

Encoded sections took about 3.9x less import time for mixed terrain and 10.5x less
for stratified terrain than setters. Packed imports were slightly faster here;
the small copied-versus-owned timing difference is within observed run variation.
Ownership transfer saved 524,256 import allocation bytes per mixed 254-section
chunk. Creating the owned arrays belongs to the provider and is outside timing.

The 24-section mixed fixture measured 2.046 ms for setters, 0.470 ms for copied
palettes, 0.479 ms for owned palettes, and 0.556 ms for encoded sections. The
24-section stratified setter baseline varied substantially across forks
(1.699–5.488 ms); its much larger apparent speedup should not be generalized.
All measurements and checksums, including that variation, are retained in
[the raw results](chunk-import-26.3.json).

## Reproduction

Build with Java 25. This run used OpenJDK 25.0.2, aarch64 macOS, a 2 GiB heap,
and `-XX:ActiveProcessorCount=4`. Run the following three times, with no server
or other benchmark running concurrently:

```sh
JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=4 -Dbenchmark.warmups=20 -Dbenchmark.samples=15' \
  ./gradlew --offline :folia-server:benchmarkChunkImports
```

Each fresh JVM bootstraps the Minecraft registries, prewarms shared import paths
with 100 iterations of a 128-section mixed fixture, then warms each measured
case 20 times. It measures 15 samples with rotating path order. The report
contains each fork's median and p95 sample time, current-thread allocation
median, and output checksum. Timing p95 is descriptive for these 15 samples,
not a production latency guarantee. The benchmark checks equivalent sampled
output across paths; unit tests check every block and server bookkeeping.

Mixed fixtures use air, stone, water and randomly ticking leaves. Stratified
fixtures alternate uniform solid, uniform air and mixed sections. The baseline
uses Bukkit's public `OldCraftChunkData#setBlock` in the same block index order.
Fast paths construct native server sections and recalculate ticking/fluid counts.
The encoded path decodes complete block and biome containers. The packed paths
start with equivalent typed palettes; they adopt matching native storage or
repack when necessary. Production Anti-Xray presets, registry layouts and palette
sizes can change this balance.

These are import microbenchmarks, not JMH results or whole-server throughput
measurements. They exclude provider generation, network framing/compression,
transport, lighting, heightmaps and world insertion. No Rust service or backend
layout is assumed. If a provider already has encoded section bytes, the encoded
path avoids per-block API calls and intermediate block objects. A provider that
already has typed packed arrays can use the packed path instead. Benchmark the
actual provider and terrain before choosing between them.
