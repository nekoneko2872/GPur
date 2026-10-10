"""Run CPU, RTX 3070, GTX 1080, and mixed-device semantic worldgen cases."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from uuid import uuid4

from run_case import CASES, ROOT, SAFE_ROOT, parse_initial_heap_gib, safe_output, worldgen_jvm_options


def invoke_case(args, output: Path, case: str) -> dict:
    jar = getattr(args, "candidate_jar", args.jar)
    command = [sys.executable, str(Path(__file__).with_name("run_case.py")),
               "--case", case, "--mode", args.mode, "--output", str(output), "--jar", str(jar),
               "--probe", str(args.probe), "--mojang", str(args.mojang), "--eula", str(args.eula),
               "--jdk", str(args.jdk), "--port", str(args.port), "--seed", str(args.seed),
               "--center-x", str(args.center_x), "--center-z", str(args.center_z),
               "--radius", str(args.radius), "--initial-heap-gib", str(args.initial_heap_gib),
               "--startup-timeout", str(args.startup_timeout),
               "--phase-timeout", str(args.phase_timeout)]
    if args.freeze_simulation:
        command.append("--freeze-simulation")
    if args.parity_dump_dir is not None:
        case_dump_dir = safe_output(args.parity_dump_dir / output.name / case)
        command.extend(("--parity-dump-dir", str(case_dump_dir)))
    command.append("--require-structures" if args.require_structures else "--no-require-structures")
    process = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if process.stdout:
        print(process.stdout, end="", flush=True)
    if process.stderr:
        print(process.stderr, end="", file=sys.stderr, flush=True)
    markers = [line.removeprefix("WORLDGEN_CASE_RESULT ") for line in process.stdout.splitlines()
               if line.startswith("WORLDGEN_CASE_RESULT ")]
    if not markers:
        raise RuntimeError(f"case {case} failed without a result marker; see output above")
    result = json.loads(markers[-1])
    result["exit_code"] = process.returncode
    return result


def file_sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def validate_strict_reference(path: Path, jar_sha256: str, cases: list[str], corpus: dict, runtime: dict) -> dict:
    if not path.is_file():
        raise FileNotFoundError(f"verified-exact mode requires an existing passing strict report: {path}")
    report = json.loads(path.read_text(encoding="utf-8"))
    if report.get("pass") is not True or report.get("mode") != "strict":
        raise ValueError("strict reference must be a passing matrix report with mode=strict")
    if report.get("source_jar_sha256") != jar_sha256:
        raise ValueError("strict reference JAR SHA-256 does not match this verified-exact candidate")
    if report.get("corpus") != corpus:
        raise ValueError("strict reference seed/center/radius corpus does not match this matrix")
    if report.get("runtime") != runtime:
        raise ValueError("strict reference JDK/JVM/worker/simulation settings do not match this matrix")
    expected_simulation_mode = "frozen-worldgen" if runtime["freeze_simulation"] else "normal-world-ticks"
    if (report.get("freeze_simulation") is not runtime["freeze_simulation"]
            or report.get("simulation_mode") != expected_simulation_mode):
        raise ValueError("strict reference frozen-simulation mode does not match this matrix")
    if set(report.get("cases", {})) != set(cases):
        raise ValueError("strict reference case set does not match this matrix")
    for case in cases:
        row = report["cases"][case]
        if row.get("jvm_options") != runtime["jvm_options"]:
            raise ValueError(f"strict reference case {case} JVM options do not match the matrix runtime")
        if (row.get("server_heap_gib") != runtime["heap_gib"]
                or row.get("initial_heap_gib") != runtime["initial_heap_gib"]
                or row.get("freeze_simulation") is not runtime["freeze_simulation"]
                or row.get("simulation_mode") != expected_simulation_mode):
            raise ValueError(f"strict reference case {case} heap/simulation metadata does not match the matrix runtime")
        expected_freeze_property = "-Dgpur.worldgen.freeze-simulation=true"
        properties = row.get("jvm_system_properties")
        if (not isinstance(properties, list)
                or ((expected_freeze_property in properties) is not runtime["freeze_simulation"])):
            raise ValueError(f"strict reference case {case} freeze JVM property does not match the matrix runtime")
        if (row.get("pass") is not True or row.get("exit_code") != 0 or row.get("jar_sha256") != jar_sha256
                or row.get("case") != case or row.get("seed") != corpus["seed"]
                or row.get("center_chunk") != corpus["center_chunk"]
                or row.get("radius_chunks") != corpus["radius_chunks"]
                or row.get("require_structure_starts") != corpus["require_structure_starts"]
                or row.get("worker_threads") != runtime["worker_threads"]
                or row.get("io_threads") != runtime["io_threads"]
                or row.get("jdk_home") != runtime["jdk_home"]
                or row.get("java_sha256") != runtime["java_sha256"]):
            raise ValueError(f"strict reference case {case} did not pass on the same candidate JAR")
        if row.get("mode") != "strict":
            raise ValueError(f"strict reference case {case} does not record mode=strict")
    for case in cases:
        if case != "cpu" and report.get("semantic_parity", {}).get(case, {}).get("pass") is not True:
            raise ValueError(f"strict reference lacks passing CPU-vs-{case} semantic parity")
    return {"path": str(path.resolve()), "sha256": file_sha256(path), "mode": "strict"}


def compare_snapshots(script: Path, left: Path, right: Path, report: Path, require_structures: bool) -> dict:
    command = [sys.executable, str(script), "compare", "--left", str(left), "--right", str(right), "--report", str(report)]
    if require_structures:
        command.append("--require-structures")
    process = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if process.stdout:
        print(process.stdout, flush=True)
    if process.stderr:
        print(process.stderr, end="", file=sys.stderr, flush=True)
    if not report.is_file():
        raise RuntimeError(f"semantic comparison did not write its report: {report}")
    return json.loads(report.read_text(encoding="utf-8"))


def throughput_entry(case_result: dict, cpu_rate: float | None, mode: str, freeze_simulation: bool) -> dict:
    rate = case_result.get("chunks_per_second")
    ratio = (round(rate / cpu_rate, 3)
             if mode == "verified-exact" and rate is not None and cpu_rate else None)
    return {
        "chunks_per_second": rate,
        "request_elapsed_ms": case_result.get("request_elapsed_ms"),
        "server_measured_tps": (case_result.get("generation_tick_metrics") or {}).get("measured_tps"),
        "tick_duration_ms": (case_result.get("generation_tick_metrics") or {}).get("tick_duration_ms"),
        "tick_start_interval_ms": (case_result.get("generation_tick_metrics") or {}).get("tick_start_interval_ms"),
        "ticks_over_50ms": (case_result.get("generation_tick_metrics") or {}).get("ticks_over_50ms"),
        "ticks_over_100ms": (case_result.get("generation_tick_metrics") or {}).get("ticks_over_100ms"),
        "generation_tick_metrics": (case_result.get("generation_tick_metrics") or {}).get("raw_csv"),
        "worldgen_throughput_ratio_vs_cpu": ratio,
        "speedup_vs_cpu": ratio if not freeze_simulation else None,
        "worldgen_throughput_comparison_eligible": mode == "verified-exact" and ratio is not None,
        "speedup_claim_eligible": mode == "verified-exact" and not freeze_simulation and ratio is not None,
        "measurement_mode": mode,
        "measurement_scope": "frozen-worldgen" if freeze_simulation else "normal-world-ticks",
        "simulation_mode": case_result.get("simulation_mode"),
        "tick_metrics_interpretation": case_result.get("tick_metrics_interpretation"),
        "gpu_accepted_values": case_result.get("gpu_accepted_values", 0),
        "gpu_full_parity_checks": case_result.get("gpu_full_parity_checks", 0),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=SAFE_ROOT / "worldgen")
    parser.add_argument("--cases", default="cpu,rtx3070,gtx1080,mixed")
    parser.add_argument("--mode", choices=("strict", "verified-exact"), default="strict",
                        help="strict is correctness-only; verified-exact is throughput after a matching strict run")
    parser.add_argument("--strict-report", type=Path,
                        help="required passing strict matrix report for --mode verified-exact")
    parser.add_argument("--continue-on-error", action="store_true",
                        help="attempt every requested case and preserve all smoke outcomes; final matrix still fails if any gate fails")
    parser.add_argument("--parity-dump-dir", type=Path,
                        help="explicit isolated root for failed GPU parity snapshots (disabled by default)")
    parser.add_argument("--jar", type=Path, default=ROOT / "gpur-server/build/libs/gpur-server-26.2-SNAPSHOT1.1.0.jar")
    parser.add_argument("--probe", type=Path, default=SAFE_ROOT / "worldgen-plugin/GPurWorldgenProbe.jar")
    parser.add_argument("--mojang", type=Path, default=ROOT / "validation/smoke-gpu-final/cache/mojang_26.2.jar")
    parser.add_argument("--eula", type=Path, default=ROOT / "validation/eula.txt")
    parser.add_argument("--jdk", type=Path, default=Path.home() / ".jdks" / "openjdk-25.0.2")
    parser.add_argument("--initial-heap-gib", type=parse_initial_heap_gib, default=16,
                        help="initial Java heap in GiB (1..16); maximum remains fixed at 16 GiB")
    parser.add_argument("--freeze-simulation", action="store_true",
                        help="freeze world/entity/block/fluid simulation during saved-world parity validation")
    parser.add_argument("--port", type=int, default=25620)
    parser.add_argument("--seed", type=int, default=1196459378)
    parser.add_argument("--center-x", type=int, default=128)
    parser.add_argument("--center-z", type=int, default=128)
    parser.add_argument("--radius", type=int, default=32)
    parser.add_argument("--require-structures", action=argparse.BooleanOptionalAction, default=None,
                        help="require structure starts in the corpus (defaults on only for radius 32 or larger)")
    parser.add_argument("--startup-timeout", type=int, default=300)
    parser.add_argument("--phase-timeout", type=int, default=3600)
    args = parser.parse_args()

    result = {"schema": 1, "pass": False, "mode": args.mode,
              "stages": [], "cases": {}, "semantic_parity": {}, "parity_dump_dir": None}
    try:
        args.output = safe_output(args.output)
        if args.parity_dump_dir is not None:
            args.parity_dump_dir = safe_output(args.parity_dump_dir)
            result["parity_dump_dir"] = str(args.parity_dump_dir)
        cases = [part.strip().lower() for part in args.cases.split(",") if part.strip()]
        if not cases or len(cases) != len(set(cases)) or any(case not in CASES for case in cases):
            raise ValueError("--cases must be a unique comma-separated subset of cpu,rtx3070,gtx1080,mixed")
        if "cpu" not in cases:
            raise ValueError("the CPU baseline must be included for a parity matrix")
        if args.mode == "verified-exact" and args.strict_report is None:
            raise ValueError("--mode verified-exact requires --strict-report from a passing strict matrix")
        if args.mode == "strict" and args.strict_report is not None:
            raise ValueError("--strict-report is only valid with --mode verified-exact")
        if args.require_structures is None:
            args.require_structures = args.radius >= 32
        corpus = {"seed": args.seed, "center_chunk": [args.center_x, args.center_z],
                  "radius_chunks": args.radius, "expected_chunks": (args.radius * 2 + 1) ** 2,
                  "require_structure_starts": args.require_structures}
        jar_sha256 = file_sha256(args.jar.resolve())
        java = (args.jdk.resolve() / "bin" / ("java.exe" if sys.platform == "win32" else "java"))
        runtime = {"jdk_home": str(args.jdk.resolve()), "java_sha256": file_sha256(java),
                   "worker_threads": 4, "io_threads": 2, "heap_gib": 16,
                   "initial_heap_gib": args.initial_heap_gib,
                   "freeze_simulation": args.freeze_simulation,
                   "jvm_options": worldgen_jvm_options(args.initial_heap_gib)}
        strict_reference = None
        if args.mode == "verified-exact":
            strict_reference = validate_strict_reference(args.strict_report.resolve(), jar_sha256, cases, corpus, runtime)
        run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid4().hex[:8]
        matrix = args.output / "matrices" / run_id
        matrix.mkdir(parents=True, exist_ok=False)
        artifact_dir = matrix / "artifacts"
        artifact_dir.mkdir()
        args.candidate_jar = artifact_dir / "GPurServer.jar"
        shutil.copy2(args.jar, args.candidate_jar)
        if file_sha256(args.candidate_jar) != jar_sha256:
            raise RuntimeError("candidate JAR changed while creating the immutable matrix copy")
        case_results = {}
        failed_cases = []
        for case in cases:
            if file_sha256(args.candidate_jar) != jar_sha256:
                raise RuntimeError("immutable candidate JAR changed during the matrix")
            try:
                case_results[case] = invoke_case(args, matrix, case)
            except Exception as case_error:
                if not args.continue_on_error:
                    raise
                case_results[case] = {
                    "case": case, "pass": False, "exit_code": None,
                    "error": repr(case_error), "workspace": None,
                }
            result["cases"] = case_results
            valid = (case_results[case].get("pass") is True
                     and case_results[case].get("exit_code") == 0
                     and case_results[case].get("jar_sha256") == jar_sha256)
            if not valid:
                failed_cases.append(case)
                case_results[case].setdefault("error", "case failed or did not run the immutable candidate JAR")
                result["stages"].append({"id": f"worldgen-{case}", "status": "fail",
                                         "evidence": case_results[case].get("workspace"),
                                         "error": case_results[case].get("error")})
                if not args.continue_on_error:
                    raise RuntimeError(f"case {case} failed; preserved workspace: {case_results[case].get('workspace')}")
            else:
                result["stages"].append({"id": f"worldgen-{case}", "status": "pass",
                                         "evidence": case_results[case]["workspace"]})
        compare_script = Path(__file__).with_name("semantic_snapshot.py")
        cpu_ok = ("cpu" in case_results and case_results["cpu"].get("pass") is True
                  and case_results["cpu"].get("exit_code") == 0
                  and case_results["cpu"].get("jar_sha256") == jar_sha256
                  and case_results["cpu"].get("workspace"))
        if not cpu_ok:
            for case in cases:
                if case != "cpu":
                    result["semantic_parity"][case] = {
                        "pass": False, "status": "blocked",
                        "reason": "passing CPU reference snapshot is unavailable",
                    }
                    result["stages"].append({"id": f"cpu-vs-{case}-semantic-parity", "status": "blocked",
                                             "error": "passing CPU reference snapshot is unavailable"})
        else:
            cpu_snapshot = Path(case_results["cpu"]["workspace"]) / "snapshots" / "generated"
            for case in cases:
                if case == "cpu":
                    continue
                if case_results[case].get("pass") is not True or not case_results[case].get("workspace"):
                    result["semantic_parity"][case] = {
                        "pass": False, "status": "blocked", "reason": f"case {case} has no passing generated snapshot",
                    }
                    result["stages"].append({"id": f"cpu-vs-{case}-semantic-parity", "status": "blocked",
                                             "error": f"case {case} has no passing generated snapshot"})
                    continue
                report_path = matrix / "reports" / f"cpu-vs-{case}.json"
                report = compare_snapshots(compare_script, cpu_snapshot,
                    Path(case_results[case]["workspace"]) / "snapshots" / "generated",
                    report_path, args.require_structures)
                result["semantic_parity"][case] = report
                result["stages"].append({"id": f"cpu-vs-{case}-semantic-parity",
                                         "status": "pass" if report.get("pass") else "fail",
                                         "evidence": str(report_path)})
                if not report.get("pass"):
                    # Preserve the failing CPU-vs-GPU result, then check whether two
                    # fresh CPU worlds also differ before assigning the cause. This
                    # diagnostic never changes the failed semantic gate.
                    try:
                        if file_sha256(args.candidate_jar) != jar_sha256:
                            raise RuntimeError("immutable candidate JAR changed before CPU repeatability control")
                        control = invoke_case(args, matrix, "cpu")
                        control_row = {"case": control, "semantic_parity": None}
                        if (control.get("pass") is not True or control.get("exit_code") != 0
                                or control.get("jar_sha256") != jar_sha256):
                            raise RuntimeError("second CPU world did not complete on the immutable candidate JAR")
                        control_report_path = matrix / "reports" / "cpu-vs-cpu-control.json"
                        control_report = compare_snapshots(
                            compare_script, cpu_snapshot,
                            Path(control["workspace"]) / "snapshots" / "generated",
                            control_report_path, args.require_structures)
                        control_row["semantic_parity"] = control_report
                        control_row["report"] = str(control_report_path)
                        control_row["interpretation"] = (
                            "CPU reference is not repeatable under the strict comparator; preserve the GPU mismatch without attribution"
                            if not control_report.get("pass") else
                            "CPU reference repeated successfully; preserve the GPU mismatch for investigation"
                        )
                        result["cpu_repeatability_control"] = control_row
                    except Exception as control_error:
                        result["cpu_repeatability_control"] = {
                            "status": "unavailable",
                            "error": repr(control_error),
                            "interpretation": "CPU-vs-GPU semantic mismatch remains preserved and unresolved",
                        }
                    if not args.continue_on_error:
                        raise RuntimeError(f"CPU-vs-{case} semantic parity failed; see {report_path}")

        cpu_rate = case_results["cpu"].get("chunks_per_second")
        result["throughput"] = {
            case: throughput_entry(case_results[case], cpu_rate, args.mode, args.freeze_simulation)
            for case in cases
        }
        result["cases"] = case_results
        result["source_jar_sha256"] = jar_sha256
        result["candidate_jar_copy"] = str(args.candidate_jar)
        result["corpus"] = corpus
        result["runtime"] = runtime
        if strict_reference is not None:
            result["strict_reference"] = strict_reference
        result["freeze_simulation"] = args.freeze_simulation
        result["simulation_mode"] = "frozen-worldgen" if args.freeze_simulation else "normal-world-ticks"
        result["throughput_interpretation"] = (
            "simulation was frozen for this matrix; strict timings are correctness diagnostics only, and any matching verified-exact ratio compares isolated frozen-worldgen numeric throughput. Frozen tick metrics are not normal SMP TPS/MSPT and the ratio is not a normal server-speedup claim."
            if args.freeze_simulation else
            ("verified-exact speedup is eligible only after same-JAR strict parity; strict timings include full CPU verification and are correctness diagnostics only. Server TPS/MSPT fields describe per-case tick quality, not a GPU speedup claim."
             if args.mode == "verified-exact" else
             "strict timings include full CPU verification and are correctness diagnostics only; speedup claims are disabled. Server TPS/MSPT fields describe per-case tick quality only.")
        )
        result["matrix_workspace"] = str(matrix)
        parity_failed = any(row.get("pass") is not True for row in result["semantic_parity"].values())
        result["pass"] = not failed_cases and not parity_failed
        if not result["pass"] and "error" not in result:
            result["error"] = "one or more requested cases or semantic parity gates failed or were blocked"
        result["finished_utc"] = datetime.now(timezone.utc).isoformat()
        out = matrix / "worldgen-matrix-report.json"
        out.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(("WORLDGEN_MATRIX_PASS " if result["pass"] else "WORLDGEN_MATRIX_FAIL ") + str(out), flush=True)
        return 0 if result["pass"] else 1
    except Exception as error:
        result["error"] = repr(error)
        result["finished_utc"] = datetime.now(timezone.utc).isoformat()
        if "matrix" in locals():
            result["matrix_workspace"] = str(matrix)
            out = matrix / "worldgen-matrix-report.json"
            out.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
            print("WORLDGEN_MATRIX_FAIL " + str(out), file=sys.stderr, flush=True)
        print(f"WORLDGEN_MATRIX_ERROR {error}", file=sys.stderr, flush=True)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
