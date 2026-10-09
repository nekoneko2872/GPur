# Exact terrain dispatch microbenchmark

This opt-in benchmark measures the workload-4 `computeAsync` API on one selected physical FP64 Vulkan device. It does not start a Minecraft server, create a world, run bots, or benchmark vanilla chunk generation. Its CPU figure is the standalone CPU wire-reference calculation; it is not a vanilla chunk-generation baseline. The results can describe dispatch behavior for this build and machine, but they do not establish production terrain speedup.

The test is skipped unless `gpurGpuBenchmark=true` is passed through the init script. It runs as part of `GPurRuntimeTestSuite`, whose package scan includes `org.gpur.compute`. The benchmark creates a fresh configuration at `gpur-server/build/reports/tmpcfg/gpur.yml`, uses one FP64 device, sets four execution contexts and a maximum batch size of four, then restores the JVM's `GPurConfig` static state. Every run performs `ComputeService.selfTest` and all mandatory `ExactStartupCorpus` checks before timing.

From the repository root, run:

```powershell
.\gradlew.bat -I benchmarks/terrain-micro.init.gradle :purpur-server:test --tests org.gpur.GPurRuntimeTestSuite -PgpurGpuBenchmark=true --rerun-tasks
```

Use `-PgpurGpuDevice=<uuid-or-name>` to choose a device; the default is `auto`, which picks the first discovered FP64 device. Use `-PgpurGpuIterations=<count>` to set measured requests per workload; the default is 100 and values are clamped to 1 to 1000. The init script forwards these project properties to the JUnit system properties. It does not enable the separate `gpur.gpu-tests` hardware parity suite.

The deterministic matrix uses finite random density corners in `[-1,000,000, 1,000,000]` and covers these workload sizes:

| Approximate invocations | Interpolators | Cell width | Cell height | Vertical cells |
| ---: | ---: | ---: | ---: | ---: |
| 1K | 1 | 1 | 1 | 32 |
| 4K | 1 | 1 | 1 | 128 |
| 16K | 1 | 4 | 1 | 128 |
| 64K | 1 | 8 | 1 | 256 |
| 256K | 1 | 16 | 1 | 512 |
| 1M | 4 | 16 | 1 | 512 |

Each case warms up with 16 requests, then measures the configured number of requests in waves of up to four. The recorded median, p95, and p99 include the Java API call through future completion, with exact result comparison outside that timed interval. Null GPU results are counted as CPU-fallback outcomes; each workload must produce at least one GPU result. The separately recorded CPU reference time covers one standalone wire-reference calculation per workload.

Gson writes the report to `gpur-server/build/reports/gpur-terrain-micro/<device-uuid>.json`. Per-workload JSON contains input/output payload sizes, measured request and fallback counts, API latency percentiles, and cumulative device metrics before, after, and across the timed phase. Vulkan timestamp counters are reported when the selected device exposes them. The benchmark uses Vulkan 1.1 fence-based dispatch; it does not require timeline semaphores. This is not a 2 GB or 16 GB Minecraft server benchmark, and no GPU run is performed during normal test runs. This harness was not run on a GPU as part of this change.

Relevant Vulkan references: [NVIDIA Vulkan Dos and Don'ts](https://developer.nvidia.com/blog/vulkan-dos-donts/), [Khronos command-buffer usage sample](https://docs.vulkan.org/samples/latest/samples/performance/command_buffer_usage/README.html), and [`vkGetFenceStatus`](https://docs.vulkan.org/refpages/latest/refpages/source/vkGetFenceStatus.html).
