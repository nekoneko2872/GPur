import json
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest

from run_matrix import Controller


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


if __name__ == '__main__':
    unittest.main()
