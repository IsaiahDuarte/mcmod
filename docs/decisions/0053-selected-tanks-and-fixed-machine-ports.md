# ADR 0053 — Selected tanks and fixed machine partitions

Status: Accepted implementation decision; integrated verification pending.

## Context

R02/R04/R05 require local slot/tank/energy bindings, and A04/A08 require shared
accounting and competing callers to preserve resources. NeoForge `IFluidHandler`
exposes indexed observations but only whole-capability fill/drain. Selecting an
observed tank and then invoking whole-capability mutation can drain another tank
containing the same fluid. Inferring independently owned storage from different
sided wrappers also permits overlapping holds and duplicate catalog entries.

## Decision

Reuse the persisted `ConnectorSettings.firstSlot/slotCount` for zero-based tank
windows when kind is FLUID. The existing 0/64 default retains whole-capability
semantics and legacy saved identities, including larger advisory catalogs. Other
fluid windows require an explicit `IndexedFluidHandler` capability with genuine
per-tank fill/drain. Unsupported providers fail closed; never emulate targeted
mutation with whole-handler calls or speculative refunds. Calls traverse at most
64 selected tanks and preserve mB and exact components. Observation, Stock goals,
holds and mutation use the same selection. Energy retains first0/count64.

By default registrations remain exclusive across the whole physical backing and
resource kind, even with disjoint selected windows. Providers may explicitly
implement `ExternalBackingProvider.factoryCorePorts(kind)` to declare at most64
fixed, nonoverlapping global slot/tank partitions. Every alias/face must use the
same immutable catalog for the backing identity's entire lifetime and expose the
same indexes. Only an exact declared partition can bind; fluid0/64 is reserved for legacy whole
capabilities and cannot be declared as an indexed partition. An empty catalog retains
legacy exclusive accounting. Do not opt in when wrappers remap indexes, storage
partitions change, or multiple nominal slots alias the same physical storage.

Partition backing UUIDs derive deterministically from existing persistent group
identity and the exact first/count pair (`factorycore:indexed-port:v1`); default
whole-fluid access retains the group ID and cannot overlap another registration.
Separate partitions have separate stock/holds/staging identities, while mutation
rates share the physical resource group's128 item or1000mB ceiling per direction
per tick plus each connector's configured rate. Thus separate interfaces cannot
multiply physical provider throughput. Different resource kinds retain their
independent budgets. Configuration/lifetime invalidation expires old handles;
a newly bound exact partition inherits that partition's saved holds. Undeclared
unions/subranges cannot create another identity over held storage.

There is no new persistence schema: connector1/machine2 fields and provider marker1
are unchanged. Existing providers return an empty catalog by default. A provider
integration changing a previously declared partition layout must migrate its
physical backing identity and all pending ownership explicitly; silently reusing
the old marker with different partitions violates the integration contract.
Neither registration nor index metadata owns foreign contents. Interrupted foreign
calls retain existing staging/uncertain ownership and are never replayed.

All operations remain on the server thread, use current structural/configuration
and permission checks, avoid chunk loading and share existing scheduling. Total
registration/provider claims remain4096/16384; each partition catalog has at most64
entries. This does not claim new representative performance measurements or finish
complete processing-job/script lease interoperability. The coordinated exact-target
and legacy migration correction is documented in [ADR0032](0032-processing-patterns.md#physical-target-lease-correction-2026-10-18).

## Alternatives

- Whole-handler mutation after selected observation: rejected because identical
  fluids in excluded tanks are not protected.
- Arbitrary disjoint windows with newly derived backing IDs: rejected because
  reconfiguring an overlapping window could bypass saved holds.
- Guess canonical indexes from wrapper class or face: rejected because aliases
  can reorder/remap slots and tanks.
- Separate resource copies or a new transfer engine: rejected; existing ownership,
  staging, reservations and permission paths already apply.

## Verification

`SelectedFluidPortTest`, selected-window packet/persistence tests and
`IndexedMachinePortsGameTests` cover targeted same-fluid isolation, metadata,
partial insertion/simulation, bounded windows, unsupported/faulted adapters,
shared group rates, independent partition holds, duplicate/union rejection,
stale ports and coexisting item/energy access. Current run results belong in the
implementation plan. These focused tests do not prove full A04/A08/A14 acceptance,
third-party integration compatibility, real restart or graphical usability.
