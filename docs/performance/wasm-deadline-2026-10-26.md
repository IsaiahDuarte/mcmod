# Wasm deadline check cost (Q59)

Scope: [ergonomics plan](../ERGONOMICS_PLAN.md) Q59 (M58), [ADR 0004](../decisions/0004-wasm-feasibility.md).

## Workload

`WasmDeadlineBenchmark` (`./gradlew wasmDeadlineBenchmark`, raw samples in
`build/benchmarks/wasm-deadline.csv`). A runaway guest (`loop { br 0 }`) spends the whole
rule budget, `WasmProgram.RULE_INSTRUCTIONS` = 65,536, and traps. Each sample is one rule run
on a fresh instance (instantiation is not timed). It is run once with the real clock
(`System::nanoTime`) and once with a constant clock that never reports the deadline. The
difference is the cost of reading the clock. 300 warm-up pairs, then 5 passes of 300 pairs;
medians per pass.

Hardware and runtime: Intel Core i7-12700K (20 threads), Linux 7.2.11-zen1-1-zen, Java
21.0.13 (2026-10-20 LTS), Chicory interpreter as pinned in `gradle.lockfile`, 2 GB heap. Desktop
timing with no other build or client running; evidence, not a CI assertion.

## Results

| Deadline check | Full-budget rule, median of pass medians | Clock share | Clock call |
| --- | --- | --- | --- |
| Every instruction (before) | 1.549 ms (passes 1.529–1.569) | 46.7–49.9 % | 11.8 ns |
| Every 256 instructions (after) | 0.799 ms (passes 0.772–0.850) | 0.6–3.2 % (noise) | 11.9 ns |

Reading the clock on every instruction cost about as much as the interpreted instruction itself
(about 12 ns each), so it was half of a full-budget rule.

## Decision and trade-off

`WasmProgram` now reads the clock when the remaining instruction count is a multiple of
`DEADLINE_STRIDE` (256). The 50 ms deadline can be overrun by at most 255 instructions after it
passes, about 3 µs at the measured instruction rate. A host call's own time is still bounded by
the host-call limit (512 per callback) and counts toward the deadline at the next stride. The
instruction budget, operand limit and frame limit are still checked on every instruction.
`WasmProgramTest.runawayGuestHitsTheTimeDeadline` still traps on time, and
`fullBudgetRuleReadsTheClockOncePerStride` pins the read count.
