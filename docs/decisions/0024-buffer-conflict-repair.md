# ADR 0024 — Audited canonical buffer conflict repair

Status: selected P2/P3/R09 prerequisite; implementation and verification recorded
in the implementation plan. Full ownership/interrupted-save recovery remains open.

## Decision

Repair a conflicted Buffered Interface only at its existing loaded canonical
position, with the exact raw/parsed reference and expected structural generation.
Preserve evidence before mutation and refuse any unresolved transfer mentioning
its backing. Rotate the structural generation, preserving identity, position and
canonical inventory. Invalidate the old binding/handlers and bind the new reference
to the current physical holder. Never choose a different survivor or copy stock.

Keep the complete canonical deployment unchanged, including original principal,
policy, mode, revision, accepted scope and accepted lease generation. The repaired
holder's new lease therefore cannot execute that old deployment. Reapplying through
normal authenticated EDIT/DEPLOY/current transfer-grant checks establishes a new
deployment. Repair does not deploy as an operator or assume current grants from an
old configuration. Queued old work rechecks generation/configuration before mutation.
Physical hopper access to the repaired owned slots follows existing local ownership
rules; it does not require an active preset or network route.

The trusted NodeRegistry transition is reusable structural infrastructure, not an
operator API. It preserves gateway policy and changes no permission network state.
The initial physical command exposes only Buffered Interfaces. Other structural
kinds require their own holder/evidence/authority integration before exposure.
BufferRegistry removes the old transient binding only on successful lease repair;
inventory, deployment and metadata charge remain unchanged. Registry/evidence/
physical work runs on the owning Minecraft server thread.

## Evidence and persistence

Before repair preserve standalone binary audit schema3 in the existing bounded
factorycore-device-recovery directory. Fields: Schema3, WorldSchema12, World UUID,
HolderKind=BUFFERED_INTERFACE, Nodes (one existing structural-codec record), Buffers
(one owned-slot record plus optional existing deployment using its current nested
codec), BlockState and complete BlockEntity NBT. Retain exact item components,
admitted limits, deployment policy/principal/scope and raw physical reference.
Existing cell audit1/bank audit2, world12, buffer reference1 and canonical table
schemas are unchanged. Audit files are evidence, never a second owner/import path.

Reuse existing content-addressed CREATE_NEW/force evidence writes,9MiB/file,
512 files and32MiB total, with bounded serialization/directory/write checks.
All three repair commands share one20-tick server throttle. Metadata list pages
visit at most16 structural handles and filter buffers; empty matching pages can
still have a next cursor. No provider calls or chunk loading are permitted.
Synchronous explicit audit IO can delay a tick; performance is not yet measured.

## Alternatives and limitations

Clearing conflict without consuming the generation lets a clone quarantine it
again. Rewriting a deployment to accept the repaired lease would silently grant
an operator the ability to republish another principal's automation. Deleting it
would lose policy/principal evidence. Retaining the accepted old lease provides
an explicit safe reapply path with current authorization and stale-work fencing.

Canonical and chunk saves remain independent. A stale/missing original reference,
unsupported/raw schema, unresolved staging, wrong position or generation refuses.
Interrupted independent-save mismatches, uncertain outcomes and arbitrary survivor
selection remain for backup/evidence investigation; no refunds or replay are added.
No automatic redeployment or hard-crash atomicity is promised.

## Verification requirements

Core tests must preserve slots/deployment/charge, consume generation, reject old
bindings, reject wrong/stale/unconflicted/foreign/overflow repairs unchanged, and
retain gateway policy in the reusable structural transition. Audit tests require
exact slot/components/policy/physical round trips, reuse/corruption refusal and
unchanged existing evidence formats. Native tests must exercise real command
permission/holder/generation checks, queued old work, stale capabilities, retained
principal/policy, current grant refusal/reapply, actual resumed conservation,
failed audit no-mutation and unresolved staging refusal. Old chunk replay and
normal reference/canonical reload are required; interrupted filesystem evidence
remains a separate gate.
