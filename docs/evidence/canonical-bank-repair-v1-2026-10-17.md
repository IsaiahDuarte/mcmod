# Audited canonical energy-bank repair — 2026-10-17

Partial P2/R09/A04/A12/A13 evidence against baseline3ca24ec on Minecraft1.21.1,
NeoForge21.1.252, Java21.0.12, macOS15.3.1 arm64, Apple M1/8GiB. Only production
mod and excluded test fixtures. [ADR0023 extension](../decisions/0023-canonical-cell-repair.md)
records authority, bank audit schema2 and the existing bounds/recovery boundary.

`./gradlew spotlessApply compileJava` and
`./gradlew spotlessApply test runGameTestServer` passed. The latter executed227
JVM tests (zero failed/skipped) and213 required GameTests, in34s with19 tasks:
8 executed/11 up-to-date. One new audit case and three native bank cases extend
baseline226/210. Existing cell schema1 and repair cases remain intact.

Final `python3 scripts/verify.py` passed24 tooling, the227-test JVM gate (up-to-date
from the focused run),213 executed required GameTests and both mandatory dedicated
staging JVMs. Gradle47s,31 tasks:10 executed/21 up-to-date. Rust guest checks were
up-to-date. Formatting, PMD, compilation, architecture and packaging gates passed.
The archived final log has no ERROR. Seed39572 exited before restore39587;
accepted known delivery/return and frozen uncertainty still passed. Final docs,
artifact checksums and `git diff --check` passed.

The real production console dispatcher inspects and repairs a conflicted native
bank at its original holder. Operator-only permission, kind, expected generation
and wrong holder checks refuse unsupported choices. Repair preserves900FE/200
claimed, rotates generation2→3 and leaves routing offline pending normal validation.
Same-tick input remains100FE after the original900FE receipt; rotating the lease
does not reset the shared backing rate budget. Subsequent800 unreserved extraction
leaves the exact200FE claim. Cached clone capabilities remain inert. Repaired
physical/canonical references reload; replaying the old chunk snapshot stays inert
with unchanged ledger rather than choosing another owner or resetting quantities.

A native audit-output failure retains exact physical and canonical conflict data.
A real100FE extraction into owned staging leaves400FE in its bank; the unresolved
record blocks repair without changing either balance/reference. This controlled
low-level staging setup is not a new shared-scheduler/authentication claim.
The existing required dedicated probe separately checks scheduled acceptance and
known/uncertain persistence across two real server processes.

Bank audit schema2 retains world/lease/location/conflict, full checksummed FE ledger
and claims, original native block state and complete block-entity reference NBT.
The new JVM case checks those fields, exact reuse and refused corrupt overwrite.
Existing cell schema1 binary tests still pass. World12/bank-reference1 are unchanged.
Both holder kinds share the bounded device archive and20-tick operator throttle.
Metadata pages now filter cell/bank kinds, inspecting at most16 handles per call;
a zero-match page can still have a next cursor.

[Artifacts/checksums](canonical-bank-repair-v1-2026-10-17/SHA256SUMS) contain the
full final verifier log (trailing whitespace trimmed), exact content-addressed
native bank audit, audit JVM report and dedicated PASS reports. No graphical chat,
remote multiplayer, alternate-survivor selection, independent-save interruption,
buffer/node repair or uncertain external reconciliation is claimed. Synchronous
explicit audit IO has no P7 latency evidence. Full A01–A17 remains incomplete.
