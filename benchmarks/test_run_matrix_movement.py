import json
import argparse
import hashlib
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest

from run_matrix import (Controller, DEFAULT_INITIAL_HEAP_GIB, add_runtime_arguments,
                        build_java_command, parse_initial_heap_gib, render_gpur_config)


def telemetry_phase(target, percent):
    selected = [index for index in range(target)
                if (index + 1) * percent // 100 > index * percent // 100]
    movers = len(selected)
    selected_set = set(selected)
    players = [
        {
            'uuid': f'00000000-0000-0000-0000-{index + 1:012d}',
            'name': f'GPurBot{index:04d}',
            'samples': 20,
            'nonzero_displacement_samples': 20,
            'accumulated_from_to_distance_blocks': 2.0,
            'first_nonzero_from': {'x': 0.0, 'y': 64.0, 'z': 0.0},
            'last_nonzero_to': {'x': 1.0, 'y': 64.0, 'z': 1.0},
        }
        for index in selected
    ]
    players.extend({
        'uuid': f'00000000-0000-0000-0000-{index + 1:012d}',
        'name': f'GPurBot{index:04d}',
        'samples': 0,
        'nonzero_displacement_samples': 0,
        'accumulated_from_to_distance_blocks': 0.0,
        'first_nonzero_from': None,
        'last_nonzero_to': None,
    } for index in range(target) if index not in selected_set)
    return {
        'phase': 'players-walk',
        'configured_walking_percent': percent,
        'players_at_start': target,
        'players_at_end': target,
        'distinct_mover_count': movers,
        'players': players,
    }


class MovementValidationTests(unittest.TestCase):
    def run_validation(self, phase, target=50, percent=20):
        temporary = TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        directory = Path(temporary.name)
        summary = directory / 'summary.json'
        summary.write_text('{}', encoding='utf-8')
        (directory / 'movement-telemetry.json').write_text(json.dumps({
            'schema': 1,
            'configured_walking_percent': percent,
            'phases': [phase],
        }), encoding='utf-8')
        controller = Controller.__new__(Controller)
        controller.directory = directory
        controller.args = SimpleNamespace(walking_percent=percent)
        controller.movement_phases_to_validate = [('players-walk', target)]
        controller.metadata = {}
        return controller, summary

    def test_twenty_percent_requires_server_observed_expected_movers_and_locations(self):
        controller, summary = self.run_validation(telemetry_phase(50, 20))
        controller.validate_movement_evidence(summary)
        result = json.loads((controller.directory / 'movement-validation.json').read_text(encoding='utf-8'))
        self.assertTrue(result['pass'])
        self.assertEqual(result['phases'][0]['expected_distinct_movers'], 10)
        self.assertEqual(result['phases'][0]['observed_distinct_movers'], 10)
        self.assertEqual(result['phases'][0]['expected_mover_names'],
                         [f'GPurBot{index:04d}' for index in range(4, 50, 5)])
        self.assertEqual(len(result['phases'][0]['observed_movers']), 10)

    def test_client_selection_without_server_displacement_fails(self):
        phase = telemetry_phase(50, 20)
        phase['distinct_mover_count'] = 9
        controller, summary = self.run_validation(phase)
        with self.assertRaisesRegex(RuntimeError, 'server-observed movement validation failed'):
            controller.validate_movement_evidence(summary)
        result = json.loads((controller.directory / 'movement-validation.json').read_text(encoding='utf-8'))
        self.assertFalse(result['pass'])

    def test_mover_without_uuid_name_or_from_to_evidence_fails(self):
        phase = telemetry_phase(50, 20)
        phase['players'][0]['last_nonzero_to'] = None
        controller, summary = self.run_validation(phase)
        with self.assertRaisesRegex(RuntimeError, 'server-observed movement validation failed'):
            controller.validate_movement_evidence(summary)

    def test_wrong_clients_moving_fails_even_when_mover_count_matches(self):
        phase = telemetry_phase(50, 20)
        phase['players'][0]['name'] = 'GPurBot0000'
        controller, summary = self.run_validation(phase)
        with self.assertRaisesRegex(RuntimeError, 'server-observed movement validation failed'):
            controller.validate_movement_evidence(summary)
        result = json.loads((controller.directory / 'movement-validation.json').read_text(encoding='utf-8'))
        self.assertTrue(any('configured bot selection' in failure['error'] for failure in result['failures']))


class RunnerConfigurationTests(unittest.TestCase):
    def test_initial_heap_defaults_to_sixteen_and_keeps_fixed_maximum(self):
        parser = argparse.ArgumentParser()
        add_runtime_arguments(parser)
        defaults = parser.parse_args([])
        self.assertEqual(DEFAULT_INITIAL_HEAP_GIB, 16)
        self.assertEqual(defaults.initial_heap_gib, 16)
        self.assertEqual(parse_initial_heap_gib('4'), 4)
        command = build_java_command(Path('C:/jdk'), Path('C:/validation/server.jar'), 4)
        self.assertIn('-Xms4G', command)
        self.assertIn('-Xmx16G', command)

    def test_initial_heap_validation_rejects_non_integer_and_out_of_range(self):
        for invalid in ('zero', '0', '17', '-1'):
            with self.subTest(value=invalid), self.assertRaises(argparse.ArgumentTypeError):
                parse_initial_heap_gib(invalid)

    def test_worldgen_mode_defaults_off_and_preserves_historical_gpu_config(self):
        parser = argparse.ArgumentParser()
        add_runtime_arguments(parser)
        args = parser.parse_args([])
        self.assertEqual(args.worldgen_mode, 'disabled')
        cpu = render_gpur_config('cpu', False, args.worldgen_mode)
        gpu = render_gpur_config('rtx3070', False, args.worldgen_mode)
        self.assertIn('enabled: false', cpu)
        self.assertIn('enabled: true', gpu)
        self.assertNotIn('vanilla-terrain:', cpu)
        self.assertNotIn('vanilla-terrain:', gpu)

    def test_worldgen_modes_enable_both_boundaries_only_on_gpu_cases(self):
        strict_gpu = render_gpur_config('rtx3070', False, 'strict')
        exact_gpu = render_gpur_config('mixed', False, 'verified-exact')
        strict_cpu = render_gpur_config('cpu', False, 'strict')
        self.assertIn('enabled: true\n    mode: strict', strict_gpu)
        self.assertIn('noise-batches: true', strict_gpu)
        self.assertIn('aquifer-ranking: true', strict_gpu)
        self.assertIn('verify-every-batch: true', strict_gpu)
        self.assertIn('mode: verified-exact', exact_gpu)
        self.assertIn('verify-every-batch: false', exact_gpu)
        self.assertIn('enabled: false\n    mode: disabled', strict_cpu)
        self.assertIn('noise-batches: false', strict_cpu)
        self.assertIn('aquifer-ranking: false', strict_cpu)
        self.assertIn('enabled: false', strict_cpu)

    def test_case_metadata_records_heap_mode_and_unfrozen_runtime(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            paths = {key: root / f'{key}.bin'
                     for key in ('jar', 'plugin', 'viaversion', 'viabackwards', 'eula', 'mojang')}
            for key, path in paths.items():
                path.write_bytes(f'fixture-{key}'.encode())
            hashes = {}
            for key, path in paths.items():
                with path.open('rb') as stream:
                    hashes[key] = hashlib.file_digest(stream, 'sha256').hexdigest()
            args = SimpleNamespace(
                output=root / 'output', _artifact_hashes=hashes, **paths,
                seed=123, initial_heap_gib=4, worldgen_mode='strict',
                view=6, simulation=4, force=False, targets=[50, 150, 300],
                walking_percent=20, no_natural=False, natural_seconds=0,
                seconds=90, travel_seconds=20, port=25620,
            )
            controller = Controller(args, 'gtx1080')
            metadata = json.loads((controller.directory / 'run.json').read_text(encoding='utf-8'))
            self.assertEqual(metadata['heap_gib'], 16)
            self.assertEqual(metadata['initial_heap_gib'], 4)
            self.assertIn('-Xms4G', metadata['jvm_options'])
            self.assertIn('-Xmx16G', metadata['jvm_options'])
            self.assertEqual(metadata['worldgen_mode_requested'], 'strict')
            self.assertEqual(metadata['vanilla_terrain_mode'], 'strict')
            self.assertEqual(metadata['simulation_mode'], 'normal-world-ticks')
            self.assertFalse(metadata['freeze_simulation'])
            config = (controller.directory / 'gpur.yml').read_text(encoding='utf-8')
            self.assertIn('noise-batches: true', config)
            self.assertIn('aquifer-ranking: true', config)


if __name__ == '__main__':
    unittest.main()
