# ADR 0026 — Audited connector and Machine Interface conflict repair

Status: selected P2/P3/R09 prerequisite; evidence belongs in the implementation
plan. Known-staging/ownership combinations and independent-save recovery remain open.

## Decision

Repair an ownership conflict only at the loaded original canonical connector or
Machine Interface, with exact raw/parsed reference, kind/generation/position and
no persisted provider fault. Preserve settings, label, face and local configuration;
consume the structural lease. Clear only the transient ownership diagnostic, never
provider-fault evidence. Invalidate old mod-owned registrations/ports/cache/token
and topology before reacquiring a provider normally. Repair itself makes no provider
call and owns/copies no foreign contents.

Refuse unresolved records naming the node backing or endpoint device. If an exact
remembered registration exists, also refuse any unresolved record for its backing,
including legacy records without endpoint context. The remembered registration must
match physical owner, lease, configuration, kind, token and face; it grants no
resource authority. If no such witness exists and any unresolved legacy record
lacks device context, repair cannot prove an unrelated backing and refuses. The
legacy check scans at most1024 canonical metadata records, only on explicit recovery.
No unknown provider metadata or capability is invoked to guess an association.

Machine configuration must match its positive canonical fence. Keep accepted
preset/fence/authority/revision/filter/goal unchanged. Its old lease cannot run
after rotation; an authorized player explicitly redeploys through current grants.
Do not observe a mismatched physical configuration into canonical state during
repair. This is ownership repair, not configuration or provider-fault reconciliation.

## Evidence and limits

Standalone audit schema5 stores WorldSchema12/World/HolderKind=FOREIGN_PORT,
one Nodes record, native BlockState/BlockEntity, one optional remembered Registration
(lease/configuration/provider token/backing/kind), and MachinePresets encoded with
its existing schema2 (empty for a connector; optional deployment and exact fence
for a machine). Record foreign identity metadata only, never provider contents.
World12, connector reference1/machine reference2 and audit1–4 stay unchanged.

Share the existing9MiB/file/512-file/32MiB device archive, bounded serialization/
directory/writes, content-addressed CREATE_NEW/force and20-tick server repair
throttle. IO failure leaves authority/configuration/registrations unchanged.
All work is on the server thread. Explicit synchronous IO can delay a tick; no
P7 latency claim or new thread pool is introduced.

## Alternatives and limitations

Rebinding a conflicted port to inspect its provider could invoke unknown handlers
before recovery admission. Ignoring legacy records could strand known staging.
Clearing a saved fault or silently updating an accepted deployment would erase
uncertainty or adopt another principal's automation. Instead inspect only bounded
canonical/remembered metadata and require explicit authorized redeployment.

Foreign provider identity, holds and resources remain provider/shared-accounting
owned. Direct foreign capabilities remain outside mod authority; only stale
mod-owned registration/ResourcePort handles are invalidated. Missing/stale original
holders, raw schemas, mismatched fences, persisted faults, unresolved staging and
independent-save mismatches remain for investigation. No replay/refund or arbitrary
survivor selection is provided.

## Required verification

Exercise legacy metadata classification without calls/mutation; audit exact local
settings/label/fence/deployment/registration evidence, corruption/mismatched-state
refusal and compatibility. Native cases must preserve foreign contents and saved
holds, invalidate old mod ports, retain binding settings and same backing on normal
reacquisition, freeze old queued presets, deny redeployment after grant revocation,
resume only on explicit authenticated deployment, and refuse failed audit/provider
fault/unresolved ownership without mutation. Headless evidence is not client,
remote multiplayer or interrupted-file proof.


## Amendment — 2026-10-18

[ADR 0047](0047-legacy-staging-settlement.md) narrows the legacy check. Without a remembered
registration, only an uncertain legacy record (pending call or quarantine) that names a
backing that is not a Factory Core identity blocks repair. Known legacy staging settles only
into canonical holders and never through a port, so it no longer blocks.
