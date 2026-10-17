# Separate-process orderly world restart — 2026-10-17

Partial P2/P3/A01/A03/A04/A12/A13/A14 evidence, not complete release acceptance.
Minecraft1.21.1 / NeoForge21.1.252 / Java21.0.12 on macOS15.3.1 arm64,
Apple M1/8GiB, with Minecraft/NeoForge/Factory Core and the excluded test fixture.
No JEI or Mekanism.

`./gradlew spotlessApply runTerminalRestartClient --console=plain` passed in67s,
7 executed/8 up-to-date tasks. The dependent seed client completed all20 required
terminal/buffer/machine captures, added mixed fluid/hold/bank state, captured
expected observations, and quit/save/exited. Seed PID23720 and restart PID23774
were distinct; the second process required the seed process to have exited.
It opened the existing generated world with Minecraft's normal open-world flow.
It never called fresh setup or installed expected data into production registries.

Before resource changes, the complete canonical SavedData snapshot equaled the
pre-save observation, including resources/claims, world namespace, leases,
configurations, presets, grants and tables. Physical root/machine IDs, provider
backing component identity and authenticated principal also matched. Real player,
chest and canonical buffer contents conserved cell6 + machine8 + buffer9 + player2
=25 named items. Mixed storage recovered1200mB water with400 held/800 available,
300mB lava and a loaded finite bank containing500FE. The hold is a primitive
principal-owned reservation; this is not evidence of an implemented crafting job.

The fixture removed3 actual chest items into an empty player slot. The saved
Stock preset replenished via production scheduling without redeployment. Final
conservation was cell3 + machine8 + buffer9 + player5 =25. The authenticated native
machine editor reopened with saved exact filter/Stock goal8, then quit/saved normally.
No ERROR appears in the final archived combined run.

The default second window exposed a real UI regression: the prior272-pixel-high
preset panel clipped in a427×240 GUI (window854×480, framebuffer1708×960, scale4).
The production panel is now300×236, with four rule rows and a four-button footer.
The final run required every visible widget to fit the actual screen, and its
unedited screenshot was inspected without observed panel/control clipping.
Goal tooltip units also now use items/mB/FE rather than transfer-rate units.

An earlier setup attempt tried to fill a just-placed bank while wire membership
was rebuilding and failed. The fixture now waits for current loaded availability
before initial fluid/FE mutations. No production availability guard, resource
expectation or assertion was weakened. A subsequent successful restart exposed
the clipping issue; the final full seed/restart rerun passed after its correction.

[Checksums and archived artifacts](world-restart-v1-2026-10-17/SHA256SUMS) include
expected observations, restored canonical SNBT, both results, full final run log
and native screenshot. Generated worlds are not checked in. Reproduction replaces
only the generated fixture world/reports under build; ordinary saves are untouched.

Limits: one orderly integrated-server restart on one OS/host and a small world.
This does not prove independent chunk/player/controller save ordering, forced
unload, interrupted external calls, crash/power-loss, dedicated-server restart,
separate-world namespace isolation, multiplayer, performance, survival balance,
job cleanup or complete A01–A17 acceptance. Those remain required work.
