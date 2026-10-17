# Accepted staged operations across dedicated-server restart — 2026-10-17

Partial P2/P3/A03/A13/A14 evidence. Minecraft1.21.1/NeoForge21.1.252/Java21.0.12,
macOS15.3.1 arm64, Apple M1/8GiB. Only Minecraft, NeoForge, Factory Core and the
excluded verification fixture are loaded; no JEI/Mekanism. This is a small native
functional workload, not performance evidence or full release acceptance.

## Required native check

The canonical `python3 scripts/verify.py` now runs `runStagingRecoveryServer`
through Gradle check. It passed24 tooling,218 cached JVM and205 freshly executed
required GameTests plus the dedicated staging probe. Gradle45s,31 tasks:
7 executed/24 up-to-date; Rust guest checks were up-to-date. Formatting/static/
architecture/packaging checks passed. The final archived verifier log has no ERROR.

Two real dedicated JVMs run in build/staging-server-run: seed PID36849 exits before
restore PID36862 opens the same world. Each uses an ephemeral loopback port and
normal server shutdown/save. Seed deletes only its generated world/report; restore
never reseeds or installs expected state. A complete canonical SavedData snapshot
must match before any recovery operation. A trusted fixed synthetic program
principal (NeoForge fake player for level/registry access) owns the network; no
connected-player, player inventory endpoint, login/session or UI claim is made.

Three actual shared scheduled DIGITAL→EXTERNAL operations start from separate
exact item identities with20 units each. A test-only native provider simulates
full insertion but accepts only3 of8 during the actual call. Source ledgers debit
to12, native providers own3, and known records retain staged5/delivered3. A third
provider accepts3 and throws before returning, leaving QUARANTINED staged8/
pendingDELIVER8/delivered0. Staging is produced by accepted production submissions,
never direct registry start, fabricated snapshots or hand-written context.

After restart, native inventories/identities, backing markers, budgets/call counters
and canonical contexts/debits match. Known delivery transfers the existing5 only:
source12 + provider8 =20. Original-source cleanup returns5: source17 + provider3
=20, without another destination call. These complete empty records can retire.
Uncertain delivery and return both reject UNCERTAIN before callbacks/native calls;
retirement rejects and the pending record remains unchanged. Its8 is an offered
uncertain quantity, not a proven refundable balance additional to physical3.

The fixture mod now has a common registry/server entry and a client-only setup
subscriber. Actual dedicated startup verifies server execution does not load
screen classes. The same provider/recovery code also extends the graphical
restart fixture. Fixtures/classes/resources remain outside the production JAR.

## Attempts and practical limits

The graphical extension compiled but did not launch: GLFW found no primary monitor.
macOS CoreGraphics reported activeDisplays=0/result0, confirmed again after the
headless checks. Its blocked log is archived; no staging/UI interaction is claimed.
The dedicated check was added and completed instead. The changed client setup
hook and shared graphical staging flow still require a successful desktop run.

An initial dedicated run passed native recovery but logged a flat-world settings
fallback; the fixture now writes explicit valid layers/biome. A later canonical
attempt caught an unformatted added identity assertion. Formatting was applied;
the final full canonical run passed without weakening any gate/assertion.

[Checksums and artifacts](staged-server-v1-2026-10-17/SHA256SUMS) archive expected
observations, restored canonical SNBT, both native reports, final verifier log,
blocked graphical launch and display observation. Ordinary saves are untouched.
No display requirement or graphical acceptance was removed: the native check is
an additional required gate, while graphical evidence stays separate.

This proves orderly dedicated save/rebind for these accepted operations, not
independent chunk/player/controller save ordering, a crash between foreign write
and journal completion, queued/staged chunk unload, administrative reconciliation,
multiplayer, large workloads or complete A01–A17 acceptance. Those remain open.
