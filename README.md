# GPur

[English](README.md) | [日本語](README.ja.md)

GPur is a Purpur 26.2 fork with optional Vulkan compute: https://github.com/nekoneko2872/GPur.

Paper/Purpur plugins execute on ordinary server threads. GPU kernels receive copied numeric inputs. Events, random generators, world changes, and dependent update ordering remain on the CPU.

## Documentation

| Document | English | 日本語 |
| --- | --- | --- |
| Migration and implementation | [English](docs/26.2-migration.en.md) | [日本語](docs/26.2-migration.md) |
| Hardware and load validation | [English](docs/26.2-validation.en.md) | [日本語](docs/26.2-validation.md) |
| GPur 1.1.0 validation record | [English](docs/1.1.0-validation.en.md) | [日本語](docs/1.1.0-validation.md) |
| Runtime status and elytra update | [English](docs/26.2-runtime-updates.en.md) | [日本語](docs/26.2-runtime-updates.md) |
| Vanilla terrain noise interpolation | [English](docs/26.2-vanilla-terrain.en.md) | [日本語](docs/26.2-vanilla-terrain.md) |
| Asynchronous vanilla terrain dispatch | [English](docs/26.2-async-terrain.en.md) | [日本語](docs/26.2-async-terrain.md) |
| Retired custom terrain design archive | [English](docs/26.2-custom-terrain.en.md) | [日本語](docs/26.2-custom-terrain.md) |

## Build

Use a Git checkout and JDK 25:

```sh
./gradlew applyAllPatches
./gradlew :purpur-server:test :purpur-server:createPaperclipJar
```

Artifact: `gpur-server/build/libs/gpur-server-26.2-SNAPSHOT1.1.0.jar`. The filename combines `mcVersion` and `gpurVersion` from `gradle.properties`. Source folders are `gpur-server`, `gpur-api`, and `gpur-checkstyle`; Gradle project names and `org.purpurmc` API packages are retained. Generated sources are not committed; their changes must be rebuilt into patches.

Run with JDK 25 and `--enable-native-access=ALL-UNNAMED`. The `-dev.jar` and `gpur-bundler-*.jar` files are build artifacts, not the Paperclip launch jar. See [the migration and validation record](docs/26.2-migration.en.md) for the implemented scope and checks.

## Configuration

Existing `gpur.yml` keys are retained. Schema 2 migrates the old unreachable Mob candidate threshold (32 to 1) and Anti-Xray section threshold (12 to 1) only when they still equal legacy defaults. Other custom settings are retained. Purpur, Paper, and plugin configuration formats are unchanged.

```yaml
gpu:
  multi-gpu:
    enabled: true
    devices: [auto]
  force: false
  timeout-ms: 100
```

Device selectors accept the UUID printed at startup, an exact device name, or `auto`. Physical UUIDs deduplicate driver entries. Independent devices support mixed GPUs without SLI/NVLink. Disabling multi-GPU selects one compatible device. Unsupported or failed devices use CPU fallbacks.

`force` bypasses performance preference for diagnostics, but never parity checks or safety limits. Normal operation measures dispatch/readback time and temporarily prefers CPU when GPU compute is slower. Context occupancy is not hardware GPU utilization.

## Vanilla terrain interpolation

GPur 1.1.0 has an experimental, opt-in Vulkan path for bounded noise-density interpolation in the existing vanilla terrain generator. It is disabled by default. No `bukkit.yml` generator entry or new world is needed; existing and new vanilla worlds keep the vanilla generator and world data contract. The option does not replace saved chunks or change seeds, datapacks, or the save format.

The CPU builds the density graph and computes the actual corner densities. The GPU interpolates those values for bounded slabs using FP64 and the CPU operation order. Strict verification compares every GPU batch with the full CPU reference for that slab. The GPU does not generate whole chunks: density/noise graph work, random-number generation, blending, aquifers, surface rules, caves, biomes, structures, block placement, lighting, serialization, and saving remain CPU work.

The path needs `chunk-generation.vanilla-terrain.enabled: true`, `chunk-generation.gpu-acceleration.enabled: true`, and `chunk-generation.gpu-acceleration.terrain-enabled: true`. Its bounded defaults are:

```yaml
chunk-generation:
  vanilla-terrain:
    enabled: false
    verify-every-batch: true
    max-interpolators: 16
    max-slab-values: 1048576
    minimum-values: 1024
    parity-interval: 128
```

The strict per-batch CPU comparison is enabled by default. `parity-interval` applies only when `verify-every-batch` is false. Slabs smaller than 1,024 values remain on the CPU. Keep strict verification on; this new path has no hardware or performance validation, so disabling verification or using `gpu.force: true` is not a performance recommendation. Select devices with the existing `gpu.multi-gpu.devices` names or UUIDs. A configured or idle device does not mean terrain work is being dispatched.

Preloading is a separate CPU workload control. `chunk-generation.preloading.max-extra-distance` defaults to `0`; setting it to `2` increases the preload range and can increase CPU chunk loading, generation, and sending. It does not enable GPU terrain work. See the [vanilla terrain guide](docs/26.2-vanilla-terrain.en.md) for the full scope and settings.

New unmarked-world selections of `gpur:terrain-v1` are retired and fail at startup. Remove the retired generator setting to use vanilla terrain, or point `server.properties` `level-name` to the intended existing vanilla world directory. For example, `level-name=gpur` loads the existing directory named `gpur`; remove a matching `worlds.gpur.generator` entry if present. Keep every world directory and marker file intact. A world with `gpur-terrain.properties` retains its legacy custom terrain rules on CPU, even when GPU terrain is disabled. Never delete or edit that marker to convert a world. See the [retired custom-terrain archive](docs/26.2-custom-terrain.en.md) for historical implementation details.

The old `chunk-generation.custom-terrain.enabled` setting does not select a world or enable vanilla interpolation. Use `chunk-generation.vanilla-terrain.enabled` for the bounded vanilla path.

The 1.1.0 artifact is `gpur-server/build/libs/gpur-server-26.2-SNAPSHOT1.1.0.jar`. The historical 1.0.0 hardware and load measurements remain valid for their recorded builds and workloads, but they do not validate this new interpolation path. No performance improvement or new hardware certification is claimed.

## Runtime status

`/gpur` or `/gpur status` shows a compact, colored overview grouped by GPU. Player distances and Anti-Xray each have a named state: no results yet, GPU enabled, CPU cooldown with a retry countdown, blocked, or disabled. GPU result counters describe accepted compute batches, not complete chunks, players, ticks, or hardware utilization. The preload radius and flight rate boost are shown separately. Hover a row for its meaning; `/gpur status detail` adds dispatch timings in milliseconds, CPU reference samples, cooldown history, UUIDs, and admission skips. Both commands require `gpur.command` (operators by default).

An available GPU can be idle. Result counts are accepted GPU batches since start/reload, not players, ticks, or applied packets. In-flight jobs are occupied execution contexts, not GPU utilization. CPU fallbacks and admission skips do not count every calculation that remains on the CPU. `/gpur reload` resets the counters and revalidates devices.

## Elytra preloading

New configurations prioritize the flight direction within Paper's existing chunk range. They use `chunk-generation.preloading.max-extra-distance: 0` and `chunk-generation.preloading.elytra-throughput-boost.enabled: false`. Existing explicit values are preserved; older configurations with an extra radius of 3 and flight rate boosts enabled should set these values to 0 and false when prioritizing tick stability. Extra radius expands chunk work in all directions, and the boost increases CPU load/generation/send rates. Elytra preload settings do not enable GPU terrain interpolation; see the [vanilla terrain guide](docs/26.2-vanilla-terrain.en.md).

On a high-spec server, the optional boost can increase generation concurrency during actual elytra flight:

```yaml
chunk-generation:
  preloading:
    max-extra-distance: 2
    elytra-throughput-boost:
      enabled: true
      max-extra-concurrent-generates: 8
      concurrent-generates-multiplier: 2.0
```

The concurrency cap defaults to `8` (`0..64`); it is separate from the generation request-rate multiplier. This only increases CPU-side generation concurrency, does not change which chunks are selected beyond the separate extra-distance setting, and does not guarantee TPS or alter GPU terrain settings. See the [vanilla terrain guide](docs/26.2-vanilla-terrain.en.md) for the concurrency formula and Paper limit behavior.

Directional priority bands are cached until the queues are rebuilt, and speed changes that cannot affect any queued chunk no longer trigger a rebuild. When directional preloading is inactive, the original Paper queue comparator is used. These changes reduce preload overhead; they do not guarantee 20 TPS while generating new terrain.

## Compute contract

- Distances use FP64, Java arithmetic order, and no fused multiply-add. Each device must pass startup parity checks.
- Anti-Xray masks support Paper HIDE mode, stage the entire packet before mutation, and periodically compare packet bytes with Paper. Randomized modes and custom hidden non-conductors retain Paper's CPU implementation. GPur does not enable Paper Anti-Xray in worlds where it is disabled.
- The opt-in `gpur:terrain-v1` custom generator is retired for new unmarked worlds. Existing marker-backed worlds retain the legacy custom CPU rules. The optional vanilla path only interpolates bounded density slabs; the CPU retains the rest of vanilla terrain generation.
- Mob AI, movement state changes, plugin callbacks, and dependent redstone updates cannot be dispatched as a parallel tick while preserving Paper behavior. Only isolated calculations with verified CPU references can be offloaded.

This implementation requires Vulkan 1.1, a compute queue, FP64 shader support, and host-visible coherent memory. GTX 1080 and RTX 3070 pass hardware kernel checks; older hardware is not certified. Startup tests determine device admission. Vulkan support alone does not guarantee a speedup.

## Validation target

A dispersed SMP with 300 players is a benchmark target, not a capacity guarantee. Compare p50/p95/p99 MSPT, chunk load/generation latency, GC, plugins, and full CPU-to-GPU-to-CPU cost using fresh worlds with matching seeds/settings and the same hardware. Hardware kernel parity does not establish whole-server performance or universal plugin compatibility.

The [hardware and load validation record](docs/26.2-validation.en.md) includes the CPU, RTX 3070, GTX 1080 and mixed-device experiments. The stability target was **not met**: the published load-matrix build ran at about 4 TPS with 300 connected clients, 60 moving and 3,000 AI mobs. Earlier tests before the backoff and result-acceptance fixes triggered watchdog termination when all 300 clients moved continuously. No whole-server speedup over ordinary Paper/Purpur is established.

The interim completion criterion is CPU-equivalent or better performance. The single-run measurements are mixed: RTX 3070 improves some metrics, GTX 1080 is slower in the fixed-AI phase, and the mixed configuration has higher p95/p99 MSPT. CPU-equivalent or better performance across all configurations is not established.

## Upstream and licensing

Based on Purpur 26.2 and its pinned Paper revision. Required license/copyright notices and patch attribution are retained. Upstream project contributor/sponsor lists and funding links, IDE metadata, historical unapplied patches, and local server worlds/logs are excluded. Dependency lockfile metadata is retained. See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).
