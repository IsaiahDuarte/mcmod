# Q76 — Durability matching (2026-10-31)

**Term.** `ResourceFilter.Worn(id, below)` matches item `id` with under `below`% durability left.
`Tags.worn` (default false) asks the platform; `PlatformFilterTags.worn` decodes the stack and
compares `(max − damage) · 100 < below · max`, so only damageable items match. Fluid or energy
filters reject the term. Program filters have no syntax for it; the program preview prints it as
`id below N%`.

**Editing.** `FilterEdits.cycle`: Item → Exact → (damageable only) worn 99 → 75 → 50 → 25 → the
item's tags → Item. The rule type is `WORN` (3), with the badge **%** and the label "ID … · durability
below N%". `WorldSignals` now matches through `PlatformFilterTags`, so a Signal Interface watching a
worn slot counts correctly.

**Saving.** A `WORN` term with `Id` and `Below` in `FilterCodec` (connectors, presets, partitions,
signals) and `BufferPolicyCodec`. Other terms are byte-identical and no schema numbers change.
Older readers reject the unknown type.

## Checks

- `ResourceFilterTest.wornMatchesItsItemOnlyBelowTheThresholdTheProviderReports`: below / above
  the threshold, another item, a provider without durability, 0/100 rejected, fluid filters
  rejected, not program-only.
- `BufferPolicyCodecTest.wornTermsRoundTripInBothCodecsAndNeedTheirThreshold`: both codecs round
  trip; missing or zero `Below` rejects, and so does `Below` on a non-worn term.
- `PortGameTests.wornFilterExportsOnlyWornTools`: storage holds iron pickaxes with 30%, 80% and
  100% durability left, and an Exporter set to "below 50%" sends only the 30% one. The real-registry
  slot cycle gives 99, 75, 50, 25 and then a tag for a pickaxe, and never a worn step for stone.
- 480 GameTests passed twice.
- Presentation client `exporter-filter-scale-4.png`: the fourth slot (iron pickaxe) shows the
  **%** badge, and the evidence line lists it as `type=3 … durability below 99%`.
- `python3 scripts/verify.py`: PASS.
