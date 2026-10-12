# Isolated vanilla worldgen validation

## W7 live corner graph adoption fixture (2026-10-12)

The focused physical-GPU evidence reported 190 XML executions in 45 suites (zero failures/errors, 2 skipped); the full run reported 9,480 executions in 160 suites (zero failures/errors, 23 skipped) and 7,535 distinct `classname` plus `displayname` keys. Repeated parameterized display names remain separate executions. All 41 source hashes matched across focused, full, and current-source snapshots; Luna independently audited them at xhigh. The full run enabled `gpurGpuLiveCornerAdoptionTests=true`. Live adoption consumes the complete eight-root default Overworld corner closure through normal `NoiseChunk` processing, 112 batches per card per direct or staging buffer path. Separate shader-compiler/corpus tests on GTX 1080 and RTX 3070 matched initialized nested-spline and `TerrainProvider` cases, `Float.MIN_VALUE`, and adversarial binary32 inputs bit-for-bit through both buffer paths. Explicit bit/RTE binary32 emulation with exact double registers handles native FP32 subnormal flushing without approximation or quantization. CPU `FlatCache` table construction remains on the CPU; this does not claim full graph execution or CPU-work offload.

Each card consumed 19,600 actual corner values across two complete default 384-high `NoiseChunk`s at `(0,0)` and `(-32,-48)` (9,800 per chunk); each chunk compared all 98,304 block positions for raw density, classification, and fluid-update flags. Corner graphs default to disabled. Pass `--corner-graphs` to the worldgen CLI to enable live W7 corner graph adoption. That mode disables redundant W5 noise batching and requires positive consumed-value deltas for both W6 and W7 on every selected GPU. Without the flag, the default validation path remains W5 `noise-batches` plus W6 `aquifer-ranking`. The movement runner also accepts `--corner-graphs` with `--worldgen-mode strict|verified-exact`; GPU cases use W5 off and W6/W7 on, CPU cases keep all GPU workloads off, and the default is unchanged. Combining the flag with disabled worldgen is rejected. The fixture uses an empty `Beardifier` and `Blender.empty()`; it does not validate structures, saved-world parity, or server performance. The updated standalone Java probe compiled with `javac` (five existing deprecation warnings), the Python worldgen suite passed 47/47 tests, and the movement harness passed 10/10. The new candidate JAR has not yet been built or runtime-validated. See the [adoption receipt](../../docs/results/1.1.0-corner-graph-adoption-validation.json). The strict CPU cross-world comparison remains failed on retained entity NBT; no fields are excluded or normalized. Version 1.1.0 remains in effect.

This harness validates vanilla `NORMAL` chunk generation through lighting, save, clean shutdown, server restart, reload, and saved Anvil comparison. Its only runtime output belongs under `C:\GPur-validation-20261009\validation`; it binds loopback port `25620` by default and refuses port `25565`. It does not access or copy from the live server directory.

The earlier b693 runs remain historical failures: the first four-case smoke had a GPU startup parity mismatch with zero consumed work, and a later CPU postprocessing pilot failed normal-tick restart parity. The later production81 candidate (`81cd39cad0065edf3fb1e9e9888d5dc2509d9792`, JAR SHA-256 `175469927553c38137f487a45cccf125dbd4fbbf1f314f639fa35879a40ff31a`) passed the unfiltered physical suite (9,424 tests, 151 XML suites, 23 skipped). Two fresh frozen CPU radius-4 rounds each passed their own 81-chunk save/reload comparison with zero differences in all 10 semantic categories. Their strict CPU-vs-CPU comparison did not pass: the inputs matched, but 1,784 block positions differed. Those block differences remain unexplained and the CPU reference gate remains blocked; no all-GPU worldgen matrix has run.

Some entity UUID variation is independently expected and remains part of comparison. `Entity` creates UUIDs from its entity RNG; Purpur defaults to a shared RNG seeded independently of world generation, while the opt-out uses `RandomSource.create()`. `MineshaftPieces` creates chest minecarts through `EntityTypes.CHEST_MINECART.create`. These UUID differences must be reported separately from block differences, never removed from semantic NBT, and do not explain the 1,784 block positions.

The older worldgen Python source suite passed 44/44 tests and the movement harness passed 9/9. W7 source commit `456e864d73d08fe0b9ea32f6374a97a65996a7dd` historically passed 145 focused tests and 9,435 full tests. Its immutable candidate is `C:\GPur-validation-20261009\validation\artifacts\456e864d7\gpur-server-26.2-SNAPSHOT1.1.0.jar` (92,205,013 bytes, SHA-256 `1a1e290183e3de847e9d675bbbc9b2ecc4656023aa1bd379092354ece762e896`). The standalone Java probe was compiled against that candidate: source SHA-256 `6582f48ce9f8bc2e75c1bb09c99fe7194124418ab2a09dee65a6c71613511d07`, JAR SHA-256 `6bc7972a90cace93572cd55fe71487278b84604cb2becdf001b9567e8a34da29`. Its first `javac` attempt exposed a log statement referencing an undeclared `run`; the statement was corrected to use `active`, and the next compile succeeded with five existing deprecation warnings. Two serial frozen CPU controls each passed their own full 81-chunk roundtrip with zero differences in all 10 categories. The unfiltered cross-world comparison still fails on 70 entity-NBT leaf differences; blocks, biomes, heightmaps, light, structures, block entities, POI, section metadata, and other NBT all match. These are historical candidate results; the worldgen release gate remains blocked. See [`release-gate.json`](release-gate.json) for those runs' hashes, reports, and pending stages.

## Historical W7 runtime matrix commands (2026-10-11)

The commands below document the historical 2026-10-11 run using the immutable `456e864d7` candidate and its probe; they are not commands for the updated W7 adoption candidate. That candidate JAR has not yet been built or runtime-validated. The probe builder invokes `javac` only; it does not run Gradle or a server. Do not run a four-case saved-world GPU matrix until the strict CPU cross-world reference comparison passes every category.

For historical reproducibility, this is the previous probe build and strict-matrix command shape. The cross-world reference check remains failed on retained entity NBT, so do not start the GPU saved-world matrix yet:

```powershell
$CandidateJar = 'C:\GPur-validation-20261009\validation\artifacts\456e864d7\gpur-server-26.2-SNAPSHOT1.1.0.jar'
$ProbeOutput = 'C:\GPur-validation-20261009\validation\worldgen-plugin-w7-current'

python benchmarks/worldgen/build_plugin.py `
  --classpath validation/classpath.txt `
  --jdk C:\Users\caram\.jdks\openjdk-25.0.2 `
  --output $ProbeOutput

$ProbeJar = Join-Path $ProbeOutput 'GPurWorldgenProbe.jar'
python benchmarks/worldgen/run_matrix.py `
  --jar $CandidateJar `
  --probe $ProbeJar `
  --mojang validation\smoke-gpu-final\cache\mojang_26.2.jar `
  --eula validation\eula.txt `
  --jdk C:\Users\caram\.jdks\openjdk-25.0.2 `
  --output C:\GPur-validation-20261009\validation\worldgen-w7-xms4 `
  --radius 4 `
  --initial-heap-gib 4 `
  --continue-on-error `
  --parity-dump-dir C:\GPur-validation-20261009\validation\worldgen-parity-w7-xms4
```

For an isolated diagnostic rerun, optionally pass `--parity-dump-dir C:\GPur-validation-20261009\validation\worldgen-parity-dumps`. The matrix creates a unique subdirectory per matrix and case, and the JVM writes a binary input/expected/actual snapshot only when strict parity fails. The option is disabled by default and its path must remain under the validation root.

The default corpus uses seed `1196459378`, center chunk `(128,128)`, radius `32` and a `65 × 65 = 4,225` chunk square. The probe creates a new normal world for each CPU, RTX 3070, GTX 1080 and mixed-device case, enables vanilla structures, disables natural mob spawning and day/weather cycling, then submits chunks in stable X-major/Z-minor order through a bounded 64-request async window. Completion order may differ; the deterministic ordering is request order. The off-spawn corpus starts far from server spawn chunks. Each case has a unique server directory/world; no prior world is reused.

Every server uses `-Xmx16G`; `--initial-heap-gib` selects `-Xms` from 1 GiB through 16 GiB and defaults to 16 GiB. The initial and maximum heap are recorded separately, and verified-exact matrices require matching JVM flags in the strict reference. Cases use the same seed and settings, no connected clients, and loopback only. CPU has GPU acceleration disabled. By default corner graphs are disabled, and GPU cases enable W5 `noise-batches` and W6 `aquifer-ranking` with `gpu.force=false`. `--mode strict` is the default correctness gate: each selected card must have positive dispatch, accepted-batch, computed-value, consumed-value, and strict-parity-sample deltas for W5 and W6. With explicit `--corner-graphs`, live W7 corner adoption disables redundant W5 noise batching and requires positive consumed-value deltas for W6 and W7 on every selected card. Fallback attempt deltas are retained by workload. Mixed requires evidence from both cards. Equality after CPU fallback, startup tests, or unconsumed kernel results cannot pass as GPU validation.

For saved-world parity diagnostics, `--freeze-simulation` opts into `gpur.worldgen.freeze-simulation=true`. The probe freezes Bukkit's server tick manager before it creates or loads the test world, records the request and `isFrozen()` readback in each status and phase report, verifies the mode remains active through save/reload, and restores the prior state only during plugin shutdown. Strict-reference validation requires matching freeze mode and JVM property on every case. A matching frozen strict reference may be followed by `--mode verified-exact`; that report can show a frozen-worldgen chunks-per-second ratio as an isolated numeric worldgen comparison. Frozen tick metrics are not normal SMP TPS/MSPT, and the ratio is not a normal server-speedup claim. The default remains unfrozen; the full 1.0.0 movement regression must run without this flag.

### Opt-in serial CPU repeatability diagnostic

The probe request window defaults to 64 and accepts `--request-window 1..64`; the Paper chunk worker count defaults to 4 and accepts `--worker-threads 1..64`. The request-window JVM property, Paper worker configuration, and probe READY/status/phase readback are recorded in metadata. Strict-reference validation requires the exact requested values and JVM property. Invalid values are rejected before Java starts. Normal benchmark defaults remain 64/4.

The diagnostic used two serial, fresh, radius-4 CPU-only strict runs with `--freeze-simulation --request-window 1 --worker-threads 1`, identical seed/JDK/JAR/probe, and separate timestamped matrices under one output root. Both controls completed on 2026-10-11 and each self-roundtrip passed. The cross-run comparison below preserves all fields and fails only in the entities category; no field was filtered.

```powershell
$OutputRoot = 'C:\GPur-validation-20261009\validation\worldgen-cpu-serial-requestwindow1-worker1-w7'
$CandidateJar = 'C:\GPur-validation-20261009\validation\artifacts\456e864d7\gpur-server-26.2-SNAPSHOT1.1.0.jar'
$ProbeJar = 'C:\GPur-validation-20261009\validation\worldgen-plugin-w7-current\GPurWorldgenProbe.jar'

python benchmarks/worldgen/run_matrix.py `
  --cases cpu `
  --mode strict `
  --output $OutputRoot `
  --jar $CandidateJar `
  --probe $ProbeJar `
  --mojang validation\smoke-gpu-final\cache\mojang_26.2.jar `
  --eula validation\eula.txt `
  --jdk C:\Users\caram\.jdks\openjdk-25.0.2 `
  --initial-heap-gib 4 `
  --freeze-simulation `
  --request-window 1 `
  --worker-threads 1 `
  --seed 1196459378 `
  --center-x 128 `
  --center-z 128 `
  --radius 4 `
  --no-require-structures `
  --port 25620 `
  --continue-on-error

# Run the exact command above a second time after the first process exits.
```

Then compare `snapshots\generated` from the two `cases.cpu.workspace` directories named by their matrix reports:

```powershell
python benchmarks/worldgen/semantic_snapshot.py compare `
  --left '<first CPU workspace>\snapshots\generated' `
  --right '<second CPU workspace>\snapshots\generated' `
  --report "$OutputRoot\cpu-repeatability-comparison.json"
```

This comparison keeps all semantic categories and entity UUIDs. The completed report is `C:\GPur-validation-20261009\validation\worldgen-cpu-serial-requestwindow1-worker1-w7\cpu-repeatability-comparison.json` (SHA-256 `fa2e98630ef6adfc0c050334d8425845710ff928f7ec2c813584096e3b49530c`). It found zero block-position differences; the eight other non-entity categories also match. The `entities` category has 70 typed-NBT leaf differences in the UUID-sorted strict comparison. These leaf counts include UUID 24, Paper.OriginWorld 24, WorldUUIDLeast/WorldUUIDMost 12 combined, LootTableSeed 2, Paper.Origin 4, and Pos 4. A separate diagnostic keyed records by entity type and exact `Pos` double bits; all 6 records matched uniquely with no duplicate or unmatched keys. That diagnostic showed the two chest minecarts in chunk `(129,131)` were reordered by UUID sorting: their positions, origins, and loot-table seeds match. After keyed alignment, 60 entity-NBT leaf differences remain, only in UUID, Paper.OriginWorld, WorldUUIDLeast, and WorldUUIDMost. The original all-category comparison remains failed; no fields were excluded or normalized. The details are in `C:\GPur-validation-20261009\validation\worldgen-cpu-serial-requestwindow1-worker1-w7\entity-key-diagnostic.json` (SHA-256 `314e5f0fb8cb04792c8d90a18e23026bc21a06f8597f0093d4df27ef80bddee5`). Do not run the four-case worldgen GPU matrix while the CPU reference gate is unresolved.

Strict mode recomputes GPU batches on the CPU, so its chunks/second is only diagnostic. After a passing strict matrix, run a separate `--mode verified-exact --strict-report <strict-matrix-report.json>` matrix. It uses new worlds and keeps the candidate JAR, seed, corpus, workers, and simulation-freeze mode unchanged. It still requires positive per-device/workload consumed-value deltas, nonzero startup/periodic parity evidence, zero parity failures, and full CPU-vs-GPU saved-world semantic equality. A zero parity-sample delta during this run is allowed because world-creation warmup may already have produced the periodic sample; a zero absolute sample count is not. A normal-tick verified-exact matrix may report server speedup; a frozen matrix reports only the isolated worldgen throughput ratio described above and is ineligible for a normal server-speedup claim.

For each case the script records initial and post-generation GPU status, including per-device native queue/in-flight/submitted/completed/failure counters, generates and saves the corpus, gracefully stops the server, snapshots the saved chunks, restarts, reloads every corpus chunk with generation disabled, saves, gracefully stops again, and snapshots the result. It compares the pre-restart and post-restart snapshots. For both `generate` and `reload`, the probe writes raw per-tick CSV plus p50/p95/p99/max tick duration and tick-start interval, actual TPS, and counts above 50/100 ms. The matrix throughput report carries these timing-quality fields alongside chunks/second; strict-mode timings remain diagnostic only and cannot claim GPU speedup. `run_matrix.py` then compares each GPU generated-world snapshot against the CPU baseline. Verified-exact reports include the throughput ratio only after the matching strict report and semantic checks pass. Any missing chunk, incomplete saved-light metadata, data-version mismatch, required-but-absent structure coverage, GPU admission failure, zero consumed-work counter delta, insufficient parity evidence, parity failure, nonzero server exit, incomplete tick report, or semantic difference fails the case or matrix. Radius-4 smoke runs do not require a structure start; the full radius-32 release corpus does.

The separate `inflight-worldgen-reload-retirement` release gate is not part of the current matrix command yet. The standalone probe has been compiled and status snapshots include per-device native in-flight and queue counters. The test can require a positive in-flight batch while the probe remains active, issue `/gpur reload`, and record the status-to-reload time gap. It must then prove request completion, clean save/restart and semantic equality. This gate remains not run until the candidate smoke passes.

Run a smaller smoke corpus by passing `--radius 4`; use the default radius for the full release gate. A smoke result does not satisfy the full gate. The run matrix can take hours; the folders and reports remain intact if a run fails.

## What the semantic comparator checks

`semantic_snapshot.py` uses only the Python standard library. It reads gzip/zlib/raw Anvil chunks, external chunk payloads, all region files intersecting the requested bounds, and typed NBT. Unsupported compression, malformed sectors/NBT, missing chunks, or invalid chunk coordinates fail closed.

For each terrain chunk it requires `Status=full` and complete saved-light metadata: the `isLightOn` byte and Starlight light-version tag must be present with the expected version, and the saved status must be at least `LIGHT`. Paper/Starlight can deliberately save `isLightOn=false` to force vanilla relighting on load, so the checker preserves and compares that value instead of requiring it to be true. It expands every in-dimension section's block palette to 4,096 semantic block states and biome palette to 64 semantic biome cells, expands every named heightmap to 256 values, compares each saved block/sky-light nibble array, and compares structure starts, structure references, block entities, section metadata and every other typed chunk-NBT field. Sections wholly outside the dimension height may contain only Starlight lighting metadata and are retained without inventing block or biome palettes. It also reads the separate entity and POI Anvil stores: entity order is normalized by UUID/canonical NBT, but UUID values and all other entity NBT remain part of equality. Random or otherwise different generated entity UUIDs therefore fail comparison; the checker never rewrites or ignores them. POI records are sorted as a set. The report records expected versus observed chunk coverage. Structure starts are required for the default radius-32 release comparison; a small smoke corpus may validly contain none.

Normalization is narrow and explicit. Anvil sector offsets, lengths, compression bytes and timestamps are excluded from content equality; region timestamps are still carried per chunk and their differences are reported separately. Compound keys and section order are sorted; block/biome palette order and unused palette entries are removed by decoding to semantic values; heightmap arrays are decoded; block-entity entries are sorted by coordinates and ID; structure references are treated as sets; structure `Children` and `Pieces` lists retain their stored order because placement consumes them sequentially; entity and POI set-like lists are sorted. Other NBT list order remains unchanged. Only the chunk-root `LastUpdate` and `InhabitedTime` clocks are excluded from content equality, and their differences remain in the report. `DataVersion` must match. Changing actual block, biome, heightmap, light, structure, entity, POI or other chunk NBT fails. If CPU-vs-GPU comparison fails, the matrix preserves that report and runs a second fresh CPU world as a repeatability control; neither generated entity UUIDs nor other fields are silently normalized to turn a mismatch into a pass.

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

The current explicit GPurBench artifact is `C:\GPur-validation-20261009\validation\movement-plugin-current-source-20261010\GPurBench.jar` (43,726 bytes, SHA-256 `99d581c4382a84de3282ed3dd93fa1ceee923a63b57273e3ffff50c6c7eed71e`). It was used for a CPU-only 10-player pilot on production81, which completed with server exit 0. Server-observed `PlayerMoveEvent` telemetry passed for exactly 2/2 selected movers in both the 10-player walk and 100-fixed-mob phases, with UUIDs and nonzero from/to displacement. The pilot actually targeted 10 players; its result/run metadata incorrectly also listed the default targets `[50,150,300]`. Its portable proof is `C:\GPur-validation-20261009\validation\movement-pilot-current-plugin-20261010\movement-pilot-proof.json` (SHA-256 `8f98eb9b7b9d0047ee5b0faae47d442c51b6af7d9d8801e77fd0367414c8127a`). This remains historical pilot evidence only. The full normal-tick 50/150/300 CPU/RTX 3070/GTX 1080/mixed replay on the W7 candidate started on 2026-10-11 in `C:\GPur-validation-20261009\validation\movement-full-replay-w7-current-plugin-20261011`; final movement and performance results are pending.

The full replay uses normal unfrozen ticks, Xms4G/Xmx16G, view distance 6, simulation distance 4, 90-second walk/mob/natural-spawn/redstone phases, 45-second settles and recovery, 20-second synthetic travel at 20 TPS, and the full 500/1500/3000 normal-AI ladder. That is at least 1,145 measured seconds per case before startup/ramp/cleanup. The current run uses the immutable W7 candidate and explicit current plugin shown below, sequentially across all four cases. The default plugin path is stale. The runner verifies exact expected mover counts from server-side `PlayerMoveEvent` telemetry, including UUIDs and nonzero displacement, not client packet counts.

```powershell
$CandidateJar = 'C:\GPur-validation-20261009\validation\artifacts\456e864d7\gpur-server-26.2-SNAPSHOT1.1.0.jar'

python benchmarks/run_matrix.py `
  --cases cpu,rtx3070,gtx1080,mixed `
  --jar $CandidateJar `
  --plugin C:\GPur-validation-20261009\validation\movement-plugin-current-source-20261010\GPurBench.jar `
  --viaversion validation\bots\ViaVersion-5.12.1-SNAPSHOT-build1469.jar `
  --viabackwards C:\Users\caram\Downloads\ViaBackwards-5.12.1-SNAPSHOT.jar `
  --eula validation\eula.txt `
  --mojang validation\smoke-gpu-final\cache\mojang_26.2.jar `
  --output C:\GPur-validation-20261009\validation\movement-full-replay-w7-current-plugin-20261011 `
  --initial-heap-gib 4 `
  --worldgen-mode verified-exact `
  --seconds 90 `
  --natural-seconds 90 `
  --settle-seconds 45 `
  --travel-seconds 20 `
  --targets 50,150,300 `
  --walking-percent 20 `
  --view 6 `
  --simulation 4 `
  --port 25620 `
  --seed 1196459378
```

Do not add `--pilot`, `--force` or `--freeze-simulation`; default natural spawning stays enabled. The prior four 300-client all-walking watchdog exit-70 cases remain failed diagnostics and are not blended into this 20%-walking replay. Walkers send grounded position/look packets at 20 Hz around a bounded two-block-radius route and cap movement near 2 blocks/second; they do not exercise ordinary exploration, collision physics, block interaction, combat or inventory. The server-directed travel phase is synthetic teleport exploration, not client walking. Fixed mobs use AI and ordinary activation but are persistent/invulnerable and fixture damage/conversion is controlled; ticking-chunk census is not proof that every mob ran its full AI every tick. Redstone remains a CPU workload.

Historical baseline references: [`controlled-matrix.json`](../../docs/results/controlled-matrix.json), [`all-walking-before-retry-fix.json`](../../docs/results/all-walking-before-retry-fix.json), and [`26.2-validation.en.md`](../../docs/26.2-validation.en.md).
