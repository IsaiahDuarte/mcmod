"""Build/check the factory_v1 Rust SDK and its example guests; no in-game Rust compiler."""

from pathlib import Path
import os
import re
import shutil
import subprocess
import sys


def nonempty_unskipped_tests(output: str) -> bool:
    summaries = re.findall(
        r"test result: ok\. (\d+) passed; (\d+) failed; (\d+) ignored; (\d+) measured; (\d+) filtered out",
        output,
    )
    return (sum(int(row[0]) for row in summaries) > 0
            and all(all(int(value) == 0 for value in row[1:]) for row in summaries))


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    common = ["cargo", "+1.95.0"]
    sdk = root / "sdk/rust/Cargo.toml"
    sdk_target = root / "build/rust-sdk"
    sdk_environment = dict(os.environ, CARGO_TARGET_DIR=str(sdk_target))
    wasm_flags = ["-C", "link-arg=--max-memory=2097152", "-C", "link-arg=--initial-memory=131072",
                  "-C", "link-arg=-zstack-size=65536",
                  "-C", "target-feature=-reference-types,-multivalue,-bulk-memory,-simd128,-nontrapping-fptoint"]
    sdk_commands = [
        common + ["fmt", "--manifest-path", str(sdk), "--all", "--", "--check"],
        common + ["clippy", "--manifest-path", str(sdk), "--locked", "--workspace", "--all-targets", "--", "-D", "warnings"],
        common + ["clippy", "--manifest-path", str(sdk), "--locked", "--workspace", "--release", "--lib",
                  "--target", "wasm32-unknown-unknown", "--", "-D", "warnings"],
        common + ["test", "--manifest-path", str(sdk), "--locked", "--workspace"],
        common + ["rustc", "--manifest-path", str(sdk), "--locked", "-p", "factorycore-smelter", "--release",
                  "--target", "wasm32-unknown-unknown", "--crate-type", "cdylib", "--"] + wasm_flags,
        common + ["rustc", "--manifest-path", str(sdk), "--locked", "-p", "factorycore-counter", "--release",
                  "--target", "wasm32-unknown-unknown", "--crate-type", "cdylib", "--"] + wasm_flags,
    ]
    for command in sdk_commands:
        print("SDK CHECK:", " ".join(command), flush=True)
        testing = command[2] == "test"
        try:
            result = subprocess.run(command, cwd=root, env=sdk_environment, check=False,
                                    capture_output=testing, text=testing)
        except OSError as error:
            print(f"FAIL: missing Rust build tool: {error}", file=sys.stderr)
            return 1
        if testing:
            print(result.stdout, end="")
            print(result.stderr, end="", file=sys.stderr)
        if result.returncode:
            return result.returncode
        if testing and not nonempty_unskipped_tests(result.stdout):
            print("FAIL: required SDK tests must be nonempty and unskipped.", file=sys.stderr)
            return 1
    smelter = sdk_target / "wasm32-unknown-unknown/release/factorycore_smelter.wasm"
    if not smelter.is_file() or smelter.stat().st_size < 8:
        print("FAIL: SDK example did not produce a nonempty Wasm artifact.", file=sys.stderr)
        return 1
    counter = sdk_target / "wasm32-unknown-unknown/release/factorycore_counter.wasm"
    if not counter.is_file() or counter.stat().st_size < 8:
        print("FAIL: SDK counter example did not produce a nonempty Wasm artifact.", file=sys.stderr)
        return 1
    (root / "build/test-guests").mkdir(parents=True, exist_ok=True)
    shutil.copyfile(smelter, root / "build/test-guests/smelter.wasm")
    shutil.copyfile(counter, root / "build/test-guests/counter.wasm")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
