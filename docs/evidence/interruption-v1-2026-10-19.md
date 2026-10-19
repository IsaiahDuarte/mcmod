# Abrupt-kill and independent-save interruption — 2026-10-19

macOS arm64 (Apple M1), Java 21.0.12, Minecraft 1.21.1, NeoForge 21.1.252, dedicated servers on a
flat world.

## Fixture

`InterruptionVerification` builds a real network: a controller, a Starter Item Cell, a Crafting
Interface and a fuelled blast furnace, with a raw iron → iron ingot processing pattern. The seed
puts 4 raw iron in storage, then:

1. Job 1 (2 ingots) inserts. **S1** is saved and copied to `*.s1`.
2. Job 1 finishes. **S2** is saved and copied to `*.s2`.
3. Job 2 (2 ingots) inserts. **S3** is saved.
4. The seed runs 20 more ticks, then `kill -9`s its own JVM. Gradle requires exit 137.

Each restore runs in its own JVM, on its own copy of the world.

## Results (all passed; `python3 scripts/verify.py` 4m15s, 321 GameTests)

| Variant | Files | Observed |
| --- | --- | --- |
| kill | S3 as written | Job 2 resumed from its S3 dispatch and finished. Storage ended with 4 ingots and 0 raw iron, and every seeded unit was counted exactly once ([report](interruption-v1-2026-10-19/kill.txt)). |
| data-old | S1 SavedData + S2 chunks | Job 1 stopped as `save_mismatch` with no machine or ledger change for 100 ticks. Resolve preserved the absence evidence and aligned the journal. The 2 ingots the newer chunk had already handed back are **lost** to the skew; they were reported, not re-created ([report](interruption-v1-2026-10-19/data-old.txt)). |
| chunks-old | S2 SavedData + S1 chunks | The orphaned job-1 journal blocked job 3 as `machine_journal`, with no dispatch for 100 ticks. Resolve removed it. Job 3 then refused the rolled-back ingot as `output_not_clean`; the fixture took it out by hand, as a player would. Job 3 then added exactly one ingot ([report](interruption-v1-2026-10-19/chunks-old.txt)). |

The run found a real gap. With an older ledger and a newer chunk, the job record claimed
progress at a machine with no journal, and the resolve command refused ("none"). The job could
never be recovered. Resolve now accepts a saved dispatch with a missing journal and records
`{Absent:1b}` as evidence. A regression GameTest covers it.

## Not covered

- Program-driven jobs and Wasm programs with persistent state across SIGKILL.
- Two competing jobs or scripts racing across a restart.
- Chunk unload of the interface mid-job.
- Table (non-processing) jobs. They need no machine journal; their staging is ledger-owned.
