# Independent-save injection across dedicated JVMs — 2026-10-18

Partial A13 evidence. Minecraft 1.21.1 / NeoForge 21.1.252 / Java 21, macOS 15.3.1 arm64.
Only Minecraft, NeoForge, Factory Core and the excluded verification fixture are loaded.

`runSaveOrderRestoreServer` (part of `check`, so of `python3 scripts/verify.py`) runs two
dedicated JVMs in `build/save-order-run`:

1. **Seed**:
   - Places a controller commissioned by a fixed synthetic principal and drives A and B,
     then installs a starter item cell in A and inserts 20 iron.
   - Runs `saveEverything`, copies `factorycore_devices.dat` to
     `factorycore_devices.before`, moves the cell A→B (generation +2), and saves again.
2. **Between the JVMs**, Gradle copies the `.before` file over the data file. This
   simulates an interruption after the chunks were saved but before SavedData was.
3. **Restore**:
   - Asserts that the chunk holds the newer reference in B, A's chunk is empty, the
     canonical lease is still at A with the old generation, and B reports the stale
     reference.
   - Asserts there is one ledger with exactly 20 iron that is **not** usable.
   - Runs the real console command `factorycore recovery cell adopt`. After network
     revalidation, the lease is at B with generation +1, exactly 20 iron extract once,
     nothing remains, and adoption evidence exists.

Observed: seed and restore printed `FACTORYCORE_SAVE_ORDER_PASS`. The reports are in
`build/reports/save-order/`.

**Not covered:**
- Banks, buffers, structural nodes, player files, torn or corrupt writes.
- Multiplayer and performance.


## Converse order (added 2026-10-18)

`runSaveOrderChunksRestoreServer` (also part of `check`) uses `build/save-order-chunks-run`
and `factorycore.saveorder.order=chunks-old`.

1. **Seed:** additionally copies `region/r.0.0.mca` after the first save.
2. **Between the JVMs:** Gradle restores that older region file, while the newer SavedData
   stays.
3. **Restore:** asserts that drive A's chunk holds the old reference, drive B's chunk is
   empty, the canonical lease is already at B with the new generation, and A reports the
   stale reference. It also asserts one offline ledger with exactly 20 iron. It then adopts
   A by console command, and after revalidation 20 iron extracts exactly once.

Both orders printed `FACTORYCORE_SAVE_ORDER_PASS` for seed and restore in distinct JVMs.
