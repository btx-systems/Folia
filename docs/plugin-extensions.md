# Plugin generation and persistence extensions

This fork adds transport-independent extension points under `io.nzsmp.folia`.
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
