# Buffer settings graphical check attempt — 2026-10-13

Status: **blocked before Minecraft/client fixture execution**, not a graphical pass.
Scope: P3/A01/A03/A04/A11/A14; the full release remains incomplete.

## Command and observed environment

`./gradlew runTerminalClient --console=plain` failed in38 seconds (2 executed,
7 up-to-date tasks). Compilation completed; NeoForge early display initialization
reported `Failed to locate a primary monitor` and `glfwGetPrimaryMonitor failed`.
The fixture reached no stages and produced no new result/captures. The launcher
opened an error dialog; its specific test-owned osascript process was terminated,
and the same Gradle execution then exited1. No normal game world was launched.

CoreGraphics independently reported zero active displays and one online display.
A short `caffeinate -u -t 5` wake request did not restore an active display.
The console still belonged to the logged-in user. This establishes a current
local display prerequisite; it does not diagnose the physical monitor or macOS
session further. Java21.0.12/macOS15.3.1 arm64/NeoForge21.1.252 were reported by
this launch. [Full attempted-launch log](buffer-client-attempt-2026-10-13/launch.log)
is archived with trailing line whitespace removed only.

## Prepared verification and remaining work

The isolated existing graphical fixture is extended to open the real buffer menu,
edit mode/rate/goal and registry/tag rules, page rules, copy exact samples without
consumption, Apply an exact Stock policy, withdraw and replenish, reject a deposit
at the full goal, pause and perform partial/return deposits. It checks final named
ownership cell14 + buffer9 + original sample2 =25 and captures Settings/Inventory
at GUI scales2–4. Native samples position the actual GLFW pointer before clicking
the production handler. Deposits still use the production C2S payload; the physical
Shift gesture is not simulated. The task now requires all13 terminal/buffer
captures and a PASS result; its headless compilation cannot prove these steps.

**None of those new client steps has executed yet.** Once an active desktop display
is available, rerun the command, resolve observed failures without weakening
assertions, inspect the captures and archive actual results. Prior terminal-only
[evidence](terminal-client-v1-2026-10-13.md) remains the historical successful run;
it proves no new buffer-screen behavior. Multiplayer, restart, independent save
interruptions, manual keyboard/Shift handling and full R08 acceptance remain open.
