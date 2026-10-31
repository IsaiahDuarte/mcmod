# Q24 — Signal watches a filter (2026-10-31)

The Signal Interface's single watch slot became the shared 8×2 filter slots (items, exact
resources or tags, up to 16, cycling like Buffer filters), with **Add by ID…** (Item, Fluid or
Tag) and **Clear** beside them; the selected slot's rule shows under them. Two new choices:
**Energy** (the network's stored FE, no slots needed) and **=** (on at exactly the amount,
always full strength). See the ADR 0059 amendment for the evaluation path and schema 2.
Signal packets protocol 1 → 2.

## Checks

- `./gradlew -q runGameTestServer`: 473 passed.
  - `SignalGameTests.tagFilterSumsItsMatchesAcrossTheThresholdAndReload`: with 50 cobblestone,
    4 oak and 5 birch logs stored, a `#minecraft:logs` filter at ≥ 10 is off (and "checked");
    one more birch log turns it on and lights the lamp; a save/load keeps the filter, rule and
    strength and it is still on after the next evaluation; switched to = 10 it is on, and one
    more log turns it off.
  - `SignalGameTests.energyConditionReadsStoredNetworkEnergy`: 5000 FE in a bank turns an
    Energy ≥ 1000 signal on, and ≥ 1,000,000 off.
  - The existing exact-resource tests (threshold, proportional, VIEW loss, reload, job) pass
    unchanged through the one-exact-slot path.
- `SignalRuleTest.equalIsOnOnlyAtTheThresholdAndAlwaysFull`; `MenuStatusPacketsTest`
  round-trips the new view with a tag slot.
- Presentation client: `signal-scale-1.png` and `signal-scale-4.png` show the slots, side
  buttons, three condition buttons and three comparisons without overlap at both scales; the
  existing JEI-drop step still adds stone and applies.
- `python3 scripts/verify.py`: PASS.

Not done here: emit to craft (needs a QD19-style amendment) → Q115.
