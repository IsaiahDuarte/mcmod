# U4 — Crafting panel — implementation pending graphical verification

Scope: P4/R08, partial A11/A14. Minecraft 1.21.1, NeoForge 21.1.252,
Java 21.0.12, macOS 15.3.1 arm64. U4 is **not complete** until the graphical
and JEI checks below pass and their scale 1/4 captures have been inspected.

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

Resume with the display awake: run `runPresentationClient`, `runTerminalRestartClient`
and `runJeiClient`, each under the graphical lock and `caffeinate`. Inspect the new
Table/Inputs/Outputs captures at scales 1 and 4, plus JEI-filled/processing captures.
Fix failures without weakening assertions, update A11/A14 evidence, commit U4 and
only then mark its UX-plan checkbox complete. Physical JEI drag/recipe-plus gestures
and full release acceptance remain separate gaps.
