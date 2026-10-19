# Bounded scheduling contract

Implemented P1 portable scheduler, now used by [live wired coordination](WORLD_NETWORKS.md).
UI, crafting and production guest-runtime integration remain pending. [ADR 0003](../decisions/0003-scheduling-workloads.md)
owns targets and version-one workloads.

## Ownership, admission and lifecycle

Construct `FairScheduler(limits, nanoClock, failures)` on its owning thread.
`nanoClock` is a trusted monotonic nanosecond clock; tests use deterministic fake
clocks. `failures` receives failed network/program/key and a diagnostic. Production
must provide a bounded diagnostic sink. Every API checks thread ownership.

`submit(networkUuid, programUuid, key, cost, action)` admits one trusted bounded
host step. UUIDs identify budget owners; keys are nonempty strings of <= 128 UTF-16
units. `cost` is a positive conservative host-work credit estimate, chosen by the
host adapter, **not by clients or guests**. `action` must revalidate current
authority, loaded state and generation before mutation. Guest execution requires
its own instruction/time/stack/memory controls; a raw unbounded callback cannot
be made safe by a scheduler cost field.

Admission results are `ACCEPTED`, `REPLACED`, `QUEUE_FULL`, `NETWORK_LIMIT`,
`PROGRAM_LIMIT`, `COST_LIMIT`. Replacement keeps pending-key position and updates
its latest operation without growing the queue. If not replacing, all applicable
queue/owner limits are checked before retaining the task. Idle owners remain in
the bounded registry until cancellation; they have no active tick queue entries.

`tick()` rotates active networks and then each network's active programs. Lazy
epoch counters reset budget use when an owner is visited, avoiding an idle scan.
One step consumes program/network/global shares. Visits, successful or failed
executions, credits and elapsed time each limit further work. A deferred step
remains in its queue. No local priority bypasses these global shares.

`TickReport` returns visits, executed (including failures), failed, credits,
remaining backlog and whether time blocked further work. Indivisible Java/handler
work cannot be preempted; elapsed time is checked before the next action.
Current defaults are in `Limits.defaults()`. Invalid configurations or keys throw
`IllegalArgumentException`; off-thread/reentrant ticks throw `IllegalStateException`.

Task runtime exceptions remove that attempted step once and emit a failure;
other pending steps remain active. There is no automatic replay of a transfer
whose external mutation may be uncertain. If the diagnostic sink itself throws,
the tick propagates the error and restores remaining active queues before exit.

`cancelProgram(network, program)` removes undispatched steps and releases empty
owner registry entries at a tick boundary. Calls during a tick reject: already
started work owns its resource cleanup. Pause/removal/replacement/revocation must
invoke this boundary operation and also disable persistent rules (P5). Removing
a program does not fabricate resource returns or cancel external processes.
Cancellation performs a bounded queue removal (bounded by configured owners);
mass topology teardown must still be scheduled under its P2 rebuild budget.

## Example

```java
var scheduler = new FairScheduler(FairScheduler.Limits.defaults(), System::nanoTime,
    failure -> diagnostics.record(failure));
scheduler.submit(networkId, deploymentId, "import-iron", 256,
    () -> sharedOperations.transferIfAuthorized(request));
var report = scheduler.tick();
```

The example's diagnostics and shared operations are integration responsibilities,
not implemented globals. A step's cost must cover all bounded adapter slot/host
visits; callbacks that plan recursively must split into bounded continuation steps.

## Tests and baseline

`FairSchedulerTest` verifies independent network/program fairness under saturation,
replacement/coalescing, cancellation, cost rejection, queue capacity, deadlines,
task-failure isolation and preserving pending work when diagnostics fail.

Run `./gradlew coreBenchmark` (Windows: `gradlew.bat coreBenchmark`) for the
version-one portable baseline. The harness performs three warmup passes and five
measured passes per scenario, 2,000 ticks per pass, using a 2 GiB JVM heap. It
records timing for admissions plus scheduler execution, actual transfer units,
queue visits/credits/backlog/rejections, thread allocations and observed heap.
Raw output is `build/benchmarks/core-v1.csv`. Reporting allocations excludes CSV
formatting performed after the timed step. Reference execution requires the JDK
thread-allocation bean and fails if unavailable rather than fabricating zeros.
Heap use includes garbage and harness data; it is not retained mod memory or RSS.

Catalogs are initialized outside timed ticks with distinct metadata keys and
separate giant identical counts. Transfers choose direction from current stock,
keeping real transfer work active instead of draining the initial stock. Overload
destinations accept 16 of 64 offered units; recoverable staging returns immediately
to its source and is checked. An initial exploratory harness drained many keys;
those samples are excluded from the published baseline.

An idle case registers then drains 65,536 program owners across 1,024 networks
before timing. No active queue means zero visits/executions. This is evidence for
the portable idle scheduler, not idle Wasm or full-game block-entity overhead.


`FairScheduler.cancelTask(networkId, programId, key)` removes one undispatched
keyed action, returning false when absent, while retaining unrelated queued work.
It runs on the owner thread at a tick boundary and rejects during execution.
`isQueued(networkId, programId, key)` is a side-effect-free owner-thread query;
it is false once dispatch takes the action. [World transfers](WORLD_STORAGE_TRANSFERS.md)
use these primitives for individual cancellation and bounded reclamation after
whole-principal cancellation. Neither primitive refunds already executed resources.

## Paid throughput and visible deferral

[ADR 0054](../decisions/0054-throughput-upgrades.md) keeps all fair limits unchanged.
New transfer offers cost 16 credits per begun 128-item or 1,000-mB/FE base quantum;
the largest item/fluid/FE requests cost 128/256/512 credits. Metadata-only claims,
catalog pages and the unchanged small recovery quantum retain their existing
bounded costs. A paid tier never creates another worker thread or raises shares.

`throttled(networkId, window)` reports whether that network's pending work waited
for credits, visits, execution count or time within a positive number of scheduler
ticks. It checks owner-thread access, returns false for unknown/never-deferred
owners, and grants no execution rights. Deferral stamping visits only the bounded
active-network queue (at most 1,024); it does not scan idle owners. UI inspection
uses a 20-tick window. Representative measurement of this additional metadata work
remains part of the controlled performance gate.

## Reserved maintenance slots (ADR 0056)

`submitReserved` admits network maintenance into 8 slots beyond `queuedTotal`. `WorldNetworks` retries its rebuild wake-up every tick while regions are active, so a saturated queue delays topology work but can never stall it. See [ADR 0056](../decisions/0056-program-limits-and-overload.md).
