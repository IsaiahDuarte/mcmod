# Q25 — Energy use in the Overview (2026-10-31)

**Check first:** the Overview showed status, role counts and the checklist, and nothing about
energy. The gap was real.

Devices that pay their own running cost do so from a private `RateLimitedEnergyInput` (Access
Point, Program Controller, Wireless Link on a Network Node); the network's banks are storage, not a
shared supply. So "network energy use" is what those buffers spend. The input now meters its
spending as FE per tick averaged over the last window of at least 20 ticks (not saved; reads 0
after an idle window). `EnergyUsers` tracks the loaded devices (at most 4,096; joined on load,
left on removal or unload, stale entries pruned). The Overview sums those in the controller's
segment under the same View check as the counts. It syncs the total, each kind's count and use,
and the five largest users through data slots (tenths of FE/t in two 15-bit slots), by kind only,
since the Overview shows no positions. The screen lists the total and one line per kind; hovering
lists the largest users.

## Checks

- `RateLimitedEnergyInputTest.useAveragesSpendingOverTheLastWindowAndFallsToZeroWhenIdle`:
  16 FE every tick reads 16; 50 FE every 10 ticks reads 5; an idle window reads 0.
- `OverviewGameTests.overviewReportsEnergyUseOfAProgramControllerAndAnAccessPoint`: a deployed
  program (every 10 ticks) and an Access Point serving an open wireless terminal for 45 ticks.
  The Overview reports one of each and no Wireless Link, a use for each (the point between half
  and all of its 16 FE tick cost, since the first window can start before the session; the
  program above 0), a total that adds them, and the point first among the largest users.
  474 GameTests passed, twice.
- Presentation client: `overview-scale-1.png` and `-scale-4.png` show the energy line and the
  Program Controllers line, plus the hover tooltip (the cursor rests on it) with the largest
  user. Nothing overlaps at either scale.
- `python3 scripts/verify.py`: PASS.
