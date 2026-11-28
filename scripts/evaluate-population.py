#!/usr/bin/env python3
"""Conservative screen of paired population soak CSVs, not a correctness proof."""
import argparse
import csv
import math
import statistics
from pathlib import Path

def evaluate(paths, min_gain=0.02, max_heap_growth=0.05):
    reports = []
    for path in paths:
        with open(path, newline='', encoding='utf-8') as f:
            rows = list(csv.DictReader(f))
        pairs = {}
        for r in rows:
            key = (r['spawned'], r['ticks'], r['site'], r['repeat'])
            version = r['version']
            if version not in ('baseline', 'candidate') or version in pairs.setdefault(key, {}):
                raise ValueError(f'{path}: duplicate or unknown version {key} {version}')
            pairs[key][version] = r
        if len(pairs) < 2 or any(set(p) != {'baseline','candidate'} for p in pairs.values()):
            raise ValueError(f'{path}: need at least two complete paired runs')
        if len({k[:3] for k in pairs}) != 1:
            raise ValueError(f'{path}: mixed population, duration or site')
        def avg(version, metric):
            vals = [float(p[version][metric]) for p in pairs.values()]
            if not all(math.isfinite(x) and x >= 0 for x in vals):
                raise ValueError(f'{path}: invalid {metric}')
            return statistics.mean(vals)
        def change(metric):
            a, b = avg('baseline', metric), avg('candidate', metric)
            if a <= 0: raise ValueError(f'{path}: zero baseline {metric}')
            return b/a - 1
        mspt=change('mean_sampled_mspt')
        p95=change('p95_sampled_mspt')
        heap=change('mean_sampled_heap_mib')
        errors=avg('candidate','errors')
        gone=avg('candidate','gone')-avg('baseline','gone')
        reasons=[]
        if mspt > -min_gain: reasons.append('mean MSPT gain below threshold')
        if p95 > 0: reasons.append('sampled p95 increased')
        if heap > max_heap_growth: reasons.append('mean sampled heap increased above threshold')
        if errors > 0: reasons.append('candidate errors')
        if gone > 0: reasons.append('more missing/dead clones on average')
        reports.append((path, mspt, p95, heap, reasons))
    return reports

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('csv',nargs='+',type=Path)
    p.add_argument('--min-gain',type=float,default=0.02)
    p.add_argument('--max-heap-growth',type=float,default=0.05)
    a=p.parse_args()
    if not (0<=a.min_gain<1 and a.max_heap_growth>=0): p.error('invalid thresholds')
    if len(a.csv)<2: p.error('provide CSVs from at least two independent seed runs')
    if len({x.resolve() for x in a.csv})!=len(a.csv): p.error('duplicate CSV')
    try: reports=evaluate(a.csv,a.min_gain,a.max_heap_growth)
    except (ValueError,KeyError,OSError) as e: p.error(str(e))
    for path,mspt,p95,heap,reasons in reports:
        print(f'{path}: MSPT {mspt:+.2%}, sampled p95 {p95:+.2%}, heap {heap:+.2%}: '+('; '.join(reasons) or 'passes performance screen'))
    passed=all(not x[-1] for x in reports)
    print('PASS performance screen only; review full regressions and AI outcomes separately.' if passed else 'HOLD: performance screen not met.')
    return 0 if passed else 1

if __name__=='__main__': raise SystemExit(main())
