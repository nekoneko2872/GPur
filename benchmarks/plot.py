"""Plot retained tick data, without smoothing or dropping setup spikes."""
import argparse
import csv
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--runs', type=Path, required=True, help='Parent of recorded run directories')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    report = json.loads(args.report.read_text(encoding='utf-8'))
    figure, axes = plt.subplots(len(report['runs']), 1, figsize=(13, 3 * len(report['runs'])), squeeze=False)
    for axis, run in zip(axes[:, 0], report['runs']):
        path = args.runs / run['run_name'] / 'plugins/GPurBench/runs'
        files = sorted(path.glob('*/ticks.csv'))
        if len(files) != 1:
            raise ValueError(f'Expected one measurement record: {path}')
        with files[0].open(encoding='utf-8', newline='') as stream:
            rows = list(csv.DictReader(stream))
        elapsed = [float(row['elapsed_ms']) / 1000 for row in rows]
        durations = [float(row['mspt']) for row in rows]
        axis.scatter(elapsed, durations, s=2, color='#176c9b', rasterized=True)
        axis.axhline(50, color='#bf392b', linewidth=1, label='50 ms budget')
        axis.set_yscale('log')
        axis.set_ylabel('Reported MSPT (log scale)')
        axis.set_xlabel('Seconds after measurement plugin enabled')
        axis.set_title(f"{run['case']}: walking {run.get('walking_percent', 100)}%; completed={run['completed']}; {run['all']['ticks_over_50_ms']} ticks >50 ms")
        axis.grid(True, which='major', alpha=.2)
        axis.legend(loc='upper right')
    figure.suptitle('GPur 26.2 / i9-11900KF / 16 GiB heap / fresh worlds / real TCP players', fontsize=13)
    figure.tight_layout()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    figure.savefig(args.output, dpi=160)
    plt.close(figure)


if __name__ == '__main__':
    main()
