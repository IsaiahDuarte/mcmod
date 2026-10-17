# Actual distance-driven chunk unload/reload — 2026-10-17

Partial P2/P3/A03/A04/A12/A13/A14 evidence, extending the
[orderly restart experiment](world-restart-v1-2026-10-17.md).
Minecraft1.21.1/NeoForge21.1.252/Java21.0.12, macOS15.3.1 arm64,
Apple M1/8GiB. Only Minecraft, NeoForge, Factory Core and the excluded graphical
fixture are loaded. No JEI or Mekanism.

`./gradlew spotlessApply runTerminalRestartClient --console=plain` passed69s,
5 executed/10 up-to-date tasks. Seed PID34423 exited before restart PID34481.
Both normal world exit/reopen and the existing20 seed captures/native reopened
screen check passed. The final combined log contains no ERROR.

The second JVM first repeated complete canonical/physical restart validation,
then removed3 items and observed saved Stock replenishment. Its baseline before
chunk departure was cell3 + machine8 + buffer9 + player5 =25. It captured physical
holders/leases, canonical state and live buffer/bank/machine capabilities, set
spawnChunkRadius=0 only in the generated fixture world, and teleported the real
player1024 blocks away. No chunk was directly unloaded, no lifecycle method was
invoked to imitate unload, and no synthetic chunk event was posted.

Observed server ticks: departure45, actual unload51 (one native unload event),
return91, reload92 (one native load event), completed lifecycle113. These are
fixture observations, not representative performance guarantees. For40 server
ticks after unload, `getChunkNow(0,0)` remained null and every old holder was
removed. Exact item/fluid totals and the400mB hold remained canonical, availability
was zero, digital extraction returned OFFLINE, cached bank/buffer/machine ports
rejected mutation, and the entire canonical snapshot stayed unchanged. Repeated
resource probes did not reload the chunk.

Returning normally constructed new physical objects with the same root/machine/
buffer/cell/bank leases, canonical snapshot, provider backing and original preset
authority. Old capability objects stayed inert after the new holders bound.
The chest/buffer/bank physical quantities survived native chunk serialization.
A real one-item removal into the matching player stack then caused saved Stock
to replenish without redeployment. Final conservation was cell2 + machine8 +
buffer9 + player6 =25. The native saved preset editor reopened before normal quit.

[Checksums and artifacts](chunk-lifecycle-v1-2026-10-17/SHA256SUMS) archive both
results, expected observations, initial restored canonical SNBT, full final log
and reopened native capture. The initial canonical SNBT precedes the intentional
item removals; the log/assertions cover the later chunk baseline and mutations.
Only the generated fixture world/reports under build are replaced; normal saves
and production packaging remain separate.

Limits: one loaded network wholly inside one chunk on one integrated server/OS.
Cross-chunk root/branch suspension, split providers, queued/owned staging during
unload, independent player/chunk/controller save ordering, interrupted external
writes, dedicated servers/multiplayer, separate-world isolation, large workloads
and full release acceptance remain unfinished. This is genuine lifecycle evidence,
not completion of D03/A13 or the full first playable release.
