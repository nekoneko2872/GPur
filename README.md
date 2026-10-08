# GPur

[English](README.md) | [日本語](README.ja.md)

GPur is a Purpur 26.2 fork with optional Vulkan compute: https://github.com/nekoneko2872/GPur.

Paper/Purpur plugins execute on ordinary server threads. GPU kernels receive copied numeric inputs. Events, random generators, world changes, and dependent update ordering remain on the CPU.

## Documentation

| Document | English | 日本語 |
| --- | --- | --- |
| Migration and implementation | [English](docs/26.2-migration.en.md) | [日本語](docs/26.2-migration.md) |
| Hardware and load validation | [English](docs/26.2-validation.en.md) | [日本語](docs/26.2-validation.md) |
| Runtime status and elytra update | [English](docs/26.2-runtime-updates.en.md) | [日本語](docs/26.2-runtime-updates.md) |
| Opt-in custom terrain | [English](docs/26.2-custom-terrain.en.md) | [日本語](docs/26.2-custom-terrain.md) |

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

## Opt-in custom terrain

GPur 1.1.0 adds a versioned terrain generator for explicitly selected, new Overworlds. Vanilla generation remains unchanged and CPU-based by default. A `worlds` entry in `bukkit.yml` only associates a generator with that world name; it does not create a world or convert existing chunks. Your current main world remains on vanilla CPU terrain, and adding a generator entry alone cannot convert it. Keep existing world directories intact and choose a new, unused world name.

To make a new world the server's main world, put a new name in `server.properties`:

```properties
level-name=gpur_world
```

Then add a matching `bukkit.yml` entry before starting the server:

```yaml
worlds:
  gpur_world:
    generator: gpur:terrain-v1
```

Alternatively, use an ordinary world-management plugin to create a new NORMAL world with `gpur:terrain-v1` selected at creation.

`chunk-generation.custom-terrain.enabled: true` must also be enabled for a new opt-in. This setting alone does not change any world. A world marked by GPur keeps using the custom generator if you later remove its `bukkit.yml` generator entry. The `gpur-terrain.properties` marker stores the terrain version, seed, minimum Y, height, and sea level; it is written atomically before chunk generation. These values are permanent for that world. Do not delete or edit the marker to disable GPU compute or regenerate a seed. A marked world always keeps the same custom rules, with exact CPU fallback when GPU compute is disabled or unavailable. A first opt-in on a populated world is rejected. Nether and End worlds keep their existing CPU generators; GPur terrain supports NORMAL worlds only.

The world opt-in, custom-terrain setting, and GPU readiness are separate. GPU terrain also requires GPU acceleration and the legacy `chunk-generation.gpu-acceleration.terrain-enabled` gate. That legacy key gates GPU compute for this opt-in generator; it does not move vanilla terrain generation onto a GPU. Device selection uses the existing `gpu.multi-gpu.devices` names or UUIDs. For example, to select a GTX 1080 by itself:

```yaml
gpu:
  multi-gpu:
    enabled: false
    devices: [NVIDIA GeForce GTX 1080]
```

Custom terrain computes a deterministic block palette for the opted-in world. Bukkit writes the palette into chunk data on the generator callback thread. Biome lookup, base-height queries, vanilla structures and decorations such as ores, lighting, chunk serialization and saving, Mob AI, redstone, plugin callbacks, and world updates remain CPU work. The existing Paper/Minecraft chunk-save path is unchanged; this release makes no save-performance claim. The integration uses standard Bukkit generator and biome-provider callbacks, but does not claim compatibility with every plugin.

`gpu.cache.enabled: true` enables the existing per-device pipeline cache and the persisted terrain batch profile. When auto-tuning is enabled, GPur tries bounded batch sizes up to eight chunks per device; a fresh profile uses at most 16 calibration dispatches and exact CPU/GPU parity checks. Later terrain dispatches receive periodic parity checks, controlled by `chunk-generation.custom-terrain.parity-interval`. Cache identity includes the selected GPU, driver, shader, and terrain rules version, so incompatible cached data is ignored. No AI training model is involved. Status reports GPU terrain chunks, fallback reasons, and the selected batch size for each GPU. Only newly generated chunks in opted-in worlds create terrain work, so low GPU use is expected when that workload is small or absent.

This describes implemented code, not new hardware or whole-server performance validation. The historical 1.0.0 hardware and load records remain valid for the builds and workloads they measured; they do not measure the 1.1.0 custom-terrain path. No speedup or hardware certification for custom terrain is claimed.

## Runtime status

`/gpur` or `/gpur status` shows a compact, colored overview grouped by GPU. Player distances and Anti-Xray each have a named state: no results yet, GPU enabled, CPU cooldown with a retry countdown, blocked, or disabled. For opt-in custom terrain, status shows per-GPU counts of chunks in accepted GPU dispatches, fallback reasons, and the selected batch size. The global `Custom terrain returned` count measures palettes returned by the terrain scheduler, split between GPU and CPU. Per-GPU accepted-dispatch counts can include chunks whose callers timed out and received a CPU palette; primary-thread CPU generation bypasses the scheduler and is excluded. The preload radius and flight rate boost are shown separately. Hover a row for its meaning; `/gpur status detail` adds dispatch timings in milliseconds, CPU reference samples, cooldown history, UUIDs, and admission skips. Both commands require `gpur.command` (operators by default).

An available GPU can be idle. Result counts are accepted GPU batches since start/reload, not players, ticks, or applied packets. In-flight jobs are occupied execution contexts, not GPU utilization. CPU fallbacks and admission skips do not count every calculation that remains on the CPU. `/gpur reload` resets the counters and revalidates devices.

## Elytra preloading

New configurations prioritize the flight direction within Paper's existing chunk range. They use `chunk-generation.preloading.max-extra-distance: 0` and `chunk-generation.preloading.elytra-throughput-boost.enabled: false`. Existing explicit values are preserved; older configurations with an extra radius of 3 and flight rate boosts enabled should set these values to 0 and false when prioritizing tick stability. Extra radius expands chunk work in all directions, and the boost increases CPU load/generation/send rates. Elytra preload settings do not opt a world into GPU terrain; see [Opt-in custom terrain](docs/26.2-custom-terrain.en.md).

Directional priority bands are cached until the queues are rebuilt, and speed changes that cannot affect any queued chunk no longer trigger a rebuild. When directional preloading is inactive, the original Paper queue comparator is used. These changes reduce preload overhead; they do not guarantee 20 TPS while generating new terrain.

## Compute contract

- Distances use FP64, Java arithmetic order, and no fused multiply-add. Each device must pass startup parity checks.
- Anti-Xray masks support Paper HIDE mode, stage the entire packet before mutation, and periodically compare packet bytes with Paper. Randomized modes and custom hidden non-conductors retain Paper's CPU implementation. GPur does not enable Paper Anti-Xray in worlds where it is disabled.
- The old FP32 interpolation path is not used. `gpur:terrain-v1` is a separate, explicitly selected custom generator with versioned palette rules; it does not replace vanilla generation in unselected worlds.
- Mob AI, movement state changes, plugin callbacks, and dependent redstone updates cannot be dispatched as a parallel tick while preserving Paper behavior. Only isolated calculations with verified CPU references can be offloaded.

This implementation requires Vulkan 1.1, a compute queue, FP64 shader support, and host-visible coherent memory. GTX 1080 and RTX 3070 pass hardware kernel checks; older hardware is not certified. Startup tests determine device admission. Vulkan support alone does not guarantee a speedup.

## Validation target

A dispersed SMP with 300 players is a benchmark target, not a capacity guarantee. Compare p50/p95/p99 MSPT, chunk load/generation latency, GC, plugins, and full CPU-to-GPU-to-CPU cost using fresh worlds with matching seeds/settings and the same hardware. Hardware kernel parity does not establish whole-server performance or universal plugin compatibility.

The [hardware and load validation record](docs/26.2-validation.en.md) includes the CPU, RTX 3070, GTX 1080 and mixed-device experiments. The stability target was **not met**: the published load-matrix build ran at about 4 TPS with 300 connected clients, 60 moving and 3,000 AI mobs. Earlier tests before the backoff and result-acceptance fixes triggered watchdog termination when all 300 clients moved continuously. No whole-server speedup over ordinary Paper/Purpur is established.

The interim completion criterion is CPU-equivalent or better performance. The single-run measurements are mixed: RTX 3070 improves some metrics, GTX 1080 is slower in the fixed-AI phase, and the mixed configuration has higher p95/p99 MSPT. CPU-equivalent or better performance across all configurations is not established.

## Upstream and licensing

Based on Purpur 26.2 and its pinned Paper revision. Required license/copyright notices and patch attribution are retained. Upstream project contributor/sponsor lists and funding links, IDE metadata, historical unapplied patches, and local server worlds/logs are excluded. Dependency lockfile metadata is retained. See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).
