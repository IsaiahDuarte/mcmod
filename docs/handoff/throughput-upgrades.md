# Handoff: paid throughput upgrades (ADR 0054, not yet written)

Branch: `worktree-agent-af290ca1c3c2d6e51`. This is work in progress. It has not been verified.

## Done (in the WIP commit)

- Core `ThroughputTier` (core/resource) has three tiers: BASE, ADVANCED and ENDGAME.
  - Port items/mB/FE per tick: 128/1,000/1,000, then 256/4,000/8,000, then 1,024/16,000/32,000.
  - Buffer items per tick: 64, then 256, then 576.
  - Link-pair scale: 1, then 1, then 4.
  - Scheduler credits are charged at 16 per begun base quantum.
- Transfers accept requests up to the ENDGAME quantum and charge proportional credits. The
  `FairScheduler` limits are unchanged.
- `FairScheduler.throttled(network, window)` reports networks whose work waited for the fair
  budget.
- `WirelessBudget.Hop` has a per-pair scale. A pair gets the Overdrive scale only when both ends
  carry Overdrive. `WorldNetworks` publishes this scale.
- Persistence:
  - `ConnectorData` schema 3/4 and `BufferData` schema 2 store the tier. They are written only
    when a device is upgraded, so base devices keep their earlier bytes.
  - `LinkEndpointEntity` stores the tier as an optional `Throughput` tag.
- Installation: use `ThroughputUpgradeItem` on a device.
  - Connector and Machine Interface: the rate is set to the new maximum. On a Machine Interface
    the preset pauses, as with any configuration change.
  - Buffer: the device is redeployed with the new maximum rate.
  - Link: takes Overdrive only, and needs Manage permission.
  - The tier stays in a portable device's item when it is broken. A broken link endpoint drops its
    Overdrive separately.
- Feedback:
  - Chat inspection lines for connectors, buffers and links.
  - Item tooltips.
  - The Machine settings screen shows a throughput/throttle line at y=163 and a new `upgrade`
    status.
  - The buffer and machine rate boxes were widened.
- Assets:
  - Items `throughput_upgrade` (recipe IDI/RHR/IDI) and `overdrive_upgrade` (NDN/DSD/NDN, makes
    4).
  - Advancements, item models and lang keys.
  - Art in `tools/art/generate_art.py`, plus an art shape test.
- `RecipeProgressionTest` was extended with the stages, the tier table and wired >= wireless.

## Verified

- `./gradlew compileJava` passed before the art, recipe and test edits.
- `python3 tools/art/generate_art.py` regenerated 51 files.
- Nothing else has run. `verify.py` has not run.

## Broken / next

1. Fix the JVM tests that assumed the old limits. Explain each change as a requirement change and
   replace the old check with a check at the new ceiling (1,025 / 16,001):
   - `MachineSettingsPacketsTest`: the View constructor now has 3 more fields, and it still asserts
     that a rate of 129 throws.
   - `StockTargetTest`: asserts that 129 / 1,001 throw.
2. Add new JVM tests:
   - `ThroughputTier`: credits, step rules, parse.
   - `FairScheduler.throttled`.
   - `WirelessBudget` hops/scale: usage stays keyed by the link.
   - ConnectorData schema 3/4 and BufferData schema 2 round-trip and rejection.
3. Add GameTests:
   - Install, then raised connector rate through `connectors().port()`; base rate stays capped at
     128.
   - Denied or inapplicable install consumes nothing.
   - Persistence across break and place.
   - Link drop on break.
   - Overdrive pair caps of 1,024/16,000/32,000.
   - `RecipeGameTests` counts and grids.
   - Guide page count, if a guide page is added.
4. Docs:
   - Write ADR 0054.
   - Update the ADR 0048 "Open" section, the CONNECTORS, BUFFERED_INTERFACES, MACHINE_INTERFACES,
     WIRELESS and SCHEDULING module docs, PLAYER_GUIDE, ASSETS rows, the Factory Guide page 15
     (`GuideContent.PAGES` 16) and the IMPLEMENTATION section/acceptance rows A04/A16/A17.
5. Run `./gradlew spotlessApply` and `python3 scripts/verify.py`. Then capture graphical evidence
   for the machine settings screen with the GUI lock.
