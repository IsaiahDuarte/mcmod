"""Compare the prior production HashMap ledger with the current catalog implementation."""
import argparse
import hashlib
import os
from pathlib import Path
import platform
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
BASELINE = "d36268b"
SOURCE = "src/main/java/dev/izzy/factorycore/core/resource/ResourceLedger.java"
MAIN = "dev.izzy.factorycore.core.resource.LedgerCatalogBenchmark"


def capture(command):
    result = subprocess.run(command, cwd=ROOT, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, check=True)
    return result.stdout.strip()


def run():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    classpath = [ROOT / "build/classes/java/main", ROOT / "build/classes/java/test"]
    if not all(path.is_dir() for path in classpath):
        raise SystemExit("Run ./gradlew testClasses with JDK 21 first.")
    baseline_source = capture(["git", "show", f"{BASELINE}:{SOURCE}"])
    lines = [f"platform={platform.platform()} machine={platform.machine()}",
             capture(["java", "-version"]), f"baseline_commit={BASELINE}",
             f"current_ledger_sha256={hashlib.sha256((ROOT / SOURCE).read_bytes()).hexdigest()}",
             "heap=256MiB initial/512MiB maximum; one process per version; owner-thread samples",
             "workload=10000 registry-only keys; 2048 same-ID/shared-prefix 4096-byte component keys",
             "mutation=existing-key insert/extract; churn=whole-type removal/recreation; first-page=16 exact stock rows",
             "warmup=50 copy/sort; 10000 per mutation/churn case; current additionally 10000 catalog pages"]
    with tempfile.TemporaryDirectory(prefix="factorycore-ledger-baseline-") as temporary:
        path = Path(temporary)
        source = path / "ResourceLedger.java"
        source.write_text(baseline_source + "\n", encoding="utf-8")
        classes = path / "classes"
        classes.mkdir()
        capture(["javac", "-cp", str(classpath[0]), "-d", str(classes), str(source)])
        for label, paths, arguments in [("baseline", [classes] + classpath, ["baseline"]),
                                         ("current", classpath, [])]:
            lines.append(f"version={label}")
            lines.append(capture(["java", "-Xms256m", "-Xmx512m", "-cp",
                                  os.pathsep.join(map(str, paths)), MAIN] + arguments))
    report = "\n".join(lines) + "\n"
    print(report, end="")
    if args.output:
        args.output.write_text(report, encoding="utf-8")


if __name__ == "__main__":
    run()
