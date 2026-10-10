"""Run one isolated vanilla worldgen, lighting, save, and reload case.

The script is intentionally separate from Gradle and from benchmarks/run_matrix.py.
It writes only below C:/GPur-validation-20261009/validation and uses loopback.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from uuid import uuid4


ROOT = Path(__file__).resolve().parents[2]
SAFE_ROOT = Path("C:/GPur-validation-20261009/validation")
DEFAULT_JDK = Path.home() / ".jdks" / "openjdk-25.0.2"
ANSI = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")
CASES = {
    "cpu": {"devices": [], "multi": False},
    "rtx3070": {"devices": ["NVIDIA GeForce RTX 3070"], "multi": False},
    "gtx1080": {"devices": ["NVIDIA GeForce GTX 1080"], "multi": False},
    "mixed": {"devices": ["auto"], "multi": True},
}


def parse_initial_heap_gib(value: str | int) -> int:
    try:
        gib = int(value)
    except (TypeError, ValueError) as error:
        raise argparse.ArgumentTypeError("--initial-heap-gib must be an integer in 1..16") from error
    if not 1 <= gib <= 16:
        raise argparse.ArgumentTypeError("--initial-heap-gib must be in 1..16")
    return gib


def worldgen_jvm_options(initial_heap_gib: int) -> list[str]:
    initial_heap_gib = parse_initial_heap_gib(initial_heap_gib)
    return ["--enable-native-access=ALL-UNNAMED", f"-Xms{initial_heap_gib}G", "-Xmx16G", "-XX:+UseG1GC"]


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def atomic_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    temporary.replace(path)


def safe_output(path: Path) -> Path:
    resolved = path.resolve()
    root = SAFE_ROOT.resolve()
    try:
        common = os.path.commonpath((str(resolved).casefold(), str(root).casefold()))
    except ValueError as error:
        raise ValueError(f"output must be below {root}: {resolved}") from error
    if common != str(root).casefold():
        raise ValueError(f"output must be below {root}: {resolved}")
    return resolved


def check_port(port: int) -> None:
    if port == 25565:
        raise ValueError("port 25565 is reserved; the validation server must never use the live-server port")
    if not 1024 <= port <= 65535:
        raise ValueError("port must be in 1024..65535")
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 0)
        try:
            listener.bind(("127.0.0.1", port))
        except OSError as error:
            raise RuntimeError(f"loopback port {port} is already occupied; no server was started") from error


class Server:
    def __init__(self, directory: Path, java: Path, jar: Path, parity_dump_dir: Path | None = None,
                 initial_heap_gib: int = 16):
        self.directory, self.java, self.jar = directory, java, jar
        self.parity_dump_dir = parity_dump_dir
        self.initial_heap_gib = parse_initial_heap_gib(initial_heap_gib)
        self.lines: list[str] = []
        self.lock = threading.Lock()
        self.process: subprocess.Popen | None = None
        self.reader: threading.Thread | None = None

    def start(self, timeout: int = 300) -> None:
        check_port(self.port)
        flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
        self.process = subprocess.Popen(
            build_java_command(self.java, self.jar, self.parity_dump_dir, self.initial_heap_gib),
            cwd=self.directory, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
            creationflags=flags,
        )
        self.reader = threading.Thread(target=self._capture, name="worldgen-server-log", daemon=True)
        self.reader.start()
        self.wait(lambda: self.contains("Done (") and self.contains("GPURWGEN_READY"), timeout, "server/plugin ready")

    @property
    def port(self) -> int:
        value = (self.directory / "server.properties").read_text(encoding="utf-8")
        match = re.search(r"(?m)^server-port=(\d+)$", value)
        if not match:
            raise RuntimeError("server.properties has no server-port")
        return int(match.group(1))

    def _capture(self) -> None:
        assert self.process is not None and self.process.stdout is not None
        with (self.directory / "server.log").open("a", encoding="utf-8") as output:
            for raw in self.process.stdout:
                line = ANSI.sub("", raw).rstrip("\r\n")
                with self.lock:
                    self.lines.append(line)
                output.write(line + "\n")
                output.flush()
                if any(marker in line for marker in (
                    "GPURWGEN_", "Exact compute ready:", "GPU rejected:", "Done (",
                    "Vanilla terrain interpolation parity failed", "Exception", "watchdog",
                )):
                    print(line[:1500], flush=True)

    def contains(self, marker: str) -> bool:
        with self.lock:
            return any(marker in line for line in self.lines)

    def lines_from(self, index: int) -> list[str]:
        with self.lock:
            return list(self.lines[index:])

    def count_lines(self, marker: str) -> int:
        with self.lock:
            return sum(marker in line for line in self.lines)

    def wait(self, condition, timeout: int, reason: str) -> None:
        deadline = time.monotonic() + timeout
        while not condition():
            if self.process is not None and self.process.poll() is not None:
                raise RuntimeError(f"server exited with code {self.process.returncode} before {reason}")
            if time.monotonic() >= deadline:
                raise TimeoutError(f"timed out waiting for {reason}")
            time.sleep(0.2)

    def send(self, command: str) -> None:
        if self.process is None or self.process.poll() is not None or self.process.stdin is None:
            raise RuntimeError("server is not running")
        self.process.stdin.write(command + "\n")
        self.process.stdin.flush()

    def stop(self, timeout: int = 180) -> int:
        if self.process is None:
            return 0
        if self.process.poll() is None:
            try:
                self.send("stop")
                self.process.wait(timeout=timeout)
            except (OSError, subprocess.TimeoutExpired):
                # Preserve the workspace and try graceful shutdown before any hard stop.
                if self.process.poll() is None:
                    self.process.terminate()
                    try:
                        self.process.wait(timeout=30)
                    except subprocess.TimeoutExpired:
                        self.process.kill()
                        self.process.wait()
        if self.reader is not None:
            self.reader.join(timeout=10)
        return int(self.process.returncode or 0)


def build_java_command(java: Path, jar: Path, parity_dump_dir: Path | None = None,
                       initial_heap_gib: int = 16) -> list[str]:
    initial_heap_gib = parse_initial_heap_gib(initial_heap_gib)
    command = [str(java), "--enable-native-access=ALL-UNNAMED", f"-Xms{initial_heap_gib}G",
               "-Xmx16G", "-XX:+UseG1GC"]
    if parity_dump_dir is not None:
        command.append(f"-Dgpur.worldgen.parity-dump={parity_dump_dir.resolve()}")
    command.extend(("-jar", str(jar), "--nogui"))
    return command


def write_case_files(args, directory: Path, case: str, label: str, jar_copy: Path, plugin_copy: Path) -> dict:
    (directory / "plugins" / "bStats").mkdir(parents=True)
    (directory / "config").mkdir()
    (directory / "cache").mkdir()
    (directory / "artifacts").mkdir()
    shutil.copy2(args.eula, directory / "eula.txt")
    shutil.copy2(args.mojang, directory / "cache" / "mojang_26.2.jar")
    shutil.copy2(args.jar, jar_copy)
    shutil.copy2(args.probe, plugin_copy)
    (directory / "plugins" / "bStats" / "config.yml").write_text("enabled: false\n", encoding="utf-8")
    shutil.copy2(plugin_copy, directory / "plugins" / plugin_copy.name)
    (directory / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={args.port}\nonline-mode=false\nmax-players=1\n"
        f"view-distance=2\nsimulation-distance=2\ndifficulty=normal\ngamemode=survival\n"
        "spawn-protection=0\nlevel-name=lobby\nlevel-type=minecraft:flat\n"
        'generator-settings={"layers":[{"height":1,"block":"minecraft:bedrock"},{"height":2,"block":"minecraft:dirt"},{"height":1,"block":"minecraft:grass_block"}],"biome":"minecraft:plains","features":false,"lakes":false}\n'
        f"level-seed={args.seed}\ngenerate-structures=false\nallow-flight=true\n"
        "initial-enabled-packs=vanilla\n", encoding="utf-8",
    )
    (directory / "bukkit.yml").write_text("settings:\n  connection-throttle: 0\n  allow-end: false\n", encoding="utf-8")
    (directory / "config" / "paper-global.yml").write_text(
        "_version: 31\nchunk-system:\n  worker-threads: 4\n  io-threads: 2\n", encoding="utf-8",
    )
    (directory / "config" / "paper-world-defaults.yml").write_text(
        "_version: 31\nanticheat:\n  anti-xray:\n    enabled: false\n", encoding="utf-8",
    )
    settings = CASES[case]
    gpu_enabled = case != "cpu"
    device_yaml = json.dumps(settings["devices"])
    (directory / "gpur.yml").write_text(
        "config-version: 2\n"
        "chunk-generation:\n"
        f"  gpu-acceleration:\n    enabled: {str(gpu_enabled).lower()}\n    terrain-enabled: true\n"
        "  vanilla-terrain:\n"
        f"    enabled: {str(gpu_enabled).lower()}\n    mode: {args.mode if gpu_enabled else 'disabled'}\n"
        f"    noise-batches: {str(gpu_enabled).lower()}\n    aquifer-ranking: {str(gpu_enabled).lower()}\n"
        f"    verify-every-batch: {str(args.mode == 'strict' if gpu_enabled else True).lower()}\n"
        "    minimum-values: 1024\n"
        f"gpu:\n  multi-gpu:\n    enabled: {str(settings['multi']).lower()}\n    devices: {device_yaml}\n"
        "  force: false\n  timeout-ms: 100\n"
        "  scheduler:\n    async-submit: true\n",
        encoding="utf-8",
    )
    # Keep the workspace's generated configuration explicit and auditable.
    metadata = {
        "case": case,
        "mode": args.mode,
        "label": label,
        "seed": args.seed,
        "center_chunk": [args.center_x, args.center_z],
        "radius_chunks": args.radius,
        "expected_chunks": (args.radius * 2 + 1) ** 2,
        "world_type": "vanilla NORMAL with structures enabled in WorldCreator",
        "server_heap_gib": 16,
        "initial_heap_gib": args.initial_heap_gib,
        "worker_threads": 4,
        "io_threads": 2,
        "jdk_home": str(args.jdk.resolve()),
        "java_sha256": sha256((args.jdk / "bin" / ("java.exe" if os.name == "nt" else "java")).resolve()),
        "jvm_options": worldgen_jvm_options(args.initial_heap_gib),
        "jvm_system_properties": ([f"-Dgpur.worldgen.parity-dump={args.parity_dump_dir.resolve()}"]
                                  if args.parity_dump_dir is not None else []),
        "parity_dump_dir": str(args.parity_dump_dir.resolve()) if args.parity_dump_dir is not None else None,
        "force": False,
        "vanilla_verification_mode": args.mode if gpu_enabled else "disabled",
        "gpu_devices_configured": settings["devices"],
        "jar_sha256": sha256(jar_copy),
        "probe_sha256": sha256(plugin_copy),
        "eula_source_sha256": sha256(args.eula),
        "mojang_cache_sha256": sha256(args.mojang),
        "started_utc": datetime.now(timezone.utc).isoformat(),
    }
    atomic_json(directory / "run.json", metadata)
    return metadata


def extract_status(lines: list[str]) -> dict:
    groups: list[dict] = []
    active: dict | None = None
    for line in lines:
        header = re.search(r"GPU\s+\d+:\s*(.*?)\s+\|", line)
        if header:
            active = {"name": header.group(1).strip(), "lines": []}
            groups.append(active)
        if active is not None:
            active["lines"].append(line)
    devices = []
    for group in groups:
        block = "\n".join(group["lines"])
        workload = re.search(r"Vanilla interpolation:\s*(.*?)\s*\|\s*([\d,]+) slabs", block)
        values = re.search(r"Values / full parity checks:\s*([\d,]+)\s*/\s*([\d,]+)", block)
        devices.append({
            "name": group["name"],
            "vanilla_state": workload.group(1).strip() if workload else None,
            "slabs": int(workload.group(2).replace(",", "")) if workload else 0,
            "accepted_values": int(values.group(1).replace(",", "")) if values else 0,
            "full_parity_checks": int(values.group(2).replace(",", "")) if values else 0,
        })
    mode = re.search(r"Vanilla mode:\s*([^|\r\n]+)", "\n".join(lines))
    totals = re.search(r"Vanilla interpolation CPU attempts:\s*([\d,]+)\s*\|\s*parity failures:\s*([\d,]+)",
                       "\n".join(lines))
    return {
        "vanilla_mode": mode.group(1).strip() if mode else None,
        "cpu_attempts": int(totals.group(1).replace(",", "")) if totals else None,
        "parity_failures": int(totals.group(2).replace(",", "")) if totals else None,
        "devices": devices,
        "raw_status_lines": lines,
    }


def validate_admission(server: Server, case: str) -> list[str]:
    expected = 0 if case == "cpu" else 2 if case == "mixed" else 1
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        observed = server.count_lines("Exact compute ready:")
        if observed >= expected:
            break
        if case == "cpu":
            break
        time.sleep(0.2)
    lines = server.lines_from(0)
    admissions = [line for line in lines if "Exact compute ready:" in line]
    if len(admissions) != expected:
        raise RuntimeError(f"expected {expected} admitted compute devices for {case}; observed {len(admissions)}: {admissions}")
    required = {"rtx3070": ["RTX 3070"], "gtx1080": ["GTX 1080"],
                "mixed": ["RTX 3070", "GTX 1080"], "cpu": []}[case]
    for marker in required:
        if not any(marker in line for line in admissions):
            raise RuntimeError(f"configured device {marker} was not admitted: {admissions}")
    return admissions


def run_probe_phase(server: Server, phase: str, label: str, args, timeout: int) -> tuple[int, Path, dict]:
    before = len(server.lines_from(0))
    if phase == "generate":
        server.send("gpur status detail")
    command = (f"gpurbench terrain {phase} {label} {args.seed} {args.center_x} "
               f"{args.center_z} {args.radius}")
    server.send(command)
    start_marker = f"GPURWGEN_START phase={phase} label={label}"
    done_marker = f"GPURWGEN_DONE phase={phase} label={label}"
    error_marker = f"GPURWGEN_ERROR phase={phase} label={label}"
    server.wait(lambda: server.contains(start_marker), 120, f"{phase} start")
    server.wait(lambda: server.contains(done_marker) or server.contains(error_marker), timeout, f"{phase} completion")
    if server.contains(error_marker) and not server.contains(done_marker):
        error_line = next((line for line in reversed(server.lines_from(0)) if error_marker in line), error_marker)
        raise RuntimeError(error_line)
    log_lines = server.lines_from(before)
    plugin_report = server.directory / "plugins" / "GPurWorldgenProbe" / "runs" / label / f"{phase}.json"
    if not plugin_report.is_file():
        raise RuntimeError(f"probe report missing after {phase}: {plugin_report}")
    report = json.loads(plugin_report.read_text(encoding="utf-8"))
    tick_metrics_path = plugin_report.parent / f"{phase}-tick-metrics.json"
    tick_samples_path = plugin_report.parent / f"{phase}-tick-samples.csv"
    server.wait(lambda: tick_metrics_path.is_file() and tick_samples_path.is_file(), 120,
                f"{phase} raw tick samples and summary")
    tick_metrics = json.loads(tick_metrics_path.read_text(encoding="utf-8"))
    if (tick_metrics.get("complete") is not True or tick_metrics.get("phase") != phase
            or tick_metrics.get("tick_samples", 0) <= 0 or tick_metrics.get("start_interval_samples", 0) <= 0
            or Path(tick_metrics.get("raw_csv", "")).resolve() != tick_samples_path.resolve()):
        raise RuntimeError(f"{phase} tick metrics are incomplete or malformed: {tick_metrics}")
    expected = (args.radius * 2 + 1) ** 2
    if report.get("completed_chunks") != expected or report.get("requested_chunks") != expected:
        raise RuntimeError(f"{phase} did not complete its exact requested chunk square: {report}")
    if (report.get("lighting_complete") is not True
            or report.get("lighting_complete_chunks") != expected):
        raise RuntimeError(f"{phase} did not prove fully lit status for every requested chunk: {report}")
    if report.get("seed") != args.seed or report.get("structures") is not True:
        raise RuntimeError(f"{phase} world identity/structure settings mismatch: {report}")
    return before, plugin_report, tick_metrics


def run_worldgen_status(server: Server, label: str, stage: str) -> dict:
    marker = f"GPURWGEN_STATUS_DONE label={label} stage={stage}"
    server.send(f"gpurbench terrain status {label} {stage}")
    return read_worldgen_status(server, label, stage, marker)


def read_worldgen_status(server: Server, label: str, stage: str, marker: str | None = None) -> dict:
    if marker is None:
        marker = f"GPURWGEN_STATUS_DONE label={label} stage={stage}"
    server.wait(lambda: server.contains(marker), 30, f"worldgen status {stage}")
    report = server.directory / "plugins" / "GPurWorldgenProbe" / "runs" / label / f"worldgen-status-{stage}.json"
    if not report.is_file():
        raise RuntimeError(f"worldgen status report missing for {stage}: {report}")
    value = json.loads(report.read_text(encoding="utf-8"))
    if value.get("label") != label or value.get("stage") != stage or not isinstance(value.get("devices"), list):
        raise RuntimeError(f"malformed worldgen telemetry report for {stage}: {value}")
    return value


def validate_worldgen_consumption(before: dict, after: dict, case: str, mode: str = "strict") -> dict:
    if mode not in ("strict", "verified-exact"):
        raise ValueError(f"unsupported validation mode: {mode}")
    if case == "cpu":
        return {"required": False, "mode": mode, "reason": "CPU reference case", "deltas": []}
    expected_names = {
        "rtx3070": ("RTX 3070",),
        "gtx1080": ("GTX 1080",),
        "mixed": ("RTX 3070", "GTX 1080"),
    }[case]
    before_boundaries = before.get("configured_boundaries", {})
    after_boundaries = after.get("configured_boundaries", {})
    for name in ("gpu_acceleration_enabled", "terrain_gpu_enabled", "vanilla_terrain_enabled",
                 "noise_batches_enabled", "aquifer_ranking_enabled"):
        if before_boundaries.get(name) is not True or after_boundaries.get(name) is not True:
            raise RuntimeError(f"worldgen GPU boundary {name} was not enabled for {case}")
    if before.get("compute_service_present") is not True or after.get("compute_service_present") is not True:
        raise RuntimeError(f"ComputeService telemetry is unavailable for {case}")

    def index(report: dict) -> dict[tuple[str, int], dict]:
        return {(str(item.get("name", "")), int(item.get("workload", -1))): item
                for item in report.get("devices", [])}

    old, new = index(before), index(after)
    deltas = []
    for expected_name in expected_names:
        for workload in (5, 6):
            matches = [(key, value) for key, value in new.items()
                       if expected_name.lower() in key[0].lower() and key[1] == workload]
            if len(matches) != 1:
                raise RuntimeError(f"expected one {expected_name} workload {workload} telemetry row; found {matches}")
            key, current = matches[0]
            previous = old.get(key)
            if previous is None:
                raise RuntimeError(f"missing pre-generation telemetry for {expected_name} workload {workload}")
            if current.get("boundaryEnabled") is not True:
                raise RuntimeError(f"{expected_name} workload {workload} boundary was ineligible")
            delta = {field: int(current[field]) - int(previous[field])
                     for field in ("dispatches", "acceptedBatches", "computedValues", "consumedValues", "paritySamples")}
            required_positive = ("dispatches", "acceptedBatches", "computedValues", "consumedValues")
            if any(delta[field] <= 0 for field in required_positive):
                raise RuntimeError(f"{expected_name} workload {workload} did not prove consumed GPU work: {delta}")
            if mode == "strict" and delta["paritySamples"] <= 0:
                raise RuntimeError(f"{expected_name} workload {workload} did not prove new strict parity samples: {delta}")
            if mode == "verified-exact" and int(current["paritySamples"]) <= 0:
                raise RuntimeError(f"{expected_name} workload {workload} has no startup/periodic parity evidence: {delta}")
            row = {"device": key[0], "workload": workload, **delta}
            deltas.append(row)
    before_fallbacks = before.get("fallbacks_by_workload", {})
    after_fallbacks = after.get("fallbacks_by_workload", {})
    fallback_deltas = {}
    for workload in ("5", "6"):
        if workload in before_fallbacks and workload in after_fallbacks:
            fallback_deltas[workload] = int(after_fallbacks[workload]) - int(before_fallbacks[workload])
            if fallback_deltas[workload] < 0:
                raise RuntimeError(f"worldgen workload {workload} fallback counter moved backwards")
    return {"required": True, "mode": mode, "device_workload_deltas": deltas,
            "fallback_attempt_deltas_by_workload": fallback_deltas}


def capture_status_and_stop(server: Server) -> tuple[dict, int]:
    offset = len(server.lines_from(0))
    server.send("gpur status detail")
    server.send("stop")
    if server.process is None:
        raise RuntimeError("server process missing")
    try:
        server.process.wait(timeout=240)
    except subprocess.TimeoutExpired as error:
        raise TimeoutError("server did not shut down cleanly after terrain save") from error
    if server.reader is not None:
        server.reader.join(timeout=10)
    lines = server.lines_from(offset)
    return extract_status(lines), int(server.process.returncode or 0)


def snapshot_world(args, world: Path, output: Path, metadata: Path) -> None:
    bounds = (args.center_x - args.radius, args.center_z - args.radius,
              args.center_x + args.radius, args.center_z + args.radius)
    script = Path(__file__).with_name("semantic_snapshot.py")
    subprocess.run([
        sys.executable, str(script), "snapshot", "--world", str(world), "--output", str(output),
        "--bounds", *(str(value) for value in bounds), "--metadata", str(metadata),
    ], check=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--case", choices=sorted(CASES), required=True)
    parser.add_argument("--mode", choices=("strict", "verified-exact"), default="strict")
    parser.add_argument("--output", type=Path, default=SAFE_ROOT / "worldgen")
    parser.add_argument("--jar", type=Path, default=ROOT / "gpur-server/build/libs/gpur-server-26.2-SNAPSHOT1.1.0.jar")
    parser.add_argument("--probe", type=Path, default=SAFE_ROOT / "worldgen-plugin/GPurWorldgenProbe.jar")
    parser.add_argument("--mojang", type=Path, default=ROOT / "validation/smoke-gpu-final/cache/mojang_26.2.jar")
    parser.add_argument("--eula", type=Path, default=ROOT / "validation/eula.txt")
    parser.add_argument("--jdk", type=Path, default=DEFAULT_JDK)
    parser.add_argument("--initial-heap-gib", type=parse_initial_heap_gib, default=16,
                        help="initial Java heap in GiB (1..16); maximum remains fixed at 16 GiB")
    parser.add_argument("--port", type=int, default=25620)
    parser.add_argument("--seed", type=int, default=1196459378)
    parser.add_argument("--center-x", type=int, default=128)
    parser.add_argument("--center-z", type=int, default=128)
    parser.add_argument("--radius", type=int, default=32)
    parser.add_argument("--require-structures", action=argparse.BooleanOptionalAction, default=None,
                        help="require structure starts in the corpus (defaults on only for radius 32 or larger)")
    parser.add_argument("--parity-dump-dir", type=Path,
                        help="explicit isolated output directory for failed GPU parity input/output snapshots (disabled by default)")
    parser.add_argument("--startup-timeout", type=int, default=300)
    parser.add_argument("--phase-timeout", type=int, default=3600)
    args = parser.parse_args()

    result = {"schema": 1, "case": args.case, "mode": args.mode, "pass": False,
              "server_heap_gib": 16, "initial_heap_gib": args.initial_heap_gib,
              "jvm_options": worldgen_jvm_options(args.initial_heap_gib)}
    server: Server | None = None
    try:
        args.output = safe_output(args.output)
        if args.port == 25565:
            raise ValueError("refusing live-server port 25565")
        if not 1 <= args.radius <= 64:
            raise ValueError("radius must be in 1..64 chunks")
        if args.require_structures is None:
            args.require_structures = args.radius >= 32
        if args.parity_dump_dir is not None:
            args.parity_dump_dir = safe_output(args.parity_dump_dir)
        for name in ("jar", "probe", "mojang", "eula"):
            path = getattr(args, name).resolve()
            if not path.is_file():
                raise FileNotFoundError(f"{name} input file does not exist: {path}")
            setattr(args, name, path)
        java = (args.jdk / "bin" / ("java.exe" if os.name == "nt" else "java")).resolve()
        if not java.is_file():
            raise FileNotFoundError(f"Java 25 executable not found: {java}")
        eula_text = args.eula.read_text(encoding="utf-8", errors="replace").lower()
        if not re.search(r"(?m)^eula\s*=\s*true\s*$", eula_text):
            raise ValueError("the supplied existing eula.txt must already contain eula=true")

        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid4().hex[:8]
        args.output.mkdir(parents=True, exist_ok=True)
        directory = args.output / "runs" / f"{stamp}-{args.case}"
        directory.mkdir(parents=True, exist_ok=False)
        if args.parity_dump_dir is not None:
            args.parity_dump_dir.mkdir(parents=True, exist_ok=False)
        label = args.case
        jar_copy = directory / "artifacts" / "GPurServer.jar"
        plugin_copy = directory / "artifacts" / "GPurWorldgenProbe.jar"
        metadata = write_case_files(args, directory, args.case, label, jar_copy, plugin_copy)
        server = Server(directory, java, jar_copy, args.parity_dump_dir, args.initial_heap_gib)

        server.start(args.startup_timeout)
        admissions = validate_admission(server, args.case)
        metadata["admitted_compute_devices"] = admissions
        worldgen_before_world = run_worldgen_status(server, label, "before")
        _generate_start, generate_report_path, generate_tick_metrics = run_probe_phase(
            server, "generate", label, args, args.phase_timeout)
        worldgen_before_corpus = read_worldgen_status(server, label, "before-corpus-generate")
        worldgen_after = run_worldgen_status(server, label, "after-generate")
        worldgen_consumption = validate_worldgen_consumption(
            worldgen_before_corpus, worldgen_after, args.case, args.mode)
        status, exit_code = capture_status_and_stop(server)
        server = None
        if exit_code != 0:
            raise RuntimeError(f"generation server did not exit cleanly: {exit_code}")

        worldgen = json.loads(generate_report_path.read_text(encoding="utf-8"))
        generated_snapshot = directory / "snapshots" / "generated"
        snapshot_world(args, Path(worldgen["world_path"]), generated_snapshot, generate_report_path)

        server = Server(directory, java, jar_copy, args.parity_dump_dir, args.initial_heap_gib)
        server.start(args.startup_timeout)
        validate_admission(server, args.case)
        _reload_start, reload_report_path, reload_tick_metrics = run_probe_phase(
            server, "reload", label, args, args.phase_timeout)
        reload_exit = server.stop()
        server = None
        if reload_exit != 0:
            raise RuntimeError(f"reload server did not exit cleanly: {reload_exit}")

        reload_data = json.loads(reload_report_path.read_text(encoding="utf-8"))
        reloaded_snapshot = directory / "snapshots" / "reloaded"
        snapshot_world(args, Path(reload_data["world_path"]), reloaded_snapshot, reload_report_path)
        parity_report = directory / "reports" / "restart-parity.json"
        script = Path(__file__).with_name("semantic_snapshot.py")
        compare_command = [
            sys.executable, str(script), "compare", "--left", str(generated_snapshot),
            "--right", str(reloaded_snapshot), "--report", str(parity_report),
        ]
        if args.require_structures:
            compare_command.append("--require-structures")
        compare = subprocess.run(compare_command, check=False, capture_output=True, text=True,
                                 encoding="utf-8", errors="replace")
        if compare.stdout:
            print(compare.stdout.strip(), flush=True)
        if compare.returncode != 0:
            parity = json.loads(parity_report.read_text(encoding="utf-8")) if parity_report.is_file() else {}
            missing_coverage = []
            if not parity.get("chunks_equal", False):
                missing_coverage.append("chunk coverage incomplete")
            if parity.get("incomplete_lighting_chunks"):
                missing_coverage.append("serialized lighting incomplete")
            if parity.get("incomplete_generation_status_chunks"):
                missing_coverage.append("generation status is not FULL")
            if parity.get("structures_required") and not parity.get("structures_present_both"):
                counts = parity.get("structure_start_counts", {})
                missing_coverage.append(
                    "no structure starts in sample (left={left}, right={right})".format(
                        left=counts.get("left", 0), right=counts.get("right", 0)))
            semantic_differences = {
                key: value for key, value in parity.get("category_difference_counts", {}).items() if value
            }
            if semantic_differences:
                raise RuntimeError(
                    f"generated-to-reload semantic content mismatch {semantic_differences}; "
                    f"coverage issues={missing_coverage}; see {parity_report}")
            raise RuntimeError(
                f"generated-to-reload parity gate failed from coverage: {missing_coverage}; see {parity_report}")

        expected = args.radius * 2 + 1
        expected_chunks = expected * expected
        if status["vanilla_mode"] != ("disabled" if args.case == "cpu" else args.mode):
            raise RuntimeError(f"unexpected vanilla terrain verification mode: {status['vanilla_mode']}")
        if status["parity_failures"] not in (0, None):
            raise RuntimeError(f"GPU vanilla interpolation parity failed: {status['parity_failures']}")
        accepted = sum(device["accepted_values"] for device in status["devices"])
        parity_checks = sum(device["full_parity_checks"] for device in status["devices"])
        if args.case != "cpu":
            if not status["devices"] or accepted <= 0 or parity_checks <= 0:
                raise RuntimeError("GPU case had no accepted vanilla interpolation values and startup/periodic parity checks")
            for required_name in ("RTX 3070", "GTX 1080") if args.case == "mixed" else (
                    ("RTX 3070",) if args.case == "rtx3070" else ("GTX 1080",)):
                device = next((item for item in status["devices"] if required_name in item["name"]), None)
                if device is None or device["accepted_values"] <= 0 or device["full_parity_checks"] <= 0:
                    raise RuntimeError(f"selected device did not return parity-checked terrain values: {required_name}")
        elapsed_ms = int(worldgen["request_elapsed_ms"])
        if elapsed_ms <= 0:
            raise RuntimeError(f"invalid request elapsed time: {elapsed_ms}")
        metadata.update({
            "world_path": worldgen["world_path"],
            "generated_snapshot": str(generated_snapshot),
            "reloaded_snapshot": str(reloaded_snapshot),
            "restart_parity_report": str(parity_report),
            "request_elapsed_ms": elapsed_ms,
            "generation_chunks_per_second": round(expected_chunks * 1000 / elapsed_ms, 3),
            "generation_tick_metrics": generate_tick_metrics,
            "reload_tick_metrics": reload_tick_metrics,
            "gpu_accepted_values": accepted,
            "gpu_full_parity_checks": parity_checks,
            "require_structure_starts": args.require_structures,
            "terrain_status": status,
            "worldgen_status_before_world_creation": worldgen_before_world,
            "worldgen_status_before_corpus": worldgen_before_corpus,
            "worldgen_status_after_generate": worldgen_after,
            "worldgen_consumption": worldgen_consumption,
            "pass": True,
            "finished_utc": datetime.now(timezone.utc).isoformat(),
        })
        result.update(metadata)
        atomic_json(directory / "result.json", result)
        summary = {"case": args.case, "mode": args.mode, "pass": True, "workspace": str(directory),
                   "jar_sha256": metadata["jar_sha256"], "seed": args.seed,
                   "center_chunk": [args.center_x, args.center_z], "radius_chunks": args.radius,
                   "worker_threads": metadata["worker_threads"], "io_threads": metadata["io_threads"],
                   "jdk_home": metadata["jdk_home"], "java_sha256": metadata["java_sha256"],
                   "server_heap_gib": metadata["server_heap_gib"],
                   "initial_heap_gib": metadata["initial_heap_gib"],
                   "jvm_options": metadata["jvm_options"],
                   "chunks": expected_chunks,
                   "require_structure_starts": args.require_structures,
                   "request_elapsed_ms": metadata["request_elapsed_ms"],
                   "chunks_per_second": metadata["generation_chunks_per_second"],
                   "generation_tick_metrics": generate_tick_metrics,
                   "reload_tick_metrics": reload_tick_metrics,
                   "gpu_accepted_values": accepted,
                   "gpu_full_parity_checks": parity_checks}
        print(json.dumps(summary, indent=2), flush=True)
        print("WORLDGEN_CASE_RESULT " + json.dumps(summary, separators=(",", ":")), flush=True)
        return 0
    except Exception as error:
        result["error"] = repr(error)
        if server is not None:
            try:
                result["server_exit"] = server.stop()
            except Exception as stop_error:
                result["shutdown_error"] = repr(stop_error)
        result["finished_utc"] = datetime.now(timezone.utc).isoformat()
        if "directory" in locals():
            result["workspace"] = str(directory)
            atomic_json(directory / "result.json", result)
            print("WORLDGEN_CASE_RESULT " + json.dumps({
                "case": args.case, "pass": False, "workspace": str(directory), "error": result["error"],
                "server_heap_gib": 16,
                "initial_heap_gib": args.initial_heap_gib,
                "jvm_options": worldgen_jvm_options(args.initial_heap_gib),
            }, separators=(",", ":")), flush=True)
        print(f"WORLDGEN_CASE_FAIL {args.case}: {error}", file=sys.stderr, flush=True)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
