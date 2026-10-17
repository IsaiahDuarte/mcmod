# ADR 0025 — Audited controller and gateway conflict repair

Status: selected P2/R09 prerequisite; verification belongs in the plan. Connector/
machine holders, interrupted independent saves and full A13 remain unfinished.

## Decision

Expose operator recovery only for a conflicted controller or gateway at its exact
loaded canonical position with matching raw/parsed reference and expected kind/
generation. Audit before consuming the structural generation. Keep logical network/
segment IDs, position, controller owner/grants/permission generation and gateway
policy unchanged. Repair neither commissions a new owner nor broadens a policy.
Invalidate old topology and require normal budgeted publication before routing.
Old physical references cannot quarantine the repaired generation.

Controllers/gateways are not storage endpoints. Accepted ordinary transfers keep
the same network/segment IDs and unchanged endpoint leases/context/quantities.
They remain subject to current authorization, loaded endpoints and uncertainty
checks; repair performs no transfer progress or provider call. Refuse any retained
unresolved record that names the structural node itself as a backing or endpoint
device, rather than silently reinterpret unsupported legacy metadata.

Other structural kinds require their own adapter/dependent-state integration.
This uses NodeRegistry's trusted transition established in ADR0024, not a generic
force/unquarantine command. All work runs on the Minecraft server thread.

## Evidence and limits

Standalone binary audit schema4 stores WorldSchema12, World UUID,
HolderKind=NETWORK_NODE, Nodes (one canonical structural-codec record), Networks
(one complete controller permission record, empty for a gateway), BlockState and
complete BlockEntity reference NBT. Require the controller's permission UUID to
match its node identity; gateways own canonical policy rather than a grant copy.
The record retains gateway orientation and restricted policy masks where present.

Existing world12, node-reference1 and audit1/2/3 formats stay unchanged. Audit files
are evidence, never a restore/import authority. Reuse the bounded device archive:
9MiB/file,512 files,32MiB total, bounded serialization/directory/writes, CREATE_NEW
and force before mutation. Share the20-tick server throttle with cell/bank/buffer
repair. Metadata pages inspect at most16 structural handles and filter supported
node kinds; zero-match pages can retain a next cursor. No chunk loading/provider
access is permitted. Explicit synchronous IO can delay ticks and is unmeasured.

## Alternatives and limits

Recommissioning would lose grants or create a new identity. Clearing conflict
without a new generation lets an old copy quarantine it again. Copying policy from
the physical item could widen canonical restrictions. Resetting transfers merely
because topology changed would destroy owned staging. Instead retain canonical
permissions/policy and existing accepted resource evidence, revalidating normal
scopes before execution. A repaired root does not bypass current grant revocation.

Missing/stale original holders, raw future data and independent-save mismatches
remain inert. Repair does not choose another survivor, replay uncertain work,
refund stock or provide atomicity across Minecraft's independent files.

## Required evidence

Audit cases must round-trip exact owner/grants/generation, restricted gateway
policy, raw reference/orientation, reuse and corruption/mismatched-kind refusal.
Native cases must retain resource quantities/claims/staging and current permission
checks across root repair; revalidate topology before availability; preserve parent/
child visibility and segment identity across gateway repair; reject stale copies/
old snapshots and failed audit without mutation. Distinguish native default-policy
coverage from restricted-policy codec/core evidence. Real client/multiplayer and
interrupted filesystem saves remain separate release gates.
