# Recovery inspection, audited cleanup and retirement — 2026-10-17

Partial P2/P3/R09/A03/A12/A13/A14 evidence on Minecraft1.21.1,
NeoForge21.1.252, Java21.0.12, macOS15.3.1 arm64 / Apple M1/8GiB.
Only Minecraft/NeoForge/Factory Core and excluded test fixtures are used.

Final `python3 scripts/verify.py` passed24 tooling,221 JVM and207 required
GameTests plus both dedicated staged-operation JVMs. Gradle59s,31 tasks:
13 executed/18 up-to-date; Rust guest checks up-to-date. Formatting/static/
architecture/packaging checks passed. No ERROR appears in the final archived log.

Three additional JVM cases cover detached bounded metadata pages and audit
round-trip/reuse/corrupt overwrite/file quota/unexpected-entry refusal. Two native
command cases use actual registered player bodies and the production dispatcher.
They check operator-only list/show, console and execute-as denial, rejected
other-owner adoption, before-state archive existing while return is still queued,
known return conservation, retention until explicit audited completion retirement,
and denied live/uncertain mutation with unchanged accounting/no cleanup archive.
Command-case setup uses controlled existing staging; the required dedicated probe
separately creates accepted native partial/faulting operations through submit.

The native return restores15 exact units without fresh extraction; the original
before-state and completed state are both archived before the operator retires
its empty canonical record. Both exact schema1 audit files from the final run are
included, retaining content-addressed operationUUID/SHA256 names. Known staging,
uncertainty and resource ownership are not hidden by metadata retirement.

Dedicated seed PID38214 exits before restore PID38226. Both stages execute real
operator-console list/show on accepted uncertain records; the log contains exact
counts, root/segment/origin/device IDs, provider diagnostic and safe no-replay/
refund action. The native staged conservation/restart assertions remain intact.

## Review findings and limitations

Review required checking original CommandSource as well as execution entity:
vanilla execute-as alone must not impersonate the authenticated original owner.
The native test covers that path. PMD initially flagged a borrowed server handle;
a narrow documented suppression preserves Minecraft's server ownership.
A first audit test needed to distinguish standalone audit schema1 from its embedded
world12/transfer-entry2 encoding. No production world schema changed.

A console run caught UUID/enum translation arguments rejected by Minecraft.
All such arguments now become strings. Positive command tests no longer suppress
feedback, and the required dedicated console checks exercise actual messages.
The failed attempt is not counted as a pass and no assertion/gate was weakened.

[Artifacts/checksums](recovery-commands-v1-2026-10-17/SHA256SUMS) include final
verifier/native output and exact command audit snapshots. There is no graphical
chat, remote multiplayer or supported ownership-conflict reconciliation claim.
Uncertain external outcomes remain frozen; backups/manual investigation are still
required where the true result cannot be established. Independent-save/crash
atomicity, source IO overload and full A01–A17 remain unfinished.
Audit byte/count/write limits are enforced, but synchronous force latency can
delay explicit commands and has not passed P7 workload measurements.
