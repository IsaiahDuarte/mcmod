# Terminal crafting-grid graphical verification — 2026-10-18

Scope: P4/A11 component evidence for the manual crafting grid, not full release
acceptance. Minecraft 1.21.1, NeoForge 21.1.252, Java 21.0.12 on macOS 15.3.1 arm64,
Apple M1 with one active 2560×1600 display. Mods: Minecraft, NeoForge, Factory Core and
the graphical verification fixture; no JEI.

## Reproduction

`./gradlew spotlessApply runTerminalClient --console=plain` passed, writing `PASS` and
all 22 required nonempty captures. The 20 terminal/buffer/machine/preset captures repeat the
[2026-10-17 flow](settings-client-v1-2026-10-17.md).

## What this run found and fixed

The crafting-grid steps were added with the grid on 2026-10-17. No display was available
then, so they never ran graphically. The first run on 2026-10-18 failed at stage 11:

1. **Harness assumption (test bug).** It expected the four planks in the log's slot (20).
   The server places crafted output the way vanilla shift-crafting does: first matching
   stack, then the first free slot (0). The harness now finds the output and checks the
   whole inventory: no logs and exactly four planks.
2. **Layout bug (product bug).** The `Craft/Inputs/Outputs` grid-mode button
   (y 131–144) overlapped the Return/Cancel row (y 118–136). It also shared the label
   "Craft" with the real Craft action. The button now sits left of the inventory under a
   "Grid" caption, and its first state reads **Table**.

The harness now asserts that no visible terminal controls overlap at GUI scales 4 and 2.
Against the old layout it failed with `Controls overlap: Return / Craft`; the fixed
layout passes.

## Evidence

[terminal-grid-scale-4.png](terminal-grid-client-v1-2026-10-18/terminal-grid-scale-4.png),
[terminal-grid-scale-2.png](terminal-grid-client-v1-2026-10-18/terminal-grid-scale-2.png),
[result.txt](terminal-grid-client-v1-2026-10-18/result.txt) and `SHA256SUMS`.

The run covers: actual server recipe preview, own-slot ghost by click and `1` key, Row→,
right-click clear, Craft (1 log → 4 planks) and Clear.

**Not covered:** fluid/energy terminal kinds, the wireless terminal, the gateway policy
editor, the Factory Guide, the program editor, JEI, physical HID input and multiplayer.
