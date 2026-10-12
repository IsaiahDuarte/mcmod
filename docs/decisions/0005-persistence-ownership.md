# ADR 0005 — Ledger persistence and device ownership

Status: selected D03 design; ledger schema, owner registry and SavedData adapter
locally implemented in P2. Physical cell/drive lease tests now pass locally; complete save ordering, actual chunk unload/restart and interrupted transfers remain unvalidated.

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
are implemented and now wired to [physical cells/drives](../modules/STORAGE_CELLS.md), with headless placement/break/component tests. Actual chunk unload/restart remains pending.

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

The portable StagedTransfer prerequisite now captures immutable accounting
snapshots and notifies a caller-supplied recorder before each mutation and after
known accounting. Records include endpoint IDs, intent kind/amount and exact
known staging/delivery/returns. Rebinding requires matching IDs; any pending call
restores quarantined without handler calls, retries or refunds. This extends the
selected recovery design without changing a disk schema. The bounded canonical TransferRegistry and schema-four SavedData now persist
these records beside ledgers and grants. Schemas one/two/three migrate with empty
transfer tables; component schemas remain unchanged. Admission reserves maximum
record metadata before extraction (1,024 records / 4 MiB separate from the ledger
budget). A notification alone supplies no crash atomicity; actual world endpoints,
restart/save-boundary interruptions and reconciliation remain pending.

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
an empty replacement when Minecraft swallows a corrupt-file read. Physical cells/drives now have required headless tests; full staging/jobs and external-write recovery remain pending.

The final canonical verifier passed 41 JVM and 24 tooling tests. Two orderly
dedicated-server starts created/reloaded the empty registry with the same file
checksum. Nonempty stock/claim recovery is covered by actual SavedData disk tests;
at that slice large saves and physical block/item ownership were not yet verified. The subsequent physical-cell slice adds ten required Minecraft GameTests; full acceptance and interruption validation remain incomplete.

Primary adapter sources inspected 2026-10-03: pinned 21.1.252 merged sources for
SavedData, DimensionDataStorage and IOUtilities, and
[official 1.21.1 SavedData docs](https://docs.neoforged.net/docs/1.21.1/datastorage/saveddata/).


## Accepted transfer context extension — 2026-10-11

Advance the canonical world envelope to schema five and transfer entries to schema
two, embedding strict context schema one before any resource call. Save accepted
world/network/segment/origin and endpoint device/backing/lease/config generations
beside the single owned staging record; do not persist operator authority or live
handlers. Restore legacy entry-schema-one records without inventing context or
rewriting quantities/intents. Missing legacy context means explicit reconciliation,
not a coordinate guess. This avoids an independent context file/save boundary or
silently reinterpreting old entries as fully resumable operations. Existing source,
destination and count evidence remains intact through migration fixtures.

New context consumes 1,024 estimated bytes in addition to the existing fixed/key
record budget, while count/byte limits remain unchanged. Fresh world validation
must still recheck accepted scope/current actor, endpoint identity/generations,
permissions/policy and loaded status before delivery/cleanup. No pending intent
may replay/refund. The initial context codec and compressed canonical disk tests
are implemented; the recovery scheduler, real chunks/full restart and injected
independent-save boundaries remain incomplete.

## Canonical foreign hold table — 2026-10-12

Global schema six adds nested-schema-one external holds to the existing authority.
The alternative of a separate claim file would split holds from owned staging at
normal save boundaries. The table stores backing/owner/exact-key/long amount only;
foreign resource contents remain owned by the provider. Dirty callbacks track
positive known mutations; restore retains orphan holds without current stock.
Schemas one through five migrate with an empty table; nested/global unknown or
malformed data preserves and quarantines the whole original authority. Owned
identity collisions reject. No load-time release or uncertain-call replay occurs.
Current implementation covers canonical save/codec/migration/disk tests. Shared
scheduled reservation/extraction, job reconciliation and real restart/interruption
remain incomplete; the table alone supplies no cross-mod crash atomicity.


## Canonical routing preference extension — 2026-10-12

World schema seven adds fixed-size nested-schema-one round-robin cursors to the
same server-owned authority. A cursor records network/segment/principal/deployment,
fixed backing/kind/direction and last selected backing only. Unlike holds or
staging, it grants no stock ownership. Advancing before dispatch coordinates
concurrent requests without replaying uncertain resource calls; even a failed
attempt advances distribution. Authority and endpoint validation still belong to
the shared scheduler/transfer path. At most 1,024 scopes are retained.

An independent preference file would be resource-safe but adds a new loader/save
boundary without a current benefit. Storing the cursor only in connector NBT would
couple routing to one physical device and make terminal/program deployments lose
their shared canonical preference. Canonical metadata supports each caller with
strict scope isolation. Schemas one through six migrate with an empty table;
unknown/malformed/duplicate/over-limit data is preserved and quarantined. Downgrade
requires a matching backup. A stale saved preference changes only target order;
it cannot authorize uncertain-transfer replay. Schema-five transfer context and
entry schema two are unchanged. Actual server interruptions remain unverified.
