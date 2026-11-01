# Q8 — Exporter crafts what it is missing (2026-10-31)

QD4 resolved yes (opt-in toggle). ADR 0066 and ADR 0034 amended.

## Change

- **Saving:** `MachinePresets.Deployment.craft` is saved as `Craft` only when set and kept while
  paused. Only a Keep (or a paused one) may set it, and applying it needs the Craft grant
  (status `craft_denied`).
- **Running:** `WorldMachinePresets.shortage`. When a Keep finds none of its resource in
  storage, the Exporter submits one job for the machine's shortfall, for the filter's first
  exact or registry item or fluid, under the applier's grants. The job carries the Exporter's
  link (`craftLink`), so `submitLinked` never starts a second job while one exists.
- **Status:** the Exporter shows `crafting`, or `craft_<job status>` when the job is refused (for
  example `craft_principal_limit`), in place of its storage reason.
- **Screen:** a **Craft missing** toggle on the Exporter under Keep (`MachinePresetPackets.CRAFT`).

## Tests

- **`ExporterCraftGameTests.keepWithCraftMissingCraftsOnceThenSends`:** Keep 8 planks with 3
  logs and no planks in storage. Exactly one job runs, the chest gets 8 planks, and 1 log is
  left.
- **`craftMissingNeedsCraftAndShowsWhyAJobWasRefused`:** a builder without Craft cannot apply it.
  With the owner at the job limit (8 waiting jobs), the Exporter shows `craft_principal_limit`
  and sends nothing; the jobs are cancelled afterwards.
- **Full GameTest run:** 501 of 501 pass.
- **`runPresentationClient`:** the Exporter screen at scales 1 and 4 shows the toggle under Keep
  without overlap.
