# Bundled WebAssembly feature catalog

`features.json` is a committed snapshot of

    https://raw.githubusercontent.com/WebAssembly/website/refs/heads/main/features.json

and `features.schema.json` is the matching upstream schema, kept alongside it as the
reference for the four-way `status` union that the parser in
`plugins-shared/src/commonMain/kotlin/org/jetbrains/hexana/shared/features/WasmFeatureCatalog.kt`
implements.

The snapshot is the offline fallback: it is what the compatibility table uses on a first
run, when the network is unavailable, and in tests. At runtime each host prefers its own
ETag-validated cache of the live file and falls back to this snapshot.

`features.json` is not read at runtime — KMP resource loading is awkward on `wasmJs`, so it
is compiled into a checked-in Kotlin constant instead:

    ./gradlew :plugins-shared:updateBundledWasmFeatures       # refresh the constant from features.json
    ./gradlew :plugins-shared:verifyBundledWasmFeaturesUpToDate

To refresh the snapshot itself, re-download `features.json`, then run
`updateBundledWasmFeatures` in the same change so the constant and the JSON stay in sync.
