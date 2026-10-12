"""Unit checks for parsing the terrain-use gate from gpur status detail."""

import argparse
import subprocess
import sys
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import run_case
from run_case import (build_java_command, extract_status, parse_initial_heap_gib,
                      validate_simulation_status, validate_worldgen_consumption, worldgen_jvm_options,
                      write_case_files)


class StatusParsingTests(unittest.TestCase):
    def test_initial_heap_is_configurable_but_maximum_stays_at_sixteen_gib(self):
        java = Path("C:/jdk/bin/java.exe")
        jar = Path("C:/validation/candidate.jar")
        command = build_java_command(java, jar, initial_heap_gib=4)
        self.assertIn("-Xms4G", command)
        self.assertIn("-Xmx16G", command)
        self.assertEqual(worldgen_jvm_options(4), [
            "--enable-native-access=ALL-UNNAMED", "-Xms4G", "-Xmx16G", "-XX:+UseG1GC",
        ])
        self.assertEqual(parse_initial_heap_gib("16"), 16)

    def test_initial_heap_rejects_non_integer_and_out_of_range_values(self):
        for value in ("0", "17", "-1", "four"):
            with self.subTest(value=value), self.assertRaises(argparse.ArgumentTypeError):
                parse_initial_heap_gib(value)

    def test_case_cli_rejects_initial_heap_outside_allowed_range(self):
        script = Path(__file__).with_name("run_case.py")
        completed = subprocess.run([sys.executable, str(script), "--case", "cpu", "--initial-heap-gib", "0"],
                                   capture_output=True, text=True, encoding="utf-8")
        self.assertEqual(completed.returncode, 2)
        self.assertIn("--initial-heap-gib must be in 1..16", completed.stderr)

    def test_parity_dump_jvm_property_is_opt_in(self):
        java = Path("C:/jdk/bin/java.exe")
        jar = Path("C:/validation/candidate.jar")
        ordinary = build_java_command(java, jar)
        self.assertFalse(any(arg.startswith("-Dgpur.worldgen.parity-dump=") for arg in ordinary))

        dump_dir = Path("C:/GPur-validation-20261009/validation/parity-captures/case")
        diagnostic = build_java_command(java, jar, dump_dir)
        property_index = next(index for index, arg in enumerate(diagnostic)
                              if arg.startswith("-Dgpur.worldgen.parity-dump="))
        self.assertEqual(diagnostic[property_index], f"-Dgpur.worldgen.parity-dump={dump_dir.resolve()}")
        self.assertLess(property_index, diagnostic.index("-jar"))

    def test_request_window_property_is_opt_in_and_range_checked(self):
        java = Path("C:/jdk/bin/java.exe")
        jar = Path("C:/validation/candidate.jar")
        ordinary = build_java_command(java, jar)
        self.assertFalse(any(arg.startswith("-Dgpur.worldgen.request-window=") for arg in ordinary))

        diagnostic = build_java_command(java, jar, request_window_size=1)
        index = diagnostic.index("-Dgpur.worldgen.request-window=1")
        self.assertLess(index, diagnostic.index("-jar"))
        for value in (0, 65):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "request window size must be in 1..64"):
                build_java_command(java, jar, request_window_size=value)

    def test_request_window_readback_must_match_and_be_present(self):
        validate_simulation_status({"freeze_simulation_requested": False, "simulation_frozen": False,
                                    "request_window_size": 1}, False, "diagnostic", 1)
        for report in (
            {"freeze_simulation_requested": False, "simulation_frozen": False, "request_window_size": 64},
            {"freeze_simulation_requested": False, "simulation_frozen": False},
        ):
            with self.subTest(report=report), self.assertRaisesRegex(RuntimeError, "request-window readback"):
                validate_simulation_status(report, False, "diagnostic", 1)

    def test_invalid_diagnostic_limits_fail_before_java_starts(self):
        for option, value in (("--request-window", "0"), ("--request-window", "65"),
                              ("--worker-threads", "0"), ("--worker-threads", "65")):
            with self.subTest(option=option, value=value), patch.object(
                    sys, "argv", ["run_case.py", "--case", "cpu", option, value]), \
                    patch.object(run_case.subprocess, "Popen") as popen:
                self.assertEqual(run_case.main(), 1)
                popen.assert_not_called()

    def test_one_worker_and_one_request_are_written_to_config_and_metadata(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = root / "inputs"
            inputs.mkdir()
            for filename in ("eula.txt", "mojang.jar", "server.jar", "probe.jar"):
                (inputs / filename).write_bytes(b"fixture")
            java = root / "jdk" / "bin" / "java.exe"
            java.parent.mkdir(parents=True)
            java.write_bytes(b"java-fixture")
            directory = root / "run"
            directory.mkdir()
            args = SimpleNamespace(
                eula=inputs / "eula.txt", mojang=inputs / "mojang.jar", jar=inputs / "server.jar",
                probe=inputs / "probe.jar", worker_threads=1, port=25620, seed=42,
                center_x=128, center_z=128, radius=4, initial_heap_gib=4, freeze_simulation=False,
                request_window_size=1, jdk=java.parents[1], mode="strict", parity_dump_dir=None,
            )
            metadata = write_case_files(args, directory, "cpu", "cpu", directory / "artifacts/GPurServer.jar",
                                        directory / "artifacts/GPurWorldgenProbe.jar")
            paper_config = (directory / "config" / "paper-global.yml").read_text(encoding="utf-8")
            self.assertIn("worker-threads: 1", paper_config)
            self.assertEqual(metadata["worker_threads"], 1)
            self.assertEqual(metadata["request_window_size"], 1)
            self.assertEqual(metadata["jvm_system_properties"], ["-Dgpur.worldgen.request-window=1"])
            self.assertEqual(json.loads((directory / "run.json").read_text(encoding="utf-8")), metadata)
            args.corner_graphs = True
            gpu_directory = root / "corner-run"
            gpu_directory.mkdir()
            gpu_metadata = write_case_files(args, gpu_directory, "rtx3070", "corner-run",
                                            gpu_directory / "artifacts/GPurServer.jar",
                                            gpu_directory / "artifacts/GPurWorldgenProbe.jar")
            config = (gpu_directory / "gpur.yml").read_text(encoding="utf-8")
            self.assertIn("corner-graphs: true", config)
            self.assertIn("noise-batches: false", config)
            self.assertIn("aquifer-ranking: true", config)
            self.assertTrue(gpu_metadata["corner_graphs"])

    def test_gpu_use_and_strict_parity_metrics_are_extracted_per_device(self):
        status = extract_status([
            "GPU 1: RTX 3070 | Available (idle)",
            "  Vanilla interpolation: GPU enabled | 19 slabs",
            "    Values / full parity checks: 8,192 / 19",
            "GPU 2: GTX 1080 | Available (idle)",
            "  Vanilla interpolation: GPU enabled | 11 slabs",
            "    Values / full parity checks: 4,096 / 11",
            "Vanilla interpolation CPU attempts: 7 | parity failures: 0",
            "Vanilla mode: strict | Async noise stage",
        ])
        self.assertEqual(status["vanilla_mode"], "strict")
        self.assertEqual(status["cpu_attempts"], 7)
        self.assertEqual(status["parity_failures"], 0)
        self.assertEqual([device["accepted_values"] for device in status["devices"]], [8192, 4096])
        self.assertEqual([device["full_parity_checks"] for device in status["devices"]], [19, 11])

    def test_no_metrics_is_not_a_gpu_pass(self):
        status = extract_status(["Vanilla mode: strict | Async noise stage"])
        self.assertEqual(status["devices"], [])
        self.assertIsNone(status["parity_failures"])

    def test_worldgen_gate_requires_consumed_values_for_every_selected_device_and_workload(self):
        boundaries = {"gpu_acceleration_enabled": True, "terrain_gpu_enabled": True,
                      "vanilla_terrain_enabled": True, "noise_batches_enabled": True,
                      "aquifer_ranking_enabled": True}
        before_rows, after_rows = [], []
        for name in ("RTX 3070", "GTX 1080"):
            for workload in (5, 6):
                base = {"name": name, "workload": workload, "boundaryEnabled": True,
                        "dispatches": 2, "acceptedBatches": 2, "computedValues": 64,
                        "consumedValues": 0, "paritySamples": 1}
                after = {**base, "dispatches": 3, "acceptedBatches": 3, "computedValues": 96,
                         "consumedValues": 32, "paritySamples": 2}
                before_rows.append(base)
                after_rows.append(after)
        before = {"configured_boundaries": boundaries, "compute_service_present": True, "devices": before_rows}
        after = {"configured_boundaries": boundaries, "compute_service_present": True, "devices": after_rows}
        result = validate_worldgen_consumption(before, after, "mixed")
        self.assertEqual(len(result["device_workload_deltas"]), 4)
        self.assertTrue(all(row["consumedValues"] == 32 for row in result["device_workload_deltas"]))

    def test_worldgen_gate_rejects_dispatches_that_were_never_consumed(self):
        boundaries = {"gpu_acceleration_enabled": True, "terrain_gpu_enabled": True,
                      "vanilla_terrain_enabled": True, "noise_batches_enabled": True,
                      "aquifer_ranking_enabled": True}
        before_rows, rows = [], []
        for workload in (5, 6):
            base = {"name": "RTX 3070", "workload": workload, "boundaryEnabled": True,
                    "dispatches": 1, "acceptedBatches": 1, "computedValues": 1024,
                    "consumedValues": 0, "paritySamples": 1}
            before_rows.append(base)
            rows.append({**base, "dispatches": 2, "acceptedBatches": 2, "computedValues": 2048,
                         "paritySamples": 2})
        before = {"configured_boundaries": boundaries, "compute_service_present": True, "devices": before_rows}
        after = {"configured_boundaries": boundaries, "compute_service_present": True, "devices": rows}
        with self.assertRaisesRegex(RuntimeError, "did not prove consumed"):
            validate_worldgen_consumption(before, after, "rtx3070")

    def test_verified_exact_allows_zero_new_parity_samples_after_warmup_but_requires_absolute_parity(self):
        boundaries = {"gpu_acceleration_enabled": True, "terrain_gpu_enabled": True,
                      "vanilla_terrain_enabled": True, "noise_batches_enabled": True,
                      "aquifer_ranking_enabled": True}
        before_rows, after_rows = [], []
        for workload in (5, 6):
            base = {"name": "RTX 3070", "workload": workload, "boundaryEnabled": True,
                    "dispatches": 2, "acceptedBatches": 2, "computedValues": 64,
                    "consumedValues": 16, "paritySamples": 3}
            before_rows.append(base)
            after_rows.append({**base, "dispatches": 3, "acceptedBatches": 3,
                               "computedValues": 96, "consumedValues": 32})
        before = {"configured_boundaries": boundaries, "compute_service_present": True, "devices": before_rows}
        after = {"configured_boundaries": boundaries, "compute_service_present": True, "devices": after_rows}
        result = validate_worldgen_consumption(before, after, "rtx3070", "verified-exact")
        self.assertEqual(result["mode"], "verified-exact")
        self.assertTrue(all(row["paritySamples"] == 0 for row in result["device_workload_deltas"]))
        after_rows[0]["paritySamples"] = 0
        after_rows[1]["paritySamples"] = 0
        with self.assertRaisesRegex(RuntimeError, "no startup/periodic parity evidence"):
            validate_worldgen_consumption(before, after, "rtx3070", "verified-exact")

    def test_cpu_case_does_not_require_gpu_telemetry(self):
        result = validate_worldgen_consumption({}, {}, "cpu")
        self.assertFalse(result["required"])

    def test_corner_graph_gate_requires_live_consumption_instead_of_leaf_noise_counters(self):
        boundaries = {"gpu_acceleration_enabled": True, "terrain_gpu_enabled": True,
                      "vanilla_terrain_enabled": True, "noise_batches_enabled": False,
                      "aquifer_ranking_enabled": True, "corner_graphs_enabled": True}
        before_rows, after_rows = [], []
        for name in ("RTX 3070", "GTX 1080"):
            for workload in (6, 7):
                base = {"name": name, "workload": workload, "boundaryEnabled": True,
                        "dispatches": 2, "acceptedBatches": 2, "computedValues": 64,
                        "consumedValues": 0, "paritySamples": 1}
                before_rows.append(base)
                after_rows.append({**base, "dispatches": 3, "acceptedBatches": 3,
                                   "computedValues": 96, "consumedValues": 32, "paritySamples": 2})
        before = {"configured_boundaries": boundaries, "compute_service_present": True, "devices": before_rows}
        after = {"configured_boundaries": boundaries, "compute_service_present": True, "devices": after_rows}
        result = validate_worldgen_consumption(before, after, "mixed", corner_graphs=True)
        self.assertEqual(result["required_workloads"], [6, 7])
        self.assertEqual(len(result["device_workload_deltas"]), 4)
        after_rows[-1]["consumedValues"] = 0
        with self.assertRaisesRegex(RuntimeError, "workload 7 did not prove consumed"):
            validate_worldgen_consumption(before, after, "mixed", corner_graphs=True)

    def test_corner_graph_gate_rejects_absent_or_redundant_boundaries(self):
        boundaries = {"gpu_acceleration_enabled": True, "terrain_gpu_enabled": True,
                      "vanilla_terrain_enabled": True, "noise_batches_enabled": False,
                      "aquifer_ranking_enabled": True, "corner_graphs_enabled": None}
        report = {"configured_boundaries": boundaries, "compute_service_present": True, "devices": []}
        with self.assertRaisesRegex(RuntimeError, "corner_graphs_enabled"):
            validate_worldgen_consumption(report, report, "rtx3070", corner_graphs=True)
        boundaries["corner_graphs_enabled"] = True
        boundaries["noise_batches_enabled"] = True
        with self.assertRaisesRegex(RuntimeError, "redundant leaf-noise"):
            validate_worldgen_consumption(report, report, "rtx3070", corner_graphs=True)


if __name__ == "__main__":
    unittest.main()
