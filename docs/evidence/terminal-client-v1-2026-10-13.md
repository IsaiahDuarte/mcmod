# Physical item terminal graphical verification — 2026-10-13

Scope: P3/A01/A03/A11/A14 component evidence, not full release acceptance.
Minecraft 1.21.1 / NeoForge 21.1.252 / Java 21.0.12, macOS 15.3.1 arm64,
Apple M1, 8 GiB memory. Mods: Minecraft, NeoForge, Factory Core and the isolated
Factory Core Graphical Terminal Verification fixture; no JEI or Mekanism.

## Reproduction and authority

Run `./gradlew runTerminalClient` on a desktop with Java 21. The test-only
clientTest source set is compiled/static-checked by the normal build but never
packaged in the distributed mod or loaded in ordinary runClient/runServer.
The run replaces only its generated world under build/client-terminal-run/saves/
factorycore-terminal-verification and its build/reports/client-terminal output;
normal client/server worlds are untouched. It needs a graphical environment;
compilation or headless tests alone do not establish this evidence.

The fixture creates a fresh vanilla flat creative world (seed 0x7E4A1), with an
owned controller, Starter Item Cell/drive, terminal, owned Import buffer and
actual downward vanilla hopper. Nine distinct base resources are seeded with
three units each after wired availability; the real hopper imports 23 named
iron items. Setup uses native server APIs solely to prepare the test world.
Player authentication and menu access use the actual integrated-server player.
No mock channel negotiation or direct server-menu commands are used here.

The client sends the physical use packet, observes the production TerminalScreen,
types `iron` through its character/Enter handlers, distinguishes named/plain keys,
selects the named row by number key, and clicks Take 1 through the production mouse
handler. Its actual native inventory must receive the exact named item. A real
C2S deposit command must restore all 23 items to the cell, with hopper/buffer and
player empty. Deposit uses the production payload/codec and server authority;
the physical Shift-key gesture itself is not simulated or verified. Next/Home
mouse handlers page eight/two/eight rows through the actual server query.

GUI scale changes exercise production reinitialization and require search text
`iron` to survive. Four unedited framebuffer captures are archived below.
Window: 1280×960 logical pixels, framebuffer 2560×1920. Actual GUI sizes are
1280×960 at scale2, 854×640 at scale3, and 640×480 at scale4. Labels, selected-row
feedback, item counts, action buttons and all 36 inventory slots fit in each
capture; no panel/control clipping was observed. Small scale2 remains naturally
smaller text than scale4. Native world tutorial/advancement overlays are visible;
no screenshot was cropped or composited.

## Results and files

Final `./gradlew runTerminalClient` passed in34 seconds (3 executed,
6 up-to-date tasks), including strict presence/nonempty checks for all four named captures. All fixture stages passed, captures wrote successfully,
and native logout stopped the integrated server, saved worlds/chunks and exited
normally. Lockfile review added configuration coverage without adding/removing
any dependency version. This normal save is not a restart/interrupted-save test.
The final log records an OpenAL `Stop: Invalid name parameter` error during audio
shutdown. The process still exited normally; audible playback/teardown is not
verified by this UI/network check. No terminal rendering/network assertion failed.

- [Native result](terminal-client-v1-2026-10-13/result.txt).
- [Full client log](terminal-client-v1-2026-10-13/client.log), with only trailing line whitespace removed for repository formatting.
- [Scale2 all items](terminal-client-v1-2026-10-13/scale-2-all-items.png).
- [Scale3 iron search](terminal-client-v1-2026-10-13/scale-3-iron.png).
- [Scale4 iron search](terminal-client-v1-2026-10-13/scale-4-iron.png).
- [Scale4 next page](terminal-client-v1-2026-10-13/scale-4-next-page.png).

## Defect found and verification limits

The initial physical menu opened on the server but immediately disappeared on
clients. Native ServerPlayer.initMenu broadcasts before assigning containerMenu;
our earlier broadcast guard closed the previous inventory menu and sent a close
packet after the open packet. The corrected guard leaves broadcasts for a different
current menu passive. A native GameTest now captures actual outbound close packets
and requires none during initialization; current-menu execution guards remain.
Existing headless opening tests lacked a real client consumer and missed this bug.

Earlier fixture attempts also exposed first-launch accessibility onboarding,
missing server import setup, embedded-pipeline handler naming, screenshot directory
creation and proper native logout ordering. These were fixture failures, not passes.
PMD resource findings concern borrowed Minecraft/server/level/window objects;
narrow documented suppressions preserve their native ownership instead of closing
live shared objects. The production screen gained distinct empty-slot borders and
search preservation after GUI-scale changes based on actual captures.

Remaining: physical Shift-click, hover/rich metadata tooltips, full keyboard focus,
Take64/Return/denied/full/stale client messages, other resolutions/OSes, multiplayer/
TCP negotiation, actual chunk/reconnect/full restart, independent saves/uncertainty,
survival crafting/power progression and representative performance. Terminal block
and other device/item model views are not established by these screen captures.
Manual crafting, fluid/energy/job tabs, sorts/tag/display-name search and the rest
of A01–A17 remain unfinished. No stage or full release criterion is complete here.


The verified source snapshot SHA-256 values are:

```text
TerminalMenu.java 05a4ac9831696d4552a42d66303ed8f33414e9b342bf4f7e483b6b63e6fb9011
TerminalScreen.java a2247f27f2e1a4a5269a5ba4cdbb8b6f6736654d72ef362d4c2759ad5d6b76a5
TerminalClientVerification.java f9dfef772aef052e004b2f15ddeddfa41c622a5fe037a46d0e9c575dec20e44e
```
