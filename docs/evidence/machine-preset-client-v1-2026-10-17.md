# Machine preset editor graphical evidence — 2026-10-17

Partial P3/A04/A12/A14 evidence on Minecraft1.21.1, NeoForge21.1.252,
Java21.0.12, macOS15.3.1 arm64, Apple M1/8GiB. No JEI or Mekanism.

`./gradlew spotlessApply runTerminalClient --console=plain` passed in50s,
3 executed/9 up-to-date tasks. Its isolated fixture creates a fresh generated
world under build/client-terminal-run, requires PASS and20 nonempty screenshots,
and exits through normal integrated-server logout/save. No ERROR appears in the
archived log. Normal saves and production packaging are untouched.

The actual integrated player repeats the terminal/buffer/binding flow, then
reopens Binding and changes READ_ONLY to READ_WRITE through the native buttons
and Apply. Presets cycles Paused→Import→Export→Stock, copies its own named item
sample, types goal8 and deploys through real native handlers/production payloads.
The server must reach exactly8 matching items in selected slot2 while slots0/1
stay empty. Authoritative final conservation is cell6 + machine8 + buffer9 +
player sample2 =25. No direct server-menu command replaces these UI actions.

The fixture captures unsaved Stock at scales2/3 and deployed Stock at scale4.
Framebuffer2560×1920 / logical window1280×960 yields GUI1280×960,854×640,640×480.
The scale2 draft and scale4 deployed views were inspected with no panel/control
clipping observed. The new screen also preserves typed goal/rule text on resize.
This run exercises automated native inputs, not physical HID events.

[Seven machine captures, native result and full log](machine-preset-client-v1-2026-10-17/SHA256SUMS)
are archived with SHA256 checksums. The result lists all20 captures; only the seven
machine captures are archived here. Earlier terminal/buffer images remain in the
[previous settings record](settings-client-v1-2026-10-17.md).

This does not prove every preset action graphically: registry/tag/remove/paging,
fluid/FE samples, cleanup, permission failures, accessibility and physical Shift
need broader client checks (some have headless coverage). Remote multiplayer/TCP,
actual restart/independent saves, selected tanks, multiport coordination,
performance, crafting and full first-playable acceptance remain unfinished.
