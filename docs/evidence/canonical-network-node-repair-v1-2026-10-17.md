# Audited controller and gateway repair — 2026-10-17

Partial P2/R09/A03/A05/A12/A13 evidence against baseline0a40ff3 on Minecraft1.21.1,
NeoForge21.1.252, Java21.0.12, macOS15.3.1 arm64, Apple M1/8GiB. Production mod
and excluded test fixtures only. [ADR0025](../decisions/0025-network-node-repair.md)
records logical identity/permission preservation and standalone audit schema4.

`./gradlew spotlessApply compileJava` passed. An initial
`./gradlew spotlessApply test runGameTestServer` passed235 JVM and the new node
cases but failed the existing bank command/rate case because another fixture had
consumed the shared server20-tick repair cooldown. Isolating positive repair
fixtures in dedicated batches/cooldown preserves the real production throttle,
all expected amounts and every required test. The bank's900/200/100/800 assertions
still execute seed/repair/rate probes in one tick. Both node cases retry only the
public command until admitted. No gate, expectation or production limit changed.
A subsequent focused run passed219 GameTests; final verification below includes
all completed cell/bank/buffer/node batch-isolation changes and the final unit data.

Final `python3 scripts/verify.py` passed24 tooling,235 executed JVM tests (zero
failed/skipped),219 required GameTests and both mandatory dedicated staging JVMs.
Gradle1m1s,31 tasks:13 executed/18 up-to-date. Formatting, PMD, architecture,
packaging and compilation passed; Rust guest checks were up-to-date. No ERROR
appears in the final archived log. Dedicated seed41080 exited before restore41098;
accepted native known delivery/return and frozen uncertainty still passed.
Final documentation, artifact checksums and `git diff --check` passed.

Two new audit cases round-trip schema4 with complete controller owner/grants/
permission generation or restricted gateway policy, native orientation/reference,
exact reuse and corrupt overwrite refusal. Mismatched controller permission IDs,
missing grants, gateway grant copies and other structural kinds reject. Existing
cell1/bank2/buffer3 audit tests remain intact; world12/node reference1 are unchanged.

Three new native cases drive actual copied blocks and production operator commands.
Root repair preserves controller identity/owner/grants/permission generation,
90 stored items/60 claimed and10 known staged units without another provider call.
Routing stays unavailable until a fresh scope publishes for the same logical
network. A view-only principal remains unable to withdraw. Repaired physical
references reload; stale clone and old original snapshots remain inert.
The staged-record setup is a controlled low-level ResourcePort extraction, not
new production scheduler/context-resumption evidence. The mandatory dedicated
probe separately covers scheduled acceptance and known/uncertain restart behavior.

Gateway repair retains its logical child segment/network, canonical policy and
east-facing orientation,50 stored/20 claimed and private views. Parent storage
queries still exclude the child; child queries expose only the same local/ancestor
segments; a view-only grant still cannot withdraw. Native gateway policy remains
the existing default. Restricted-policy masks are preserved in codec/core cases;
this does not claim a new native gateway-policy editor. Audit-output failure
retains exact physical/canonical node and grants/policy for both holder kinds.

[Artifacts/checksums](canonical-network-node-repair-v1-2026-10-17/SHA256SUMS)
include the full final verifier log (trailing whitespace trimmed), both exact
content-addressed native schema4 before-state files, audit JVM report, required
GameTest XML and dedicated PASS reports. No graphical chat, real multiplayer,
connector/machine holder recovery, arbitrary survivor selection, interrupted
independent filesystem saves or uncertain refund/replay is claimed. Synchronous
explicit audit IO remains unmeasured for P7. Full A01–A17 remains incomplete.
