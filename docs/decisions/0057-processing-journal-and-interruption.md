# ADR 0057 — Processing dispatch journal and interruption recovery

Status: implemented. The canonical gate now includes a seed JVM that is SIGKILLed and three
restore JVMs (2026-10-19).

## Context

A03, A09 and A13 require jobs and programs to survive abrupt termination and independent saves
without replaying uncertain foreign calls, inventing units or silently losing them.

- Minecraft saves `factorycore_devices.dat` (which holds job records) and chunk regions (which
  hold the machine's contents) at different moments.
- A processing job's progress therefore had no proof at the machine.
- A foreign handler exception made the next pass re-insert (a replay bug).
- The deduplication of program `craft` requests lived only in memory, so a reload submitted a
  second job.

## Decision

- **Co-located journal.** `ProcessingJournal` lives in the leased machine block entity's
  persistent data (`factorycore:processing_journal`, schema 1). It holds the world, the job, a
  digest of the exact inserted/collected maps, the totals and a Pending flag. The chunk record
  saves it together with the machine's contents.
  - **Writes.** The journal is written when the dispatch is captured. Every real
    insert/extract/fill/drain call is marked pending before it starts and settled after it
    returns.
- **Checks before any machine call.**
  - A pending mark gives `uncertain`.
  - A digest that differs from the job record gives `save_mismatch`. So does a missing journal
    for non-empty saved progress, or one naming another job.
  - Either way the job stops making machine calls, and its cancel cleanup freezes too.
  - An unfinished journal at a machine blocks new dispatch with `machine_journal` before any
    ingredient is pulled. An empty orphan journal is cleared automatically.
- **Handler exceptions.** An exception from a foreign handler quarantines the job as
  `uncertain`. Nothing is retried.
- **Operator resolution.**
  - `/factorycore recovery processing show|resolve <x y z>` needs permission 2 and allows one
    resolution per second.
  - It first writes audit schema 8 `PROCESSING_JOURNAL` evidence. The evidence records
    `{Absent:1b}` when the machine holds no journal.
  - It then makes the saved job record authoritative, or removes an orphan journal.
  - It never moves resources.
- **Program craft link.** A program's `craft` request is linked to its job by deployment, rule
  and item, saved in the job's `rule` field (`WorldPrograms.craftLink`). A reload or restart
  therefore finds the existing job instead of submitting another.

## Alternatives

- **Single combined save file.** Rejected: Minecraft owns chunk saving.
- **Infer progress from the machine's contents.** Rejected: foreign machines transform and
  share contents, so this guesses.
- **Automatic replay or refund.** Rejected: forbidden for uncertain foreign writes.

## Consequences

- Save skew can still **lose** units: those a newer chunk already handed back when the ledger is
  older. Rolled-back units can reappear in a machine, but stay outside the ledger. The journal
  turns both into diagnosed, operator-resolved states; it never duplicates them into storage.
- Generic connector and program transfers into foreign inventories have no journal. Independent
  save skew can still duplicate or lose those units; R09 does not promise otherwise.
- The canonical gate grew from about 2m35s to 4m15s.

## Evidence

- GameTests: six `ProcessingJournalGameTests`, including a missing-journal regression found by
  the two-JVM run, and `ProcessingJournalTest`.
- Two-JVM suite: `runInterruptionSeedServer` with `runInterruptionKill/DataOld/ChunksOldServer`.
  [Evidence](../evidence/interruption-v1-2026-10-19.md).
