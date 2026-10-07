"""Reduce retained, unfiltered GPurBench samples into a portable report.

Only explicit run directories are accepted so failed pilots cannot silently enter
a comparison. This tool reads existing measurements and never runs a server.
"""
import argparse
import csv
from datetime import datetime
import json
import math
from pathlib import Path
import re


def percentile(values, p):
    return values[max(0, math.ceil(len(values) * p) - 1)] if values else None


def extent(rows, key):
    values = [float(row[key]) for row in rows if row.get(key) != '']
    return {'min': min(values), 'max': max(values)} if values else None


def reduce_run(directory, include_failed=False):
    result = json.loads((directory / 'result.json').read_text(encoding='utf-8'))
    completed = bool(result.get('completed') and result.get('server_exit') == 0)
    if not completed and not include_failed:
        raise ValueError(f'Incomplete or abnormal run: {directory}')
    if result.get('measurement_summary'):
        summary_path = Path(result['measurement_summary'])
    else:
        reports = list((directory / 'plugins/GPurBench/runs').glob('*/summary.json'))
        if completed or len(reports) != 1:
            raise ValueError(f'Unambiguous measurement summary not found: {directory}')
        summary_path = reports[0]
    summary = json.loads(summary_path.read_text(encoding='utf-8'))
    if not summary.get('finished') or summary['dropped_samples'] or summary['writer_queue_depth']:
        raise ValueError(f'Incomplete measurement: {directory}')
    with summary_path.with_name('ticks.csv').open(encoding='utf-8', newline='') as stream:
        rows = list(csv.DictReader(stream))
    if len(rows) != summary['all']['ticks']:
        raise ValueError(f'CSV/summary sample count mismatch: {directory}')
    groups = {}
    before = {}
    previous = None
    for row in rows:
        key = int(row['phase_id'])
        if key not in groups:
            groups[key] = []
            before[key] = previous
        groups[key].append(row)
        previous = row
    phases = []
    counters = ('redstone_events', 'spawn_events', 'move_events', 'chunk_load_events',
                'damage_events', 'gpu_completed', 'cpu_fallbacks')
    for item in summary['phases']:
        subset = groups.get(item['id'], [])
        durations = sorted(float(row['mspt']) for row in subset)
        if len(subset) != item['ticks'] or (durations and max(durations) != item['max_ms']):
            raise ValueError(f'Phase samples differ: {directory} {item["phase"]}')
        phase = {k: item[k] for k in ('phase', 'start_epoch_ms', 'wall_seconds', 'ticks',
            'actual_wall_tps', 'start_interval_tps', 'p50_ms', 'p95_ms', 'p99_ms', 'mean_ms',
            'max_ms', 'ticks_over_50_ms', 'ticks_over_100_ms', 'gc_collections', 'gc_collection_ms')}
        phase['census_ranges'] = {k: extent(subset, k) for k in ('online_players', 'bench_players',
            'synthetic_mobs', 'mobs', 'in_ticking_chunk_mobs', 'loaded_chunks', 'survey_age_ticks', 'survey_ms')}
        intervals = sorted(float(row['start_interval_ms']) for row in subset if row['start_interval_ms'])
        phase['start_interval_ms'] = {'p99': percentile(intervals, .99), 'max': max(intervals, default=0),
            'over_55_ms': sum(value > 55 for value in intervals), 'over_100_ms': sum(value > 100 for value in intervals)}
        phase['gpu_status_start'] = item.get('metadata', {}).get('gpu_status')
        phase['gpu_status_end'] = item.get('end_metadata', {}).get('gpu_status')
        baseline = before.get(item['id'])
        if subset:
            phase['event_deltas'] = {k: int(subset[-1][k]) - int(baseline[k] if baseline else 0) for k in counters}
        else:
            phase['event_deltas'] = {}
        phases.append(phase)
    pause_values, safepoints = [], []
    with (directory / 'gc.log').open(encoding='utf-8') as stream:
        for line in stream:
            match = re.search(r'\[gc\s*\]\s+GC\(\d+\).*Pause.* ([\d.]+)ms\s*$', line)
            if match:
                pause_values.append(float(match.group(1)))
            match = re.search(r'Total: (\d+) ns', line)
            if match:
                safepoints.append(int(match.group(1)) / 1e6)
    resources = [json.loads(line) for line in (directory / 'resources.jsonl').read_text(encoding='utf-8').splitlines()]
    client_samples = []
    for path in sorted(directory.glob('clients-*.jsonl')):
        for line in path.read_text(encoding='utf-8').splitlines():
            event = json.loads(line)
            if event.get('type') == 'fleet_stats':
                event['epoch_ms'] = datetime.fromisoformat(event['timestamp'].replace('Z', '+00:00')).timestamp() * 1000
                client_samples.append(event)
    for phase in phases:
        start = phase['start_epoch_ms']
        end = start + phase['wall_seconds'] * 1000
        samples = [sample for sample in client_samples if start <= sample['epoch_ms'] < end]
        # Multiple fleets have staggered report instants: keep fleet maxima rather
        # than summing them as though they were simultaneous host CPU readings.
        phase['client_fleet_maxima'] = {k: max((sample.get(k, 0) for sample in samples), default=0)
            for k in ('cpuCorePercent', 'eventLoopP99Ms', 'eventLoopMaxMs', 'rssBytes', 'errors',
                      'kicks', 'unexpectedLosses')}
    if 'clients_at_finish' not in result and completed:
        raise ValueError(f'Run predates retained client snapshots: {directory}')
    clients = list(result.get('clients_at_finish', {}).values())
    client_report = {k: sum(client.get(k, 0) for client in clients) for k in
        ('live', 'play', 'spawned', 'survival', 'everSpawned', 'errors', 'kicks', 'unexpectedLosses',
         'receivedBytes', 'sentBytes', 'movementPackets', 'teleportConfirms')}
    # Client statistics fields are retained verbatim to preserve the generator's
    # own CPU/event-loop limitations and distinguish traffic from an SMP replay.
    return {'case': result['case'], 'run_name': directory.name,
            'completed': completed, 'server_exit': result['server_exit'], 'error': result.get('error'),
            'duration_seconds': result.get('duration_seconds', summary['all']['wall_seconds']),
            'walking_percent': result.get('walking_percent', 100),
            'player_targets': result.get('player_targets', [50, 150, 300]),
            'natural_spawn_phase': result.get('natural_spawn_phase', True),
            'natural_phase_seconds': result.get('natural_phase_seconds'),
            'travel_seconds_at_20tps': result.get('travel_seconds_at_20tps', 20),
            'record_end_without_tick_ms': max(0, summary['all']['start_epoch_ms']
                + summary['all']['wall_seconds'] * 1000 - int(rows[-1]['epoch_ms'])) if rows else None,
            'tick_record_note': 'A tick that never completes has no TickEnd sample. Failure and the final gap must be considered alongside reported MSPT.',
            'jar_sha256': result['jar_sha256'], 'seed': result['seed'],
            'plugin_sha256': result['plugin_sha256'], 'viaversion_sha256': result['viaversion_sha256'],
            'viabackwards_sha256': result['viabackwards_sha256'],
            'heap_gib': result['heap_gib'], 'view_distance': result['view_distance'],
            'simulation_distance': result['simulation_distance'],
            'force_gpu_diagnostic': result['force_gpu_diagnostic'],
            'admitted_devices': result['admitted_devices'], 'clients': client_report,
            'client_fleets_at_finish': clients,
            'all': {k: v for k, v in summary['all'].items() if k not in ('metadata', 'end_metadata')},
            'end_metadata': summary['all']['end_metadata'], 'phases': phases,
            'stopped_world_gc_pause_ms': {'count': len(pause_values), 'max': max(pause_values, default=0),
                                         'total': sum(pause_values)},
            'safepoint_total_ms': {'count': len(safepoints), 'max': max(safepoints, default=0)},
            'resources': {k: {'max': max(row[k] for row in resources),
                             'mean': sum(row[k] for row in resources) / len(resources)} for k in
                          ('server_cpu_core_percent', 'server_rss_bytes', 'host_cpu_percent')},
            'zero_over_50_ms': completed and summary['all']['ticks_over_50_ms'] == 0}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('runs', type=Path, nargs='+')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--include-failed', action='store_true', help='Retain failed outcomes explicitly; never count them as passing comparisons')
    args = parser.parse_args()
    report = {'schema': 1, 'method': 'Every recorded tick retained; nearest-rank percentiles; no spike removal.',
              'runs': [reduce_run(path.resolve(), args.include_failed) for path in args.runs]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print('case,phase,ticks,wall_tps,p50_ms,p95_ms,p99_ms,max_ms,over50,over100')
    for run in report['runs']:
        for phase in run['phases']:
            print(','.join(str(x) for x in (run['case'], phase['phase'], phase['ticks'],
                round(phase['actual_wall_tps'], 3), phase['p50_ms'], phase['p95_ms'],
                phase['p99_ms'], phase['max_ms'], phase['ticks_over_50_ms'], phase['ticks_over_100_ms'])))


if __name__ == '__main__':
    main()
