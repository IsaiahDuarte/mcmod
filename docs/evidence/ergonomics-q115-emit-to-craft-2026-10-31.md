# Q115 — Emit to craft (2026-10-31)

QD19 resolved yes. ADR 0059 and ADR 0032 amended.

## Change

- **Signal Interface:** `SignalRule.Condition.CRAFT` (the **Craft** button). The Interface
  offers an emit pattern (`Pattern.Type.EMIT`: no inputs, one unit out, an ID derived from its
  position) to its segment's crafting index (`WorldSignals.emitters`, `WorldCrafting.Index`).
  It is on while a job's current step is that pattern.
- **Jobs:** `WorldCraftingJobs.emit` reserves arriving, unreserved units of the output, and
  `CraftingJob.emitted` counts each unit as one craft. A step still short waits with the new
  reason `emitting`.
- **Jobs view:** an emit step names its Signal Interface and that Interface's label.

## Tests

- **`CraftingJobTest`:**
  - a plank job plans two emit crafts for logs;
  - emitted units are bounded by the step and its key, stay reserved, and are pulled by the
    plank step;
  - progress survives a snapshot;
  - an emit pattern must have no inputs and make one unit.
- **`EmitCraftGameTests.aRedstoneMachineMakesTheMissingLogsForAPlankJob`:** the machine is
  driven only by the lamp the Interface powers; while lit, it puts one log a second into
  storage.
  - Without the Craft Interface, 8 planks are `missing`. With it, the job is accepted.
  - The lamp lights, two arriving logs are taken, 8 planks are delivered, and the lamp turns
    off.
  - Logs made after the second stay in storage, unreserved, and the machine stops.
- **`EmitCraftGameTests.cancellingTheJobTurnsTheSignalOff`:** one arriving log counts as one
  craft. Cancelling ends the job, turns the signal off, and leaves the log stored and
  available.
- **Full GameTest run:** 503 of 503 pass.
- **`runPresentationClient`:** the Signal screen at scales 1 and 4 shows **Craft** as a fourth
  condition without overlap.

## Limits

- **Any source counts:** an emit step cannot tell the machine's output from any other arrival
  of the same item (ADR 0032 amendment), as with AE2.
- **Real machine not tested:** the GameTest machine is test code driven by the lamp's state. A
  real redstone machine behind an Importer was not built in a test.
