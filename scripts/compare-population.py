#!/usr/bin/env python3
"""Run the same real-world soak on two isolated Git revisions (Linux/macOS, Java 17).
No source branch is modified. Example from the repository root:
  python3 scripts/compare-population.py --counts 100 200 300 --repeats 1 --output ../rlclones-comparison
The probe samples Minecraft's reported average tick duration and used JVM heap,
not raw per-tick p95 or process RSS. It runs only the soak; the full regression
suite is a separate gate. World seed and test duration match, but AI randomness
means repeated runs are necessary. A fresh world is used for every run.
"""
import argparse
import csv
import json
import math
import os
from pathlib import Path
import re
import statistics
import shutil
import signal
import subprocess
import tempfile

SOURCE = Path("src/main/java/com/rlclones/gametest/CloneGameTests.java")
PROBE_NAMESPACE = "rlclones_population_probe"
PROBE = """package com.rlclones.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("rlclones_population_probe")
@PrefixGameTestTemplate(false)
public final class PopulationSoakProbe {
    @GameTest(template = "arena", timeoutTicks = 300600, batch = "population-probe")
    public static void populationSoak(GameTestHelper h) {
        CloneGameTests.soakHowFarCloneGetsOnItsOwn(h);
    }
}
"""
START = "public static void soakHowFarCloneGetsOnItsOwn(GameTestHelper h)"
ANCHOR = "            tick[0]++;\n            try {"
INSTRUMENT = """            tick[0]++;
            if (tick[0] > 400 && tick[0] % 20 == 0) {
                RLClones.LOGGER.info("SOAK-METRIC tick={} mspt={} heapBytes={}", tick[0],
                        manager(h).tickMillis(),
                        java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            }
            try {"""
METRIC = re.compile(r"SOAK-METRIC tick=(\d+) mspt=([0-9.eE+-]+) heapBytes=(\d+)")
SUMMARY = re.compile(r"SOAK-SUMMARY ticks=(\d+) clones=(\d+) gone=(\d+) full_diamond_armor=(\d+)/(\d+) errors=(\d+) reached=(.*)")
SITE = re.compile(r"SOAK start at (.*?) with (\d+) clones, (\d+) ticks")


def git(repo, *args):
    return subprocess.check_output(["git", "-C", str(repo), *args], text=True).strip()


def instrument(work):
    path = work / SOURCE
    source = path.read_text(encoding="utf-8")
    before, sep, after = source.partition(START)
    if not sep or after.count(ANCHOR) != 1:
        raise RuntimeError("Soak instrumentation anchor changed; inspect both revisions.")
    path.write_text(before + sep + after.replace(ANCHOR, INSTRUMENT, 1), encoding="utf-8")
    # Select only the existing soak, not the hundreds of other regression tests.
    # Both checked-out revisions receive identical temporary wrapper and template.
    path.with_name("PopulationSoakProbe.java").write_text(PROBE, encoding="utf-8")
    data = work / "src/main/resources/data"
    template = data / PROBE_NAMESPACE / "structures/arena.nbt"
    template.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(data / "rlclones/structures/arena.nbt", template)


def parse(log, count, ticks):
    summary = SUMMARY.findall(log)
    sites = SITE.findall(log)
    rows = [(int(t), float(mspt), int(heap)) for t, mspt, heap in METRIC.findall(log)]
    expected = list(range(420, ticks + 1, 20))
    if len(summary) != 1 or len(sites) != 1:
        raise RuntimeError("Missing or repeated soak start/summary; see saved logs.")
    duration, spawned, gone, armor, denominator, errors, reached = summary[0]
    site, start_count, start_ticks = sites[0]
    if (int(spawned), int(duration), int(denominator), int(start_count), int(start_ticks)) != (count, ticks, count, count, ticks):
        raise RuntimeError("Requested population or duration did not match actual run.")
    if [t for t, _, _ in rows] != expected or not rows:
        raise RuntimeError("Missing or duplicate MSPT samples.")
    values = sorted(mspt for _, mspt, _ in rows)
    if not all(math.isfinite(x) and x >= 0 for x in values):
        raise RuntimeError("Invalid MSPT samples.")
    heaps = [heap for _, _, heap in rows]
    return {
        "site": site, "spawned": count, "ticks": ticks, "samples": len(rows),
        "mean_sampled_mspt": round(statistics.mean(values), 3),
        "p95_sampled_mspt": round(values[math.ceil(len(values) * .95) - 1], 3),
        "mean_sampled_heap_mib": round(statistics.mean(heaps) / 1048576, 3),
        "max_sampled_heap_mib": round(max(heaps) / 1048576, 3),
        "gone": int(gone), "full_diamond_armor": int(armor), "errors": int(errors),
        "reached": reached, "prof": next((x.split("SOAK-PROF ", 1)[1] for x in log.splitlines() if "SOAK-PROF " in x), ""),
    }


def run_one(repo, sha, output, count, ticks, seed, timeout):
    output.mkdir(parents=True, exist_ok=False)
    with tempfile.TemporaryDirectory(prefix="rlclones-population-") as tmp:
        work = Path(tmp) / "checkout"
        git(repo, "worktree", "add", "--detach", str(work), sha)
        try:
            instrument(work)
            run = work / "run"
            run.mkdir(exist_ok=True)
            (run / "soak.flag").write_text(f"{ticks}\nclones {count}\n", encoding="utf-8")
            (run / "server.properties").write_text(f"level-seed={seed}\n", encoding="utf-8")
            with (output / "console.log").open("w", encoding="utf-8") as f:
                try:
                    proc = subprocess.Popen(
                        ["bash", "./gradlew", "--no-daemon", "-Drlclones.gameTestNamespaces=" + PROBE_NAMESPACE, "runGameTestServer"],
                        cwd=work, stdout=f, stderr=subprocess.STDOUT, start_new_session=True,
                    )
                    try:
                        returncode = proc.wait(timeout=timeout)
                    except BaseException:
                        # Stop the isolated Gradle/JVM process group before removing its worktree.
                        try:
                            os.killpg(proc.pid, signal.SIGTERM)
                            proc.wait(timeout=15)
                        except subprocess.TimeoutExpired:
                            os.killpg(proc.pid, signal.SIGKILL)
                            proc.wait()
                        except ProcessLookupError:
                            pass
                        raise
                finally:
                    latest = run / "logs" / "latest.log"
                    if latest.exists():
                        (output / "latest.log").write_bytes(latest.read_bytes())
            if returncode:
                # Preserve complete artifacts, but also expose the final diagnostics in CI
                # so a baseline infrastructure failure cannot be mistaken for a regression.
                from collections import deque
                for name in ("console.log", "latest.log"):
                    log = output / name
                    if log.exists():
                        print(f"--- {name} (last 60 lines) ---", flush=True)
                        with log.open(encoding="utf-8", errors="replace") as stream:
                            for line in deque(stream, maxlen=60):
                                print(line.rstrip()[:2000], flush=True)
                raise RuntimeError(f"Gradle exited {returncode}; see {output}")
            latest = output / "latest.log"
            if not latest.exists():
                raise RuntimeError(f"No server log: {output}")
            result = parse(latest.read_text(encoding="utf-8", errors="replace"), count, ticks)
            result["sha"] = sha
            (output / "metrics.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
            return result
        finally:
            git(repo, "worktree", "remove", "--force", str(work))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--baseline", default="23b71eaee9077217cf4db888692db52b11288275")
    p.add_argument("--candidate", default="HEAD")
    p.add_argument("--counts", type=int, nargs="+", default=[100, 200, 300])
    p.add_argument("--ticks", type=int, default=2400)
    p.add_argument("--repeats", type=int, default=2)
    p.add_argument("--seed", default="rlclones")
    p.add_argument("--timeout", type=int, default=7200, help="seconds per Gradle process")
    p.add_argument("--output", type=Path, required=True, help="new output directory outside temporary worktrees")
    args = p.parse_args()
    if not 600 <= args.ticks <= 300000 or args.ticks % 200 or args.repeats < 1 or args.timeout < 1:
        p.error("ticks must be a multiple of 200 within 600..300000; repeats and timeout must be positive")
    if any(not 1 <= n <= 400 for n in args.counts) or len(set(args.counts)) != len(args.counts):
        p.error("counts must be distinct and within 1..400")
    if "\n" in args.seed or "\r" in args.seed:
        p.error("seed must be a single line")
    repo = Path(git(Path.cwd(), "rev-parse", "--show-toplevel"))
    refs = {name: git(repo, "rev-parse", "--verify", ref + "^{commit}") for name, ref in
            (("baseline", args.baseline), ("candidate", args.candidate))}
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    with (out / "settings.json").open("w", encoding="utf-8") as f:
        json.dump({"refs": refs, "counts": args.counts, "ticks": args.ticks,
                   "seed": args.seed, "repeats": args.repeats}, f, indent=2)
    results = []
    for count in args.counts:
        for repeat in range(args.repeats):
            pair = {}
            order = ["baseline", "candidate"] if repeat % 2 == 0 else ["candidate", "baseline"]
            for name in order:
                dest = out / f"{count}-{repeat+1}-{name}"
                print(f"Running {dest.name} at {refs[name]}", flush=True)
                pair[name] = run_one(repo, refs[name], dest, count, args.ticks, args.seed, args.timeout)
            if pair["baseline"]["site"] != pair["candidate"]["site"]:
                raise RuntimeError("Different soak sites; refusing to compare this pair.")
            for name in ("baseline", "candidate"):
                results.append({"version": name, "repeat": repeat + 1, **pair[name]})
            with (out / "comparison.csv").open("w", newline="", encoding="utf-8") as f:
                writer = csv.DictWriter(f, fieldnames=list(results[0]))
                writer.writeheader()
                writer.writerows(results)
    print(f"Completed {len(results)} real soak runs. See {out / 'comparison.csv'}")


if __name__ == "__main__":
    main()
