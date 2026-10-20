# Handoff — A03/A09/A13 interruption/restart slice (ADR 0057, unfinished)

Branch `worktree-agent-a9f012e11da71bc82`, worktree
`.claude/worktrees/agent-a9f012e11da71bc82`. Status: WIP, `scripts/verify.py` not run.

## Done (code)

- `platform/crafting/ProcessingJournal`: co-located processing dispatch journal in the
  leased machine block entity's persistent data (`factorycore:processing_journal`, schema 1:
  World, Job, Progress digest of inserted/collected, totals, Pending). Saved atomically with
  the machine's contents in its chunk record.
- `WorldCraftingJobs`: journal written at dispatch capture; every real insert/extract/fill/
  drain call is marked pending before and settled after; any mismatch (`save_mismatch`) or
  pending mark (`uncertain`) stops all machine calls. Exceptions from foreign handlers are
  caught: the job waits `uncertain` and nothing is retried (previously the next pass
  re-inserted: replay bug). A cancelled job with a disagreeing journal, or with an
  unloaded target chunk (`machine_unloaded`), freezes delivery/release/abandonment.
  An unfinished journal at a machine blocks new dispatch (`machine_journal`) before any
  ingredient pull; an empty orphan journal is cleared automatically.
- Operator command `/factorycore recovery processing show|resolve <x y z>`
  (`ProcessingRecoveryCommands`, permission 2, 1/s): audit schema 8
  `PROCESSING_JOURNAL` via `DeviceRecoveryAudit.preserveProcessingJournal`, then makes the
  saved job record authoritative (or removes an orphan journal). Never moves resources.
- Bug fix: program `craft` dedupe was in-memory only; a controller chunk reload or restart
  submitted a second job. Now a name-based link (`WorldPrograms.craftLink`, deployment+rule+
  item) is saved as the job's `rule` field; `WorldCraftingJobs.submitLinked/linked`.
- Lang entries for the new reasons/commands.

## Verified

- `./gradlew runGameTestServer`: 310 GameTests, 0 failures (5 new in
  `ProcessingJournalGameTests`).
- With the old `WorldCraftingJobs`/`WorldPrograms` restored temporarily, all 5 new tests
  failed; the program test saw 2 jobs instead of 1, the uncertain test never saw quarantine.
- `./gradlew test --tests '*ProcessingJournalTest*'` passed (new JVM test, real furnace BE).

## Not done / next

1. Two-JVM dedicated suite (clientTest `InterruptionVerification`): seed JVM builds fixtures
   (contended table jobs, processing staged/inserted/collecting/cancelling, two jobs on one
   furnace, friendly+Wasm programs, RecoveryProvider in-call fault, chunk unload mid-job),
   saves S1 (copy `data/factorycore_devices.dat` and `region/r.0.0.mca` to `.s1`), advances,
   saves S2, mutates more, then SIGKILLs itself (`kill -9 <pid>`). Gradle prepares three
   world copies: kill (as-is), data-old (S1 .dat + S2 region), chunks-old (S2 .dat + S1
   region) and runs one restore JVM each with per-variant expectations (design in this
   agent's transcript). Seed task needs `ignoreExitValue` + exit-value check.
2. Wire into `check` if total time is reasonable; else documented required task.
3. Docs: ADR 0057, CRAFTING.md, PROGRAMS.md (craft link), PERSISTENCE/TRANSFER notes,
   player guide recovery note, evidence under `docs/evidence`, own section at end of
   IMPLEMENTATION.md and rows A03/A09/A13.
4. Run `./gradlew spotlessApply` and `python3 scripts/verify.py` before a non-WIP commit.
5. Known limitation to document: generic connector/program transfers to foreign
   inventories have no journal, so independent-save skew can still duplicate or lose
   those units (R09 does not promise it).
