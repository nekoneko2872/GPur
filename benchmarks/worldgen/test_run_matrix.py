"""Release-matrix reference validation tests."""

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from run_matrix import invoke_case, validate_strict_reference


class StrictReferenceTests(unittest.TestCase):
    runtime = {"jdk_home": "C:/jdk25", "java_sha256": "java-hash", "worker_threads": 4,
               "io_threads": 2, "heap_gib": 16, "initial_heap_gib": 16,
               "jvm_options": ["--enable-native-access=ALL-UNNAMED", "-Xms16G", "-Xmx16G", "-XX:+UseG1GC"]}

    def make_report(self, root: Path):
        cases = {}
        for name in ("cpu", "rtx3070", "gtx1080", "mixed"):
            cases[name] = {"pass": True, "exit_code": 0, "mode": "strict", "jar_sha256": "candidate-hash",
                           "case": name, "seed": 42, "center_chunk": [8, 9], "radius_chunks": 4,
                           "require_structure_starts": False,
                           "server_heap_gib": self.runtime["heap_gib"],
                           "initial_heap_gib": self.runtime["initial_heap_gib"],
                           "jvm_options": self.runtime["jvm_options"],
                           "worker_threads": self.runtime["worker_threads"],
                           "io_threads": self.runtime["io_threads"], "jdk_home": self.runtime["jdk_home"],
                           "java_sha256": self.runtime["java_sha256"]}
        report = {
            "pass": True,
            "mode": "strict",
            "source_jar_sha256": "candidate-hash",
            "corpus": {"seed": 42, "center_chunk": [8, 9], "radius_chunks": 4,
                       "expected_chunks": 81, "require_structure_starts": False},
            "runtime": self.runtime,
            "cases": cases,
            "semantic_parity": {name: {"pass": True} for name in ("rtx3070", "gtx1080", "mixed")},
        }
        path = root / "strict-matrix.json"
        path.write_text(json.dumps(report), encoding="utf-8")
        return path

    def test_matching_passing_strict_matrix_is_accepted(self):
        corpus = {"seed": 42, "center_chunk": [8, 9], "radius_chunks": 4,
                  "expected_chunks": 81, "require_structure_starts": False}
        with tempfile.TemporaryDirectory() as directory:
            path = self.make_report(Path(directory))
            result = validate_strict_reference(path, "candidate-hash", ["cpu", "rtx3070", "gtx1080", "mixed"], corpus, self.runtime)
        self.assertEqual(result["mode"], "strict")
        self.assertEqual(len(result["sha256"]), 64)

    def test_matrix_passes_explicit_parity_dump_path_to_each_case_only_when_enabled(self):
        args = SimpleNamespace(
            candidate_jar=Path("C:/validation/candidate.jar"), jar=Path("C:/validation/candidate.jar"),
            mode="strict", probe=Path("C:/validation/probe.jar"), mojang=Path("C:/validation/mojang.jar"),
            eula=Path("C:/validation/eula.txt"), jdk=Path("C:/jdk"), port=25620, seed=42,
            center_x=8, center_z=9, radius=4, startup_timeout=300, phase_timeout=3600,
            require_structures=False, parity_dump_dir=Path("C:/GPur-validation-20261009/validation/parity-captures"),
            initial_heap_gib=4,
        )
        completed = __import__("subprocess").CompletedProcess([], 0, 'WORLDGEN_CASE_RESULT {"pass":true}\n', "")
        with patch("run_matrix.subprocess.run", return_value=completed) as run:
            invoke_case(args, Path("C:/GPur-validation-20261009/validation/matrices/matrix-1"), "rtx3070")
            command = run.call_args.args[0]
        heap_index = command.index("--initial-heap-gib")
        self.assertEqual(command[heap_index + 1], "4")
        index = command.index("--parity-dump-dir")
        self.assertEqual(Path(command[index + 1]),
                         Path("C:/GPur-validation-20261009/validation/parity-captures/matrix-1/rtx3070"))

        args.parity_dump_dir = None
        with patch("run_matrix.subprocess.run", return_value=completed) as run:
            invoke_case(args, Path("C:/GPur-validation-20261009/validation/matrices/matrix-1"), "cpu")
            command = run.call_args.args[0]
        self.assertNotIn("--parity-dump-dir", command)

    def test_reference_rejects_different_candidate_corpus_or_failed_semantics(self):
        corpus = {"seed": 42, "center_chunk": [8, 9], "radius_chunks": 4,
                  "expected_chunks": 81, "require_structure_starts": False}
        with tempfile.TemporaryDirectory() as directory:
            path = self.make_report(Path(directory))
            with self.assertRaisesRegex(ValueError, "JAR SHA-256"):
                validate_strict_reference(path, "other-hash", ["cpu", "rtx3070", "gtx1080", "mixed"], corpus, self.runtime)
            with self.assertRaisesRegex(ValueError, "corpus"):
                validate_strict_reference(path, "candidate-hash", ["cpu", "rtx3070", "gtx1080", "mixed"],
                                          {**corpus, "seed": 43}, self.runtime)
            with self.assertRaisesRegex(ValueError, "JDK/JVM/worker"):
                validate_strict_reference(path, "candidate-hash", ["cpu", "rtx3070", "gtx1080", "mixed"],
                                          corpus, {**self.runtime, "worker_threads": 8})
            report = json.loads(path.read_text(encoding="utf-8"))
            report["semantic_parity"]["mixed"]["pass"] = False
            path.write_text(json.dumps(report), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "semantic parity"):
                validate_strict_reference(path, "candidate-hash", ["cpu", "rtx3070", "gtx1080", "mixed"], corpus, self.runtime)

    def test_strict_reference_rejects_case_jvm_flag_list_mismatch(self):
        corpus = {"seed": 42, "center_chunk": [8, 9], "radius_chunks": 4,
                  "expected_chunks": 81, "require_structure_starts": False}
        with tempfile.TemporaryDirectory() as directory:
            path = self.make_report(Path(directory))
            report = json.loads(path.read_text(encoding="utf-8"))
            report["cases"]["rtx3070"]["jvm_options"] = [
                "--enable-native-access=ALL-UNNAMED", "-Xms4G", "-Xmx32G", "-XX:+UseG1GC",
            ]
            path.write_text(json.dumps(report), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "JVM options"):
                validate_strict_reference(path, "candidate-hash", ["cpu", "rtx3070", "gtx1080", "mixed"],
                                          corpus, self.runtime)

    def test_four_gib_initial_heap_with_sixteen_gib_max_is_valid_strict_reference(self):
        corpus = {"seed": 42, "center_chunk": [8, 9], "radius_chunks": 4,
                  "expected_chunks": 81, "require_structure_starts": False}
        self.runtime = {**self.runtime, "initial_heap_gib": 4,
                        "jvm_options": ["--enable-native-access=ALL-UNNAMED", "-Xms4G", "-Xmx16G", "-XX:+UseG1GC"]}
        with tempfile.TemporaryDirectory() as directory:
            path = self.make_report(Path(directory))
            result = validate_strict_reference(path, "candidate-hash", ["cpu", "rtx3070", "gtx1080", "mixed"],
                                               corpus, self.runtime)
        self.assertEqual(result["mode"], "strict")

    def test_matrix_cli_rejects_initial_heap_above_maximum(self):
        script = Path(__file__).with_name("run_matrix.py")
        completed = subprocess.run([sys.executable, str(script), "--initial-heap-gib", "17"],
                                   capture_output=True, text=True, encoding="utf-8")
        self.assertEqual(completed.returncode, 2)
        self.assertIn("--initial-heap-gib must be in 1..16", completed.stderr)


if __name__ == "__main__":
    unittest.main()
