import subprocess
import sys
import os
import glob
import json
import time
import shutil

"""Parameter"""
N_START = 2
P_START = 1
K = 3
TIMEOUT_PER_RUN = 120
STARTUP_DELAY = 0.05

RING_SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "process.py")


def run_ring(n):
    """Startet Ring; dict mit Statistiken """
    statsdir = os.path.join("/tmp", f"feuerwerk_n{n}")
    if os.path.exists(statsdir):
        shutil.rmtree(statsdir)
    os.makedirs(statsdir)

    procs = []
    fail_reason = None
    try:
        start_order = list(range(1, n)) + [0]  # 0 als letztes wegen Token
        for pid in start_order:
            p = subprocess.Popen(
                [sys.executable, RING_SCRIPT, str(pid), str(n), str(P_START), str(K), "--statsdir", statsdir],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            procs.append(p)
            time.sleep(STARTUP_DELAY)

        """Auf Terminierung aller Prozesse warten"""
        deadline = time.time() + TIMEOUT_PER_RUN
        for p in procs:
            remaining = deadline - time.time()
            if remaining <= 0:
                fail_reason = "Timeout"
                raise TimeoutError
            try:
                p.wait(timeout=remaining)
            except subprocess.TimeoutExpired:
                fail_reason = "Timeout"
                raise TimeoutError

    except TimeoutError:
        for p in procs:
            if p.poll() is None:
                p.kill()
        print(f"Fehlschlag-Grund: {fail_reason}", file=sys.stderr)
        return None
    except OSError as e:
        for p in procs:
            if p.poll() is None:
                p.kill()
        print(f"Fehlschlag-Grund: OSError ({e})", file=sys.stderr)
        return None
    finally:
        for p in procs:
            if p.poll() is None:
                p.kill()

    # Abgestürzte Prozesse erkennen
    crashed = [p.returncode for p in procs if p.returncode not in (0, None)]
    if crashed:
        print(f"Fehlschlag-Grund: {len(crashed)} Prozess(e) mit Returncode != 0", file=sys.stderr)
        return None

    """Statistiken einlesen"""
    stats_files = glob.glob(os.path.join(statsdir, "stats_*.json"))
    if len(stats_files) != n:
        print(f"Fehlschlag-Grund: nur {len(stats_files)}/{n} Statistik-Dateien", file=sys.stderr)
        return None

    total_multicasts = 0
    total_rounds = 0
    round_times = []
    for path in stats_files:
        with open(path) as f:
            s = json.load(f)
        total_multicasts += s.get("multicasts", 0)
        if s["pid"] == 0:
            total_rounds = s.get("total_rounds", 0)
            round_times = s.get("round_times_ms", [])

    return {
        "n": n,
        "total_rounds": total_rounds,
        "total_multicasts": total_multicasts,
        "min_ms": min(round_times) if round_times else 0.0,
        "avg_ms": sum(round_times) / len(round_times) if round_times else 0.0,
        "max_ms": max(round_times) if round_times else 0.0,
    }


def main():
    results = []
    max_n_ok = None

    # Phase 1: Verdopplung bis n=256
    n = N_START
    while n <= 256:
        print(f"\n-- Experiment n = {n}")
        r = run_ring(n)
        if r is None:
            print(" FEHLGESCHLAGEN -> Abbruch.")
            _print_results(results, max_n_ok)
            return
        results.append(r)
        max_n_ok = n
        _print_ok(r)
        n *= 2

    # Phase 2: Binäre Suche, um möglichst genau maximales n zu ermitteln
    print(f"\n BINÄRE SUCHE")
    lo, hi = 256, 512
    while lo < hi - 1:
        mid = (lo + hi) // 2
        print(f"\n-- Experiment n = {mid}")
        r = run_ring(mid)
        if r is None:
            print(f"FEHLGESCHLAGEN -> Grenze liegt unter {mid}")
            hi = mid
        else:
            print(f"OK -> Grenze liegt über {mid}")
            lo = mid
            max_n_ok = mid
            results.append(r)
            _print_ok(r)

    _print_results(results, max_n_ok)


def _print_ok(r):
    print(f"OK: {r['total_rounds']} Runden, "
          f"{r['total_multicasts']} Multicasts, "
          f"Rundenzeit min/avg/max = "
          f"{r['min_ms']:.2f}/{r['avg_ms']:.2f}/{r['max_ms']:.2f} ms")


def _print_results(results, max_n_ok):
    print("\n" + "=" * 84)
    print("ERGEBNISSE")
    print("=" * 84)
    header = f"{'n':>5} | {'Runden':>8} | {'Multicasts':>11} | {'min ms':>9} | {'avg ms':>9} | {'max ms':>9}"
    print(header)
    print("-" * len(header))
    for r in sorted(results, key=lambda x: x["n"]):
        print(
            f"{r['n']:>5} | {r['total_rounds']:>8} | {r['total_multicasts']:>11} | {r['min_ms']:>9.2f} | {r['avg_ms']:>9.2f} | {r['max_ms']:>9.2f}")
    print("-" * len(header))

    if max_n_ok is not None:
        print(f"\nMaximales erfolgreiches n: {max_n_ok}")
    else:
        print("\nKein einziger Lauf war erfolgreich.")

    with open("ergebnisse.json", "w") as f:
        json.dump({"max_n": max_n_ok, "results": results}, f, indent=2)
    print("Detaillierte Statistiken in ergebnisse.json gespeichert.")


if __name__ == "__main__":
    main()
