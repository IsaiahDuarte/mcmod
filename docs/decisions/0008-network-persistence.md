# ADR 0008 — Canonical network grant persistence

Status: accepted; selected before source, with local P2 adapter evidence. Physical structural
leases, discovery and device activation remain subsequent work.

## Decision

Extend the existing Overworld `factorycore_devices.dat` authority to schema two
with network UUID, owner UUID, grant generation and explicit operation grants.
Keep metadata in the same SavedData boundary as resource ownership rather than
introducing a separate grants file with independent orderly-save state.
Network UUIDs are allocated by the authority, disjoint from resource backing IDs;
block references cannot manufacture an owner or reset a missing network record.

Migrate recognized schema-one saves by preserving world identity, exact ledgers,
claims and lease generations, adding an empty network table and marking the
recognized migration dirty. No previous slice contained network records. Schema
two requires an explicit network list. Unknown schemas/fields, malformed masks,
duplicate identities/principals and invalid ownership fail closed with the
original source retained and write-protected. Existing/unreadable files never
become empty replacements through a swallowed upstream read failure.

Persist explicit permission bits: VIEW=1, DEPOSIT=2, WITHDRAW=4, CRAFT=8, EDIT=16,
DEPLOY=32, MANAGE=64. Owner rights remain implicit; operator status is resolved
from current server authentication and is never stored. Unknown bits and empty
explicit grants reject. Enum reordering must not alter saved meanings.

## Alternatives and consequences

A separate grants file would introduce another independently saved authority
without helping any current gameplay boundary. Keeping grants in block NBT would
let cloned or stale references manufacture an owner. Retaining schema one while
adding optional unknown fields would leave older writers free to erase metadata.
A required versioned table instead allows exact legacy migration and explicit
rejection by incompatible readers. Quarantining the entire canonical authority
on invalid grants suspends otherwise valid resource owners as well; that favors
preservation over silently loading a partially trusted world.

## Admission and boundaries

Limits are 1,024 networks, 256 explicit principals/network and 65,536 explicit
network/principal bindings/world. Principal-count deltas are preflighted before
mutation and accounted in O(1). Revocation and transfer reclaim admission space;
failed admission/overflow preserves grants, owner and generation.

No stock is created by commissioning network metadata. A network UUID is not a
physical ownership lease or usable topology scope. Controller/block leases,
placement/clone handling, topology reconstruction and stored gateway policies
must be implemented before activating storage or exposing player operations.
Future structural metadata must use a versioned compatible extension/migration,
not abuse resource ledgers or the permission generation as physical lease state.

This schema does not add durable transfer/job staging or promise abrupt crash
atomicity across chunks/player inventory/foreign handlers. Orderly SavedData
save/reload is the current boundary; outer NBT read/allocation and disk errors
remain upstream-owned. Count ceilings are not measured heap/autosave guarantees.

## Verification

Require fixed schema-one migration and schema-two grant fixtures, exact offline
stock/claim preservation, real compressed disk reload, unchanged permission bits,
owner transfer/revocation across reload, global admission/reclamation, collision
and corruption rejection, and write protection. Continue full P2/P7 world,
interruption, physical identity, authority and performance verification separately.
