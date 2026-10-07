# Fresh-world load matrix

`run_matrix.py` launches each server sequentially with a 16GiB maximum Java heap, creates a fresh isolated run directory and normal-terrain dimension, and joins genuine loopback TCP players through the pinned 26.1 protocol/ViaVersion/ViaBackwards bridge. Dependencies: Python 3.12 with `psutil==7.0.0`, Node 22, JDK 25, the server Paperclip JAR, benchmark plugin and the explicitly supplied/recorded Via plugin binaries.

Run the correctness/compile checks first and **finish all builds before measuring performance**. Parallel servers, clients from another case, compilers or hardware microbenchmarks would confound the result.

```powershell
python benchmarks/plugin/build.py
node --test benchmarks/bots/fleet.test.cjs
python benchmarks/run_matrix.py --pilot --cases cpu
python benchmarks/run_matrix.py --cases cpu,rtx3070,gtx1080,mixed --seconds 60
```

The default runtime root is `C:/GPur-validation-20261007`. Override input paths using `--help`. The EULA input must be an existing accepted file; this tool does not create a new agreement. Every case gets a new directory and world; changing GPU code, selectors or force mode must use a new run. Previously generated world data are never copied between cases. The vanilla launch cache may be copied because it contains server code rather than world chunks.

Each case ramps to 50, 150 and 300 players. It records player-only pad walking, 500/1,500/3,000 nearby AI mobs with concurrent walking, natural-spawn phases, 300 redstone circuits, synthetic new-chunk exploration and recovery. Setup/ramp/settle phases remain in the inclusive record. A GPU case is invalid if its selected physical devices do not initialize; a phase is invalid if clients are lost or protocol errors occur. `--force` is a separate diagnostic experiment and must not be represented as the recommended adaptive setting.

View distance 6, simulation distance 4, HIDE Anti-Xray and four chunk workers/two I/O workers are fixed across the matrix. IP join throttling is disabled because all test clients share one loopback address; this is not a public server configuration recommendation. The client view request matches the server view distance. Full-world mob AI/player/redstone GPU migration is not part of this implementation.

Outputs include per-tick CSV, phase/all/rolling JSON, GC/safepoint logs, per-process resource samples, client NDJSON and whole-device NVIDIA telemetry. Whole-device utilization includes unrelated desktop use; GPur's per-device/workload completion counts are the proof of its dispatches. All runtime data remain outside the source checkout. The same host runs server and clients, so client CPU/RSS/event-loop delays are part of the limitations, not an external unlimited load generator.

Report maximum MSPT and every tick over the 50ms budget, not only average TPS. A finite passing run cannot guarantee zero future drops. Failed or interrupted experiments retain their raw files and must be explicitly distinguished from completed comparisons.

Natural mobs are cleared from the isolated benchmark dimension after each natural-spawn phase and after placement, before fixed-population measurements. This avoids carrying a variable natural population into the next controlled stage. All generation, cleanup and settle costs remain in the inclusive record.

After closing the tick record, the runner exports the existing built-in spark background profile locally with `spark profiler stop --save-to-file`, as described by the [official spark command documentation](https://spark.lucko.me/docs/Command-Usage). Profile serialization occurs outside the measured interval. Normal Paper background profiling remains enabled during all cases; on this Windows host it uses the Java sampler.

Reduce only completed runs explicitly, rather than globbing exploratory or aborted runs:

```powershell
python benchmarks/analyze.py <cpu-run> <3070-run> <1080-run> <mixed-run> --output C:/GPur-validation-20261007/results/matrix.json
python benchmarks/plot.py --report C:/GPur-validation-20261007/results/matrix.json --runs C:/GPur-validation-20261007/runs --output C:/GPur-validation-20261007/results/mspt.png
```

Plotting additionally requires Matplotlib. The plot retains every recorded tick, uses a logarithmic MSPT axis, and marks the 50ms budget. Create a run-local `ABORT` text file to request controlled shutdown; an aborted run is excluded from completed comparisons.

For a separate controlled-activity experiment, use `--targets 300 --walking-percent 20 --no-natural --seconds 60`. All 300 clients remain connected, while 60 walk at 20Hz and 240 send idle heartbeats. The default is 100% walking. This experiment must be labeled with its movement fraction; it does not demonstrate capacity for 300 simultaneously active players. `--no-natural` omits variable natural populations but keeps the fixed 3,000 AI fixture, circuits and fresh-chunk travel. `--settle-seconds` adjusts the explicitly recorded settling period.

The published final matrix used the following command. `--natural-seconds` controls the separate variable-population phase; `--travel-seconds` counts simulation time at 20TPS, so exploration takes longer in wall time when the server is overloaded.

```powershell
python benchmarks/run_matrix.py --cases cpu,rtx3070,gtx1080,mixed --targets 300 --walking-percent 20 --seconds 30 --settle-seconds 15 --natural-seconds 10 --travel-seconds 5
```

The runner checks shared input files before starting, continues subsequent device cases after a running case fails, preserves the pre-teardown client snapshot, and exits unsuccessfully if any case failed. Use `analyze.py --include-failed` only to report such failures explicitly. A tick that stalls permanently has no TickEnd sample; inspect the failure, watchdog log and final time without a tick alongside MSPT maxima. A CSV maximum from completed ticks cannot describe an unfinished 60-second tick.
