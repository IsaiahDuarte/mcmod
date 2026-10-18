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

## Screen tour (second pass, same day)

The fixture now continues past the machine presets. Before the restart checkpoint, it
covers:
- **Gateway policy editor:** opened by a real sneak-use packet; the owner clicks the
  first toggle, and exactly that bit flips server-side; captured at scales 2 and 4.
- **Program editor:** captured at scale 2.
- **Factory Guide:** opened by actual item use; pages 1 and 2 captured.

After the checkpoint has stocked fluids and FE, it covers the terminal **Fluids** view
(Lava 300, Water 1,200 mB) and **Energy** view (500 FE). The task requires 29 captures.

Every capture asserts that no controls overlap and every button label fits. Inspecting
the screenshots found four more product bugs, now fixed:
1. Gateway policy column headers were cut off ("Branch grants (ch…"). They are now
   *Branch grants / Inherited access / Resource kinds*. The fixture checks they fit.
2. The gateway policy footer was cut off mid-word. It now wraps.
3. Energy view buttons "Charge held"/"Discharge held" overflowed. They are now
   **Charge**/**Drain**.
4. The Fluids and Energy views showed the item hint. Each now shows its own hint.

The program editor's character counter was also drawn over the log pane; the editor is
now shorter.

Screenshots: [policy ×2](terminal-grid-client-v1-2026-10-18/gateway-policy-scale-2.png),
[program](terminal-grid-client-v1-2026-10-18/program-editor-scale-2.png),
[guide 1](terminal-grid-client-v1-2026-10-18/guide-page-1.png)/[2](terminal-grid-client-v1-2026-10-18/guide-page-2.png),
[fluids](terminal-grid-client-v1-2026-10-18/terminal-fluids-scale-2.png),
[energy](terminal-grid-client-v1-2026-10-18/terminal-energy-scale-2.png).

## Restart run reliability

`runTerminalRestartClient` passed in 5 of 7 runs after the tour was added. It failed
twice:
- once at restart stage 4 (the reopened machine Presets tab);
- once in the split-root fixture: the child branch stayed `NO_ROOT` for 1,000 ticks; it
  normally validates within 100.

Neither failure has been reproduced or explained. Both failure messages now report the
open screen, the root/gateway membership and the network diagnostic. This is an open
flaky failure, not a pass.

**Not covered:** the wireless terminal, JEI, physical HID input and multiplayer.
