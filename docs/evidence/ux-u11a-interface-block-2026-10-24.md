# U11a — One Interface block

Scope: U11 step a ([ADR 0059](../decisions/0059-unified-interface.md)). Minecraft
1.21.1, NeoForge 21.1.252, Java 21, macOS arm64.

Implemented:

- `factorycore:interface` is a single block and item with block states
  `kind=storage|machine|buffer` and `facing`. `InterfaceBlock` picks the backing
  entity from the kind: `ConnectorEntity`, `MachineInterfaceEntity` or
  `BufferedInterfaceEntity`. Those entity types keep their ids, lease kinds, schemas
  and recovery tools.
- **Placement.** A fresh item placed against a chest, barrel, shulker box or
  identified backing provider becomes Storage. Against any other block with an
  item, fluid or energy capability it becomes Machine; against nothing usable,
  Buffer. A moved item keeps the kind of the lease it carries.
- **Behavior.** The old blocks' code now lives, unchanged, in `ConnectorControls`
  and `BufferControls`. Buffer is a full cube wired on all faces; Storage and
  Machine keep the socket shape and socket-less wiring.
- **Removed.** The `storage_connector`, `machine_interface` and `buffered_interface`
  blocks, items, blockstates, recipes, recipe advancements and names, with no
  aliases (UD7). The one recipe is the old connector's (4 copper, 4 redstone,
  1 hopper). Screen titles now read "Interface: Storage / Machine / Buffer".
- **Docs.** The player guide has a new Interface section explaining the modes, and
  its recipe table and names are updated. Guide pages 4 and 6, the module-doc
  headers and SPEC R02 are updated.

Test updates. The requirement changed, so coverage moved with it; nothing was
weakened:

- About 20 GameTest classes and 7 client fixtures now place the Interface with an
  explicit kind. One test that changed a placed device's kind now clears the
  position first, because it is the same block.
- Recipe expectations follow the single recipe: 23 recipes. The starter kit drops
  the two removed recipes' exact inputs, and the Machine and Buffer recipe tests
  check the Interface recipe. The asset inventory lower bound is now ≥15 blocks.
- Audit golden inputs use `factorycore:interface`. The audit stores the opaque name
  verbatim, so input and golden changed together.
- The guide topics test now requires "Interface", "Machine mode" and "Buffer mode".
- New `InterfacePlacementGameTests` covers placement detection for chest, barrel,
  furnace, stone and air, maps each kind to its entity, and checks that a moved
  item keeps its kind.
- The terminal client's gateway step predates U8, so it now presses Custom before
  toggling and allows two-line column headers.

Checks:

- `python3 scripts/verify.py` PASS; all 350 required GameTests pass.
- `runPresentationClient` PASS, `runJeiClient` PASS and the terminal client PASS.
  `runTerminalRestartClient`'s restart phase failed once with a split-root deadline
  (a 272-cable branch reported NO_ROOT in time), then passed on an unchanged rerun.
  It is recorded as timing-sensitive, not fixed.

Next: U11b adds in-screen mode switching (idle only) and a shared mode row.
