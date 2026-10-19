# Wireless endpoint chunk lifecycle evidence

2026-10-18, macOS 15.3.1 arm64, OpenJDK 21.0.12, Minecraft 1.21.1,
NeoForge 21.1.252. Working tree based on `c7cc16c` with resumed V1 integration;
the verification fixture is
`src/clientTest/java/dev/izzy/factorycore/clienttesting/WirelessLifecycleVerification.java`.

Command: `./gradlew spotlessApply compileClientTestJava runWirelessLifecycleServer`.
Result: **BUILD SUCCESSFUL**, 24 seconds. The dedicated server shut down normally
and saved all dimensions. The fresh generated report
`build/reports/wireless-lifecycle/result.txt` contained `PASS`, `unloads=1`,
`ticks=48`.

The fixture loads a controller, gateway and endpoint in chunk (112, 0), plus a
reciprocal endpoint, gateway and drive in chunk (127, 0). The pair spans 240 blocks.
It restores pairing through normal block-entity NBT loading, continuously supplies
operating power and waits for valid topology before storing 24 iron ingots and
reserving four under the original principal.

It releases only the remote chunk's administrative setup ticket. One actual
`ChunkEvent.Unload` and removed remote block entities are required; the parent
endpoint stays current. For 40 ticks the remote chunk remains absent, catalog stock
is offline with total 24/reserved 4/available 0, claimed extraction returns OFFLINE,
and the entire canonical SavedData snapshot remains unchanged. Production probes
never load the remote chunk.

Explicit fixture loading then reacquires a new drive block entity with the same
cell lease and restores the original reciprocal endpoint pair. After topology
revalidation, extracting the four reserved ingots succeeds exactly, leaving 20
stored and no reservation. No production lifecycle defect was exposed.

This supports A16's orderly endpoint unload/reload behavior. It does not prove
per-pair item/fluid/energy caps, a wired/wireless throughput comparison, staging
recovery during interrupted external calls, or graphical interaction. These remain
separate release work. The task is a required `check` dependency and rejects a
missing or stale PASS report.
