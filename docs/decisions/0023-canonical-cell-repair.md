# ADR 0023 — Audited canonical cell conflict repair

Status: selected and implemented as a bounded P2/R09 prerequisite; full D03/A13
reconciliation and independent-save interruption guarantees remain unfinished.

## Decision

Support explicit operator repair of conflicted digital item/fluid cells and finite
energy banks only at their existing canonical holders (drive slot or bank position).
The operator supplies backing UUID and expected current generation. Require a loaded, readable current holder object, exact physical
world/backing/generation, matching physical tier and no unresolved transfer for
that backing. Never load a chunk, choose another holder, adopt accepted staging,
change quantities/claims or synthesize contents. Other kinds and missing/stale
canonical holders stay unavailable for investigation.

Before mutation, preserve full canonical ledger bytes, generation/conflict/location,
world namespace/schema and the complete serialized physical holder. Rotate the
canonical generation exactly once, clear conflict, keep stock/claims/location and
leave availability false. Replace only the original holder lease, preserving other
components. Revalidate physical references and invalidate network membership normally;
routing can resume only through current network validation. Every previous copy
has a stale generation and cannot quarantine the new lease on reload.

The portable registry method is trusted owner-thread infrastructure, not player
permission checking. The operator command and physical adapter supply permission,
loaded-holder, unresolved-transfer and evidence checks before invoking it. No
provider/resource callbacks occur in the repair critical section.

## Evidence format and limits

A separate directory, world/factorycore-device-recovery, contains uncompressed
binary NBT cell audit schema1 or bank audit schema2. Common fields: Schema, WorldSchema12, World UUID, Generation long,
Conflict boolean byte, Location (Dimension, Position, Slot), Ledger checksummed
component bytes. Cell schema1 retains Stack (native serialized item). Bank schema2
retains HolderKind=ENERGY_BANK, BlockState (native state NBT), and BlockEntity
(complete native block-entity NBT, including its exact reference). The bank writer
does not reinterpret older cell evidence or use an item break/drop transition. Existing world12, ledger1 and
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
uncertain external refunds, or repair of buffers/structural nodes.

## Verification

Core cases cover immutable evidence, unchanged quantities/claims, wrong location,
foreign/unknown/stale/unconflicted/portable leases, overflow, offline restore and
stale-copy rejection. Audit cases cover exact binary contents, components, reuse,
corruption, oversized data and blocked output. Native cases exercise the actual
operator dispatcher, copied drive conflict, current physical holder/tier, failed
evidence with unchanged authority, saved reference reload, revalidated extraction
and unresolved staging refusal. Final commands/results belong in the plan.


## Energy-bank adapter extension — 2026-10-17

Expose operator `/factorycore recovery bank list [after]`, `show <backing>` and
`repair <backing> <expected-generation>` through the same permission/throttle
and bounded metadata path as cells. Cell/bank pages examine at most16 canonical
handles and emit only matching kinds; a page can show zero matches while still
providing a continuation cursor. Show/repair refuse the other resource kind.

`EnergyBankEntity.repairConflict` requires the current loaded canonical object,
matching parsed/raw lease, exact finite ENERGY tier/capacity, slot0/location and
no unresolved transfer. It prepares the replacement reference and preserves the
schema2 evidence before the same canonical generation transition. It retains the
existing EnergyBankAccess service, so spending remains keyed by backing UUID and
cannot reset during repair. After normal network validation, current capabilities
use the repaired lease; cloned holders/cached clone capabilities remain stale.
Bank schema1 references and world12 remain unchanged. Audit limits/quotas are
shared with cell archives; the explicit20-tick repair throttle is shared too.

Native checks require900FE/200 claimed unchanged across repair, only100FE of the
same tick's input allowance remaining, exact800 unreserved extraction, cached
clone denial, repaired reference/canonical reload, stale snapshot inertness,
failed audit with exact no-mutation and400 ledger/100 staged unresolved refusal.
Graphical chat, real multiplayer and interrupted filesystem saves remain separate.
