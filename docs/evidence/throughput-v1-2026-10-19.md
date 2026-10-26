# Paid throughput client and automatic import evidence — 2026-10-19

Status: verified slice (canonical gate and graphical run passed); retention below. This is partial A04/A11/A16/A17 evidence, not full acceptance.

## Workload and environment

macOS 15.3.1 arm64 / Apple M1, Java 21.0.12, Minecraft 1.21.1,
NeoForge 21.1.252, Factory Core and its excluded verification fixture only.
Graphical framebuffer 1708×960, scales 1–4 (427×240 GUI pixels at scale 4).
No concurrent build/client was running during the isolated native measurement.

The workload fills a vanilla chest's 27 slots with 64 iron each (1,728 items),
then uses an actual Machine Interface Import preset into a Starter Item Cell.
It repeats on the same device at Base/default 64, Advanced/256 and Endgame/1,024,
pausing and upgrading between runs. Every stage checks exact digital totals and
an empty source. Timing is elapsed server ticks, not wall-clock throughput.

The first run after the 312-test suite took `[158,158,158]` ticks. Successful
transfers waited for another active sweep. The candidate scheduler continuation
reduced that to `[132,131,131]`, but still could not distinguish the upgraded
tiers: repeated catalog discovery spent credits scanning the earlier fixtures'
persistent ledger entries. Those failures are retained as diagnosis, not passes.

The required fresh-world `runAutomaticThroughputServer` isolates that workload
from old fixture state. First isolated result: `[28,16,14]` ticks, exact
conservation, one required GameTest passed. This is a functional regression for
useful paid speed under an otherwise empty world. It does not establish nominal
caps under load, repeated controlled performance, multi-factory fairness,
large-catalog performance, client packet cost or full A15/A16 evidence.

## Behavior and graphical review

Successful automatic moves now queue another bounded operation through the
unchanged fair scheduler. Item operations request one native maximum stack;
each additional stack pays again and rechecks authority/configuration. Rate
exhaustion retries next tick; shortage/fault backoff remains 20 ticks. No
foreign multi-call aggregation or scheduler-budget increase was introduced.

The expanded presentation fixture installs both upgrades on machine and buffer
through real client use in survival, asserts one-item consumption and synced
256/1,024 machine and 256/576 buffer rates, and checks panels at scales 1–4.
It also pages through the Factory Guide to the new speed instructions and draws
actual localized upgrade tooltips. The capture gate now requires 44 images.

First graphical pass succeeded in 1m14s, but visual review caught blank buffer
rate/goal fields after resize and excessively long tooltip text. Numeric drafts
now survive reinitialization, with a graphical assertion against empty fields.
Upgrade help is shorter. A second pass succeeded in 1m12s and confirmed visible
576/64 buffer rate/goal values and readable guide instructions; the tooltip board
needed its top anchor moved from 12 to 20 GUI pixels to keep the vanilla tooltip
border inside the framebuffer. Final rerun status follows below.

## Final results and retained evidence

- `python3 scripts/verify.py` PASSED in 2m44s: 30 tooling, 357 JVM, 312 native GameTests plus
  the isolated `runAutomaticThroughputServer` gate (repeat `[28,16,14]` ticks), zero
  failures/errors/skips, all dedicated probes and static/build gates.
- Final `caffeinate -d -i ./gradlew spotlessApply runPresentationClient` (GUI lock held) PASSED in
  1m11s with all 44 required captures. Reviewed: the upgrade tooltip border now sits inside the
  framebuffer with the 20-pixel anchor; the machine panel shows `Overdrive Upgrade · max 1024
  items/tick · server: OK`; the buffer panel keeps rate 576 / goal 64 at scale 1; guide page 16
  is readable at scale 4.
- Retained: the 12 throughput captures, `result.txt` and `SHA256SUMS` in
  [throughput-v1-2026-10-19/](throughput-v1-2026-10-19/).

## Crafting row (parity E2, added 2026-10-25)

`CrafterSpeedGameTests` (native GameTest server, same environment) runs a 64-craft stick job
(128 planks → 256 sticks) on one Crafter per tier and records each scheduled run's crafts
(`WorldCraftingJobs.lastRunCrafts`).

| Crafter | Crafts per run (measured max) | Credits per run | Runs for 64 crafts |
| --- | --- | --- | --- |
| Base | 1 | 16 | 64 |
| Throughput | 8 | 128 | 9 (1 + 8 × 8) |
| Overdrive | 32 | 512 | 3 (1 + 32 + 31) |

Parallel Crafters (E3): a 128-plank job (32 crafts) took **32 runs on one Crafter and 17 on
two** holding copies of the same pattern (1 run at the old batch, then 16 × 2), measured
side by side in `CrafterSpeedGameTests.twoCraftersFinishA128PlankJobInAboutHalfTheRuns`.

The first run after an upgrade is paid at the previous batch, so it makes one craft. 32 is
the cap because 32 × 16 credits equals the scheduler's per-task maximum (512), so
crafting speed is bought only with credits, never with fairness (R11). Processing patterns
still make one craft per dispatch. Not measured: wall-clock time under load and many
Crafters on one network.

Still open: nominal caps under load, multi-factory fairness, large-catalog discovery cost,
client packet cost and repeated controlled A15/A16 measurements.
