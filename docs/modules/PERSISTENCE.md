# Ledger persistence contract

Initial P2 component implementation. The [device registry/adapter](DEVICE_OWNERSHIP.md)
now owns world-scoped generations and exact ledgers, with physical cell/bank
references and [canonical transfer staging](TRANSFER_PERSISTENCE.md). Job state,
complete world save ordering and chunk integration remain pending under
[ADR 0005](../decisions/0005-persistence-ownership.md). This codec does not by
itself make a playable device durable or prevent creative clones.

## API and lifecycle

`ResourceLedger.persistentState()` captures stable ID, kind, capacity/tier,
catalog/claim limits, exact contents and all owner/key reservations on the
ledger's owning thread. `LedgerState` copies maps/lists and retains immutable
resource keys. It rejects nonpositive stock/claims, wrong kinds, duplicate
owner/key claims, claims beyond stock, finite-capacity excess and signed-long
aggregate overflow. Different owners may claim the same key.

`LedgerStateCodec.encode(state)` and `decode(bytes)` are bounded operations
that may run on a worker with immutable snapshots. Encoding uses deterministic
key/owner ordering; `ResourceKey.compareTo` orders by kind, registry ID and
unsigned component bytes, consistent with equality. `componentByteCount()`
returns length without exposing mutable metadata. Decode copies input after
checking size and never modifies the caller's recovery bytes. Malformed,
checksum-invalid, unsupported-schema or over-limit data throws
`IllegalArgumentException`; no fallback empty ledger is returned. SHA-256 is
integrity checking, not authentication or a portable ownership credential.

`ResourceLedger.restore(state)` constructs a new ledger on the calling thread
with the same backing UUID and exact claims, initially **offline**. Queries
retain total/reserved counts and expose zero availability; operations reject
until the adapter revalidates and calls `setLoaded(true)`. Loading never silently
releases reservations. Only the canonical registry may install one
authoritative instance per backing UUID. Calling restore twice does not establish
two valid owners; duplicate resolution belongs to that registry.

```java
byte[] encoded = LedgerStateCodec.encode(ledger.persistentState());
LedgerState validated = LedgerStateCodec.decode(encoded);
ResourceLedger recovered = ResourceLedger.restore(validated);
// Keep offline until the world registry validates ownership, topology and grants.
```

Quantities remain exact long item units, mB and FE. Limits are 8 MiB per envelope,
4,096 configured catalog entries, 8,192 configured claims, 256 ASCII registry-ID
bytes and 65,536 component bytes/key. These technical/administrative limits are
independent of item tiers. Serialization rejects snapshots/configurations it
cannot represent, preserving original contents. The registry preflights byte
budgets before deposits/reservations; the P1 ledger constructor alone does not
enforce persistent-device admission.

## Version-one format

Integers use big-endian encoding; UUIDs are two 64-bit words. The payload ends
with its 32-byte SHA-256 hash. Loaded availability is not persisted.

| Field | Encoding |
| --- | --- |
| Magic/schema | 32-bit `0x46434c52` / 32-bit version `1`. |
| Backing ID | UUID, 16 bytes. |
| Resource kind | One byte: item `0`, fluid `1`, energy `2`. |
| Capacity/tier | Exact 64-bit capacity; one-byte infinite flag `0`/`1`. |
| Limits | 32-bit catalog limit, 32-bit claim limit. |
| Contents | 32-bit count; repeated exact key then 64-bit positive quantity. |
| Reservations | 32-bit count; repeated owner UUID, exact key, 64-bit positive claim. |
| Exact key | Unsigned 16-bit ID length, ASCII ID, 32-bit component length, bytes; kind inherited from envelope. |
| Integrity | SHA-256 of preceding bytes. |

The empty envelope is 82 bytes. Version one is the first experimental component
schema; no older released format exists. Unknown versions, including zero, are
retained and rejected for explicit migration. Future changes require a new
version, migration evidence and preserved compatibility fixture. Component bytes
remain exact; platform registry migration/decoding is the adapter's responsibility
and must not silently delete unknown resources.

## Recovery evidence

`LedgerPersistenceTest` exercises empty/all-kind saves, quantities beyond 2^53
and Long.MAX_VALUE, competing claims, safe downgrade, offline recovery, immutable
snapshots, deterministic bytes, unknown schemas, corruption, truncated/amplified
inputs, invalid/duplicate claims, overflow and serialization byte ceilings.
The fixed `src/test/resources/persistence/ledger-v1.hex` fixture protects schema
compatibility; it contains 17 FE with a 7-FE owner reservation. Generated recovery
performs 2,000 stock/claim operations with a save/reload after each operation,
replay seed `0x5A7E001`. This is portable recovery evidence, not a world crash or
external-inventory atomicity guarantee.

The world envelope now targets schema six, retaining schema-five [accepted transfer context](TRANSFER_PERSISTENCE.md).
Ledger/component formats remain unchanged. Version-one through four world fixtures
preserve their exact data; missing legacy context is never invented.

## Canonical foreign claims — world schema six

The canonical world file now writes schema six with an additional ExternalClaims
compound (nested schema one: Schema and Claims). Each positive claim contains
Backing/Owner UUIDs, Kind/Registry/Components exact identity and Amount long units
(items, mB or FE). No foreign contents, observed totals or platform handles are
stored. The world namespace comes from the enclosing authority. Claims save at
the same server-thread snapshot boundary as digital ledgers, grants and staging.
Hold changes mark SavedData dirty; zero/rejected operations and restore do not.

Schemas one through five migrate with an empty foreign claim table, preserving
all existing tables, quantities and intent evidence. Schema-five context and
transfer-entry schema two remain unchanged. Missing table/fields, wrong types,
future/unknown fields, duplicate claims, nonpositive amounts, total overflow,
4,096-record/4 MiB metadata violations or backing collisions with owned
ledgers/nodes/networks quarantine the entire authority and preserve its raw tags.
Global schema seven and newer reject; nested future schemas reject. Old binaries
reject schema six, so downgrade requires a matching backup.

Foreign providers may be absent or empty during restore. Orphan claims remain
held; no load-time release, guessed inventory, automatic retry or refund occurs.
Snapshot references do not establish usable stock, grants or current provider
identity. Authenticated scheduler/provider/job reconciliation is still required
before world reservations are exposed. Hard crashes cannot make independent
third-party saves atomic. Pending uncertain staging stays quarantined separately.
Fixed nested-claim and schema-five world fixtures plus compressed SavedData reload
cover exact quantities including beyond 2^53, orphan preservation, dirty behavior,
strict rejection and migration. Real server-restart/interruption evidence remains
pending.
