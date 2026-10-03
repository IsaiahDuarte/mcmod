# ADR 0003 — Bounded fair scheduling and reference workloads

Status: accepted target/design selection; D07 measurements pending.

## Decision

Scheduling runs on the owning server thread, with active network and program
queues and lazy per-tick quota resets. Idle programs have no tick visitation.
Admission limits are explicit: 1,024 networks, 64 programs per network, 256
queued keys per program and 8,192 total queued tasks. Duplicate pending keys
replace/coalesce instead of accumulating. Queues contain bounded host operations,
not arbitrary network scans. Runtime/UI/crafting work must share these budgets.

Default per-tick targets: at most 256 active queue visits, 64 executed tasks,
8,192 host-work credits globally, 2,048 per network, 512 per program. Each task
declares conservative bounded host-work cost before admission; costs exceeding
a program/network/global share reject. Program/network round-robin prevents a
single factory or program draining all shares. Priority within a factory cannot
bypass global quotas. Real-time limits stop before another task when the elapsed
budget is exhausted; indivisible external handlers still require measured bounds.
Exceptions isolate the failed task and produce a diagnostic, not a silent retry.

Reference hardware is the local Apple M1 macOS 15.3.1 / OpenJDK 21.0.12 baseline,
with a 2 GiB test/benchmark heap. Linux/Windows CI checks semantics, not latency.
Tick time targets are initially 2 ms for this mod's small workload and 5 ms for
large/overload work, p99 <= 10 ms, bounded backlog and memory below 512 MiB for
mod-owned state under the reference workload. These are targets, not claims.
Minecraft overhead and other mods are separate; full-server tick/packet/memory
evidence remains required in P7. Do not move these targets after a failure without
product reasoning. Paid gameplay upgrades change routing rates, not these shares.

Queue-latency targets in the reference workloads are <= 8 ticks for small and
<= 256 ticks for large/overload admitted tasks; overload admission may reject.
Portable throughput targets are 1,024 item units/tick small, 4,096 large and
1,024 overload after warmup. Allocation targets are <= 512 KiB/tick small,
2 MiB/tick large and 4 MiB/tick overload, including bounded admissions. Future
catalog deltas target <= 128 rows/64 KiB per packet, 16 KiB/player/tick and
256 KiB/network/tick; these are unimplemented client targets until P6/P7.

## Workloads and measurement

Version 1 portable baseline uses a reproducible standalone harness, three warmup
passes and five measured passes, each 2,000 scheduler ticks. Record raw samples,
runtime/OS/CPU/heap, revision and min/median/p95/p99/max duration, visits, execution,
backlog and memory. No assertion of server tick performance from this harness.

| Scenario | Networks / programs | Operations | Resource workload |
| --- | --- | --- | --- |
| Small | 4 / 8 | 16 tasks/tick, cost 32 | Exact transfer/accounting over 64 identities, 64-unit moves. |
| Large | 64 / 1,024 | 256 attempted admissions/tick, cost 32 | 4,096 metadata keys, plus signed-long large identical stacks in separate stores; bounded moves. |
| Overload | 128 / 4,096 | 1,024 attempted admissions/tick, cost 64 | Saturated queues, competing requests, partial acceptance and rejection; all layers retain bounds. |
| Idle | 1,024 / 65,536 configured | No submitted work | No queue visits and no guest execution. |

Each workload must report actual active/idle programs and admission rejections.
Large catalog initialization is outside timed ticks; no whole catalog is copied
inside a transfer. Future crafting/UI/Wasm/client deltas extend these scenarios
with frozen parameters before their optimizations; they cannot be omitted from
the release measurement. Identical giant counts and many unique metadata keys
remain separate cases, not interchangeable performance evidence.

## Alternatives and consequences

One thread per program is unbounded and complicates world mutations. Scanning
all programs per tick charges idle factories and hurts large servers. Active
queues with bounded visits make overload behavior observable. Fixed quotas favor
simple, testable fairness over global optimization; deferred work exposes latency
and backlog. Admission/replacement can reject at capacity rather than grow queues.

This ADR selects the P1 scheduler concept before implementation/measurement.
Tick events, live topology, crafting graph budgets, network packets and immutable
planning workers arrive in their owning stages. All full performance acceptance
remains incomplete until those measurements exist.
