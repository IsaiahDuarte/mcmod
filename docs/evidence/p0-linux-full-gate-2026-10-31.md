# P0 — Full release gate on Linux (2026-10-31)

## Environment

- Revision: `d7cd59d` (main).
- OS: Linux 7.2.11-zen1-1-zen, x86_64.
- JDK: OpenJDK 21.0.13 (`JAVA_HOME=~/.local/jdk-21`).
- Rust: 1.95.0-x86_64-unknown-linux-gnu.
- Optional mods: none.

## Command

```
export JAVA_HOME=$HOME/.local/jdk-21 PATH=$HOME/.local/jdk-21/bin:$PATH
python3 scripts/verify.py --full
```

## Observed

- `PASS: documentation, acceptance tracking, tooling, and JVM build/checks (full release checks).`
- `BUILD SUCCESSFUL in 4m 11s` (Gradle daemon reused).
- `All 487 required tests passed` (GameTest server), then `All 1 required tests passed` (release
  server suite).
- The two-JVM interruption suite SIGKILLed the seed server and passed all three restore modes:
  `kill`, `data-old`, `chunks-old` (ADR 0057).
- `verifyRustGuest` was up to date (built on this machine earlier in the same tree).

The graphical development client also starts and runs on this machine:
`./gradlew runPresentationClient` wrote 137 screenshots under
`build/reports/client-presentation/screenshots/` on 2026-10-31, during the ergonomics-plan slices.

## Still open for P0

- Windows has not been run.
- The manual `.github/workflows/quality.yml` workflow (ubuntu-latest, windows-latest) has not been
  triggered for this revision, so there is no hosted CI record yet.
