# Buffer and machine settings graphical verification — 2026-10-17

Scope: P3/A01/A03/A04/A11/A12/A14 component evidence, not full release acceptance.
Minecraft1.21.1/NeoForge21.1.252/Java21.0.12 on macOS15.3.1 arm64, Apple M1/8GiB.
Mods: Minecraft, NeoForge, Factory Core and isolated graphical verification fixture;
no JEI or Mekanism. This attempt had one active CoreGraphics display; the prior
[no-display attempt](buffer-client-attempt-2026-10-13.md) remains historical evidence.

## Reproduction and input

`./gradlew spotlessApply runTerminalClient --console=plain` passed in45s (4 executed,
8 up-to-date tasks). The task requires a PASS result and all17 nonempty captures.
The test-only client source set is compiled/static-checked but not packaged or
loaded during ordinary runs. It replaces only its generated world/report under
build/client-terminal-run and build/reports/client-terminal. Normal saves are untouched.

The existing terminal search/withdraw/paging/deposit loop repeats on a fresh flat
world with actual integrated-server authentication and connection. Buffer native
buttons cycle Import/Export/Stock and edit rate8/goal8, seven registry/tag rules,
and two rule pages. Exact ghost clicks copy the server's actual named sample
without consuming its two items. The fixture feeds Minecraft's installed GLFW
cursor callback before the production click handler; this exercises actual render
hover/input handling without depending on desktop focus. It is not a physical HID
mouse/keyboard claim. No direct server-menu command is used.

Apply deploys an exact Stock rule; the live buffer reaches8 named items. Take1
merges into the matching sample stack (2→3) and Stock replenishes. A real C2S
one-item deposit at goal reports FULL and retains all three player items. Paused
Apply allows that one-item deposit (3→2). Take64 under rate8 moves exactly8, reports
8/64 and merges player2→10; deposit8 restores2. Final authoritative conservation
is named cell14 + buffer9 + original player sample2 =25. Original hopper is empty.
Deposits use production payloads, not a physical Shift gesture, which remains open.

The fixture places a physical Machine Interface and chest, opens its native menu,
types/reset/retypes a complete local form, cycles access and changes GUI scale2→3→4
without losing the draft. Cancel must leave saved default label/rate unchanged;
reopening proves that. Apply & Close must save exactly label smelter, ITEM,
READ_ONLY, priority-7, first slot2/count1/rate8 through the actual C2S handler.
This verifies configured state, not machine presets or an actual machine job.

The final client quit logs normal integrated-server save/logout and exits0.
No ERROR appears in the final archived run. Earlier attempts exposed fixture
assumptions: GLFW cursor movement depended on focus, and matching items merge
into an existing stack rather than an empty slot. The fixture now uses the
registered native callback and asserts exact merge/conservation counts; no
production resource or authority checks were weakened.

## Captures and limits

All17 unedited framebuffer captures are archived with
[SHA256 checksums](settings-client-v1-2026-10-17/SHA256SUMS),
[native result](settings-client-v1-2026-10-17/result.txt) and
[full run log](settings-client-v1-2026-10-17/client.log) (trailing whitespace removed).
Window1280×960 logical/framebuffer2560×1920 gives GUI1280×960 at scale2,
854×640 at scale3 and640×480 at scale4. Representative captures were inspected:
no panel/control clipping was observed in those views. Vanilla tutorial/chat
world overlays remain visible. Scales/resolutions outside these combinations,
manual accessibility navigation, physical Shift, multiplayer/TCP, actual restart/
independent saves, survival balance and full model/state inspection remain open.

- [buffer-scale-2-full.png](settings-client-v1-2026-10-17/buffer-scale-2-full.png).
- [buffer-scale-2-rules-page-1.png](settings-client-v1-2026-10-17/buffer-scale-2-rules-page-1.png).
- [buffer-scale-2-rules-page-2.png](settings-client-v1-2026-10-17/buffer-scale-2-rules-page-2.png).
- [buffer-scale-2-settings.png](settings-client-v1-2026-10-17/buffer-scale-2-settings.png).
- [buffer-scale-2-stock.png](settings-client-v1-2026-10-17/buffer-scale-2-stock.png).
- [buffer-scale-3-inventory.png](settings-client-v1-2026-10-17/buffer-scale-3-inventory.png).
- [buffer-scale-3-settings.png](settings-client-v1-2026-10-17/buffer-scale-3-settings.png).
- [buffer-scale-4-inventory.png](settings-client-v1-2026-10-17/buffer-scale-4-inventory.png).
- [buffer-scale-4-settings.png](settings-client-v1-2026-10-17/buffer-scale-4-settings.png).
- [machine-scale-2-draft.png](settings-client-v1-2026-10-17/machine-scale-2-draft.png).
- [machine-scale-3-draft.png](settings-client-v1-2026-10-17/machine-scale-3-draft.png).
- [machine-scale-4-draft.png](settings-client-v1-2026-10-17/machine-scale-4-draft.png).
- [machine-scale-4-reopened.png](settings-client-v1-2026-10-17/machine-scale-4-reopened.png).
- [scale-2-all-items.png](settings-client-v1-2026-10-17/scale-2-all-items.png).
- [scale-3-iron.png](settings-client-v1-2026-10-17/scale-3-iron.png).
- [scale-4-iron.png](settings-client-v1-2026-10-17/scale-4-iron.png).
- [scale-4-next-page.png](settings-client-v1-2026-10-17/scale-4-next-page.png).
