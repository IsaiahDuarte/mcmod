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


GUEST_TARGET = "wasm32v1-none"
MAX_ARTIFACT = 131_072  # WasmPreflight.MAX_ARTIFACT
ABI_MODULE = "factory_v1"


def _leb(data: bytes, offset: int) -> tuple[int, int]:
    value = shift = 0
    while True:
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        shift += 7
        if byte < 0x80:
            return value, offset


def _name(data: bytes, offset: int) -> tuple[str, int]:
    length, offset = _leb(data, offset)
    return data[offset:offset + length].decode("utf-8"), offset + length


def wasm_import_modules(data: bytes) -> list[str]:
    """Module names of every import in a Wasm binary (raises ValueError if malformed)."""
    if data[:8] != b"\0asm\x01\0\0\0":
        raise ValueError("not a WebAssembly 1.0 module")
    modules, offset = [], 8
    try:
        while offset < len(data):
            section, offset = data[offset], offset + 1
            size, offset = _leb(data, offset)
            body_end = offset + size
            if section == 2:
                count, cursor = _leb(data, offset)
                for _ in range(count):
                    module, cursor = _name(data, cursor)
                    _, cursor = _name(data, cursor)
                    kind, cursor = data[cursor], cursor + 1
                    if kind == 0:
                        _, cursor = _leb(data, cursor)
                    elif kind in (1, 2):
                        cursor += kind == 1  # table element type
                        flags, cursor = _leb(data, cursor)
                        _, cursor = _leb(data, cursor)
                        if flags & 1:
                            _, cursor = _leb(data, cursor)
                    elif kind == 3:
                        cursor += 2
                    else:
                        raise ValueError(f"unknown import kind {kind}")
                    modules.append(module)
            offset = body_end
    except IndexError as error:
        raise ValueError("truncated module") from error
    return modules


def guest_artifact_problem(data: bytes) -> str | None:
    """Why a built guest would fail deployment before the JVM preflight, or None."""
    if len(data) < 8:
        return "no Wasm artifact was produced"
    if len(data) > MAX_ARTIFACT:
        return f"{len(data)} bytes exceeds the {MAX_ARTIFACT}-byte artifact limit"
    try:
        foreign = sorted(set(wasm_import_modules(data)) - {ABI_MODULE})
    except ValueError as error:
        return str(error)
    if foreign:
        return "imports from modules other than " + ABI_MODULE + ": " + ", ".join(foreign)
    return None


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    common = ["cargo", "+1.95.0"]
    sdk = root / "sdk/rust"
    sdk_target = root / "build/rust-sdk"
    sdk_environment = dict(os.environ, CARGO_TARGET_DIR=str(sdk_target))
    # Run inside the workspace so sdk/rust/.cargo/config.toml supplies the guest link arguments.
    sdk_commands = [
        common + ["fmt", "--all", "--", "--check"],
        common + ["clippy", "--locked", "--workspace", "--all-targets", "--", "-D", "warnings"],
        common + ["clippy", "--locked", "--workspace", "--release", "--lib", "--target", GUEST_TARGET,
                  "--", "-D", "warnings"],
        common + ["test", "--locked", "--workspace"],
        common + ["build", "--locked", "--release", "--target", GUEST_TARGET,
                  "-p", "factorycore-smelter", "-p", "factorycore-counter"],
        # Built alone so its `serde` feature does not unify into the other examples' builds.
        common + ["build", "--locked", "--release", "--target", GUEST_TARGET, "-p", "factorycore-restock"],
    ]
    for command in sdk_commands:
        print("SDK CHECK:", " ".join(command), flush=True)
        testing = command[2] == "test"
        try:
            result = subprocess.run(command, cwd=sdk, env=sdk_environment, check=False,
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
    (root / "build/test-guests").mkdir(parents=True, exist_ok=True)
    for name in ("smelter", "counter", "restock"):
        built = sdk_target / GUEST_TARGET / "release" / f"factorycore_{name}.wasm"
        problem = guest_artifact_problem(built.read_bytes() if built.is_file() else b"")
        if problem:
            print(f"FAIL: SDK example {name}: {problem}", file=sys.stderr)
            return 1
        shutil.copyfile(built, root / f"build/test-guests/{name}.wasm")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
