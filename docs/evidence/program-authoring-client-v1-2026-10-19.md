# Program authoring, terminal search and JEI client evidence — 2026-10-19

macOS arm64 (Apple M1), OpenJDK 21.0.12, Minecraft 1.21.1, NeoForge 21.1.252, 2560×1920 window.

Commands (each wrapped in `caffeinate -d -i`):

- `./gradlew runTerminalRestartClient` — PASS in 1m53s. All 47 required captures of the
  terminal/editor/art tour, including six search/sort captures and six program-authoring
  captures, then a separate-JVM restart that restored the saved `@minecraft`/quantity terminal
  view (`restarted-terminal-preferences.png`), chunk unload/reload and split-root checks.
- `./gradlew runJeiClient` — PASS. JEI's own runtime resolved the program ghost handler and
  dropped an item and a fluid into the new `ProgramTextEditor`
  (`item("minecraft:iron_ingot") fluid("minecraft:water")`), and filled the terminal crafting
  grid. Physical drag gestures are still not simulated.

Product bugs found by these runs and fixed (ADR 0051):

1. The hidden text editor still handled mouse clicks, so clicking a picker row moved its
   cursor instead of inserting the chosen item. A hidden editor now ignores clicks.
2. After a picker click, vanilla focused the clicked row, so Ctrl+Z/typing missed the editor.
   Focus returns to the editor after the click.
3. The rule preview showed internal machine UUIDs; it now names targets by local label
   (`"smelter" #1 -> storage`). `ProgramPreviewTest` and the graphical fixture assert this.

Screenshots: [items picker](program-authoring-client-v1-2026-10-19/program-native-items-scale-2.png),
[labels picker](program-authoring-client-v1-2026-10-19/program-native-labels-scale-2.png),
[highlighted editor at scale 4](program-authoring-client-v1-2026-10-19/program-highlighted-scale-4.png),
[rule preview](program-authoring-client-v1-2026-10-19/program-rule-preview-scale-4.png),
[tag search](program-authoring-client-v1-2026-10-19/terminal-search-tag.png),
[quantity sort](program-authoring-client-v1-2026-10-19/terminal-sort-quantity.png),
[reopened search](program-authoring-client-v1-2026-10-19/terminal-search-reopened.png),
[restored preferences after restart](program-authoring-client-v1-2026-10-19/restarted-terminal-preferences.png),
[JEI ghost drops](program-authoring-client-v1-2026-10-19/jei-program-ghost.png).

Selected tanks (ADR 0053): the tour now switches the Machine Interface draft to Fluids and types
first tank 1 / window 1 before the scale-4 capture, asserting both values were accepted by the
editable fields; Cancel then discards the draft as before.
[Capture](program-authoring-client-v1-2026-10-19/machine-fluid-tank-window-scale-4.png).
