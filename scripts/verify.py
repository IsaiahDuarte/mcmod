"""Verify documentation, tooling, and the real JVM implementation gates.

Documentation checks use a documented Markdown subset. Gradle may download
dependencies and writes build output. Review still owns documentation correctness.
"""

from pathlib import Path
import re
import os
import subprocess
import sys
import unittest
from urllib.parse import unquote, urlsplit


REQUIRED_FILES = (
    "README.md",
    "SPEC.md",
    "AGENTS.md",
    "docs/ENGINEERING.md",
    "docs/TESTING.md",
    "docs/IMPLEMENTATION.md",
    "docs/LOGISTICSNETWORKS.md",
    "docs/REVIEW.md",
    "docs/decisions/README.md",
    ".github/pull_request_template.md",
    ".github/workflows/quality.yml",
    "scripts/verify.py",
    "tests/tooling/test_verify.py",
    "build.gradle",
    "settings.gradle",
    "gradle.lockfile",
    "gradlew",
    "gradlew.bat",
    "gradle/wrapper/gradle-wrapper.jar",
    "gradle/wrapper/gradle-wrapper.properties",
    "config/pmd/ruleset.xml",
    "scripts/verify_guest.py",
    "tests/tooling/test_verify_guest.py",
    "experiments/wasm-guest/Cargo.toml",
    "experiments/wasm-guest/Cargo.lock",
    "experiments/wasm-guest/rust-toolchain.toml",
)
IGNORED_DIRS = {
    ".git", ".agents", ".codex", ".venv", "__pycache__", ".gradle",
    "build", "target", "node_modules", "run",
}
UNWIRED_SOURCE_SUFFIXES = {".kt", ".kts", ".rs"}
INLINE_LINK = re.compile(
    r'!?\[[^\]\n]*\]\((<[^>\n]+>|[^)\s]+)(?:\s+"[^"\n]*")?\)'
)
CONFLICT = re.compile(r"^(?:<{7}|={7}|>{7})(?:\s|$)", re.MULTILINE)
FENCE = re.compile(r"^\s{0,3}([" + chr(96) + r"]{3,}|~{3,})(.*)$")
ACCEPTANCE_ROW = re.compile(r"^\|\s*(A\d{2})\s*\|", re.MULTILINE)


def repository_files(root: Path):
    """Walk owned files without traversing generated or symlinked trees."""
    for entry in sorted(root.iterdir()):
        if entry.is_symlink():
            continue
        if entry.is_dir():
            if entry.name not in IGNORED_DIRS:
                yield from repository_files(entry)
        elif entry.is_file():
            yield entry


def parse_fences(document: str) -> tuple[str, bool]:
    """Return visible prose and whether every opened code fence was closed."""
    lines = []
    fence_character = None
    fence_length = 0
    for line in document.splitlines():
        marker = FENCE.match(line)
        if marker:
            run, tail = marker.groups()
            if fence_character is None:
                fence_character, fence_length = run[0], len(run)
                continue
            if run[0] == fence_character and len(run) >= fence_length and not tail.strip():
                fence_character = None
                continue
        if fence_character is None:
            lines.append(line)
    return "\n".join(lines), fence_character is None


def check_acceptance_tracking(root: Path) -> list[str]:
    """Require a unique matching checklist, without claiming its evidence passed."""
    tables = {}
    errors = []
    for name in ("SPEC.md", "docs/IMPLEMENTATION.md"):
        path = root / name
        if not path.is_file():
            continue  # Required-file check owns this error.
        try:
            prose, _ = parse_fences(path.read_text(encoding="utf-8-sig"))
        except UnicodeError:
            continue  # Markdown check owns this error.
        identifiers = ACCEPTANCE_ROW.findall(prose)
        if not identifiers:
            errors.append(f"{name}: missing nonempty acceptance-ID table.")
        if len(set(identifiers)) != len(identifiers):
            errors.append(f"{name}: duplicate acceptance IDs.")
        tables[name] = set(identifiers)
    if len(tables) == 2:
        spec_ids = tables["SPEC.md"]
        plan_ids = tables["docs/IMPLEMENTATION.md"]
        for identifier in sorted(spec_ids - plan_ids):
            errors.append(f"Acceptance {identifier}: missing from implementation plan.")
        for identifier in sorted(plan_ids - spec_ids):
            errors.append(f"Acceptance {identifier}: missing from spec.")
    return errors


def verify(root: Path) -> list[str]:
    """Return actionable validation errors for a repository root."""
    root = root.resolve()
    errors = []
    for name in REQUIRED_FILES:
        path = root / name
        if not path.is_file():
            errors.append(f"Missing required file: {name}")
        elif path.stat().st_size == 0:
            errors.append(f"Empty required file: {name}")

    for path in repository_files(root):
        relative = path.relative_to(root).as_posix()
        if path.suffix.lower() in UNWIRED_SOURCE_SUFFIXES and not (
            path.suffix.lower() == ".rs" and path.is_relative_to(root / "experiments/wasm-guest/src")
        ):
            errors.append(
                f"{relative}: implementation verification is not configured. "
                "Wire real build/static/architecture/test gates before removing "
                "the bootstrap guard; see docs/TESTING.md."
            )
        if path.suffix.lower() != ".md":
            continue
        try:
            document = path.read_text(encoding="utf-8-sig")
        except UnicodeError:
            errors.append(f"{relative}: Markdown must be UTF-8.")
            continue
        if not document.strip():
            errors.append(f"{relative}: empty Markdown document.")
        visible, closed = parse_fences(document)
        if not closed:
            errors.append(f"{relative}: unclosed code fence hides trailing content.")
        if not re.search(r"^# \S", visible, re.MULTILINE):
            errors.append(f"{relative}: missing level-one document title.")
        if CONFLICT.search(document):
            errors.append(f"{relative}: unresolved merge conflict marker.")
        for match in INLINE_LINK.finditer(visible):
            destination = match.group(1).strip("<>")
            try:
                parsed = urlsplit(destination)
            except ValueError:
                errors.append(f"{relative}: malformed link: {destination}")
                continue
            if parsed.scheme or parsed.netloc or not parsed.path:
                continue
            target = (path.parent / unquote(parsed.path)).resolve()
            if not target.is_relative_to(root):
                errors.append(f"{relative}: local link escapes repository: {destination}")
            elif not target.exists():
                errors.append(f"{relative}: broken local link: {destination}")
    errors.extend(check_acceptance_tracking(root))
    return errors


def run_tooling_tests(root: Path, stream=None) -> bool:
    """Run required tests; zero tests or skipped tests cannot produce a pass."""
    stream = sys.stderr if stream is None else stream
    suite = unittest.TestLoader().discover(str(root / "tests/tooling"))
    if suite.countTestCases() == 0:
        print("FAIL: tooling test collection is empty.", file=stream)
        return False
    result = unittest.TextTestRunner(stream=stream, verbosity=2).run(suite)
    if result.skipped:
        print("FAIL: required tooling tests were skipped.", file=stream)
    return result.wasSuccessful() and not result.skipped


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    errors = verify(root)
    if errors:
        for error in errors:
            print(f"FAIL: {error}", file=sys.stderr)
        return 1
    if not run_tooling_tests(root):
        return 1
    if not run_implementation_checks(root):
        return 1
    print("PASS: documentation, acceptance tracking, tooling, and JVM build/checks.")
    print("Client/server startup and product acceptance require separate recorded evidence.")
    return 0


def run_implementation_checks(root: Path, stream=None) -> bool:
    """Build runs compilation, packaging, formatting, PMD and nonempty JVM tests."""
    stream = sys.stderr if stream is None else stream
    wrapper = root / ("gradlew.bat" if os.name == "nt" else "gradlew")
    try:
        result = subprocess.run([str(wrapper), "--no-daemon", "build"], cwd=root, check=False)
    except OSError as error:
        print(f"FAIL: cannot execute required JVM verification: {error}", file=stream)
        return False
    if result.returncode:
        print(f"FAIL: JVM verification exited {result.returncode}.", file=stream)
        return False
    return True


if __name__ == "__main__":
    raise SystemExit(main())
