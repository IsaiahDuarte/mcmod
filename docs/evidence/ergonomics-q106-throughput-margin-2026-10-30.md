# Q106 — A throughput check with a real margin

Scope: [ergonomics plan](../ERGONOMICS_PLAN.md) Q106 (M144). The `runAutomaticThroughputServer`
check (`AutomaticThroughputGameTests`, which runs
`MachinePresetGameTests.paidTiersIncreaseAutomaticChestImport`) keeps its strict
Base > Advanced > Endgame tick ordering and exact conservation; only the workload changed.

## Workload

Before: one vanilla chest of 27 × 64 iron (1,728 items) per tier into a Starter Item Cell. Item
operations move at most one native stack each, so fixed overhead set the time and Advanced and
Endgame were 1–3 ticks apart (M144: 2 failures in 12 runs).

Now: each tier imports 13,824 items (eight chest loads). The test refills every empty chest slot
with a stack, each tick, until 13,824 have been supplied, and finishes when the chest is empty
and all of them are stored. The cell is an Expanded Item Cell (65,536) to hold all three tiers.
The timeout rose from 600 to 1,200 ticks (a run takes about 330).

Environment: Linux 7.2.11 x86_64, Intel Core i7-12700K (20 threads), OpenJDK 21.0.13, NeoForge
21.1.252, fresh flat world per run, nothing else running.

## Twenty consecutive runs

`./gradlew runAutomaticThroughputServer` 20 times in a row on 2026-10-30; every run passed.
Ticks are `[Base, Advanced, Endgame]`:

```text
run 1 rc=0 ticks=[219, 63, 36]
run 2 rc=0 ticks=[218, 64, 36]
run 3 rc=0 ticks=[219, 62, 37]
run 4 rc=0 ticks=[220, 62, 37]
run 5 rc=0 ticks=[218, 62, 36]
run 6 rc=0 ticks=[219, 62, 36]
run 7 rc=0 ticks=[219, 63, 36]
run 8 rc=0 ticks=[219, 63, 36]
run 9 rc=0 ticks=[219, 63, 36]
run 10 rc=0 ticks=[219, 64, 36]
run 11 rc=0 ticks=[220, 62, 37]
run 12 rc=0 ticks=[219, 63, 36]
run 13 rc=0 ticks=[218, 63, 36]
run 14 rc=0 ticks=[219, 63, 36]
run 15 rc=0 ticks=[219, 63, 36]
run 16 rc=0 ticks=[219, 63, 36]
run 17 rc=0 ticks=[219, 63, 36]
run 18 rc=0 ticks=[218, 63, 37]
run 19 rc=0 ticks=[218, 62, 36]
run 20 rc=0 ticks=[219, 63, 36]
```

Range: Base 218–220, Advanced 62–64, Endgame 36–37. The smallest Advanced–Endgame gap is 25
ticks and the smallest Base–Advanced gap 154, against 1–3 before. The assertion is unchanged
(`elapsed[1] < elapsed[0]`, `elapsed[2] < elapsed[1]`), so it is no weaker.
