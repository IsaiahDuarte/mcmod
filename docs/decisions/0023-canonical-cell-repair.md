# ADR 0023 — Audited canonical cell conflict repair

Status: selected and implemented as a bounded P2/R09 prerequisite; full D03/A13
reconciliation and independent-save interruption guarantees remain unfinished.

## Decision

Support explicit operator repair of a conflicted digital item/fluid cell only at
its existing canonical drive slot. The operator supplies backing UUID and expected
current generation. Require a loaded, readable current drive object, exact physical
world/backing/generation, one matching cell tier and no unresolved transfer for
that backing. Never load a chunk, choose another holder, adopt accepted staging,
change quantities/claims or synthesize contents. Other kinds and missing/stale
canonical holders stay unavailable for investigation.

Before mutation, preserve full canonical ledger bytes, generation/conflict/location,
world namespace/schema and the complete serialized physical cell stack. Rotate the
canonical generation exactly once, clear conflict, keep stock/claims/location and
leave availability false. Replace only the original slot's lease, preserving other
components. Revalidate all drive slots and invalidate network membership normally;
routing can resume only through current network validation. Every previous copy
has a stale generation and cannot quarantine the new lease on reload.

The portable registry method is trusted owner-thread infrastructure, not player
permission checking. The operator command and physical adapter supply permission,
loaded-holder, unresolved-transfer and evidence checks before invoking it. No
provider/resource callbacks occur in the repair critical section.

## Evidence format and limits

A separate directory, world/factorycore-device-recovery, contains uncompressed
binary NBT audit schema1. Fields: Schema, WorldSchema12, World UUID, Generation long,
Conflict boolean byte, Location (Dimension, Position, Slot), Ledger checksummed
component bytes and Stack native serialized item. Existing world12, ledger1 and
cell-reference1 formats are unchanged; no world migration is required. Audit files
are evidence, not executable restore instructions or a new canonical owner.

Names are backingUUID-SHA256(bytes).nbt. Reuse identical evidence; reject corrupt
existing files without overwrite. Maximum512 files,9MiB/file and32MiB total,
independent of the transfer audit directory. The ledger remains at most8MiB;
physical item plus envelope must fit remaining space. Serialization stops at the
file bound. The existing bounded CREATE_NEW/force writer and directory checks are
shared with transfer audits; their512KiB SNBT contract stays unchanged. Writes and
inspection run on the server thread; explicit repair admits one attempt per20 ticks
per server. Filesystem latency can delay a tick and remains unmeasured.

## Alternatives and recovery boundary

Allowing an arbitrary survivor coordinate would let a stale or copied holder
replace canonical ownership and needs a broader reconciliation protocol. Clearing
conflict without consuming the generation lets an old copy quarantine it again.
Dropping claims, copying stock or refunding pending staging would violate existing
ownership/accounting. Encoding an8MiB ledger as SNBT would expand byte arrays beyond
the transfer audit limit. The separate bounded binary envelope avoids that expansion.

Canonical and chunk updates complete synchronously before an orderly save, but
Minecraft saves them independently. A hard interruption may leave a stale physical
reference with newer canonical generation, or the converse. Such a mismatch stays
inert; this command intentionally refuses it. Preserve the audit and full backups
for investigation. Do not promise atomic crash recovery, automatic reconciliation,
uncertain external refunds, or repair of banks/buffers/structural nodes.

## Verification

Core cases cover immutable evidence, unchanged quantities/claims, wrong location,
foreign/unknown/stale/unconflicted/portable leases, overflow, offline restore and
stale-copy rejection. Audit cases cover exact binary contents, components, reuse,
corruption, oversized data and blocked output. Native cases exercise the actual
operator dispatcher, copied drive conflict, current physical holder/tier, failed
evidence with unchanged authority, saved reference reload, revalidated extraction
and unresolved staging refusal. Final commands/results belong in the plan.
