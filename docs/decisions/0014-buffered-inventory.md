# ADR 0014 — Owned physical inventory for Buffered Interfaces

Status: accepted P3 inventory boundary; component inventory/handler implemented.
Canonical slot persistence/lease bindings are implemented; physical blocks and
automatic presets remain pending.

## Decision and alternatives

R02 requires nine real item slots that back up a hopper when authorized storage
is full. R03 excludes machine buffers from global stock unless explicitly
registered. D06 sets nine slots and a default 64-item/tick import rate. A digital
ledger plus a second Minecraft inventory would create two owners and permit
save divergence or duplicate quantities. Persisting inventory only in chunk NBT
would split owned source quantities from canonical staging at normal save
boundaries. Neither is the selected ownership model.

Use one bounded slot inventory per canonical backing identity. Its ResourcePort
and NeoForge IItemHandler mutate the same nine slots; the platform codec preserves
exact components and resolves native per-item stack size, capped at 64. Snapshot
state retains admitted limits without silently truncating quantities when native
limits change. The port fits existing staged-transfer accounting and partial
cleanup; the mechanical handler supplies hopper-compatible real stacks.

The upcoming physical slice will store slot state in the canonical world authority
and reference it with generation-checked physical ownership. It will add a distinct
buffer endpoint, not disguise buffers as globally enumerable cells or foreign
stock. Its schema/lease migration must be implemented and tested in that slice
before block placement or capability registration. Breaking/moving/cloning,
chunk unload and stale capability behavior must reuse canonical ownership rules.
No independent chunk stock copy or provider-marker identity is authorized by this
component. No schema change is claimed here.

## Consequences and verification

Slot state is bounded to nine identities and 576 total items; stackable/nonstackable
limits still apply. All inventory/handler work belongs to the server thread and
scans a fixed maximum of nine slots. Native automation touches real local stock;
program/UI/network operations still use shared authorization, scheduling and
resource accounting. Import presets will use the existing priority/round-robin
routes, backpressure and conserved staging, with bounded per-interface/tick work.
The handler guard must validate actual loaded physical ownership, not remote
scope or a cached client grant. Buffers are not published by storage catalog.

Portable and actual Minecraft/NeoForge JVM tests cover slot operations, exact
identity, backpressure, simulations, dirty callbacks, reload snapshots, partial
staging cleanup, metadata rejection and stale handler access. Physical hopper
flow, canonical migration/interruption, clone/movement/chunks, real client UI and
survival onboarding remain owning-stage evidence. This is a prerequisite for the
playable storage loop; it does not complete A01–A17 or the Buffered Interface.


## Canonical ownership extension — 2026-10-12

World schema eight adds required nested buffer schema one and a distinct
BUFFERED_INTERFACE structural kind. NodeRegistry retains generation/copy/conflict
semantics; BufferRegistry owns the matching inventory with runtime handle/position/
owner-token bindings. At most 256 owners share 16 MiB estimated slot metadata.
ItemBuffer now accepts a proposed-state admission predicate, evaluated before any
insertion, including simulation. Quota rejection retains caller input; actual
positive mutation updates shared byte accounting before marking SavedData dirty.
Restored buffers start unbound. Buffer state and staging share normal canonical
save boundaries, while independent provider files still have crash ambiguity.

The alternative of representing a buffer as a zero-capacity digital cell would
hide its ownership role, risk accidental enumeration and require a useless second
ledger. Separate inventories with chunk-owned stacks remain rejected. Distinct
structural ownership and one table make identity/capacity/conservation explicit.
Schemas one through seven migrate with empty buffers and exact existing tables;
unknown/malformed/duplicate/over-limit payloads remain raw and quarantined. A
structural owner without inventory or inventory without owner rejects. No native
block, capability or automatic import is claimed by this extension.


## Authorized transfer endpoint extension — 2026-10-12

Expose buffers as explicit typed endpoints in the existing shared transfer path,
not foreign providers or global stock rows. Requests use authenticated actor and
server-derived origin, current segment visibility/grants, canonical structural
lease/configuration and nonloading physical checks. Network operations share
separate 64-item input/output allowances per identity/tick across wrappers;
mechanical inventory access stays local. Full/metadata/rate admission precedes
source mutation. Known actual counts alone consume allowances.

World schema nine allows BUFFER references in nested context schema two. Preserve
legacy digital/foreign context-one bytes rather than rewriting all old records
for an additive endpoint union. Context two exclusively describes operations with
at least one buffer and requires item staging. Migration from one through eight
retains all tables and counts. Buffers keep canonical ownership through partial
staging; recovery validates current identity/keys and never re-extracts. Reference
reload/removal releases live indexed bindings for the matching owner only.
Native import presets still need server-owned deployment authority; saved reference
principal metadata is not an authenticated grant or reusable actor credential.
