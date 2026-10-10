# Isolated vanilla worldgen validation

This harness validates vanilla `NORMAL` chunk generation through lighting, save, clean shutdown, server restart, reload, and saved Anvil comparison. Its only runtime output belongs under `C:\GPur-validation-20261009\validation`; it binds loopback port `25620` by default and refuses port `25565`. It does not access or copy from the live server directory.

The first strict radius-4 smoke on the earlier candidate **failed**: GPU aquifer workload 6 reported a startup parity mismatch with zero consumed work, disabling the global GPU gate and making later noise workload 5 ineligible; all four cases failed. A CPU snapshot also exposed an out-of-dimension lighting-only section that the parser initially rejected; the parser correction is covered by the unit suite. A second matrix on immutable candidate SHA `32ec7d79c7dedaf727e3d199618aeb75e221e63411c85405888e90b3869c126c` attempted all four cases, but each JVM exited before plugin readiness because Windows could not commit the initial 16 GiB heap (errno 1455). That run reached no worldgen, parity, or GPU-consumption checks. It is preserved under `C:\GPur-validation-20261009\validation\worldgen-smoke-b693a6a90`; the next attempt uses a 4 GiB initial heap while retaining the 16 GiB maximum. The release gate remains blocked. See [`release-gate.json`](release-gate.json) for the remaining stages. A version change to `2.0.0` remains blocked until those rows are complete and pass.

## Build and run after production integration is ready

Use the final candidate server JAR, the already accepted EULA file, Mojang cache, JDK 25, and the compiled standalone probe. The builder invokes `javac` only; it does not run Gradle or a server.

```powershell
python benchmarks/worldgen/build_plugin.py `
  --classpath validation/classpath.txt `
  --jdk C:\Users\caram\.jdks\openjdk-25.0.2 `
  --output C:\GPur-validation-20261009\validation\worldgen-plugin-20261010-paper-handlefix

python benchmarks/worldgen/run_matrix.py `
  --jar C:\GPur-validation-20261009\validation\artifacts\b693a6a90\gpur-server-26.2-SNAPSHOT1.1.0.jar `
  --probe C:\GPur-validation-20261009\validation\worldgen-plugin-20261010-paper-handlefix\GPurWorldgenProbe.jar `
  --mojang validation\smoke-gpu-final\cache\mojang_26.2.jar `
  --eula validation\eula.txt `
  --jdk C:\Users\caram\.jdks\openjdk-25.0.2 `
  --output C:\GPur-validation-20261009\validation\worldgen-smoke-b693a6a90-xms4-paper-handlefix `
  --radius 4 `
  --initial-heap-gib 4 `
  --continue-on-error `
  --parity-dump-dir C:\GPur-validation-20261009\validation\worldgen-parity-b693a6a90-xms4-paper-handlefix
```

For an isolated diagnostic rerun, optionally pass `--parity-dump-dir C:\GPur-validation-20261009\validation\worldgen-parity-dumps`. The matrix creates a unique subdirectory per matrix and case, and the JVM writes a binary input/expected/actual snapshot only when strict parity fails. The option is disabled by default and its path must remain under the validation root.

The default corpus uses seed `1196459378`, center chunk `(128,128)`, radius `32` and a `65 × 65 = 4,225` chunk square. The probe creates a new normal world for each CPU, RTX 3070, GTX 1080 and mixed-device case, enables vanilla structures, disables natural mob spawning and day/weather cycling, then submits chunks in stable X-major/Z-minor order through a bounded 64-request async window. Completion order may differ; the deterministic ordering is request order. The off-spawn corpus starts far from server spawn chunks. Each case has a unique server directory/world; no prior world is reused.

Every server uses `-Xmx16G`; `--initial-heap-gib` selects `-Xms` from 1 GiB through 16 GiB and defaults to 16 GiB. The initial and maximum heap are recorded separately, and verified-exact matrices require matching JVM flags in the strict reference. Cases use the same seed and settings, no connected clients, and loopback only. CPU has GPU acceleration disabled. GPU cases enable both opt-in worldgen workload boundaries (`noise-batches` and `aquifer-ranking`) with `gpu.force=false`. `--mode strict` is the default and is the correctness gate: each selected card must have positive deltas for dispatches, accepted batches, computed values, consumed values, and strict parity samples on workload IDs 5 (noise) and 6 (aquifer). Fallback attempt deltas are retained by workload. Mixed requires evidence from both cards. Equality after CPU fallback, startup tests, or unconsumed kernel results cannot pass as GPU validation.

Strict mode recomputes GPU batches on the CPU, so its chunks/second is only diagnostic and must not be used as a speedup result. After a passing strict matrix, run a separate `--mode verified-exact --strict-report <strict-matrix-report.json>` matrix. It uses new worlds and keeps the candidate JAR, seed, corpus and workers unchanged. It still requires positive per-device/workload consumed-value deltas, nonzero startup/periodic parity evidence, zero parity failures, and full CPU-vs-GPU saved-world semantic equality. A zero parity-sample delta during this run is allowed because world-creation warmup may already have produced the periodic sample; a zero absolute sample count is not. Only this second matrix reports speedup, and it rejects a strict report with a different JAR SHA, corpus or case set.

For each case the script records initial and post-generation GPU status, including per-device native queue/in-flight/submitted/completed/failure counters, generates and saves the corpus, gracefully stops the server, snapshots the saved chunks, restarts, reloads every corpus chunk with generation disabled, saves, gracefully stops again, and snapshots the result. It compares the pre-restart and post-restart snapshots. For both `generate` and `reload`, the probe writes raw per-tick CSV plus p50/p95/p99/max tick duration and tick-start interval, actual TPS, and counts above 50/100 ms. The matrix throughput report carries these timing-quality fields alongside chunks/second; strict-mode timings remain diagnostic only and cannot claim GPU speedup. `run_matrix.py` then compares each GPU generated-world snapshot against the CPU baseline. Verified-exact reports include the throughput ratio only after the matching strict report and semantic checks pass. Any missing chunk, incomplete saved-light metadata, data-version mismatch, required-but-absent structure coverage, GPU admission failure, zero consumed-work counter delta, insufficient parity evidence, parity failure, nonzero server exit, incomplete tick report, or semantic difference fails the case or matrix. Radius-4 smoke runs do not require a structure start; the full radius-32 release corpus does.

The separate `inflight-worldgen-reload-retirement` release gate is not part of the current matrix command yet. Status snapshots now include per-device native in-flight and queue counters; after compiling the probe, the test can require a positive in-flight batch while the probe remains active, issue `/gpur reload`, and record the status-to-reload time gap. It must then prove request completion, clean save/restart and semantic equality. This gate remains not run until the candidate smoke passes.

Run a smaller smoke corpus by passing `--radius 4`; use the default radius for the full release gate. A smoke result does not satisfy the full gate. The run matrix can take hours; the folders and reports remain intact if a run fails.

## What the semantic comparator checks

`semantic_snapshot.py` uses only the Python standard library. It reads gzip/zlib/raw Anvil chunks, external chunk payloads, all region files intersecting the requested bounds, and typed NBT. Unsupported compression, malformed sectors/NBT, missing chunks, or invalid chunk coordinates fail closed.

For each terrain chunk it requires `Status=full` and complete saved-light metadata: the `isLightOn` byte and Starlight light-version tag must be present with the expected version, and the saved status must be at least `LIGHT`. Paper/Starlight can deliberately save `isLightOn=false` to force vanilla relighting on load, so the checker preserves and compares that value instead of requiring it to be true. It expands every in-dimension section's block palette to 4,096 semantic block states and biome palette to 64 semantic biome cells, expands every named heightmap to 256 values, compares each saved block/sky-light nibble array, and compares structure starts, structure references, block entities, section metadata and every other typed chunk-NBT field. Sections wholly outside the dimension height may contain only Starlight lighting metadata and are retained without inventing block or biome palettes. It also reads the separate entity and POI Anvil stores: entity order is normalized by UUID/canonical NBT, but UUID values and all other entity NBT remain part of equality. Random or otherwise different generated entity UUIDs therefore fail comparison; the checker never rewrites or ignores them. POI records are sorted as a set. The report records expected versus observed chunk coverage. Structure starts are required for the default radius-32 release comparison; a small smoke corpus may validly contain none.

Normalization is narrow and explicit. Anvil sector offsets, lengths, compression bytes and timestamps are excluded from content equality; region timestamps are still carried per chunk and their differences are reported separately. Compound keys and section order are sorted; block/biome palette order and unused palette entries are removed by decoding to semantic values; heightmap arrays are decoded; block-entity entries are sorted by coordinates and ID; structure references and structure child/piece lists are order-normalized; entity and POI set-like lists are sorted. Other NBT list order remains unchanged. Only the chunk-root `LastUpdate` and `InhabitedTime` clocks are excluded from content equality, and their differences remain in the report. `DataVersion` must match. Changing actual block, biome, heightmap, light, structure, entity, POI or other chunk NBT fails. If CPU-vs-GPU comparison fails, the matrix preserves that report and runs a second fresh CPU world as a repeatability control; neither generated entity UUIDs nor other fields are silently normalized to turn a mismatch into a pass.

Example direct comparison:

```powershell
python benchmarks/worldgen/semantic_snapshot.py compare `
  --left C:\GPur-validation-20261009\validation\worldgen\cpu-snapshot `
  --right C:\GPur-validation-20261009\validation\worldgen\gpu-snapshot `
  --require-structures `
  --report C:\GPur-validation-20261009\validation\worldgen\comparison.json
```

The parity claim is for saved terrain/entity/POI chunk NBT within the bounded normal-world corpus. It is not a comparison of player data, global `level.dat` clocks, Nether/End dimensions, or client rendering. It does prove the saved chunks were fully lit, persisted through restart, and match in the compared semantic fields.

## 1.0.0 regression remains a separate gate

Do not use this terrain harness as a substitute for replaying the 1.0.0 regression scenarios on the new immutable 1.1.0 candidate. The archived exact 1.0.0 server artifact SHA-256 (`bfe87ccaf419065f49acc0b7cf1b24dbda93d6abe8680dc5d3073f7043e2c846`) is historical provenance only; do not rebuild or rerun that old artifact as the release gate. Replay the full original matrix on the candidate: CPU/RTX 3070/GTX 1080/mixed, 50/150/300 clients, configured 20% walking (10/30/60 clients at those targets), 3,000 fixed AI mobs, and 300 redstone circuits. The same immutable candidate JAR and SHA must be used for the worldgen matrices and regression runs.

The all-walking 300-client diagnostics are a separate report. Four historical cases ended in watchdog exit code 70 after roughly 60-second tick stalls, with movement/waypoint and `Entity.setPos` work in the failure stacks. Re-run and report those cases independently; preserve the failures as failures and do not blend them into the 20%-walking matrix.

Movement results have limits. The loopback clients implement the login/control packets needed for a real network session but consume many large packet payloads opaquely. Walkers send grounded position/look packets at 20 Hz around a bounded two-block-radius route and cap movement near 2 blocks/second; they do not exercise ordinary exploration, collision physics, block interaction, combat or inventory. The updated GPurBench source records per-phase server-side movement samples keyed by player UUID/name, accumulated nonzero from/to displacement and distinct movers. `benchmarks/run_matrix.py` will require exact configured mover counts in designated walk and mob phases and write a structured movement-validation report. This source change has not been built or run yet, so no movement replay is validated. The server-directed 100-tick travel phase is synthetic teleport exploration, not client walking. Fixed mobs use AI and ordinary activation but are persistent/invulnerable and fixture damage/conversion is controlled; ticking-chunk census is not proof that every mob ran its full AI every tick. Redstone remains a CPU workload.

Historical baseline references: [`controlled-matrix.json`](../../docs/results/controlled-matrix.json), [`all-walking-before-retry-fix.json`](../../docs/results/all-walking-before-retry-fix.json), and [`26.2-validation.en.md`](../../docs/26.2-validation.en.md).
