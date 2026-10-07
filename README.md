# GPur

GPur is a Purpur 26.2 fork with optional Vulkan compute: https://github.com/nekoneko2872/GPur.

Paper/Purpur plugins execute on ordinary server threads. GPU kernels receive copied numeric inputs. Events, random generators, world changes, and dependent update ordering remain on the CPU.

## Build

Use a Git checkout and JDK 25:

```sh
./gradlew applyAllPatches
./gradlew :purpur-server:test :purpur-server:createPaperclipJar
```

Artifact: `gpur-server/build/libs/gpur-server-26.2-SNAPSHOT1.0.0.jar`. The filename combines `mcVersion` and `gpurVersion` from `gradle.properties`. Source folders are `gpur-server`, `gpur-api`, and `gpur-checkstyle`; Gradle project names and `org.purpurmc` API packages are retained. Generated sources are not committed; their changes must be rebuilt into patches.

Run with JDK 25 and `--enable-native-access=ALL-UNNAMED`. The `-dev.jar` and `gpur-bundler-*.jar` files are build artifacts, not the Paperclip launch jar. See [the migration and validation record](docs/26.2-migration.md) for the implemented scope and checks.

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

## Compute contract

- Distances use FP64, Java arithmetic order, and no fused multiply-add. Each device must pass startup parity checks.
- Anti-Xray masks support Paper HIDE mode, stage the entire packet before mutation, and periodically compare packet bytes with Paper. Randomized modes and custom hidden non-conductors retain Paper's CPU implementation. GPur does not enable Paper Anti-Xray in worlds where it is disabled.
- The old FP32 interpolation and substitute terrain generator are not used: they change Minecraft terrain results. Their legacy settings remain readable for migration.
- Mob AI, movement state changes, plugin callbacks, and dependent redstone updates cannot be dispatched as a parallel tick while preserving Paper behavior. Only isolated calculations with verified CPU references can be offloaded.

This implementation requires Vulkan 1.1, a compute queue, FP64 shader support, and host-visible coherent memory. GTX 1080 and RTX 3070 pass hardware kernel checks; older hardware is not certified. Startup tests determine device admission. Vulkan support alone does not guarantee a speedup.

## Validation target

A dispersed SMP with 300 players is a benchmark target, not a capacity guarantee. Compare p50/p95/p99 MSPT, chunk load/generation latency, GC, plugins, and full CPU-to-GPU-to-CPU cost on identical worlds and hardware. Hardware kernel parity does not establish whole-server performance or universal plugin compatibility.

The [hardware and load validation record](docs/26.2-validation.md) includes the CPU, RTX 3070, GTX 1080 and mixed-device experiments. The stability target was **not met**: the final build ran at about 4 TPS with 300 connected clients, 60 moving and 3,000 AI mobs. Earlier tests before the backoff and result-acceptance fixes triggered watchdog termination when all 300 clients moved continuously. No whole-server speedup over ordinary Paper/Purpur is established.

The interim completion criterion is CPU-equivalent or better performance. The single-run measurements are mixed: RTX 3070 improves some metrics, GTX 1080 is slower in the fixed-AI phase, and the mixed configuration has higher p95/p99 MSPT. CPU-equivalent or better performance across all configurations is not established.

## Upstream and licensing

Based on Purpur 26.2 and its pinned Paper revision. Required license/copyright notices and patch attribution are retained. Upstream project contributor/sponsor lists and funding links, IDE metadata, historical unapplied patches, and local server worlds/logs are excluded. Dependency lockfile metadata is retained. See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).
