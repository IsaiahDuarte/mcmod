# Audited connector and Machine Interface repair — 2026-10-17

Partial P2/P3/R09/A05/A12/A13 evidence against baseline d197197 on Minecraft1.21.1,
NeoForge21.1.252, Java21.0.12, macOS15.3.1 arm64. Production mod and excluded test
fixtures only. [ADR0026](../decisions/0026-foreign-port-repair.md) records the
ownership-only repair contract and standalone audit schema5.

Final `python3 scripts/verify.py` passed24 tooling tests, documentation/acceptance
tracking, formatting, PMD, architecture, packaging, compilation,224 required
GameTests and both mandatory dedicated staging JVMs (seed53367/restore53379). Its
JVM `test` task was restored from the Gradle build cache, so a forced
`./gradlew test runGameTestServer --rerun-tasks --no-build-cache -x createMinecraftArtifacts`
then executed238 JVM tests (zero failed/skipped) and224 GameTests (zero failures);
15 tasks executed in41s. Neither archived log contains ERROR.

PortRecoveryAuditTest round-trips schema5 for connector and machine holders with
and without a remembered registration, preserving native block state/entity,
settings, label, fence and accepted deployment, and records no ledger or provider
contents. Reuse is content-addressed; a corrupt existing file is refused unchanged;
mismatched fence, persisted fault, wrong kind and mismatched binding are rejected.
A TransferRegistryTest case shows the unscoped-legacy preflight is read-only,
ignores completed and context-scoped records, and detects restored quarantined
intents.

Native cases drive copied holders and the production operator command. Connector
repair keeps a23-item chest and5-unit hold byte-identical, keeps settings/
configuration/face, issues a new provider token, makes the old ResourcePort inert,
reacquires the same backing after normal revalidation and keeps the same-tick rate
spent (5 more of8). Old clones and old original snapshots stay inert. Failed audit
IO and a persisted provider fault refuse with exact reference/binding/provider
state. Accepted context staging and unscoped legacy staging with no remembered
binding refuse without provider calls. Machine repair keeps the accepted Import
preset/fence, never runs it on the new lease, denies native menu redeploy without
DEPLOY, accepts explicit redeploy after restoration with19 items conserved and
excluded stock untouched, and refuses a mismatched physical/canonical fence.

[Artifacts/checksums](canonical-port-repair-v1-2026-10-17/SHA256SUMS) include both
exact content-addressed schema5 before-state files (from the first GameTest run of
this change; the forced rerun wrote equivalent files with new random IDs), both JVM
reports, GameTest XML, dedicated PASS reports and both logs (trailing whitespace
trimmed). No graphical chat, real multiplayer, arbitrary survivor selection,
interrupted independent filesystem saves, uncertain refund/replay or repair with
unresolved/unscoped staging is claimed. Synchronous explicit audit IO remains
unmeasured for P7. Full A01–A17 remains incomplete.
