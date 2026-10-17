# ADR 0022 — Recovery inspection and audited known cleanup

Status: selected/implemented P2/P3/R09 prerequisite; broader ownership-conflict
reconciliation and independent-save guarantees remain unfinished.

## Decision

Expose `/factorycore recovery list [after]` and `show <id>` only at vanilla command
permission level2. Operators/console can inspect world transfer metadata without
provider access or loading chunks. A sorted registry index returns at most16
immutable records per page; components are never dumped into chat. Show reports
counts, uncertainty, original principal, scope/origin/device IDs and safe next
steps. Unknown external results stay quarantined; there is no force/refund/replay.

`return <id>` requires the actual original player as both command source and
execution entity, a live body registered in PlayerList and matching record owner.
`execute as`, consoles, fake players and other principals cannot impersonate the
owner. Owner cleanup is global explicit recovery, not a nearby menu interaction;
it retains the existing original-owner cleanup exemption after grant revocation.
It uses only shared scheduled progress with current accepted scope/endpoints,
rates/access/capacity and known staging. It never extracts or adopts another
principal's work. Queued accepted cleanup may continue after logout unless the
original endpoint itself requires the current player connection.

Preserve an immutable before-state file before enqueueing any known cleanup.
Completed records remain canonical until explicit operator `retire <id>`, which
requires COMPLETE/no pending/no staging and preserves a separate before-state
archive before releasing metadata admission. Operators may retire empty evidence
records but cannot mutate/adopt live staging. The registry repeats its retirement
invariants. IO/quota failure queues no cleanup and removes no canonical record.

## Evidence ownership and bounds

Audit schema1 is SNBT under the world root's factorycore-recovery directory,
containing World UUID, WorldSchema12 and existing transfer-entry schema2/context3.
Files are named operationUUID-SHA256(bytes).snbt. Matching retries reuse identical
evidence; differing existing content is never overwritten. Maximums:512 files,
512KiB per file,32MiB total. A directory check visits at most513 entries and rejects
unexpected entries. Writes use CREATE_NEW, at most64 channel writes and force
before admission/removal. Failed partial evidence remains for investigation;
matching retries refuse corruption. Archive records outside this directory to
free quota; no in-game command deletes the archive.

All command/world work runs on the Minecraft server thread. Owner requests are
limited to one per20 ticks per player, with at most256 recent senders; operator
retirement is limited to one per20 ticks per server. Maps clear on server stop.
Metadata listing does no provider work. Audit IO is synchronous only on explicit
recovery actions; byte/call limits do not bound filesystem latency, and a command
can delay a tick. It is not an automation polling path or a claimed performance
budget pass. P7 still needs representative abuse/IO measurements.

## Alternatives and consequences

Logs alone can rotate and lack complete components/context. Mutating canonical
metadata first would lose pre-change evidence. A new canonical audit table would
couple all recovery history to world migrations/admission; standalone immutable
files preserve original metadata without changing world schema12. A bounded async
writer could reduce IO stalls, but would need retained request ownership, stale
snapshot revalidation and shutdown handling; that optimization needs measured
justification. No new thread pool is introduced.

This workflow handles inspection, existing known cleanup and empty record
retirement. It does not repair conflicting physical owners, infer uncertain
foreign outcomes or transact independent player/chunk/controller files. Preserve
full backups and investigate unsupported cases; never fabricate rollback.

## Verification

Registry pagination and audit round-trip/reuse/corruption/quota cases exercise
bounded metadata/evidence behavior. Native commands test permission gates,
original-source identity including execute-as, evidence before scheduled return,
conservation, explicit completed retirement and denied other-owner/uncertain work.
The required dedicated restart probe also executes real operator list/show against
accepted uncertain records. Exact final commands/results belong in IMPLEMENTATION.
