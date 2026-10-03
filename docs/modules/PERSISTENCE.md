# Ledger persistence contract

Initial P2 component implementation. World registry, portable ownership leases,
staging/jobs, save ordering and chunk integration remain pending under
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
releases reservations. Only the future canonical registry may install one
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
cannot represent, preserving original contents. P2 world admission must account
for byte budgets before deposits; the P1 ledger constructor alone does not
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
