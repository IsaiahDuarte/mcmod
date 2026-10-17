# Loaded child with unloaded upstream root — 2026-10-17

Partial P2/P3/A03/A04/A05/A12/A13/A14 evidence, extending the
[one-chunk lifecycle experiment](chunk-lifecycle-v1-2026-10-17.md).
Minecraft1.21.1/NeoForge21.1.252/Java21.0.12 on macOS15.3.1 arm64,
Apple M1/8GiB. No JEI or Mekanism. Only Minecraft/NeoForge/Factory Core and the
excluded graphical fixture are loaded.

`./gradlew spotlessApply runTerminalRestartClient --console=plain` passed76s,
5 executed/10 up-to-date tasks. Seed PID35817 exited before restart PID35873.
All prior restart/one-chunk checks,20 seed captures and the native reopened screen
check passed. No ERROR appears in the final combined run.

The second JVM builds a separate network: root chunk111,272 cables, east-facing
gateway and child drive/machine/chest in chunk129. It initially retains both ends
explicitly and positions the player midway so normal view loading covers the
whole path. Child-local Stock moves8 of24 gold, preserving a four-unit original-
principal hold. It releases only the root's setup ticket and teleports far away;
the child remains administratively forced. This forcing belongs to the test,
not production discovery/automation.

Observed departure tick146 and actual root removal tick155. A native root-unload
event, null root chunk and removed old root prove unload; gateway/drive/machine
remain the same live objects in the child chunk. A three-item physical chest
removal goes into the player. For40 ticks, the child cell retains16 total/four
held but zero availability, claimed extraction and cached machine mutations reject,
child scope/preset execution are invalid, and Stock leaves the chest at5. The
entire canonical snapshot stays unchanged and no resource probe reloads the root.

Returning to the midpoint normally loads the path/root. A new physical controller
has the original root lease; child holders remain continuously loaded. Original
preset authority revalidates and Stock replenishes without redeployment. Final
gold is cell13 + machine8 + player3 =24, hold4 retained. The split lifecycle took112
server ticks in this run (functional observation, not a performance guarantee).
The fixture releases its child ticket and returns to the main network/native menu.

## Defect and fixture corrections

The first48-cable geometry could not initialize: repeated region mergers nested
DiscoverySeeds past its32-deep iterator guard, leaving a valid finite network in
REBUILDING with `Discovery source depth limit`. The archived before-failure log
records that diagnostic and failing deadline. A flat ownership-transferred frame
queue removes recursive composition while preserving all charged completion
visits. Combined8,192-frame admission now applies across the entire adopted queue;
region replacement-depth32 and all discovery work/vertex/probe limits remain.
Portable tests cover512 successive transfers and combined admission without
polling callbacks or losing pending seeds. The old recursive-depth rejection test
was replaced because the recursive representation no longer exists; limits and
single-owner cursor/failure coverage were strengthened, not bypassed.

Later setup attempts exposed vanilla fixture geometry requirements: four chunks
of separation still retained the root through generation dependencies, and a
player near only the child did not keep the longer initial cable path available.
The final eighteen-chunk geometry, explicit setup root ticket and midpoint player
solve those setup issues without synthetic events or relaxed unload assertions.
One attempted restart overlapped a fixture correction and was aborted; the final
fresh seed/restart ran alone. Failed/aborted attempts are not counted as passes.

[Checksums and artifacts](split-root-v1-2026-10-17/SHA256SUMS) include the original
depth failure, final combined log/results, expectation file, initial restored
canonical SNBT and reopened native screen. Initial SNBT precedes the separate
remote fixture; native assertions/logs cover its later lifecycle. Ordinary worlds
and production packaging remain untouched.

Limits: one child gateway, one item preset and one orderly upstream-root
unload/rejoin. Gateway-only unload, multiple branches/depths, queued/known/uncertain
staging during unload, independent saves, interrupted external writes,
dedicated-server/multiplayer, performance and full release acceptance remain open.
