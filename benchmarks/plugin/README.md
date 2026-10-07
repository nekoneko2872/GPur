# GPurBench

An ordinary Java 21 Bukkit plugin for isolated Paper/Purpur/GPur 26.2 performance experiments. It does not use fake players or NMS entity creation. It refuses to enable unless both the working directory and world container are under a `validation` directory or `C:/GPur-validation-20261007`.

Build with JDK 25 after applying the server patches:

```powershell
.\gradlew.bat -I benchmarks/plugin/classpath.init.gradle :purpur-server:writeValidationClasspath --no-configuration-cache
python benchmarks/plugin/build.py --classpath validation/classpath.txt
```

The included init task writes the server runtime dependency paths to the ignored classpath file. Alternatively, pass a file containing the Paper API and its dependencies. The build helper writes only to the selected output directory; its default artifact is the ignored `validation/benchmark-plugin/GPurBench.jar`.

## Measurement

Paper `ServerTickEndEvent` provides the reported tick duration in milliseconds. Every observed tick is retained in `ticks.csv`. `ServerTickStartEvent` supplies start-to-start intervals and uncapped wall-clock TPS. CSV and JSON writing, percentile sorting and rolling summaries run on one dedicated writer thread. No spike removal or winsorization is performed.

`summary.json` includes every phase plus `all_including_setup_and_spikes`, p50/p95/p99/maximum MSPT, mean MSPT, counts over 50/100ms, actual player counts, wall TPS and collector metrics. `events.jsonl` records phase boundaries, GC notifications, workload commands, completion and errors. `finish` reports completion only after output files are closed.

The once-per-20-ticks census records living mobs, synthetic mobs, loaded chunks and mobs inside ticking chunks. `isTicking` is chunk membership, not proof that every entity executes its entire AI on every tick. Census age and its measured cost are recorded. Standard Paper activation behavior stays enabled; synthetic mobs have AI and awareness enabled, with nearby real survival players and targeted zombies. Reflective GPur diagnostics are optional; ordinary Paper can run the same plugin.

GC notification durations include collector-reported concurrent work and cannot all be called stop-the-world pauses. Use the separate JVM `gc.log` pause and safepoint entries for that conclusion. Measurements themselves have overhead, including ordinary CPU event callbacks and periodic entity census; the same plugin and settings must be used in comparisons.

The census executes in the tick-end listener, after Paper has calculated its reported tick duration. Its recorded `survey_ms` can therefore be omitted from that tick's Paper MSPT, while start-to-start intervals and wall TPS include it. Check all three values; do not interpret low reported MSPT alone as proof that the entire tick stayed within budget.

## Console commands

All changes are limited to marked `gpurbench_*` worlds in the isolated directory. Minecraft 26.2 stores these as dimensions inside the level directory; the plugin uses `World.getWorldPath()`.

| Command | Purpose |
| --- | --- |
| `gpurbench world <label> <seed> normal` | Create/select a marked normal-terrain test world; preserve the same seed across fresh runs. |
| `gpurbench phase <label>` | Change phase after setup jobs complete. |
| `gpurbench place 96` | Disperse actual connected survival players onto a deterministic grid and small level pads. |
| `gpurbench spawn 3000 24 20` | Add 3,000 AI-enabled cows/zombies near players, paced at up to 20 per tick and a 3ms soft setup budget. |
| `gpurbench natural true` | Enable ordinary natural spawning for its distance-query workload. |
| `gpurbench natural false` | Stop new natural spawns; existing natural mobs remain. |
| `gpurbench remove` | Remove only plugin-marked synthetic mobs. |
| `gpurbench removenatural` | Remove unmarked mobs only from the marked benchmark dimension, between population-controlled stages. |
| `gpurbench redstone 300` | Create 300 observer feedback circuits with lamps in player-ticked areas. |
| `gpurbench clearredstone` | Clear only the placed fixture blocks. |
| `gpurbench prepare 6 1 3` | Pace synchronous generation of nearby chunks; this is a measured setup phase. |
| `gpurbench travel 20 4.317 east` | Twenty seconds at 20TPS of server-directed teleport exploration at walking speed; explicitly synthetic rather than client physics. |
| `gpurbench status` | Read player/mob/chunk/GPU counts, jobs and writer validity. |
| `gpurbench finish` | Finish and close the record. Restart the server for a new run. |

Fixture mobs are persistent and invulnerable so their count stays controlled. Conversion of marked fixture mobs (for example Zombie to Drowned) is cancelled to retain the specified Cow/Zombie population and ownership markers; natural mobs retain ordinary conversion. Damage to players in the synthetic world is cancelled by an ordinary CPU plugin callback; AI, pathfinding, attacks and damage events continue. Mob griefing, daylight/weather changes and entity cramming damage are disabled for repeatability. Initially natural spawning is disabled; dedicated `natural` phases enable it and record the resulting population. These are controlled workloads, not a complete public SMP gameplay simulation.

`GPURBENCH_START`, `GPURBENCH_DONE`, `GPURBENCH_ERROR` and `GPURBENCH_INVALID` console markers let orchestration wait for real completion. A time budget cannot interrupt one long synchronous chunk/world operation; its full tick cost remains in the record.
