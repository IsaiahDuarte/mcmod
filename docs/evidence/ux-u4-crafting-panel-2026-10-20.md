# U4 — Crafting panel

Scope: P4/R08, partial A11/A14. Minecraft 1.21.1, NeoForge 21.1.252,
Java 21.0.12, macOS 15.3.1 arm64. Complete on 2026-10-24: graphical, restart and
JEI runs pass and their captures were inspected (see "Graphical verification" below).

Implemented:

- A collapsible right-side crafting panel with separate Table, Inputs and Outputs
  tabs. The adjacent resource browser still computes at least 9×4 cells at the
  minimum GUI; fit remains unverified on the display. Collapsing leaves ghosts,
  page and selected cell intact. Inventory slot identities remain unchanged.
- Existing shared ghost/craft/encode commands and server ownership/permissions.
  +/− buttons and wheel input adjust processing amounts within the existing bounds.
- Protocol 4 adds a twelve-bit fluid-kind mask to the grid view. Fluid ghosts use
  bucket icons, mB-number overlays and exact mB selected/hover labels. Internal
  counts stay 1–64 in 250 mB steps; no pattern or world persistence change.
- Recipe-viewer fills report `filled` and reveal Table even from a collapsed
  processing panel. Ordinary edit acknowledgments/crafting outcomes do not reopen
  a collapsed panel. Only Table shows a recipe-result preview; processing outputs
  display their declared amounts rather than a misleading single bucket.

Regression coverage implemented:

- Packet round trip for mixed fluid/item ghosts, exact 1,000/16,000 mB projection,
  item counts and invalid/empty/non-processing fluid-mask rejection.
- Existing native fluid-encoding test additionally checks the actual kind/mB
  projection and its removal on returning to Table; recipe-viewer fill checks the
  status that causes the client to reveal the panel.
- Presentation tour extension: table craft and Encode, processing input 1,000 mB
  water → two Stone + 500 mB water, both encoders consume exactly one blank,
  declared stock is untouched, +/- and collapse/reopen retain ghosts, all three
  pages captured with fit assertions at scales 1 and 4.
- JEI tour extension: real runtime recipe fill reveals the panel, then actual Craft,
  table Encode, Inputs/Outputs processing Encode and a second JEI fill replacing
  collapsed Outputs with Table. These graphical extensions are **not yet run**.

Checks:

- `./gradlew test --tests '*TerminalCraftingPacketsTest'` passed for the initial
  fluid-mask change.
- `python3 scripts/verify.py` passed after the initial implementation: 34 tooling,
  387 JVM, 337 native tests, formatting/static/architecture, Rust SDK and client/JEI
  fixture compilation. Final UI/fill refinements are being checked separately.
- `caffeinate -d -i -u ./gradlew runPresentationClient` was attempted under
  `/tmp/factorycore-gui.lock`. It could not initialize the display:
  `Failed to locate a primary monitor; glfwGetPrimaryMonitor failed`.
  macOS `system_profiler SPDisplaysDataType` reported its main 2560×1600 Retina LCD
  online but `Display Asleep: Yes`. An explicit `caffeinate -u -t 600` assertion set
  `UserIsActive` but did not wake it. The failed client and its own error dialog
  were terminated; the wrapper exited 1 and released the lock. No screenshot or
  graphical pass is claimed. The user has been asked to wake/unlock the display.
- `python3 scripts/verify.py --full` passed in 4m42s: 34 tooling, 387 JVM,
  337 native tests and all dedicated release suites (restart, save skew, throughput,
  interruption and wireless lifecycle). The last client-only recipe-fill refinement
  and JEI fixture refactor are also subject to the final fast build below.
- Final `python3 scripts/verify.py` passed in 1m03s after the last client/JEI
  refinements: 34 tooling, 387 JVM and 337 native tests, all static/architecture
  checks and compilation of both graphical fixture source sets.
- `git diff --check` passed. The final display inspection still reported asleep;
  no graphical rerun is claimed and the temporary wake assertion has expired.

## Graphical verification (2026-10-24)

Each command ran with the display awake, under `/tmp/factorycore-gui.lock` and
`caffeinate -d -i -u`.

- `./gradlew runTerminalRestartClient`, which also runs `runTerminalClient`, first **failed**
  at terminal stage 11, step 3. The test pressed "Use selected" right after opening the
  panel. Opening the panel narrows the item browser, which requests a new view window and
  bumps the server view revision, so the server ignored the stale row index. This would
  also affect a quick player. Fix: `TerminalScreen.windowSettled()` keeps "Use selected"
  disabled until the server view matches the requested window, and the test waits for
  it. No assertion was weakened. After the fix, terminal and restart both pass.
- The JEI processing capture showed "1 items". It now reads "1 item"
  (`gui.factorycore.terminal.grid.amount.item.one`).
- Final runs after both fixes: `runPresentationClient` PASS, `runJeiClient` PASS and
  `runTerminalRestartClient` PASS (terminal and restart reports both PASS).
- Fit lines: crafting-table, crafting-inputs and crafting-outputs report a 566×376 panel at
  scale 1 and 407×232 within the 427×240 minimum GUI at scale 4. The presentation
  report records: "real table craft and both encoders; 1000 mB water input -> 2 Stone +
  500 mB water output, no declared stock consumed; collapse/reopen and scale changes
  retain ghosts."
- JEI report: fill reveals the crafting panel. A real Craft, table Encode and visible
  Inputs/Outputs processing authoring work at scale 4. Exact stock ends at 12 planks + 1
  stone, with one crafted table and one pattern of each kind. A second JEI transfer
  reopens Table from collapsed Outputs.
- Inspected captures: `crafting-{table,inputs,outputs}-scale-{1,4}.png`,
  `jei-terminal-filled.png` and `jei-processing-panel-scale-4.png`. Nothing overlaps or
  is clipped. The vanilla "New Recipes Unlocked" toast overlaps the Name button in two
  presentation frames; it is a harness artifact, not terminal layout. Three-digit fluid
  overlays such as "500" reach the slot edge, the same as item counts in the browser.
- `python3 scripts/verify.py` passed after the fixes (337 native tests).

Remaining gaps: physical JEI drag/recipe-plus gestures and full release acceptance.
