# ADR 0013 — Sided external storage registration

Status: accepted initial P2/P3 boundary; physical connector/explicit transfer
primitives have local component evidence. Full external catalog, reservations,
provider identity persistence and restart/reconciliation remain incomplete.

## Decision and alternatives

A Storage Connector is a leased structural device, not a digital ledger. Extend
the canonical NodeRegistry with the explicit string kind CONNECTOR; do not create
permission networks or empty replacement stock for it. Store reference/configuration
in local connector schema one. Unknown schemas/fields retain the full recovery
payload; consumed portable leases cannot place again. Existing SavedData schema
four and reference schema one remain wire-compatible for old records. Older
binaries do not know CONNECTOR and reject/preserve such saves rather than accepting
unknown semantics. Downgrading to those binaries is unsupported.

Register one loaded, sided, bounded provider view per backing resource kind.
Vanilla containers use known InvWrapper/SidedInvWrapper ownership, with double
chest halves normalized into one physical group. Other providers require complete
`ExternalBackingProvider` metadata; opaque aliases return UNSUPPORTED. Shared
handler instances and overlapping groups reject OVERLAP, even through different
faces or disjoint slot windows. Canonical banks/drives cannot become foreign
stock. Providers must report nonblocking, immutable, complete lists of at most
64 same-dimension backing blocks; all must be loaded. This is an integration
contract, not proof against dishonest provider code. Real third-party identity
adapters and their compatibility evidence remain owning-stage work.

Blindly identifying every side wrapper as a separate inventory would double
stock. Inferring arbitrary multiblocks from coordinates or a network scan is
unsound and unbounded. Copying observed provider contents into digital ledgers
would invent ownership, stale reservations and independent save-order problems.
Explicit backing descriptions and rejection of unknown aliases preserve the
selected accounting boundary while leaving a concrete extension point. No
optional mod becomes a mandatory class-loading dependency.

Every move uses WorldStorageTransfers and the canonical staged-transfer registry.
Foreign simulation happens after record admission inside the staged operation;
actual mutation intents are recorded before calls, and unknown outcomes quarantine
without replay/refund. Malformed exact resource identities reject before queueing
and cannot quarantine a valid provider. Expected permissions, stale handles,
provider availability and type/access policy recheck at execution. Recursive
provider mutation/simulation is rejected before unbounded reentry.

## Bounds and lifecycle

Initial connector defaults: 64 item units/tick or 1,000 mB/FE per tick, independently
for each direction. Configuration maxima are 128 item units or 1,000 mB/FE, priority
-1,000 through 1,000, and up to 64 selected item slots starting at any valid platform
index. These are provisional defaults, not a measured performance claim. Admission
also retains the shared 128-item/1,000-mB-or-FE command quantum, 16 credits per
command and existing queue/record quotas. Registered metadata permits at most
4,096 bindings and 16,384 physical claims. Per-tick rate maps permit at most
4,096 provider groups and 12,288 connector/kind entries.

Both provider and stable connector identities consume rate budgets, so changing
settings/backing metadata cannot reset them. Simulations consume no units. Before
actual calls reserve their maximum offered amount; refund only known unaccepted
units. A fault retains the conservative charge until the next tick. Capability
invalidation, provider replacement, rotation, configuration and ownership movement
invalidate old handles. Rotation also waits for normal topology revalidation.
Chunk probes do not load chunks; provider API internals must honor their contract.

Runtime binding tokens remain transient. Backing UUIDs now derive from strict
world-scoped component markers saved on provider block entities (extension below).
Durable staging persists, but external endpoint context and automatic rebind are
not yet implemented. Do not enable automatic restart retries, claim full unload/restart
acceptance, or remove uncertain records based on these IDs. Persist endpoint
context and add the recovery driver before declaring connector integration or
automatic presets release-complete. Known/uncertain staging remains canonical.

## Evidence

ConnectorDataTest checks strict limits/reference schemas and preserved fault data.
NodePersistenceTest adds real compressed connector lease movement/reload without
inventing network grants. Nineteen ConnectorGameTests cover real vanilla and sided
NeoForge handlers, partial identity-preserving item/mB/FE moves, reservations,
revocation, child privacy, overlap/double-chest normalization, configuration,
movement, future data, rotation, rate/reentry failures and explicit unsupported
providers. The sparse-slot adapter regression covers the last valid signed-int
slot and negative slot-count arithmetic without scanning/allocating that inventory.
These are component checks; client, multiplayer, actual chunks/restart and
representative throughput/heap evidence remain pending.

Inspected pinned 21.1.252 capability/cache, vanilla container and handler sources.
The [official 1.21.1 capability documentation](https://docs.neoforged.net/docs/1.21.1/inventories/capabilities/)
provides the sided query/cache/invalidation API used here. Acceptance depends on
the actual local implementation/tests, not documentation alone.


## Persistent provider component identity — 2026-10-11

Selected the existing NeoForge block-entity persistent-data extension after
inspecting pinned 21.1.252 load/save/getPersistentData sources. Under its owned
namespace `factorycore:external_identity`, schema one stores only Schema, World
UUID and component Id UUID. It stores no quantity or inventory snapshot. Validate
all existing markers before initializing any missing component and mark changed
blocks dirty; preserve unrelated persistent data. Unknown versions/fields, wrong
worlds and copied component IDs inside a group reject without overwriting evidence.
Every described group member now needs a loaded persistent block entity.

Derive a kind-specific UUID from world namespace and the sorted component UUIDs
(maximum 64). Reordering parts, settings changes and normal block NBT reload retain
identity; replacement without the original marker gets a new identity. Changing
group membership or kind gets a distinct identity. Copied markers at separate
positions cannot register concurrently: backing-ID ownership joins physical-group
and handler-instance overlap checks. Provider rate budgets now follow this backing
ID; stable connector budgets still prevent same-tick group-change bypass.

Coordinate-only IDs could return staging into a replacement machine. Runtime-only
IDs prevented reliable matching after ordinary reload. A database or chunk scan
would not make third-party writes transactional. Reference-only provider markers
supply stable identity while preserving foreign inventory ownership. Custom
providers must retain the superclass persistent data during save/load; unsupported
serializers cannot claim this recovery guarantee. The official
[block-entity storage contract](https://docs.neoforged.net/docs/1.21.1/blockentities/)
requires preserving superclass fields and marking changed data for saving.

This is an identity prerequisite, not a complete recovery driver. No fsync or
universal cross-file crash atomicity is promised. Old schema-four transfer records
with former transient IDs remain unbound; never guess a marker to make them match.
Persist explicit source/destination/origin context and validate current actor,
network/segment, ownership, policy and endpoints before continuation. Pending calls
remain uncertain and cannot replay/refund. Full chunk/restart and injected save
boundary evidence remain required.

## Bounded stock observations — 2026-10-12

Expose 16-position advisory pages through the existing shared 16-credit scheduler
path, retaining configured item windows and tank-index pagination. This bounds
host read calls without copying or taking ownership of provider resources. The
alternative of scanning every handler slot/tank in a single catalog call violates
bounded host-work requirements for large third-party providers. Pages retain exact
component identity and label unobservable item insertion capacity as unknown.
VIEW/current visible segments precede provider reads, and failures quarantine
without publishing partial rows. Higher-level stock claims and catalog aggregation
remain separate incomplete requirements; an observation never reserves stock.

## Shared external hold metadata prerequisite — 2026-10-12

Use a separate portable claim registry keyed by stable backing/owner/exact key;
retain no observed foreign stock. Reusing an owned ResourceLedger by inserting
observations would create an authoritative resource copy and reject legitimate
foreign shortages on reload. Fresh observation instead bounds background/claimed
extraction while preserving other owners' holds. Unknown handler outcomes retain
claims for canonical quarantine/reconciliation. Metadata uses separate bounded
4,096-claim/4 MiB quotas; representative performance remains unmeasured.

The core implementation and immutable snapshot/restore exist. World persistence,
versioned codec/migration, authenticated shared scheduler integration and job
reconciliation remain required before exposing reservations. This decision does
not make foreign providers transactional or permit replay of uncertain calls.

## Scheduled principal holds — 2026-10-12

Canonical holds now protect every connector extraction path. The public scheduled
API derives hold owner from the authenticated actor, avoiding arbitrary job IDs
before a job-owner registry exists. Fresh complete totals use at most 64 configured
item positions/64 fluid tanks/one FE provider; larger fluid providers retain
paginated observation/unheld calls but cannot safely serve claims yet. This bounded
conservative limitation avoids unbounded scans or stale-page reservation promises.
Known actual extraction consumes holds; uncertain retained transfers block release
through a bounded 1,024-record check including restored/unbound records. Metadata
cleanup may proceed after grant revocation without calling resource handlers.
Crafting jobs must add authenticated ownership/reconciliation, never adopt arbitrary
principal holds. No performance throughput guarantee is claimed by these bounds.

## Bounded catalog discovery — 2026-10-12

Use metadata-only UUID-ordered registry indexes and eight-record scheduled steps
for exact-key live catalog discovery. The alternative of capturing every ledger
for each page allocates/copies unrelated resource maps; a standalone representative
measurement is recorded in ../performance/CATALOG_METADATA.md. Trees cost
O(log records) updates/seeks and provide stable bounded continuations. Discovery
checks current VIEW/segment before provider probing, and publication rechecks
scope after callbacks. Server-only cursors bind actor/root/segment/key. Per-page
priority sorting does not implement global distribution or persisted round-robin;
those need bounded collected query state. No disk format changes in this slice.
