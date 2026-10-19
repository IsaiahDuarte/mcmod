# Original art client inspection — 2026-10-18

`./gradlew runTerminalRestartClient` ran the required `runTerminalClient` dependency
with the integrated worktree above main `c7cc16c`, Minecraft 1.21.1, NeoForge
21.1.252, OpenJDK 21.0.12, macOS 15.3.1 arm64 / Apple M1. Optional mods absent.

The terminal tour **passed**, producing its PASS report and all 35 required
captures. [Full tour report](art-client-v1-2026-10-18/tour.txt). The art scenes used
a 2560×1920 framebuffer at GUI scale 2; inventory icons were also checked at
scale 4. These are actual in-game renders, not the generator's reference sheet.

## Inspected captures

- [Device fronts](art-client-v1-2026-10-18/art-blocks-front.png) and
  [tops](art-client-v1-2026-10-18/art-blocks-top.png): dark industrial casings,
  copper sockets, the buffer's nine-slot tray, drive bays, distinctive program/
  crafting/wireless symbols and both generator lit/unlit appearances rendered.
- [Cable geometry](art-client-v1-2026-10-18/art-cables.png): straight, corner,
  branch and vertical connected segments rendered. The sideways gateway used its
  marked ports; a cable touching its nonport face remained a separate core.
- All 32 registered items rendered in the double chest at
  [scale 2](art-client-v1-2026-10-18/art-items-scale-2.png) and
  [scale 4](art-client-v1-2026-10-18/art-items-scale-4.png), including the cell/
  module tier markings and distinct blank/crafting/processing pattern icons.

No missing-texture checkerboards or missing-model forms appeared in these scenes.
The source-resource test and native registry coverage test also passed in the
combined canonical gate (327 JVM / 289 GameTests). Selection/collision shape
behavior is covered by native cable checks; the captures alone do not prove it.

## Separate restart result and limits

The subsequent separate-process restart matched initial canonical/physical saved
state, resumed Stock, recovered known staging without replaying uncertain calls,
and passed actual chunk and split-root unload/reload checks. It then **failed**
with `World restart timeout stage=3 screen=none` while trying to reopen the machine
editor. That failure remains under investigation and this run is not counted as
a full restart pass. The old replacement-depth diagnostic did not recur.

The gallery does not verify held transforms, all six gateway orientations, every
ready/fault/paused state, all tooltip/keyboard behavior, JEI physical gestures or
the survival onboarding walkthrough. Those asset/acceptance requirements remain open.

## Verified rerun after fixture synchronization change

`./gradlew spotlessApply pmdClientTest runTerminalRestartClient` **passed in
1m40s**, including a second full 35-capture tour and a fresh separate-process
restart (seed PID 30845; restart PID 31045). [Restart report](art-client-v1-2026-10-18/restart.txt)
and [saved Stock editor](art-client-v1-2026-10-18/restarted-machine-stock.png).

The fixture now probes the real server's loaded machine, `canUse`, current
membership and empty hands before right-clicking. It allows bounded retries if
no screen arrives, with the original 1,200-tick stage deadline and unchanged
state/conservation/editor assertions. The verified run opened on attempt 1 with
`canUse=true membership=VALID`, then showed goal 8 and its exact rule at a
427×240 GUI, scale 4. The failed run did not log admission state, so transient
rebuilding remains a plausible synchronization cause rather than a proven
production defect. The unrelated older Presets-stage failure is not fully explained.

The report proves identical initial canonical/physical resources; water 1,200 mB
(400 reserved), lava 300 mB, bank 500 FE; resumed Stock conservation of 25 named
items; known staging delivery/return with no uncertain replay; actual chunk
unload/reload and loaded-child/root-unload suspension/rejoin. This local rerun
extends A03/A05/A11/A13/A14 evidence without completing their full release matrix.
