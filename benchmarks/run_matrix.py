"""Isolated, fresh-world, real-network-client GPur benchmark orchestration.

Only loopback servers are launched. Runtime data never belongs in the Git tree.
"""
from pathlib import Path
from collections import deque
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import threading
import time
import traceback
import psutil

ROOT = Path(__file__).resolve().parents[1]
JDK = Path(os.environ.get('JAVA_HOME', str(Path.home() / '.jdks/openjdk-25.0.2')))
ANSI = re.compile(r'\x1b\[[0-9;]*[A-Za-z]')
CREATE_FLAGS = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
DEVICES = {'cpu': [], 'rtx3070': ['NVIDIA GeForce RTX 3070'],
           'gtx1080': ['NVIDIA GeForce GTX 1080'], 'mixed': ['auto']}


def emit(**value):
    print(json.dumps({'utc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()), **value}), flush=True)


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


class Controller:
    def __init__(self, args, case):
        self.args, self.case = args, case
        stamp = time.strftime('%Y%m%d-%H%M%S', time.gmtime()) + f'-{time.time_ns() % 1_000_000:06d}'
        self.directory = args.output.resolve() / 'runs' / f'{stamp}-{case}'
        self.directory.mkdir(parents=True, exist_ok=False)
        self.lines, self.lock = deque(maxlen=12000), threading.Lock()
        self.fleets, self.clients = [], {}
        self.stop_monitor = threading.Event()
        self.server = None
        self.expected_players = 0
        self.expected_mobs = 0
        self.started = time.monotonic()
        self.metadata = {'case': case, 'jar_sha256': digest(args.jar), 'seed': args.seed,
                         'plugin_sha256': digest(args.plugin),
                         'viaversion_sha256': digest(args.viaversion), 'viabackwards_sha256': digest(args.viabackwards),
                         'fresh_directory': str(self.directory), 'heap_gib': 16,
                         'view_distance': args.view, 'simulation_distance': args.simulation,
                         'client_view_distance': args.view, 'force_gpu_diagnostic': args.force,
                         'player_targets': args.targets, 'walking_percent': args.walking_percent,
                         'natural_spawn_phase': not args.no_natural,
                         'natural_phase_seconds': args.natural_seconds or args.seconds,
                         'travel_seconds_at_20tps': args.travel_seconds,
                         'settings': 'normal terrain workload, HIDE Anti-Xray, full AI, protected synthetic players'}
        self.prepare()

    def prepare(self):
        directory = self.directory
        # Copy an already accepted EULA supplied in the user's original archive.
        shutil.copy2(self.args.eula, directory / 'eula.txt')
        (directory / 'cache').mkdir()
        shutil.copy2(self.args.mojang, directory / 'cache/mojang_26.2.jar')
        plugins = directory / 'plugins'
        (plugins / 'bStats').mkdir(parents=True)
        (plugins / 'bStats/config.yml').write_text('enabled: false\n', encoding='utf-8')
        for artifact in (self.args.plugin, self.args.viaversion, self.args.viabackwards):
            shutil.copy2(artifact, plugins / artifact.name)
        (directory / 'server.properties').write_text(
            f'server-ip=127.0.0.1\nserver-port={self.args.port}\nonline-mode=false\nmax-players=350\n'
            f'view-distance={self.args.view}\nsimulation-distance={self.args.simulation}\n'
            'gamemode=survival\ndifficulty=normal\nspawn-protection=0\nallow-nether=false\n'
            f'level-seed={self.args.seed}\nlevel-type=minecraft:flat\nlevel-name=lobby\n'
            'generator-settings={"layers":[{"height":1,"block":"minecraft:bedrock"},{"height":2,"block":"minecraft:dirt"},{"height":1,"block":"minecraft:grass_block"}],"biome":"minecraft:plains","features":false,"lakes":false}\n'
            'generate-structures=true\nnetwork-compression-threshold=256\n', encoding='utf-8')
        # The clients share one loopback IP; IP-based join throttling is disabled solely for that fixture.
        (directory / 'bukkit.yml').write_text('settings:\n  connection-throttle: 0\n  allow-end: false\n', encoding='utf-8')
        config = directory / 'config'
        config.mkdir()
        (config / 'paper-global.yml').write_text('_version: 31\nchunk-system:\n  worker-threads: 4\n  io-threads: 2\n', encoding='utf-8')
        (config / 'paper-world-defaults.yml').write_text('_version: 31\nanticheat:\n  anti-xray:\n    enabled: true\n    engine-mode: 1\n', encoding='utf-8')
        enabled = 'false' if self.case == 'cpu' else 'true'
        selectors = json.dumps(DEVICES[self.case])
        (directory / 'gpur.yml').write_text(
            f'config-version: 2\nchunk-generation:\n  gpu-acceleration:\n    enabled: {enabled}\n'
            f'gpu:\n  multi-gpu:\n    enabled: {str(self.case == "mixed").lower()}\n    devices: {selectors}\n'
            f'  force: {str(self.args.force).lower()}\n  timeout-ms: 100\n', encoding='utf-8')
        (directory / 'run.json').write_text(json.dumps(self.metadata, indent=2), encoding='utf-8')

    def capture_server(self):
        with (self.directory / 'server.log').open('w', encoding='utf-8') as output:
            for raw in self.server.stdout:
                output.write(raw)
                output.flush()
                line = ANSI.sub('', raw).strip()
                with self.lock:
                    self.lines.append(line)
                # Full dumps remain in server.log; stdout carries milestones and
                # failure headlines so an overloaded server cannot flood the UI.
                if any(s in line for s in ('GPURBENCH_', 'Exact compute ready:', 'GPU rejected:',
                        'Done (', 'Exception', 'The server has not responded', 'Encountered an unexpected')):
                    emit(type='server', case=self.case, message=line[:2400])

    def start(self):
        command = [str(JDK / 'bin/java.exe' if os.name == 'nt' else JDK / 'bin/java'),
                   '--enable-native-access=ALL-UNNAMED', '-Xms16G', '-Xmx16G',
                   '-XX:+UseG1GC', '-XX:MaxGCPauseMillis=40',
                   '-Xlog:gc*,safepoint:file=gc.log:time,uptime,level,tags',
                   '-jar', str(self.args.jar), '--nogui']
        self.metadata['java_command'] = command
        self.server = subprocess.Popen(command, cwd=self.directory, stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8',
            errors='replace', creationflags=CREATE_FLAGS)
        self.server_reader = threading.Thread(target=self.capture_server)
        self.server_reader.start()
        self.monitor = threading.Thread(target=self.monitor_resources)
        self.monitor.start()
        self.gpu_monitor_process = subprocess.Popen(
            ['nvidia-smi', '--query-gpu=index,name,utilization.gpu,memory.used,power.draw,temperature.gpu',
             '--format=csv,noheader,nounits', '-l', '1'],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8',
            errors='replace', creationflags=CREATE_FLAGS)

        def gpu_capture():
            with (self.directory / 'gpu-device.csv').open('w', encoding='utf-8') as output:
                output.write('epoch_ms,index,name,gpu_percent,memory_mib,power_watts,temperature_c\n')
                for line in self.gpu_monitor_process.stdout:
                    output.write(str(int(time.time() * 1000)) + ',' + line)
                    output.flush()

        self.gpu_monitor_reader = threading.Thread(target=gpu_capture)
        self.gpu_monitor_reader.start()
        self.wait(lambda: self.contains('Done (') and self.contains('GPURBENCH_READY'), 240, 'server ready')
        with self.lock:
            admitted = [line for line in self.lines if 'Exact compute ready:' in line]
        expected = 0 if self.case == 'cpu' else 2 if self.case == 'mixed' else 1
        if len(admitted) != expected:
            raise RuntimeError(f'GPU admission mismatch: expected={expected}, actual={admitted}')
        self.metadata['admitted_devices'] = admitted
        self.command('world load ' + str(self.args.seed) + ' normal', timeout=180)
        self.command('phase idle-baseline')
        self.pause(10, 'idle baseline')

    def contains(self, marker):
        with self.lock:
            return any(marker in line for line in self.lines)

    def wait(self, condition, timeout, reason):
        deadline = time.monotonic() + timeout
        last = 0
        while not condition():
            if (self.directory / 'ABORT').exists():
                raise RuntimeError('operator abort: ' + (self.directory / 'ABORT').read_text(encoding='utf-8'))
            if self.server.poll() is not None:
                raise RuntimeError(f'server exited {self.server.returncode}: {reason}')
            if self.contains('GPURBENCH_INVALID'):
                raise RuntimeError('invalid measurement plugin')
            if time.monotonic() >= deadline:
                raise TimeoutError(reason)
            if time.monotonic() - last >= 15:
                emit(type='waiting', case=self.case, reason=reason)
                last = time.monotonic()
            time.sleep(.2)

    def command(self, command, timeout=240):
        token = command.split()[0]
        marker = 'GPURBENCH_DONE command=' + token + ' '
        with self.lock:
            self.lines.clear()
        self.server.stdin.write('gpurbench ' + command + '\n')
        self.server.stdin.flush()
        self.wait(lambda: self.contains(marker) or self.contains('GPURBENCH_ERROR'), timeout, command)
        if self.contains('GPURBENCH_ERROR'):
            raise RuntimeError('benchmark command failed: ' + command)

    def pause(self, seconds, reason):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if (self.directory / 'ABORT').exists():
                raise RuntimeError('operator abort: ' + (self.directory / 'ABORT').read_text(encoding='utf-8'))
            if self.server.poll() is not None:
                raise RuntimeError('server stopped during ' + reason)
            time.sleep(min(1, max(.01, deadline - time.monotonic())))

    def fleet(self, count, offset):
        command = ['node', str(ROOT / 'benchmarks/bots/fleet.cjs'), '--count', str(count),
                   '--start-index', str(offset), '--port', str(self.args.port), '--workers', '2',
                   '--ramp', str(self.args.ramp), '--view-distance', str(self.args.view),
                   '--walking-percent', str(self.args.walking_percent),
                   '--action', 'idle', '--duration', '0']
        process = subprocess.Popen(command, cwd=ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace', creationflags=CREATE_FLAGS)
        index = len(self.fleets)
        self.fleets.append(process)

        def read():
            with (self.directory / f'clients-{offset}.jsonl').open('w', encoding='utf-8') as output:
                for line in process.stdout:
                    output.write(line)
                    output.flush()
                    try:
                        event = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    if event.get('type') in ('fleet_stats', 'fleet_final'):
                        self.clients[index] = event
                    if event.get('type') in ('client_error', 'client_kicked', 'worker_exit', 'control_error'):
                        emit(type='client', case=self.case, event=event)

        thread = threading.Thread(target=read)
        thread.start()
        process.reader = thread
        self.wait(lambda: self.clients.get(index, {}).get('spawned', 0) == count
                  and self.clients.get(index, {}).get('play', 0) == count,
                  max(180, count / self.args.ramp + 120), f'{count} network players joining')
        emit(type='clients_ready', case=self.case, added=count,
             total=sum(s.get('live', 0) for s in self.clients.values()))

    def client_action(self, action):
        for process in self.fleets:
            process.stdin.write(json.dumps({'command': 'action', 'action': action}) + '\n')
            process.stdin.flush()

    def measure(self, name, seconds):
        self.command('phase ' + name)
        for process in self.fleets:
            process.stdin.write(json.dumps({'command': 'phase', 'phase': name}) + '\n')
            process.stdin.flush()
        emit(type='phase', case=self.case, phase=name, seconds=seconds)
        self.pause(seconds, name)
        self.command('status')
        with self.lock:
            status = next((line for line in self.lines if 'GPURBENCH_STATUS ' in line), '')
        observed = re.search(r'online=(\d+) bench=(\d+).*synthetic=(\d+)', status)
        if not observed or tuple(map(int, observed.groups())) != (self.expected_players, self.expected_players, self.expected_mobs):
            raise RuntimeError('fixture population mismatch: ' + status)
        if 'writer_error=none' not in status:
            raise RuntimeError('measurement writer failure: ' + status)
        if any(s.get('unexpectedLosses', 0) or s.get('errors', 0) or s.get('kicks', 0) for s in self.clients.values()):
            raise RuntimeError('client connection loss or protocol error invalidated phase: ' + name)

    def run(self):
        self.start()
        current = 0
        targets = [10] if self.args.pilot else self.args.targets
        for target in targets:
            # Remove fixtures while the old player areas are still loaded. Moving
            # the grid first can unload mobs, leaving them behind in saved chunks.
            if current:
                self.client_action('idle')
                self.command('remove')
                self.expected_mobs = 0
            self.command('phase network-ramp-' + str(target))
            self.fleet(target - current, current)
            current = target
            self.expected_players = target
            self.command('place ' + str(self.args.spacing), timeout=600)
            self.command('removenatural', timeout=600)
            self.client_action('walk')
            self.measure(f'players-{target}-settle', 10 if self.args.pilot else self.args.settle_seconds)
            self.measure(f'players-{target}-walk', 10 if self.args.pilot else self.args.seconds)
            self.client_action('idle')
            self.command('remove')
            self.command(f'spawn {target * 10} 24 20', timeout=240)
            self.expected_mobs = target * 10
            self.client_action('walk')
            self.measure(f'players-{target}-mobs-{target * 10}-settle', 10 if self.args.pilot else self.args.settle_seconds)
            self.measure(f'players-{target}-mobs-{target * 10}', 15 if self.args.pilot else self.args.seconds)
            if target >= 150 and not self.args.no_natural:
                self.command('natural true')
                self.measure(f'players-{target}-mobs-natural', self.args.natural_seconds or self.args.seconds)
                self.command('natural false')
                self.command('removenatural', timeout=600)
        if not self.args.pilot:
            self.command('redstone 300', timeout=120)
            self.measure(f'players-{current}-mobs-{current * 10}-redstone-300', self.args.seconds)
            self.client_action('idle')
            self.command(f'travel {self.args.travel_seconds} 4.317 east', timeout=1800)
            self.measure('post-generation-recovery', 45)
        self.command('status')
        self.command('finish', timeout=60)
        reports = sorted((self.directory / 'plugins/GPurBench/runs').glob('*/summary.json'))
        if not reports:
            raise RuntimeError('plugin summary not found')
        self.metadata['measurement_summary'] = str(reports[-1])
        self.metadata['clients_at_finish'] = dict(self.clients)
        # The built-in profiler has already sampled the entire run. Export only
        # after closing the tick record, so serializing it cannot pollute MSPT.
        self.server.stdin.write('spark profiler stop --save-to-file\n')
        self.server.stdin.flush()
        try:
            self.wait(lambda: any((self.directory / 'plugins/spark').rglob('*.sparkprofile')), 90, 'local profiler export')
            self.metadata['local_profiles'] = [str(path.relative_to(self.directory))
                for path in (self.directory / 'plugins/spark').rglob('*.sparkprofile')]
        except TimeoutError as error:
            self.metadata['profiler_export_error'] = str(error)
        self.metadata['duration_seconds'] = time.monotonic() - self.started
        self.metadata['completed'] = True

    def monitor_resources(self):
        process = psutil.Process(self.server.pid)
        process.cpu_percent()
        with (self.directory / 'resources.jsonl').open('w', encoding='utf-8') as output:
            while not self.stop_monitor.wait(1):
                try:
                    event = {'epoch_ms': int(time.time() * 1000), 'server_cpu_core_percent': process.cpu_percent(),
                             'server_rss_bytes': process.memory_info().rss, 'server_threads': process.num_threads(),
                             'host_cpu_percent': psutil.cpu_percent(), 'host_memory_available': psutil.virtual_memory().available,
                             'server_io': process.io_counters()._asdict()}
                    output.write(json.dumps(event) + '\n')
                    output.flush()
                except (psutil.NoSuchProcess, psutil.AccessDenied):
                    return

    def close(self):
        # Freeze pre-teardown telemetry even for a failed load. Disconnects caused
        # by shutting down the fixture must not overwrite the failure evidence.
        self.metadata.setdefault('clients_at_finish', json.loads(json.dumps(self.clients)))
        self.metadata.setdefault('duration_seconds', time.monotonic() - self.started)
        for process in self.fleets:
            if process.poll() is None:
                try:
                    process.stdin.write('{"command":"stop"}\n')
                    process.stdin.flush()
                    process.wait(timeout=15)
                except (OSError, subprocess.TimeoutExpired):
                    process.kill()
                    process.wait()
            process.reader.join(timeout=5)
        if self.server is not None:
            if self.server.poll() is None:
                try:
                    self.server.stdin.write('stop\n')
                    self.server.stdin.flush()
                    self.server.wait(timeout=120)
                except (OSError, subprocess.TimeoutExpired):
                    self.server.kill()
                    self.server.wait()
            self.metadata['server_exit'] = self.server.returncode
            self.server_reader.join(timeout=5)
            reports = sorted((self.directory / 'plugins/GPurBench/runs').glob('*/summary.json'))
            if reports:
                self.metadata.setdefault('measurement_summary', str(reports[-1]))
            self.stop_monitor.set()
            self.monitor.join(timeout=5)
            if hasattr(self, 'gpu_monitor_process'):
                self.gpu_monitor_process.terminate()
                self.gpu_monitor_process.wait(timeout=10)
                self.gpu_monitor_reader.join(timeout=5)
        (self.directory / 'result.json').write_text(json.dumps(self.metadata, indent=2), encoding='utf-8')
        emit(type='case_result', case=self.case, path=str(self.directory),
             completed=self.metadata.get('completed', False), error=self.metadata.get('error'))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cases', default='cpu,rtx3070,gtx1080,mixed')
    parser.add_argument('--pilot', action='store_true')
    parser.add_argument('--force', action='store_true')
    parser.add_argument('--seconds', type=int, default=90)
    parser.add_argument('--targets', type=lambda value: [int(x) for x in value.split(',')], default=[50, 150, 300])
    parser.add_argument('--walking-percent', type=int, default=100)
    parser.add_argument('--no-natural', action='store_true')
    parser.add_argument('--natural-seconds', type=int, default=0, help='0 uses --seconds; otherwise10..1800')
    parser.add_argument('--settle-seconds', type=int, default=45)
    parser.add_argument('--travel-seconds', type=int, default=20, help='Synthetic travel seconds at20TPS;1..120')
    parser.add_argument('--view', type=int, default=6)
    parser.add_argument('--simulation', type=int, default=4)
    parser.add_argument('--spacing', type=int, default=96)
    parser.add_argument('--ramp', type=float, default=5)
    parser.add_argument('--seed', type=int, default=1196459378)
    parser.add_argument('--port', type=int, default=25620)
    parser.add_argument('--output', type=Path, default=Path('C:/GPur-validation-20261007'))
    parser.add_argument('--jar', type=Path, default=ROOT / 'gpur-server/build/libs/gpur-server-1.0.0-SNAPSHOT.jar')
    parser.add_argument('--plugin', type=Path, default=ROOT / 'validation/benchmark-plugin/GPurBench.jar')
    parser.add_argument('--viaversion', type=Path, default=ROOT / 'validation/bots/ViaVersion-5.12.1-SNAPSHOT-build1469.jar')
    parser.add_argument('--viabackwards', type=Path, default=Path.home() / 'Downloads/ViaBackwards-5.12.1-SNAPSHOT.jar')
    parser.add_argument('--eula', type=Path, default=ROOT / 'validation/eula.txt', help='Existing accepted EULA file; never synthesized')
    parser.add_argument('--mojang', type=Path, default=ROOT / 'validation/smoke-gpu-final/cache/mojang_26.2.jar')
    args = parser.parse_args()
    for key in ('jar', 'plugin', 'viaversion', 'viabackwards', 'eula', 'mojang'):
        if not getattr(args, key).is_file():
            parser.error(f'{key} input file not found; supply --{key}')
    cases = args.cases.split(',')
    if any(case not in DEVICES for case in cases):
        parser.error('cases: cpu,rtx3070,gtx1080,mixed')
    if not (10 <= args.seconds <= 1800 and 2 <= args.view <= 12 and 2 <= args.simulation <= args.view):
        parser.error('seconds10..1800; view2..12; simulation2..view')
    if (not args.targets or args.targets != sorted(set(args.targets))
            or not all(10 <= target <= 300 for target in args.targets)
            or not 0 <= args.walking_percent <= 100 or not 10 <= args.settle_seconds <= 120
            or args.natural_seconds != 0 and not 10 <= args.natural_seconds <= 1800
            or not 1 <= args.travel_seconds <= 120):
        parser.error('targets must increase within10..300; walking-percent0..100; settle-seconds10..120')
    output = args.output.resolve()
    if 'validation' not in output.name.lower():
        parser.error('output directory must have validation in its name')
    failed = False
    for case in cases:
        controller = Controller(args, case)
        try:
            controller.run()
        except Exception as error:
            controller.metadata['error'] = repr(error)
            traceback.print_exc()
            failed = True
        finally:
            controller.close()
    if failed:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
