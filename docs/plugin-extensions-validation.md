# Extension API validation

Implementation source commit: `a05aacc` on `feat/plugin-generation-persistence`.
The final documentation commits do not change executable sources. Validation used
OpenJDK 25.0.2 and Gradle 9.8.1 on aarch64 macOS.

## Build and tests

The following completed successfully:

```sh
./gradlew --offline build :folia-server:createPaperclipJar :folia-server:generateDevelopmentBundle
```

JUnit reports contained 485 API test cases and 10,141 server test cases, with
zero failures or errors; 2 API cases and 86 server cases were skipped. The new
extension fixtures contributed 4 API cases and 15 server cases. API Checkstyle,
Javadocs and the normal build verification tasks passed.

The build produced:

- `folia-api/build/libs/folia-api-26.3.local-SNAPSHOT.jar`
- `folia-server/build/libs/folia-paperclip-26.3.local-SNAPSHOT.jar`
- `folia-server/build/libs/paperweight-development-bundle-26.3.local-SNAPSHOT.zip`
- `examples/plugin-extensions/build/libs/extension-examples-26.3.local-SNAPSHOT.jar`

The server manifest identifies source commit `a05aacc`. API, Paperclip and
bundle ZIP integrity checks passed.

## Live server round trips

The reusable `tools/test-extension-server.py` harness passed all five modes:
packed, encoded, serialized, vanilla and players. All nine server runs exited
successfully, reported their expected success marker and contained no logged
errors or exceptions. Tests ran only against disposable localhost servers using
an existing accepted EULA file.

The three generator formats supplied the expected terrain. Chest inventories,
entities, chunk persistent data, world UUID and a world asset survived save/reload
with the disposable world cache deleted and the plugin backend retained outside
it. Custom storage created neither region files nor `level.dat` in the cache.
The vanilla regression created normal region files and `level.dat`. A real player
login/save/relogin restored inventory, player persistent data, statistics and
advancements and exercised offline-player lookup. The final palette-count guard
was additionally exercised by unit fixtures, the full build and refreshed import
benchmarks after these live runs.

## Patch reproducibility

A fresh local clone of the committed branch successfully ran `applyAllPatches`.
It reused upstream download/decompilation caches, but copied no patched output
source trees or project execution history. All generated Java sources matched the
tested workspace: 1,985 API files, 1,518 Paper server files and 5,545 Minecraft
files, with zero content differences.

The clean clone then completed:

```sh
./gradlew --offline --no-build-cache :folia-server:compileJava :extension-examples:jar :folia-api:check
./gradlew --offline rebuildPaperSingleFilePatches
./gradlew --offline :folia-server:rebuildAllServerPatches
git diff --exit-code
git diff --cached --exit-code
```

Both patch-diff checks were empty. This verifies patch replay and compilation
using warmed dependencies; it is not a cold download test.

## Performance scope

[The benchmark report](benchmarks/chunk-import-26.3.md) contains three fresh JVM
forks against the final importer. At 254 sections the encoded path took about
4.0x less import time for mixed terrain and 11.2x less for stratified terrain than
Bukkit setters, with approximately 76% and 92% fewer allocated bytes respectively.
The raw per-fork medians, sample p95s, allocation counts and checksums are retained.
These are section-import measurements, excluding provider work, transport,
lighting, heightmaps and world insertion. Real workload throughput and distributed
backend behavior require the implementing plugin's own load tests.
