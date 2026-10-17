# Audited canonical cell conflict repair — 2026-10-17

Partial P2/R09/A03/A12/A13 evidence against baseline a7d42bd on Minecraft1.21.1,
NeoForge21.1.252, Java21.0.12, macOS15.3.1 arm64, Apple M1/8GiB. Production mod
and excluded verification fixtures only. [ADR0023](../decisions/0023-canonical-cell-repair.md)
records scope, byte/count bounds, schemas and unsupported recovery cases.

Final `python3 scripts/verify.py` passed24 tooling,226 JVM (zero skipped/failed),
210 required native GameTests and both mandatory dedicated staging JVMs.
Gradle1m1s,31 tasks:13 executed/18 up-to-date. Formatting, PMD, architecture,
packaging and compilation passed; Rust guest checks were up-to-date. The final
log has no ERROR. Dedicated seed39018 exited before restore39053; accepted
known staging delivery/return and frozen uncertainty still passed.

Focused development commands `./gradlew spotlessApply compileJava`,
`./gradlew spotlessApply test`, `./gradlew spotlessApply runGameTestServer` and
`./gradlew spotlessApply` also passed. The final canonical command ran after the
last source changes, including wrong-tier, stale snapshot and larger-audit cases.
`git diff --check` and final documentation/checksum validation passed.

Two new core cases preserve100 units/60 reserved, enforce exact original location,
consume generation, reject all old copies and retain claims offline on restore.
Foreign/unknown/stale/portable/nonconflicted/overflow repairs leave evidence intact.
Three audit cases round-trip full binary ledger/claims/physical components, reuse
identical files, reject corrupt overwrite/oversized data/blocked output, and encode
a ledger larger than the separate512KiB transfer audit limit.

Three native cases use real blocks and copied block-entity data. The production
console dispatcher checks operator permission, bounded list/show, expected
generation and canonical repair. Wrong holders and tiers reject. A successful
repair preserves100 units/60 reserved and custom name, rotates generation2→3,
keeps the clone unchanged/stale, reloads physical/canonical references and permits
only40 unreserved extraction after normal network validation. Replaying the old
chunk snapshot remains inert. The second case injects an unexpected audit-directory
entry and requires exact physical/canonical no-mutation. The third starts a real
three-unit extraction into known staging, then creates a cell conflict; unresolved
staging prevents rotation and neither ledger97 nor staging3 changes.

The [archived artifacts/checksums](canonical-cell-repair-v1-2026-10-17/SHA256SUMS)
include the full final verifier log (trailing whitespace trimmed), core/audit JVM reports, dedicated PASS reports
and exact content-addressed native before-state NBT. The audit records original
100-unit ledger,60-unit claim, conflicting generation2 and complete named cell.
It is evidence rather than a restore instruction.

This does not prove graphical chat layout, remote multiplayer, arbitrary survivor
selection, bank/buffer/structural repair, interrupted independent saves or external
uncertainty reconciliation. Controlled stale snapshot replay is not a hard-crash
experiment. Synchronous audit IO may delay explicit commands; no P7 performance
claim is made. Full A01–A17 and the overall goal remain incomplete.
