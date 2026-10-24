# U16 — Remove offhand controls

Scope: U16 (UD5). Minecraft 1.21.1, NeoForge 21.1.252, Java 21, macOS arm64.

Implemented:

- Empty-main-hand use of an Interface now only opens its screen, in every mode,
  whatever is in the offhand and whether or not the player sneaks.
  `ConnectorControls.use` and `BufferControls.use` lost these branches:
  - offhand redstone (access, or buffer return);
  - iron/gold (priority);
  - crouch-use (resource kind, or buffer Import/Paused);
  - item samples, name tags, clock, paper, compass and chest (buffer
    filter/rate/clear/mode/goal);
  - renamed paper/name tag (machine label).
- The chat messages only those shortcuts used are deleted: `machine_label`,
  `machine_unlabelled`, `machine_binding`, `connector_status`, `connector_denied`,
  `buffer_cleanup`, `buffer_cleanup_queued`, `buffer_mode`, `buffer_policy`,
  `buffer_target`.
- Each removed action already has a screen path: the Storage settings screen (U5),
  the Machine screen's label field, and the buffer screen's Mode / Item ID / Tag /
  Sample / Filter / Clear / Rate / Goal / Apply / Return. The buffer screen's
  **Return** already looks up the buffer lease's own unresolved transfer first, so
  the original owner can still return items in transit with View only.
- Out of scope: the cell/bank upgrade offhand paths (`CellUpgradeItem`) are module
  item mechanics, not Interface controls.

Tests (the requirement changed; coverage moved to the screen path):

- `BufferPolicyGameTests.screenSamplesRateClearAndTagExclusionDoNotConsumeHeldItems`:
  sends Sample, Item ID, Rate, Clear, Tag, Filter, Goal, Mode and Apply through the
  menu, checking each applied policy and that the sample stays in the inventory.
- `BufferImportGameTests.screenControlsToggleOnlyWithCurrentEditDeploymentAndResourceGrants`:
  - without grants, the screen does not open;
  - Mode + Apply enables Import in the player's name;
  - reopening changes nothing, and the mode cycle pauses;
  - revoking View closes the screen and prevents reopening.
- `BufferImportGameTests.originalOwnerReturns…`: the owner, holding only View,
  returns items in transit with the screen's Return; the record is retired.
- `ConnectorGameTests.heldItemsAndSneakOnlyOpenTheSettingsScreen`: redstone, gold
  and an empty offhand, each with and without sneaking, only open the screen and
  change nothing.
- `MachineInterfaceGameTests.heldPaperOnlyOpensTheScreen…`: renamed paper no longer
  relabels. Screen labelling stays covered by `MachineSettingsGameTests`.

Checks: `python3 scripts/verify.py` PASS; all 355 required GameTests pass. Under the
GUI lock and `caffeinate`: `runPresentationClient`, `runTerminalClient` and
`runTerminalRestartClient` PASS. Inspected `buffer` at scale 4: the Mode, Filter,
Rate/Goal Set, Sample, Item ID, Tag and Clear controls named in the guide are
present.
