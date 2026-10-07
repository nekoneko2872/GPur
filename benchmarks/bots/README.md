# Genuine network client fleet

Each bot is an offline authenticated Minecraft TCP client on `127.0.0.1`. The server creates ordinary players from login/configuration/play traffic. No server-side fake-player API is used. Names default to `GPurBot0000` through `GPurBot0299`.

Pinned registry versions checked on2026-10-07: Mineflayer4.39.0, minecraft-protocol1.68.0, minecraft-data3.117.0. The package metadata knows26.2 protocol776 but has no usable26.2 packet schema. The validated implementation uses the supported26.1 protocol775 through ViaVersion and ViaBackwards installed on the26.2 server. Mineflayer is pinned as the permitted reference client; the fleet uses its underlying minecraft-protocol library to avoid decoding and retaining whole chunk worlds in every client.

The official ViaVersion CI build1469 artifact is `https://ci.viaversion.com/job/ViaVersion/1469/artifact/build/libs/ViaVersion-5.12.1-SNAPSHOT.jar`, SHA256 `760838F7790083ED9E22C71B29EA5A479D4997C09AD3277971D8DECA4F41BED4`. The user supplied ViaBackwards5.12.1-SNAPSHOT SHA256 is `C3CB4E2B4E04B9D9BF3D75318A44133146982295021EA9E26984606BDD709481`. Both plugin binaries are runtime inputs outside this source package. Record the actual loaded versions with server logs.

## Install and run

Node22 or newer is required. Reproduce the exact dependency tree:

```powershell
npm ci --prefix benchmarks/bots
node --test benchmarks/bots/fleet.test.cjs
node benchmarks/bots/fleet.cjs --count 300 --port 25620 --ramp 10 --workers 4 --action idle --duration 0
```

An isolated development install may instead use `npm ci --prefix validation/bots` after copying this package's `package.json` and `package-lock.json` into that directory. The CLI automatically falls back to `validation/bots/node_modules` when the source package has no local dependencies. Override with `--dependency-dir` if required.

Use50,150, or300 clients. A four-process fleet assigns interleaved names to workers and staggers connections globally at10 clients/s. `--ramp 0` connects immediately. `--duration 0` runs until controlled stop. `--duration 180` stops after180 seconds from fleet start, including ramp/setup. Each worker owns at most75 clients in the default300-player case. Worker count is capped at8; target count at300. No real account credentials or Microsoft authentication are used or accepted. The hostname is strictly restricted to `127.0.0.1`.

The launcher emits NDJSON to stdout, suitable for redirection to a run-specific file. Send JSON lines to its stdin:

```json
{"command":"phase","phase":"setup-placement"}
{"command":"action","action":"walk"}
{"command":"phase","phase":"measured-walk"}
{"command":"status"}
{"command":"stop"}
```

`Ctrl+C` and SIGTERM also stop all workers. Stop closes real sockets and allows a final report; the parent terminates workers after5 seconds if required. No reconnection hides client losses.

## Behavior and limits

The protocol package handles login, offline UUIDs, configuration acknowledgments, known-pack selection, code of conduct acknowledgment and keepalive responses. The fleet handles teleport confirmations including relative axes, player-loaded acknowledgment, chunk-batch acknowledgment, ping/pong and respawn on death. Configured view distance defaults to4 and is reported in fleet configuration.

`idle` sends a grounded heartbeat once per second. `walk` sends grounded position/look packets at20Hz, approaches a circle within radius2 blocks of the latest server teleport, and caps movement at2 blocks/s by default. The server benchmark plugin should place each survival player on a level7x7 pad in one shared world before movement starts. Clients preserve the pad's assigned height. These are genuine network players walking a bounded route; they do not simulate collision physics, block interaction, combat, inventory use or ordinary exploration. A server-directed travel test must be labeled synthetic teleport exploration. `movementPackets` counts successful serialization attempts, so server-observed displacement is the evidence of accepted movement.

Large unused play packet payloads (chunks, lights, entity metadata, recipes, attributes, particles, etc.) are consumed as opaque buffers rather than decoded into world objects. TCP framing, decompression and required gameplay/control packets are still processed. This reduces generator CPU/RSS but is not a full game client.

## Evidence and generator accounting

`client_login` identifies actual protocol play login by name, UUID and server-assigned entity ID. `client_spawn` records first server position received and acknowledged. Correlate the unique names with server console joins and the benchmark plugin's sampled survival/online player counts and `player-placement.json`. `fleet_stats` reports current `connected`, `loggedIn`, `play`, `spawned`, `live`, `survival`; cumulative `everSpawned`, `errors`, `kicks`, `ended`, `unexpectedLosses`; teleport/keepalive/chunk counters; and socket bytes. A steady valid300-player sample requires server-observed300 survival players and client `live=spawned=300`, with zero unexpected losses/kicks/errors. The source alone does not prove that a particular run achieved these conditions.

Each report includes worker PIDs, RSS, heap use, CPU core percentages and event loop p99/max delay. `cpuCorePercent=100` equals one fully busy logical processor, summed across all workers. `clientCpuHostPercent` divides by host logical processor count. Parent RSS is separate. Event loop p99/max uses10ms-resolution histograms and takes the worst worker when aggregated. Observe these during the same measurement interval as the server; large event loop stalls or saturated client workers invalidate claims that apparent low server throughput reflects only server behavior. RSS sums process working sets and may include shared pages. All telemetry is one report per worker per second, with no per-packet logs.
