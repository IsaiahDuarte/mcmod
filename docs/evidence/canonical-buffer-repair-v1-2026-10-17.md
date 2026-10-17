# Audited canonical Buffered Interface repair — 2026-10-17

Partial P2/P3/R09/A03/A12/A13 evidence against baseline7f9184d on Minecraft1.21.1,
NeoForge21.1.252, Java21.0.12, macOS15.3.1 arm64, Apple M1/8GiB. Production mod
and excluded test fixtures only. [ADR0024](../decisions/0024-buffer-conflict-repair.md)
records the accepted-deployment boundary and standalone audit schema3.

`./gradlew spotlessApply compileJava` passed. Focused
`./gradlew spotlessApply test runGameTestServer` passed233 JVM/216 required native
checks in35s,19 tasks:8 executed/11 up-to-date. The native case was then strengthened
to use the actual settings menu and registered ordinary player for reapply.
The first canonical attempt passed216 native checks but failed PMD CloseResource
on the new throttle helper's borrowed MinecraftServer variable. A narrow ownership
annotation prevents closing Minecraft's server; no PMD rule/gate/assertion changed.

Final `python3 scripts/verify.py` passed24 tooling,233 executed JVM tests (zero
failed/skipped),216 required GameTests and both dedicated staging JVMs. Gradle59s,
31 tasks:10 executed/21 up-to-date; Rust guest checks up-to-date. Formatting/PMD/
architecture/packaging and compilation passed. The final log has no ERROR.
Dedicated seed40326 exited before restore40340; accepted scheduled known delivery/
return and frozen post-insert uncertainty remained valid. Final documentation,
artifact checksum validation and `git diff --check` passed.

Two structural cases exercise the trusted transition for all node kinds while
retaining exact position/restricted gateway policy, consuming generation and
rejecting wrong/stale/foreign/unknown/portable/nonconflicted/overflow choices
without mutation. They do not establish physical root/gateway/connector/machine
repair. Two buffer cases preserve23 slot units, deployment and metadata charge,
remove the old binding only on successful rotation, keep the accepted old lease
inactive and require explicit configure for the new lease. Restore stays unbound.
Two audit cases cover schema3 with and without deployment, exact components/
admitted limits/policy/principal/raw physical data, reuse, corruption refusal and
mismatched owner/deployment rejection. Existing cell1/bank2 evidence tests pass.

Three native cases create actual copied holders. Real console commands check
operator permission, original position, kind and expected generation. Repair keeps
19 named items in their exact slot and preserves the original accepted principal,
policy/rate3/target17/revision/mode/scope, consuming generation1→2. Physical reference
principal and accepted deployer intentionally differ; neither is replaced by the
operator. Old handlers reject all input/output and old queued work cannot extract.
Repaired physical/canonical references reload; stale clone and old original chunk
snapshots stay inert without resetting quantities.

After30 ticks with19 buffered/zero cell, the ordinary registered player physically
opens native settings. Revoked DEPLOY causes Apply to report denied and retain the
accepted deployment. Restoring grants and submitting a fresh native Apply publishes
the new lease/revision under that player with the retained policy. Shared Import
then produces buffer0/cell19 exactly. The fixture uses NeoForge's test-only mock
connection setup; it does not establish a remote client handshake or real rendering.

A native evidence-directory failure retains exact node, slots, deployment and
physical NBT. Another case actually extracts3 items into known staging, leaving16
buffered; unresolved ownership blocks rotation without changing either balance.
This low-level staging setup is not a new scheduled-acceptance claim; the separate
required dedicated probe checks that production path.

[Artifacts/checksums](canonical-buffer-repair-v1-2026-10-17/SHA256SUMS) include the
full final verifier log (trailing whitespace trimmed), exact content-addressed
native before-state schema3 NBT, core/audit JVM reports and dedicated PASS reports.
World12/reference1/canonical buffer formats are unchanged. No graphical chat/UI,
remote multiplayer, other physical structural repair, arbitrary survivor selection,
interrupted independent-save recovery or uncertain refund/replay is claimed.
Explicit synchronous evidence IO has no P7 latency measurements. Full A01–A17 and
the full-release goal remain incomplete.
