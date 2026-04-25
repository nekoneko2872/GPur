# GPur

GPur is an experimental Minecraft 1.21.11 server fork based on Purpur/Paper.
It keeps Purpur compatibility while adding GPU-assisted world-pipeline features
such as directional chunk preloading, Vulkan-backed terrain assist, and optional
GPU offload paths for anti-xray and other chunk-adjacent workloads.

If Vulkan initialization fails, GPur automatically falls back to CPU mode.

## Goals

- Keep Purpur/Paper behavior as the baseline.
- Improve perceived chunk delivery during movement, especially elytra travel.
- Experiment with moving heavy numeric world-generation work to the GPU.
- Expose runtime status clearly through `/gpur status` and `gpur.yml`.

## What GPur Adds

### Directional Chunk Preloading

GPur extends the player chunk loader with movement-aware preloading:

- base lookahead
- elytra-specific lookahead and turn boost
- shared preload anchors for nearby players
- retained priority hits for recently relevant chunks
- send burst and throughput stabilization controls

These settings live under `chunk-generation.preloading` in `gpur.yml`.

### Terrain Assist

GPur can offload part of `NoiseChunk` interpolation work to a Vulkan compute
backend.

- This is controlled by `chunk-generation.gpu-acceleration`.
- The current safe path is `terrain assist`, not the old direct terrain batch.
- `legacy-terrain-batch-enabled` is available for experiments, but it is not the
  recommended default for production use.

### GPU Offload

Optional GPU offload paths are available for:

- Paper anti-xray packet obfuscation
- structure candidate scans
- mob spawn candidate scans

Important:

- Anti-xray GPU offload only works if Paper anti-xray itself is enabled in the
  Paper world configuration.
- `engine-mode: 1` (`HIDE`) is the safest mode for GPU anti-xray.
- GPur can still run with GPU acceleration enabled even when individual GPU
  workloads decide to fall back to CPU.

### Reload Optimization

GPur includes a short-lived hot-reload ticket cache for explicit unload/reload
patterns. This is controlled by `chunk-generation.reload-optimization`.

## Runtime Commands

### `/gpur status`

Shows the current GPur runtime state, including:

- backend mode and device
- terrain assist state
- assist gate state
- active noise tasks
- terrain assist dispatch totals
- anti-xray totals
- preload and send-burst counters

This is the main command to verify whether GPU-assisted paths are actually being
used.

### `/gpur reload`

Reloads GPur configuration. As with most deep server config reloads, full
restart is still safer for critical changes.

## Configuration

GPur reads its settings from `gpur.yml`.

High-level layout:

- `chunk-generation.gpu-acceleration`
- `chunk-generation.preloading`
- `chunk-generation.turbo-mode`
- `chunk-generation.reload-optimization`
- `gpu-offload`
- `logging`

Logging supports public-safe output and per-feature verbosity:

- `off`
- `summary`
- `verbose`

Example:

```yml
chunk-generation:
  gpu-acceleration:
    enabled: true
    terrain-enabled: true
    legacy-terrain-batch-enabled: false
    terrain-heavy-load-only: true

gpu-offload:
  anti-xray:
    enabled: true
    allow-inexact-results: false
    min-sections: 8

logging:
  public-safe: true
  gpu:
    terrain-assist: summary
    anti-xray: summary
```

## Current Status

GPur is not a drop-in "faster than Purpur in every benchmark" fork.

What is relatively mature:

- movement-aware preload tuning
- `/gpur status` visibility
- CPU fallback behavior
- optional GPU packet/offload paths

What is still experimental:

- terrain-assist effectiveness under all workloads
- benchmark wins in raw chunk-generation throughput
- GPU offload behavior across all server configurations

In other words: GPur is intended for experimentation and targeted tuning, not as
a guaranteed universal replacement for stock Purpur.

## Building

### Initial setup

Clone the repository, then run:

```bash
./gradlew applyAllPatches
```

### Development build

```bash
./gradlew :purpur-server:assemble
```

This produces the main development jar in:

- `purpur-server/build/libs`

### Server-ready artifacts

To build server-startable Paperclip/Bundler artifacts:

```bash
./gradlew :purpur-server:createMojmapPaperclipJar :purpur-server:createMojmapBundlerJar
```

Typical outputs:

- `purpur-paperclip-<version>-mojmap.jar`
- `purpur-bundler-<version>-mojmap.jar`

Both are written under:

- `purpur-server/build/libs`

## Project Structure

- `paper-api`, `paper-server`: imported Paper sources
- `purpur-api`, `purpur-server`: Purpur-based patched sources
- `patches`: patch metadata
- `purpur-server/src/main/java/org/gpur`: GPur-specific Java sources
- `purpur-server/src/main/resources/shaders/gpur`: Vulkan compute shaders

## Patch Workflow

The patch tree is split by version and lifecycle:

- `patches/1-20-6`, `patches/1-21-1`, `patches/1-21-3`: versioned patch sets kept for the target branch line.
- `patches/unapplied-api`, `patches/unapplied-server`: patches that are staged or intentionally not applied yet.
- `patches/1-21-3/dropped-server`: patches that were dropped from the active line but are still retained for reference.

When you change per-file patches, use `./gradlew fixup[project]FilePatches` first, then `./gradlew rebuild[project]FilePatches`.

The `test-plugin/` project is a local smoke harness. It is not part of the default build unless you explicitly enable it in `test-plugin.settings.gradle.kts`.

## Compatibility

GPur is built on top of Purpur, so the API and general plugin compatibility
target remain close to Purpur/Paper unless a GPur-specific experiment says
otherwise.

## License

All GPur patches are licensed under the MIT license unless noted otherwise.

See also:

- [LICENSE](LICENSE)
- [PaperMC/Paper](https://github.com/PaperMC/Paper)
- [PurpurMC/Purpur](https://github.com/PurpurMC/Purpur)
- [PaperMC/paperweight](https://github.com/PaperMC/paperweight)
