# ADR 0005 — Ledger persistence and device ownership

Status: selected D03 design; ledger schema, owner registry and SavedData adapter
locally implemented in P2. Physical block/item ownership, complete save ordering
and interrupted transfers remain unvalidated.

## Decision and boundaries

Use one server-owned world device registry as the canonical authority for digital
ledgers/reservations; trusted staging joins this boundary in its owning slice.
Block entities and portable
cells refer to a world namespace/backing UUID/ownership generation; they do not
each retain an authoritative contents copy. Never independently save debit,
credit and digital staging into separate chunks. Root disconnection preserves
device ownership and marks it unavailable. A view never creates another owner.

The core registry transfers the ownership lease through placed -> portable
-> placed transitions. A consumed portable generation cannot claim again;
creative copies of a handle remain duplicate references, not extra stock. Conflicting
live locations become unavailable with diagnostics until resolved. Restoring a
backup requires an explicit registry operation; unknown/incompatible data must
be retained for recovery rather than reset. These world transitions are selected
requirements for subsequent physical block/item slices. Core lease transitions
are implemented; placement/breaking and item components remain pending.

The initial component schema is a version-one exact ledger snapshot: backing
UUID, resource kind, finite capacity/infinite flag, catalog/claim limits, positive
exact contents, and positive owner/key reservations. Loaded availability is
transient. Every restored ledger starts offline until its owning world adapter
revalidates membership and authority. Snapshot records are immutable and contain
no platform inventory references. An orphan reservation stays held until the
job registry explicitly reconciles it; loading never releases it automatically.

Use a bounded binary envelope with magic/version, deterministic key ordering and
SHA-256 integrity checksum. Reject unknown schemas, truncated/checksum-invalid
bytes, duplicate entries/claims, inconsistent quantities and limit violations.
No older released ledger format exists; version zero and future versions fail
explicitly. A future schema change must provide reviewed migration fixtures and
cannot change old bytes in place. This codec is not itself a world file writer,
atomic-save mechanism or clone-prevention registry.

## Interrupted operations

World integration will snapshot all owned digital changes on the server thread
at the orderly save boundary. Digital transfer states are created -> staged ->
delivered or returning -> returned, with owned remainder retained at each step.
Jobs/reservations are included in the same authoritative save boundary; they may
not resume merely because a chunk reloaded. Reconciliation revalidates device
ownership, reservations and current grants before scheduled mutations.

Third-party inventories cannot participate in this atomic authority. Before an
external call, record the intent/owner/last known quantity; after a successful
call, record actual returned units. A fault or uncertain interrupted external
call transitions to quarantined: no automatic retry, speculative refund or
catalog availability. P2/P3 must implement these records and save integration.
Arbitrary crash/power-loss atomicity across external mods is not promised; the
normal-operation guarantee includes orderly save/restart and chunk unload.

## Alternatives and consequences

Contents stored independently in every item/block entity would simplify local
NBT but creates clone/double-owner and cross-chunk ordering problems. A database
or fsync for every inventory call adds packaging and tick latency without making
foreign inventory writes transactional. The registry design preserves one owner
and gives a shared save boundary; bounded incremental serialization/admission
and world quotas remain necessary before production use.

The initial codec accepts at most 8 MiB, 4,096 catalog entries, 8,192 reservations,
256 registry-ID bytes and 65,536 component bytes/key. These are technical bounds,
not gameplay type-count tiers. Ledger configurations above codec limits reject
serialization explicitly. The registry budgets metadata before taking deposits
and reservations. World ceilings are 1,024 devices, 64 MiB ledger envelopes and
65,536 combined stock/claim records. These bounded defaults protect admission
and snapshot size; full heap and server autosave latency remain unmeasured.

## Validation

Require exact boundary round trips and immutable snapshots, reservation recovery
offline, deterministic encoding, corruption/unknown-schema rejection, duplicate
claim/overcommit/overflow rejection and orderly snapshot conservation. Full D03
validation also requires world move/break/clone, unload/reload, single-authority
save ordering and interrupted external-write fixtures in subsequent slices.

Local `./gradlew spotlessApply build` and `python3 scripts/verify.py` passed:
30 JVM tests (six new LedgerPersistenceTest cases), 24 tooling tests, formatting,
PMD, architecture/compiler and pinned Rust gates. Recovery includes 2,000 seeded
reload operations and the committed schema fixture. See [module contract](../modules/PERSISTENCE.md)
and [implementation evidence](../IMPLEMENTATION.md) for practical limits.

The subsequent [ownership contract](../modules/DEVICE_OWNERSHIP.md) records
generations, holder uniqueness, offline revalidation and quarantine. Actual
NeoForge SavedData disk tests cover rejection/preservation, including avoiding
an empty replacement when Minecraft swallows a corrupt-file read. Physical
devices, full staging/jobs and external-write recovery remain pending.

The final canonical verifier passed 41 JVM and 24 tooling tests. Two orderly
dedicated-server starts created/reloaded the empty registry with the same file
checksum. Nonempty stock/claim recovery is covered by actual SavedData disk tests;
large saves and physical block/item ownership are not yet verified.

Primary adapter sources inspected 2026-10-03: pinned 21.1.252 merged sources for
SavedData, DimensionDataStorage and IOUtilities, and
[official 1.21.1 SavedData docs](https://docs.neoforged.net/docs/1.21.1/datastorage/saveddata/).
