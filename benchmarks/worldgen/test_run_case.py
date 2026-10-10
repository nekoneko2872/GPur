"""Unit checks for parsing the terrain-use gate from gpur status detail."""

import unittest
from pathlib import Path

from run_case import build_java_command, extract_status, validate_worldgen_consumption


class StatusParsingTests(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
