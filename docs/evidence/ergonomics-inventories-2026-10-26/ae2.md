# Applied Energistics 2 — player-facing feature inventory (MC 1.21.1 / NeoForge)

Source: `https://github.com/AppliedEnergistics/Applied-Energistics-2`, branch `1.21.1`, commit
`db17504a86128fdf3dae31f5fb7a112a646e0b93` (2026-09-27). `gradle.properties`: `minecraft_version=1.21.1`,
`neoforge_version=21.1.169`, `guideme_version=21.1.1`. The default branch `main` targets a newer MC version, so it was
not used.

Method: read the generated English lang file, the registries (`AEBlocks`, `AEItems`, `AEParts`, `InitUpgrades`), the
`Settings` enum and every `IConfigManager.builder` host, all menus and screens, every serverbound packet and
`registerClientAction`, `AEConfig`, the commands, the integration modules, the client render and hook code, and the
guidebook. Nothing was built or run.

Path abbreviations (all repo-relative):
- `J/` = `src/main/java/appeng/`
- `L` = `src/generated/resources/assets/ae2/lang/en_us.json`
- `D/` = `src/generated/resources/data/ae2/`
- `G/` = `guidebook/`

Markers: **[config: key]** means a config option changes or gates the feature. **[dev-only]** means it needs
`general.unsupportedDeveloperTools` (default on only in a dev environment). **[lang-only]** means the key exists in
the lang file but this branch's code never references it (a leftover). **[guide-only]** means the claim comes from the
guide and was not checked against code.

---

## Blocks

- ME Controller — network routing hub (32 channels per face), accepts power, multiblock up to 7x7x7 with an adjacency rule, right-click opens network status; without one the network is ad-hoc (8 channels max) — `J/block/networking/ControllerBlock.java`, `G/items-blocks-machines/controller.md`
- ME Drive — 10 cell slots, cell status LEDs, priority, rotatable, cells can be hopper/bus-automated — `J/blockentity/storage/DriveBlockEntity.java`, `G/items-blocks-machines/drive.md`
- ME Chest — 1-cell mini network: the top face is a terminal on that cell and the sides open the cell slot plus priority; small energy buffer; terminal colorable — `J/blockentity/storage/MEChestBlockEntity.java`, `G/items-blocks-machines/chest.md`
- ME Interface (block) — 9 config/stock slots plus storage, keeps stock to/from the network, multi-type (items and fluids), priority — `J/block/misc/InterfaceBlock.java`, `J/helpers/InterfaceLogic.java`
- ME Pattern Provider (block) — 9 pattern slots, pushes ingredients to adjacent inventories, return slots; omni by default, wrench makes it directional (PUSH_DIRECTION) — `J/block/crafting/PatternProviderBlock.java`, `J/helpers/patternprovider/PatternProviderLogic.java`
- Molecular Assembler — crafts crafting/smithing/stonecutting patterns from a pattern slot or one pushed by an adjacent provider; ejects results to neighbours; 5 speed cards; renders the item being crafted — `J/blockentity/crafting/MolecularAssemblerBlockEntity.java`
- Crafting Unit / Crafting Co-Processing Unit / 1k–256k Crafting Storage / Crafting Monitor — crafting CPU multiblock parts (solid rectangular prism, at least 1 storage) — `J/block/crafting/CraftingUnitType.java`, `G/items-blocks-machines/crafting_cpu_multiblock.md`
- Crafting unit in-world upgrade — right-click a crafting unit with a component (for example an engineering processor) to turn it into an accelerator, storage or monitor; shift-right-click with an empty hand reverts it and returns the component — `J/block/crafting/AbstractCraftingUnitBlock.java`, `J/recipes/game/CraftingUnitTransformRecipe.java`, `D/recipe/crafting_unit_upgrade/`
- ME IO Port — 6 input and 6 output cell slots; fills or empties cells to or from the network; 3 upgrade slots; rotatable — `J/blockentity/storage/IOPortBlockEntity.java`
- Cell Workbench — configures cells and view cells (partition, upgrades, fuzzy, copy mode) — `J/blockentity/misc/CellWorkbenchBlockEntity.java`
- Inscriber — presses and crushes using presses; sided or combined automation; auto-export; adjustable input buffer; FE or AE power; renders items in world — `J/blockentity/misc/InscriberBlockEntity.java`, `J/client/render/tesr/InscriberTESR.java`
- Charger — charges powered items, certus to charged certus, compass to meteorite compass, book to guide; FE/AE from top/bottom; crank-powerable; rotatable; Fluix Researcher job site — `J/block/misc/ChargerBlock.java`, `D/recipe/charger/`
- Wooden Crank — placed on a charger or growth accelerator, right-click to power manually — `J/block/misc/CrankBlock.java`, `J/client/render/tesr/CrankRenderer.java`
- Matter Condenser — trash any key type, or condense into matter balls or singularities (needs a component in the top slot) — `J/blockentity/misc/CondenserBlockEntity.java`
- Energy Acceptor (block) — converts FE to AE — `J/block/networking/EnergyAcceptorBlock.java`
- Energy Cell (200k AE) / Dense Energy Cell (1.6M AE) — network energy buffer; 5-step fill indicator; keeps stored energy when broken; the item can be charged — `J/blockentity/networking/EnergyCellBlockEntity.java`, `J/block/networking/EnergyCellBlockItem.java`
- Creative Energy Cell — infinite power — `J/block/networking/CreativeEnergyCellBlock.java`
- Vibration Chamber — burns furnace fuel for AE (40 AE/t default) and throttles when the network is full; energy and speed cards — `J/block/misc/VibrationChamberBlock.java` **[config: vibrationChamber.*]**
- Crystal Resonance Generator — fuel-free AE generator; only one per network works, extras show "Suppressed" — `J/block/networking/CrystalResonanceGeneratorBlock.java` **[config: powerRatios.crystalResonanceGeneratorRate]**
- Growth Accelerator — extra random ticks for adjacent budding quartz, amethyst and plants; FE/AE or crank — `J/block/misc/GrowthAcceleratorBlock.java` **[config: crafting.growthAccelerator]**
- ME Wireless Access Point — booster slot, wireless terminal link slot, range and power scale with boosters; needs a channel — `J/blockentity/networking/WirelessAccessPointBlockEntity.java`, `J/menu/implementations/WirelessAccessPointMenu.java`
- ME Quantum Ring / ME Quantum Link Chamber — 3x3 ring formation makes a Quantum Network Bridge (32 channels, any distance, cross-dimension, needs an entangled singularity) — `J/block/qnb/`, `G/items-blocks-machines/quantum_bridge.md`
- Spatial IO Port — moves a pylon-bounded volume into or out of a spatial cell on a redstone signal; cell automatable — `J/blockentity/spatial/SpatialIOPortBlockEntity.java`
- Spatial Pylon — defines the spatial volume; lines of at least 2 blocks, 1 channel per line; textured by state — `J/block/spatial/SpatialPylonBlock.java`, `J/client/render/tesr/spatial/`
- Spatial Anchor — force-loads every chunk the network occupies (follows quantum bridges, same dimension); energy 80 + x(x+1)/2; chunk-overlay hologram; optional random ticks — `J/blockentity/spatial/SpatialAnchorBlockEntity.java` **[config: general.spatialAnchorEnableRandomTicks]**
- Matrix Frame — indestructible border block of spatial-storage plots — `J/block/spatial/MatrixFrameBlock.java`
- Sky Stone Chest / Sky Stone Block Chest — 36-slot chests with an opening animation — `J/blockentity/storage/SkyChestBlockEntity.java`, `J/client/render/tesr/SkyChestTESR.java`
- Sky Stone Tank — 16-bucket fluid tank that renders its fluid; does not keep its contents when broken — `J/block/storage/SkyStoneTankBlock.java`, `J/client/render/tesr/SkyStoneTankBlockEntityRenderer.java`
- Mysterious Cube — meteorite core; breaking it (no silk touch) drops inscriber presses; has a quote tooltip — `J/block/misc/MysteriousCubeBlock.java` **[config: worldGen.spawnPressesInMeteorites]**
- Not So Mysterious Cube — craftable decorative replica — `D/recipe/shaped/not_so_mysterious_cube.json`
- Flawless / Flawed / Chipped / Damaged Budding Certus Quartz — grow certus buds; non-flawless tiers may decay as buds grow; silk touch prevents decay except on flawless; flawless cannot be moved by mining — `J/block/` (budding), `G/items-blocks-machines/budding_certus.md` **[config: worldGen.spawnFlawlessOnly]**
- Small / Medium / Large Certus Quartz Bud, Certus Quartz Cluster — growth stages; immature stages drop dust, the cluster drops crystals (Fortune applies) — `L` (`ae2.rei_jei_integration.*`)
- Charged Quartz Fixture — small light source — `J/block/misc/QuartzFixtureBlock.java`
- Light Detecting Fixture — emits redstone from its block light level — `J/block/misc/LightDetectorBlock.java`
- Tiny TNT — small explosion (used for entangled singularities); dispenser-primable — `J/block/misc/TinyTNTBlock.java` **[config: general.tinyTntBlockDamage]**
- Quartz Glass / Vibrant Quartz Glass — connected-texture glass; the vibrant one emits light — `J/client/render/model/GlassModel.java`
- Paint (splotches) — paint left by paintball shots; lumen paint glows; snowball cleans it — `J/block/paint/PaintSplotchesBlock.java`
- Decorative: Certus Quartz Block, Cut, Smooth, Bricks, Pillar, Chiseled (plus slab, stairs and wall for each); Fluix Block (plus slab, stairs, wall); Sky Stone, Sky Stone Block, Sky Stone Brick, Sky Stone Small Brick (plus slab, stairs, wall) — `J/core/definitions/AEBlocks.java`, `D/recipe/decorative/`, `D/recipe/block_cutter/`
- Sky stone break-speed rule — sky stone needs an iron tool and takes 10x longer to break unless the tool is better than iron — `J/hooks/SkyStoneBreakSpeed.java`
- AE2 Cable and/or Bus (`cable_bus`) — the internal block that holds cables, parts and facades; per-part outlines and selection — `J/block/networking/CableBusBlock.java`
- Dev.CubeGen / Dev.EnergyGen / Dev.ItemGen / Dev.PhantomNode — debug blocks **[dev-only]** — `J/debug/`

## Parts (cable-attached)

- Cables: Glass (8 channels), Covered (8), Smart (8, shows channel lines), Dense Covered (32), Dense Smart (32, lines in groups of 4); 17 colors; same-color-or-fluix connection rule — `J/parts/networking/`, `G/items-blocks-machines/cables.md`
- Cable dyeing — 8 cables around a dye; a water bucket removes the color; the color applicator paints in world — `D/recipe/network/cables/`
- Quartz Fiber — shares power between two networks without merging them — `J/parts/networking/QuartzFiberPart.java`
- Cable Anchor — stops a cable from connecting on that side; climbable like a ladder; facade ingredient — `J/parts/misc/CableAnchorPart.java`
- ME Toggle Bus / ME Inverted Toggle Bus — redstone turns the connection on or off (causes a network reboot) — `J/parts/misc/ToggleBusPart.java`, `J/parts/misc/InvertedToggleBusPart.java`
- Illuminated Panel (Bright / normal / Dark) — decorative light, terminal ingredient — `J/parts/reporting/PanelPart.java`, `J/parts/reporting/SemiDarkPanelPart.java`, `J/parts/reporting/DarkPanelPart.java`
- ME Terminal — grid storage UI — `J/parts/reporting/ItemTerminalPart.java`
- ME Crafting Terminal — terminal plus a 3x3 grid refilled from the network — `J/parts/reporting/CraftingTerminalPart.java`
- ME Pattern Encoding Terminal — terminal plus an encoder (crafting, processing, smithing, stonecutting) — `J/parts/encoding/PatternEncodingTerminalPart.java`, `J/menu/me/items/PatternEncodingTermMenu.java`
- ME Pattern Access Terminal — remote access to every pattern provider's slots — `J/parts/reporting/PatternAccessTerminalPart.java`
- Terminals take the color of their cable — `G/items-blocks-machines/terminals.md`
- ME Storage Monitor — shows one item or fluid and its amount (and craftability); right-click with an item to set it (right-click again with a container to switch to its fluid); empty hand clears; shift + empty hand toggles lock; no channel needed — `J/parts/reporting/AbstractMonitorPart.java`, `J/parts/reporting/StorageMonitorPart.java`
- ME Conversion Monitor — storage monitor plus I/O: left-click extracts a stack, shift-left-click extracts 1, right-click with the item inserts it, right-click with an empty hand (when locked) inserts all matching items from the inventory; opens a craft request if none are stored — `J/parts/reporting/ConversionMonitorPart.java`
- Monitors rotate with a wrench when on a floor or ceiling — `G/items-blocks-machines/monitors.md`
- ME Import Bus — pulls from the adjacent inventory (items and fluids; key types selectable); whitelist/blacklist; adaptive tick rate — `J/parts/automation/ImportBusPart.java`
- ME Export Bus — pushes filtered keys from the network; scheduling modes; crafting card — `J/parts/automation/ExportBusPart.java`
- ME Storage Bus — exposes an adjacent inventory (items and fluids in one bus) as network storage; partition, access mode, filter-on-extract, report-inaccessible, priority; on an unconfigured Interface it exposes that whole network — `J/parts/storagebus/StorageBusPart.java`
- ME Level Emitter — redstone by stored amount (at or above, or below); fuzzy; crafting card adds "emit while crafting" or "emit to craft" (a virtual pattern); no channel needed — `J/parts/automation/StorageLevelEmitterPart.java`
- ME Energy Level Emitter — redstone by stored network energy — `J/parts/automation/EnergyLevelEmitterPart.java`
- ME Annihilation Plane — breaks blocks, picks up colliding items, picks up fluid source blocks; only acts if the network can store the drops; takes pickaxe enchants (Fortune, Silk Touch, Efficiency lowers energy, Unbreaking can make it free); acts with the owner's player profile — `J/parts/automation/AnnihilationPlanePart.java`, `J/parts/automation/FluidPickupStrategy.java`, `J/parts/automation/AnnihilationPlanePartItem.java`
- Annihilation plane sky dust — a plane facing up at max build height passively generates sky stone dust — `J/parts/automation/AnnihilationPlanePart.java:105` **[config: crafting.annihilationPlaneSkyDustGeneration]**
- ME Formation Plane — places blocks or drops items, places fluids as blocks; insert-only storage behaviour, priority, owner profile — `J/parts/automation/FormationPlanePart.java`, `J/parts/automation/FluidPlacementStrategy.java` **[config: automation.formationPlaneEntityLimit]**
- Planes visually connect to adjacent planes — `J/parts/automation/PlaneConnectionHelper.java`
- ME Interface (part) / ME Pattern Provider (part) / Energy Acceptor (part) — flat versions of the blocks, swappable with the block in a crafting grid — `J/parts/misc/InterfacePart.java`, `J/parts/crafting/PatternProviderPart.java`, `J/parts/networking/EnergyAcceptorPart.java`, `D/recipe/network/blocks/interfaces_interface_part.json`
- P2P tunnels: ME (carries channels, cannot nest), Redstone, Item, Fluid, Energy (FE, with tax), Light — one input, many outputs; frequency shown as a 2x2 color code on the back — `J/parts/p2p/`, `J/client/render/cablebus/P2PTunnelFrequencyModel.java`
- P2P attunement — right-click a tunnel with an item from the type's tag, or any item exposing an energy or fluid capability, to change its type — `J/init/internal/InitP2PAttunements.java`
- Cable Facade — covers a cable side using any eligible block's look; parts still show through — `J/facade/FacadePart.java`, `J/items/parts/FacadeItem.java`
- Part placement on the opposite side — holding the keybind (Left Ctrl) puts the part on the far side of the clicked cable — `J/parts/PartPlacement.java:140`, `J/core/AppEngClient.java:151`

## Items and tools

- Certus Quartz Wrench / Nether Quartz Wrench — rotate blocks (right-click) and dismantle (shift-right-click; keeps settings and name); removes single parts from a cable; any item tagged `c:tools/wrench` works — `J/hooks/WrenchHook.java`, `J/items/tools/quartz/QuartzWrenchItem.java`
- Wrench on a facade — right-click cycles the selected blockstate property of the facade block; left-click picks which property to cycle — `J/facade/FacadePart.java:89-140`
- Network Tool — wrench (dismantle only, no rotation); right-click a network block for diagnostics; right-click in air opens its 9-slot upgrade storage ("Upgrade Toolbelt") — `J/items/tools/NetworkToolItem.java`, `J/menu/me/networktool/`
- Memory Card — shift-right-click a device to copy its settings, right-click to paste; shift-right-click in air to clear; links P2P tunnels; dyeable, color washes off in a cauldron — `J/items/tools/MemoryCardItem.java`, `J/init/InitCauldronInteraction.java`
- Certus Quartz Cutting Knife / Nether Quartz Cutting Knife — opens a naming UI that makes Inscriber Name Presses; used to craft cable anchors — `J/items/tools/quartz/QuartzCuttingKnifeItem.java`, `J/menu/implementations/QuartzKnifeMenu.java`
- Inscriber Name Press — names items in the inscriber (useful for pattern access terminal labels) — `J/items/materials/NamePressItem.java`
- Certus / Nether Quartz tools (axe, hoe, shovel, pickaxe, sword) — iron-equivalent tools — `J/items/tools/quartz/`
- Fluix tools (axe, hoe, shovel, pickaxe, sword) — about 3x iron durability, intrinsic Fortune/Looting I; need a Fluix Upgrade smithing template — `J/items/tools/fluix/`, `J/hooks/IntrinsicEnchantItem.java`
- Entropy Manipulator — right-click heats, shift-right-click cools (recipe-driven block transforms), in-world smelting fallback, otherwise lights fire; ignites hit entities; 1600 AE per use; works from a dispenser — `J/items/tools/powered/EntropyManipulatorItem.java`, `D/recipe/entropy/`
- Charged Staff — 6 damage melee weapon, 300 AE per hit — `J/items/tools/powered/ChargedStaffItem.java`
- Color Applicator — paints colorable blocks and cables from stored paintballs or dyes; snowball cleans; right-click air or Shift+scroll changes color; pick-block on a colored block picks that color; a storage cell for paint; dispenser-usable — `J/items/tools/powered/ColorApplicatorItem.java`, `J/hooks/ColorApplicatorPickColorHook.java`
- Matter Cannon — fires stored items as ammo (damage by ammo weight, nugget tags), paintballs leave paint, can break blocks; a storage cell for ammo; dispenser-usable — `J/items/tools/powered/MatterCannonItem.java`, `D/recipe/matter_cannon/` **[config: general.matterCannonBlockDamage]**
- Wireless Terminal / Wireless Crafting Terminal — portable terminals linked through a WAP, used within range, battery, energy cards; the wireless crafting terminal is craftable from wireless terminal + crafting table + calculation processor — `J/items/tools/powered/WirelessTerminalItem.java`, `D/recipe/network/upgrade_wireless_crafting_terminal.json`
- Portable Item Cell / Portable Fluid Cell (1k–256k) — pocket storage with its own terminal; battery; dyeable; washable in a cauldron — `J/items/tools/powered/PortableCellItem.java`, `J/items/tools/powered/AbstractPortableCell.java`
- Meteorite Compass — points to the nearest mysterious cube (server-side lookup) — `J/items/misc/MeteoriteCompassItem.java`, `J/hooks/CompassManager.java`, `J/client/render/model/MeteoriteCompassModel.java`
- Guide — opens the in-game guide (made in a charger from a book) — `J/items/tools/GuideItem.java`
- Blank Pattern; Crafting / Processing / Smithing Table / Stonecutting Pattern — encoded recipes; shift-right-click clears back to a blank pattern; tooltip lists inputs/outputs/substitution flags and "Invalid Pattern" — `J/crafting/pattern/EncodedPatternItem.java`
- View Cell — terminal display filter, configured in the cell workbench — `J/items/storage/ViewCellItem.java`
- Paint Ball / Lumen Paint Ball (16 colors each) — color applicator and matter cannon ammo — `J/items/misc/PaintBallItem.java`
- Materials: Certus Quartz Crystal/Dust, Charged Certus Quartz Crystal, Fluix Crystal/Dust/Pearl, Silicon, Sky Stone Dust, Ender Dust, Matter Ball, Singularity, Quantum Entangled Singularity, four Inscriber presses plus printed circuits, Logic/Calculation/Engineering Processor, Printed Silicon, Formation Core, Annihilation Core, Wireless Receiver, Wireless Booster, Basic Card, Advanced Card, Item/Fluid Cell Housing, 1k–256k Storage Component, 2³/16³/128³ Spatial Component, Fluix Upgrade template — `J/core/definitions/AEItems.java`
- Storage cell disassembly — shift-right-click an empty cell or portable cell to split it into housing and component (plus upgrades) — `J/items/storage/BasicStorageCell.java:136`, `J/recipes/game/StorageCellDisassemblyRecipe.java`
- Storage cell tier upgrade — craft a cell with a higher-tier component to upgrade it in place (contents kept, old component returned) — `J/recipes/game/StorageCellUpgradeRecipe.java`, `D/recipe/upgrade/`, `D/recipe/cell_upgrade/`
- Add or remove upgrade cards by crafting (for upgradable items) — `J/recipes/game/AddItemUpgradeRecipe.java`, `J/recipes/game/RemoveItemUpgradeRecipe.java`
- Missing Content — placeholder that keeps data and shows an error tooltip for stacks whose mod or data broke — `J/items/misc/MissingContentItem.java`
- Wrapped Generic Stack — internal item wrapper for fluids and other keys in item slots (cannot be cloned) — `J/items/misc/WrappedGenericStack.java`
- Dev.DebugCard / Dev.Eraser / Dev.MeteoritePlacer / Dev.ReplicatorCard **[dev-only]** — `J/debug/`

## Upgrades/cards (with the devices each fits)

Source for all lines: `J/init/internal/InitUpgrades.java`. The upgrade panel lists compatible cards (`GuiText.CompatibleUpgrades`); chat messages cover max-installed and unsupported cards.

- Acceleration (Speed) Card — IO Port ×3, Import Bus ×4, Export Bus ×4, Matter Cannon ×4 (more power per shot), Molecular Assembler ×5, Inscriber ×4, Vibration Chamber ×3 (burn rate +50% each)
- Capacity Card — Import Bus ×5, Export Bus ×5 (more filter slots; unlocks scheduling mode), Storage Bus ×5, Formation Plane ×5
- Redstone Card — IO Port ×1, Import Bus ×1, Export Bus ×1 (adds the redstone mode button)
- Fuzzy Card — Interface (part and block) ×1, Level Emitter ×1, Import Bus ×1, Export Bus ×1, item cells ×1, portable item cells ×1, View Cell ×1, Storage Bus ×1, Formation Plane ×1, Matter Cannon ×1 (not fluid cells)
- Inverter Card — Import Bus, item and fluid cells, portable cells, View Cell, Storage Bus, Formation Plane, Matter Cannon (×1 each; whitelist becomes blacklist)
- Crafting Card — Interface (part and block) ×1, Level Emitter ×1, Export Bus ×1
- Equal Distribution Card — item and fluid cells, portable item and fluid cells, Color Applicator (×1; splits the cell evenly across partitioned types)
- Overflow Destruction (Void) Card — item and fluid cells, portable cells, Storage Bus, Color Applicator, Matter Cannon (×1; voids input when full)
- Energy Card — portable item and fluid cells ×2, Wireless Terminal ×2, Wireless Crafting Terminal ×2, Color Applicator ×2, Matter Cannon ×2, Vibration Chamber ×3 (efficiency +50% each)
- Annihilation Plane — takes enchantments (enchanting table or anvil), not cards; tooltips "Can be enchanted" and "Enchants increase energy use" — `J/parts/automation/AnnihilationPlanePartItem.java`
- Toolbox — a Network Tool anywhere in the inventory shows its 9 stored cards in every upgradeable UI for quick install — `J/menu/ToolboxMenu.java`, `J/client/gui/widgets/ToolboxPanel.java`
- Memory card upgrade restore — pasting settings also installs the recorded upgrades, taking them from the network tool or the player inventory and moving extras out; reports missing counts — `J/items/tools/MemoryCardItem.java:178-273`

## Storage (cells, partition, priority, access, fuzzy, etc.)

- Item Storage Cells 1k/4k/16k/64k/256k — 63 types, bytes-and-types model — `J/items/storage/BasicStorageCell.java`, `G/ae2-mechanics/bytes-and-types.md`
- Fluid Storage Cells 1k–256k — 5 types — `J/core/definitions/AEItems.java`
- Portable cells — half the bytes and fewer types as tier rises; own terminal; battery — `J/items/tools/powered/PortableCellItem.java`
- Creative ME Storage Cell — infinite source and sink of its partitioned keys; tooltip "Press [Shift] for full list" — `J/items/storage/CreativeCellItem.java`
- Spatial Storage Cells 2³/16³/128³ — store a volume; serial number and stored size in tooltip; cannot be reformatted — `J/items/storage/SpatialStorageCellItem.java`
- Cell tooltip — bytes used, types, partitioned flag, installed upgrades and a content preview with icons — `J/client/render/StorageCellClientTooltipComponent.java` **[config: tooltips.showCellUpgrades, showCellContent, maxCellContentShown]**
- Cell nesting guard — items with contents that cannot go into cells are marked "does not fit in other storage cells" — `J/menu/slot/CellPartitionSlot.java:61`
- Partitioning — filter which keys a cell, storage bus or view cell accepts; "Partition Storage" fills the filter from current contents; "Clear" — `J/menu/implementations/CellWorkbenchMenu.java`, `J/menu/implementations/StorageBusMenu.java`
- Partition by fluid container — right-click a filter slot with a bucket or tank to set the fluid, not the item (all filter slots) — `J/client/gui/AEBaseScreen.java:318` (SetAction tooltip)
- Drag-to-filter — drag stacks from REI/EMI (or across slots) into filter slots without owning them — `J/integration/modules/itemlists/DropTargets.java`, `J/client/gui/AEBaseScreen.java:597`
- Fuzzy mode — Match Any or split damage at 25/50/75/99%, ignoring components — `J/api/config/FuzzyMode.java`
- Priority — per drive, chest, storage bus, formation plane, interface, pattern provider; insert goes to highest, extract to lowest; same-priority storage that already holds the item (or is partitioned) is preferred — `J/menu/implementations/PriorityMenu.java`, `G/items-blocks-machines/drive.md`
- Access mode (storage bus) — Bi-Directional, Insert Only, Extract Only — `J/api/config/AccessRestriction.java`
- Filter on extract — the filter applies to insert and extract, or to insert only — `Settings.FILTER_ON_EXTRACT`
- Report inaccessible — show or hide items the bus can see but not extract — `Settings.STORAGE_FILTER`
- Storage bus "Attached to: X" display and external storage naming — `J/client/gui/implementations/StorageBusScreen.java`, `J/me/storage/ExternalStorageFacade.java`
- Cell Workbench copy mode — keep or clear the config when the cell is removed (copies settings between cells) — `Settings.COPY_MODE`
- Cell status LEDs — green empty, blue partial, orange types full, red bytes full, black unpowered — `J/client/render/tesr/CellLedRenderer.java`
- Matter Condenser — trash, matter balls (256 per ball), singularity (256,000) **[config: condenser.matterBalls, condenser.singularity]**
- ME Chest — one cell plus terminal; insert-only through the top face for automation — `G/items-blocks-machines/chest.md`
- Recursive storage — a storage bus on an Interface shows the other network (advancement "Recursive Networking") — `G/items-blocks-machines/interface.md`
- Statistics — "Items added to ME Storage" / "Items extracted from ME Storage" — `J/core/stats/AeStats.java`, `L` (`stat.ae2.*`)

## Terminals and grid interaction

Main source: `J/client/gui/me/common/MEStorageScreen.java`, `J/menu/me/common/MEStorageMenu.java`, `J/helpers/InventoryAction.java`.
- Left-click an entry — pick up a stack; right-click — pick up half (`PICKUP_OR_SET_DOWN` / `SPLIT_OR_PLACE_SINGLE`)
- Shift-left-click — move a stack to the player inventory; shift-right-click — take 1 (`SHIFT_CLICK` / `PICKUP_SINGLE`)
- Click an entry with a held item — put down the held stack (left) or one item (right)
- Shift + mouse wheel over an entry — move one item at a time into or out of the hand (`ROLL_UP` / `ROLL_DOWN`)
- Space + click — move every item of that slot region (inventory or hotbar) into the network (`MOVE_REGION`) — also in AE base screens `J/client/gui/AEBaseScreen.java:681`
- Shift double-click — vanilla-style "move all of this item" in AE screens — `J/client/gui/AEBaseScreen.java:688`
- Pick-block (middle-click) on a craftable entry — open the craft-amount UI; in creative on a non-craftable entry — creative duplicate (`AUTO_CRAFT` / `CREATIVE_DUPLICATE`)
- Left-click a craftable entry with 0 stored (or in "craftable only" view) — opens the craft UI
- Fluid containers — left-click a fluid entry fills the held container (shift fills it fully, or moves the filled container to the inventory when the hand is empty); right-click with a filled container empties one unit (shift empties all) (`FILL_ITEM*` / `EMPTY_*`)
- Hotbar number keys and F (offhand) swap on AE slots — `J/client/gui/AEBaseScreen.java:740-765` (`SwapSlotsPacket`)
- Holding Shift freezes the grid order (no re-sorting while you click) — `MEStorageScreen.java:375`
- Tooltip extras — stored amount, "Requestable", "Craftable", serial number (F3+H advanced tooltips), store/fill action hints for held containers
- Search box — AND by spaces, OR by `|`; `@mod`, `#tag`, `$tooltip`, `*id`; substring matching (the guide's "regex" claim is stale: the code uses `contains`) — `J/client/gui/me/search/RepoSearch.java`, `G/items-blocks-machines/terminals.md`
- Search box clicks — right-click clears it; Enter unfocuses; a space in an empty box is ignored — `MEStorageScreen.java:509,730,745`
- "Showing X of Y" when external search filters — `MEStorageScreen.java:395`
- Sort by Name / Amount / Mod; Ascending / Descending — `J/api/config/SortOrder.java`, `J/api/config/SortDir.java`
- View All / Stored / Craftable — `J/api/config/ViewItems.java`
- Key types shown (items, fluids, addon types) — checkbox sub-screen; shift-click the button to cycle — `J/client/gui/widgets/KeyTypeSelectionButton.java`, `J/client/gui/implementations/KeyTypeSelectionScreen.java`
- Terminal style Small / Medium / Tall / Full height — `Settings.TERMINAL_STYLE` **[config: client.terminals.terminalStyle, terminalMargin]**
- Terminal Settings sub-screen — pin auto-crafted items, notify on finished jobs, clear grid on close, AE vs REI/EMI search, remember last search, auto-focus, sync with external search, clear external search on open — `J/client/gui/me/common/TerminalSettingsScreen.java`
- Pinned row — up to 9 recently auto-crafted keys shown in a top row while their jobs run — `J/client/gui/me/common/PinnedKeys.java` **[config: client.terminals.pinAutoCraftedItems]**
- Crafting status tab (hammer icon) with a badge counting active jobs — `MEStorageScreen.java:164,452`
- View cell slots (5) — combined (additive) display filters — `MEStorageScreen.java` ("viewCells")
- Wireless and portable terminal hosts show upgrade slots instead of view cells — `G/items-blocks-machines/wireless_terminals.md`
- Close hotkey — pressing the open-terminal hotkey again closes the terminal — `MEStorageScreen.java:845`
- Crafting Terminal grid — auto-refills from the network; click crafts one, right-click crafts a stack, shift-click crafts to the inventory, Space-click crafts all (`CRAFT_ITEM` / `CRAFT_STACK` / `CRAFT_SHIFT` / `CRAFT_ALL`); buttons return the grid to the network or to the player — `J/client/gui/AEBaseScreen.java:663`, `J/client/gui/me/items/CraftingTermScreen.java`, `J/menu/me/items/CraftingTermMenu.java`
- Recipe-viewer "+" transfer into the crafting grid — pulls ingredients from network and inventory; Ctrl+click also queues crafts for missing items — `J/integration/modules/itemlists/CraftingHelper.java`, `J/core/network/serverbound/FillCraftingGridFromRecipePacket.java`
- Pattern Encoding Terminal — tabs Crafting, Processing, Smithing, Stonecutting; blank-pattern slot and encoded slot (put a pattern in to edit it); Encode arrow; Clear; Substitutions toggle; Fluid Substitutions toggle (substitutable slots highlighted green) — `J/client/gui/me/items/PatternEncodingTermScreen.java`, `J/client/gui/me/items/*EncodingPanel.java`
- Processing encoding — 81 input and 27 output slots, scrollable; left-click places the stack, right-click places 1; left-click on an ingredient removes it, right-click removes 1; pick-block opens an exact-amount sub-screen (fluids in buckets/mB); fluid-container right-click sets the fluid; "Cycle Outputs" picks the primary output; primary vs secondary output hints — `J/client/gui/me/items/ProcessingEncodingPanel.java`, `J/client/gui/me/items/SetProcessingPatternAmountScreen.java`
- Stonecutting encoding — choose among valid stonecutter recipes for the input — `PatternEncodingTermMenu` `ACTION_SET_STONECUTTING_RECIPE_ID`
- Smithing encoding — template/base/addition slots — `J/client/gui/me/items/SmithingTableEncodingPanel.java`
- Encode from a recipe viewer — writes the recipe into the encoder (prefers craftable, undamaged and plentiful items), highlights already-craftable ingredients, warns "Requires processing mode" or "Recipe larger than 3x3" — `J/integration/modules/itemlists/EncodingHelper.java`, `J/integration/modules/itemlists/TransferHelper.java`
- Pattern Access Terminal — one row per provider, grouped by adjacent machine or custom name (tooltip when a provider touches several machines); search by pattern output or provider name; show Visible / Visible with empty slots / All; terminal style; shift-click a pattern from the inventory into the first free slot — `J/client/gui/me/patternaccess/PatternAccessTermScreen.java`, `J/core/network/serverbound/QuickMovePatternPacket.java`
- ME Chest and portable cells use the same terminal UI (no view cells in the chest) — `J/menu/me/items/BasicCellChestMenu.java`, `MEStorageMenu.PORTABLE_*_TYPE`

## Autocrafting

- Pattern types — crafting, processing (any input to any output, multiple outputs), smithing table, stonecutting — `J/crafting/pattern/`
- Substitutions — a crafting pattern accepts tag alternatives — `PatternEncodingTermMenu` `ACTION_SET_SUBSTITUTION`
- Fluid substitution — use stored fluids in place of filled buckets or containers — `ACTION_SET_FLUID_SUBSTITUTION`
- Secondary processing outputs — not requestable, but used before stored items in multi-step jobs — `L` (`gui.ae2.PatternEncoding.*`)
- Batch patterns — for example 8 in, 8 out, pushed as one unit — `G/items-blocks-machines/patterns.md`
- Pattern provider push — tries crafting machines (assemblers) first, sending the pattern along; then round-robins all faces; only whole batches; skips faces touching other providers or same-network interfaces — `J/helpers/patternprovider/PatternProviderLogic.java:315-460`
- Unconfigured subnet interface passthrough — a provider pushes straight into the subnet's storage — `G/items-blocks-machines/pattern_provider.md`
- Blocking mode — do not push while the target already holds pattern inputs — `Settings.BLOCKING_MODE`
- Lock crafting — Never, With redstone, Without redstone, Until redstone pulse, Until primary result returned; the lock reason shows in the GUI and in Jade/TOP — `J/api/config/LockCraftingMode.java`, `J/client/gui/implementations/PatternProviderLockReason.java`
- Show in Pattern Access Terminal toggle — `Settings.PATTERN_ACCESS_TERMINAL`
- Provider priority — higher-priority providers' patterns win when their ingredients are available — `G/items-blocks-machines/pattern_provider.md`
- Return slots — anything inserted into a provider goes into the network (counts as "entering the system") — `J/helpers/patternprovider/PatternProviderReturnInventory.java`
- Memory card copies patterns — pasting re-encodes them using blank patterns from the player inventory (reports how many are missing) and returns old patterns as blanks — `J/helpers/patternprovider/PatternProviderLogic.java:589-680`
- Crafting CPU — multiblock; storage sets job size; co-processors allow parallel steps and faster pushes; monitors show the current item (colorable); custom name from a named unit — `J/me/cluster/implementations/CraftingCPUCluster.java`
- CPU selection mode — accept jobs from players and automation, players only, or automation only — `Settings.CPU_SELECTION_MODE`
- Craft amount screen — number entry with math expressions; +/- 1/10/100/1000 (Shift or Ctrl gives 1/16/32/64); `=N` crafts only up to N stored; Shift+Next starts immediately — `J/client/gui/me/crafting/CraftAmountScreen.java`, `J/client/gui/widgets/NumberEntryWidget.java`, `J/client/gui/MathExpressionParser.java`
- Craft confirm screen — plan table (available, to craft, missing), CPU storage and co-processor summary; cycle CPU (right-click cycles backwards); Start (or Enter); Cancel; "Partial Plan" when ingredients are missing — `J/client/gui/me/crafting/CraftConfirmScreen.java`
- Craft error screen — explains the failure (CPU busy, offline, too small, none suitable with busy/excluded/offline/too-small counts, missing ingredient, incomplete plan) with Replan, Retry, Cancel — `J/client/gui/me/crafting/CraftErrorScreen.java`, `L` (`gui.ae2.CraftError*`)
- Crafting status screen — CPU list (status, storage, co-processors, item crafted or "Crafted X in T"), per-item stored/crafting/scheduled table, elapsed time, Cancel, Suspend/Resume — `J/client/gui/me/crafting/CraftingStatusScreen.java`, `J/client/gui/me/crafting/CraftingCPUScreen.java`, `J/client/gui/widgets/CPUSelectionList.java` **[config: client.useColoredCraftingStatus]**
- Job suspend/resume — pause a running job — `J/menu/me/crafting/CraftingCPUMenu.java:135`
- Finished-job toast — "Auto-Crafting Complete" when a player job finishes and no terminal is open; needs a powered, linked wireless terminal in the inventory or Curios — `J/client/gui/me/common/PendingCraftingJobs.java`, `J/client/gui/me/common/FinishedJobToast.java` **[config: client.notifyForFinishedCraftingJobs]**
- Requests from automation — Export Bus with crafting card (craft-only or use stock), Interface with crafting card (craft to keep stock), Level Emitter with crafting card ("emit to craft" virtual pattern, or "emit while crafting") — `J/parts/automation/ExportBusPart.java`, `J/helpers/InterfaceLogic.java`, `J/parts/automation/StorageLevelEmitterPart.java`
- Conversion monitor craft request — left-click with none stored opens the amount UI — `G/items-blocks-machines/monitors.md`
- Molecular assembler — 1 pattern slot; standalone crafting with an inserted pattern; speed cards; progress bar; animation packet — `J/client/gui/implementations/MolecularAssemblerScreen.java`, `J/core/network/clientbound/AssemblerAnimationPacket.java`
- Encoded pattern item preview — holding Shift shows the pattern's output in the slot render — `J/hooks/GuiGraphicsHooks.java`
- Calculation budget per tick — `J/core/AEConfig.java:608` **[config: craftingCPU.craftingCalculationTimePerTick]**

## Import/export and world interaction

- Import bus — speed: 1/8/32/64/96 per operation with 0–4 acceleration cards; 4 operations/s when active, sleeps when idle — `G/items-blocks-machines/import_bus.md` **[config: tickRates.ImportBus*]**
- Export bus — same speeds; scheduling Default / Round-robin / Random (with capacity cards); crafting behaviour (craft only vs use stock) — `Settings.SCHEDULING_MODE`, `Settings.CRAFT_ONLY`
- Interface stocking — set the amount per slot (wrench button opens a "Set amount to stock" UI); fills or empties up to 9 stacks per tick; blocks external insertion of other items into stocked slots — `J/client/gui/implementations/InterfaceScreen.java`, `J/menu/implementations/SetStockAmountMenu.java`
- Storage bus as I/O — pipe subnets, bucket filler/emptier patterns — `G/example-setups/`
- Annihilation and formation planes — break, collect, place and drop in world (see Parts)
- Inscriber automation — sided mode (top, bottom, sides into specific slots) or combined mode; auto-export to adjacent; buffer size 1/4/64 — `Settings.INSCRIBER_SEPARATE_SIDES`, `Settings.AUTO_EXPORT`, `Settings.INSCRIBER_INPUT_CAPACITY`
- IO port — fill or empty cells; move to output when empty, full or when work is done — `Settings.OPERATION_MODE`, `Settings.FULLNESS_MODE`
- Charger automation — insert from any side, extract only finished items — `G/items-blocks-machines/charger.md`
- Pattern provider push to adjacent machines (see Autocrafting)
- In-world transformations — throw items in water (budding quartz tiers, fluix crystals, certus crystals) and explode singularities with ender dust or pearls (entangled pair) — `J/recipes/transform/`, `D/recipe/transform/`
- Entropy manipulator transforms (heat or cool) — `D/recipe/entropy/heat/`, `D/recipe/entropy/cool/`
- Dispenser behaviours — Tiny TNT, Entropy Manipulator, Matter Cannon, Color Applicator — `J/init/InitDispenserBehavior.java`
- Spatial IO — swap a cuboid (blocks, block entities, entities) into a spatial storage dimension plot; the player can be stored too (advancement) — `J/spatial/SpatialStorageHelper.java`, `J/init/internal/InitBlockEntityMoveStrategies.java` **[config: spatialio.spatialPowerMultiplier, spatialPowerExponent]**
- Budding certus and growth accelerator farming — `G/ae2-mechanics/certus-growth.md`
- Meteorites — worldgen structure with crater variants (none, normal, lava, obsidian, water, snow, ice) and fallout; the center holds a mysterious cube — `J/worldgen/meteorite/` **[config: worldGen.*]**
- Fluix Researcher villager — charger job site; buys and sells AE materials — `J/init/InitVillager.java`, `G/items-blocks-machines/fluix_researcher.md`
- Planes act as the network owner's player profile (respect claims and fake-player rules) — `J/parts/automation/AnnihilationPlanePart.java:175`, `J/parts/automation/FormationPlanePart.java:109`

## Redstone

- Redstone mode (with a redstone card): Ignore, Active with signal, Active without signal, Once per pulse — Import Bus, Export Bus, IO Port — `J/api/config/RedstoneMode.java`, `Settings.REDSTONE_CONTROLLED`
- Level Emitter: emit when at or above, or below, the threshold (fuzzy-aware; fluid units) — `Settings.REDSTONE_EMITTER`
- Level Emitter with crafting card: emit while crafting, or emit to craft — `Settings.CRAFT_VIA_REDSTONE`
- Energy Level Emitter — threshold on stored network AE — `J/parts/automation/EnergyLevelEmitterPart.java`
- Toggle bus and inverted toggle bus — connect or disconnect by redstone
- Pattern provider lock crafting by redstone (high, low, pulse) — `Settings.LOCK_CRAFTING_MODE`
- Spatial IO Port — triggers on a redstone rising edge — `J/blockentity/spatial/SpatialIOPortBlockEntity.java:51`
- Redstone P2P tunnel — carries a signal from input to outputs
- Light Detecting Fixture — redstone from light level
- Light P2P tunnel — carries light level

## Wireless and distance

- Wireless Access Point range = `wirelessBaseRange` + `wirelessBoosterRangeMultiplier` × boosters^`wirelessBoosterExp` (default 16 + n^1.5) — `J/core/AEConfig.java:93` **[config: wireless.*]**
- WAP power draw = baseCost + costMultiplier × boosters^(1 + boosters/highWirelessCount) — `J/core/AEConfig.java:98`
- Wireless terminal drain proportional to range — `J/core/AEConfig.java:90` **[config: wireless.wirelessTerminalDrainMultiplier]**
- Link a wireless terminal — put it in the WAP's link slot; tooltip shows Linked/Unlinked; errors "Device is not linked", "Linked network cannot be found", "Wireless Out Of Range", "Device is low on power" — `J/menu/implementations/WirelessAccessPointMenu.java`, `J/items/tools/powered/WirelessTerminalItem.java`
- Wireless terminal battery and energy cards **[config: battery.wirelessTerminal]**
- Hotkeys open a wireless terminal or portable cell from the inventory or a Curios slot (unbound by default) — `J/hotkeys/HotkeyActions.java`
- Quantum Network Bridge — infinite and cross-dimension link carrying 32 channels; both ends must be chunk-loaded — `J/block/qnb/`
- P2P tunnels — channel and resource teleport within a network; Energy P2P tax **[config: powerRatios.p2pTunnelEnergyTax, p2pTunnelTransportTax]**
- Spatial Anchor — chunk loading across the network's footprint
- Meteorite Compass — long-range meteorite finder

## Security/permissions

- No security terminal on this branch; access is gated by world interaction permission (`level.mayInteract`) on the network tool, conversion monitor extraction and part/block use — `J/util/Platform.java:212`
- "You lack permission to access this." message — `L` (`ae2.permission_denied`)
- Grid nodes record an owning player (ME player registry); planes use that profile for block break and place events — `J/me/GridNode.java:276`, `J/api/features/PlayerRegistryInternal.java`
- Storage bus access mode as a soft permission — `Settings.ACCESS`
- CPU selection mode limits who can use a CPU (players or automation)
- Admin commands need permission level 4 — `J/server/Commands.java`

## Energy

- AE as the internal unit; FE conversion (2 FE = 1 AE by default) — `J/core/AEConfig.java:660` **[config: powerRatios.forgeEnergy, usageMultiplier]**
- Energy acceptor (block and part); the controller also accepts power
- Energy cells, dense cells and the creative cell; per-node buffer (25 AE per node) **[config: powerRatios.gridEnergyStoragePerNode]**
- Passive drain per device and per channel, shown in network status — `J/menu/me/networktool/NetworkStatus.java:64`
- Generators — Vibration Chamber (fuel, adaptive output), Crystal Resonance Generator (one per network)
- Charger and crank; charge rate factor — **[config: battery.chargerChargeRate]**
- Portable tools with batteries (wireless terminal, portable cell, entropy manipulator, color applicator, matter cannon, charged staff); energy cards add capacity — **[config: battery.*]**
- Energy P2P (FE) with tax; Quartz Fiber power sharing
- Display unit toggle AE/FE — available in the network status, vibration chamber, spatial IO port, spatial anchor and wireless access point screens — `J/client/gui/widgets/CommonButtons.java` **[config: client.powerUnit]**
- Out-of-power behaviour — devices go offline ("Device Offline", "Out of Power"); controller and cells show state — `L`
- Spatial operation energy cost — `J/menu/implementations/SpatialIOPortMenu.java` **[config: spatialio.*]**

## Screens (every setting/button per screen)

Every AE screen has an "Open Guide" button on the left toolbar (`J/client/gui/widgets/OpenGuideButton.java`). Setting buttons cycle forward on left-click and backward on right-click (`ConfigButtonPacket(rotationDirection)`).
- Terminal / Crafting Terminal / Pattern Encoding Terminal / Wireless / Portable / ME Chest terminal — Sort By, View (All/Stored/Craftable), Sort Direction, Terminal Settings, Terminal Style, Configure Visible Types, Crafting Status tab, search box, view cell slots or upgrade slots, toolbox — `J/client/gui/me/common/MEStorageScreen.java`
- Crafting Terminal extra — Store Items (grid to network), Take Items (grid to player) — `J/client/gui/me/items/CraftingTermScreen.java`
- Pattern Encoding Terminal extra — mode tabs, Encode, Clear, Substitutions, Fluid Substitutions, Cycle Outputs, processing amount sub-screen — `J/client/gui/me/items/`
- Pattern Access Terminal — Terminal Style, Show Pattern Providers (Visible / Not Full / All), search — `J/client/gui/me/patternaccess/PatternAccessTermScreen.java`
- Terminal Settings — 3 terminal checkboxes plus search radio buttons and 4 search checkboxes — `J/client/gui/me/common/TerminalSettingsScreen.java`
- Pattern Provider — Blocking Mode, Lock Crafting mode, Show in Pattern Access Terminal, Priority tab, lock-reason line — `J/client/gui/implementations/PatternProviderScreen.java`
- Interface — Fuzzy Mode (with card), per-slot "Set amount to stock" buttons, Priority tab, upgrades — `J/client/gui/implementations/InterfaceScreen.java`
- Set Stock Amount — number entry and Set — `J/client/gui/me/crafting/SetStockAmountScreen.java`
- Import/Export Bus — Redstone Mode (card), Fuzzy Mode (card), Crafting Behavior (export, card), Scheduling Mode (export, capacity), Configure Imported Types (import) — `J/client/gui/implementations/IOBusScreen.java`
- Storage Bus — Clear, Partition, Access mode, Report Inaccessible, Filtered Operations, Fuzzy (card), Priority, "Attached to" label — `J/client/gui/implementations/StorageBusScreen.java`
- Formation Plane — Block Placement (place or drop), Fuzzy (card), Priority — `J/client/gui/implementations/FormationPlaneScreen.java`
- Level Emitter — threshold entry, Redstone Mode (above or below), Fuzzy (card), Crafting Emitter Mode (card) — `J/client/gui/implementations/StorageLevelEmitterScreen.java`
- Energy Level Emitter — energy threshold entry, Redstone Mode — `J/client/gui/implementations/EnergyLevelEmitterScreen.java`
- Cell Workbench — Fuzzy (client toggle on the cell), Partition, Clear, Copy Mode, upgrade slots — `J/client/gui/implementations/CellWorkbenchScreen.java`
- IO Port — Fullness mode, Operation mode (direction arrow), Redstone Mode (card) — `J/client/gui/implementations/IOPortScreen.java`
- Inscriber — Separate Sides, Auto-Export, Input Capacity, progress bar — `J/client/gui/implementations/InscriberScreen.java`
- Condenser — Output mode, progress bar, component slot — `J/client/gui/implementations/CondenserScreen.java`
- Vibration Chamber — power unit toggle, generation-rate bar — `J/client/gui/implementations/VibrationChamberScreen.java`
- Spatial IO Port — power unit, stored and required power, efficiency, structure size — `J/client/gui/implementations/SpatialIOPortScreen.java`
- Spatial Anchor — power unit, Overlay Mode, network statistics (chunks loaded per world, energy) — `J/client/gui/implementations/SpatialAnchorScreen.java`
- Wireless Access Point — power unit, range and usage readout, booster slot, link in/out slots — `J/client/gui/implementations/WirelessAccessPointScreen.java`
- Drive / ME Chest (cell side) — Priority tab — `J/client/gui/implementations/DriveScreen.java`, `MEChestScreen.java`
- Priority — number entry, back tab, insert and extract hints — `J/client/gui/implementations/PriorityScreen.java`
- QNB — singularity slot — `J/client/gui/implementations/QNBScreen.java`
- Molecular Assembler — pattern slot, progress, upgrades — `J/client/gui/implementations/MolecularAssemblerScreen.java`
- Sky Chest — 36-slot inventory — `J/client/gui/implementations/SkyChestScreen.java`
- Quartz Knife — name text field to make a name press — `J/client/gui/implementations/QuartzKnifeScreen.java`
- Network Status (controller or network tool) — channels, stored and max power, generation and usage, passive and channel drain, machine list with counts and power, power unit toggle, "Export Grid" (only when allowed) — `J/client/gui/me/networktool/NetworkStatusScreen.java`
- Network Tool (in air) — 9 upgrade storage slots — `J/client/gui/me/networktool/NetworkToolScreen.java`
- Craft Amount / Craft Confirm / Craft Error / Crafting Status / Crafting CPU — see Autocrafting
- Key Type Selection — per-type checkboxes plus a back tab — `J/client/gui/implementations/KeyTypeSelectionScreen.java`
- Upgrades panel — card slots plus a tooltip of compatible cards and counts — `J/client/gui/widgets/UpgradesPanel.java`

## Keybindings

- Open Wireless Terminal (unbound) — `J/hotkeys/HotkeyActions.java`, `J/client/Hotkeys.java`
- Open Portable Item Cell (unbound)
- Open Portable Fluid Cell (unbound)
- Modifier for Mouse-Wheel Items (Left Shift) — Shift+scroll cycles the color applicator color — `J/core/AppEngClient.java:147`
- Place Parts on Opposite Side (Left Ctrl) — `J/core/AppEngClient.java:151`
- Open Guide for Items — lang-only on the AE2 side; the item-guide hotkey itself comes from the GuideME library — `L` (`key.ae2.guide`)
- "Toggle search box focus" — **[lang-only]** — `L` (`key.toggle_focus.desc`)
- In-screen keys: Space (move region or craft all), Shift (freeze sort, 64-step entry, start craft), Ctrl (64-step entry, craft-missing transfer), Enter (confirm craft, unfocus search), pick-block (craft or set amount), number keys and F (swap)

## Config options

Source: `J/core/AEConfig.java`. Client config:
- recipeViewers: disableColoredCableRecipesInRecipeViewer, enableFacadesInRecipeViewer (default false), enableFacadeRecipesInRecipeViewer (true), provideNetworkInventoryToEmi (false)
- client: enableEffects, useTerminalUseLargeFont, useColoredCraftingStatus, powerUnit, showDebugGuiOverlays, showPlacementPreview, notifyForFinishedCraftingJobs
- client.terminals: terminalStyle, pinAutoCraftedItems, clearGridOnClose, terminalMargin
- client.search: searchModNameInTooltips, useExternalSearch, clearExternalSearchOnOpen, syncWithExternalSearch, rememberLastSearch, autoFocusSearch
- client.tooltips: showCellUpgrades, showCellContent, maxCellContentShown (1–32)

Common config:
- general: unsupportedDeveloperTools, matterCannonBlockDamage, tinyTntBlockDamage, channels (INFINITE / DEFAULT 8 / X2 / X3 / X4), spatialAnchorEnableRandomTicks
- automation.formationPlaneEntityLimit (128); craftingCPU.craftingCalculationTimePerTick (5)
- crafting: growthAccelerator (ticks), annihilationPlaneSkyDustGeneration
- spatialio: spatialPowerMultiplier, spatialPowerExponent
- logging: blockUpdateLog, craftingLog, debugLog, gridLog, chunkLoggerTrace
- battery: chargerChargeRate, wirelessTerminal, chargedStaff, entropyManipulator, portableCell, colorApplicator, matterCannon
- worldGen: spawnPressesInMeteorites, spawnFlawlessOnly
- wireless: wirelessBaseCost, wirelessCostMultiplier, wirelessBaseRange, wirelessBoosterRangeMultiplier, wirelessBoosterExp, wirelessHighWirelessCount, wirelessTerminalDrainMultiplier
- powerRatios: forgeEnergy, usageMultiplier, gridEnergyStoragePerNode, crystalResonanceGeneratorRate, p2pTunnelEnergyTax, p2pTunnelTransportTax
- condenser: matterBalls, singularity
- tickRates: Min/Max for Interface, ImportBus, ExportBus, AnnihilationPlane, METunnel, Inscriber, Charger, IOPort, VibrationChamber, StorageBus, ItemTunnel, LightTunnel — `J/core/settings/TickRates.java`
- vibrationChamber: baseEnergyPerFuelTick, minEnergyPerGameTick, baseMaxEnergyPerGameTick

Related:
- Config values can be embedded in guide pages (`<ae2:ConfigValue name=...>`) — `J/client/guidebook/ConfigValueTagExtension.java`
- Client config edits from terminal UIs are synced through `ConfigValuePacket` — `J/core/network/bidirectional/ConfigValuePacket.java`

## Commands

Source: `J/server/Commands.java`, `J/server/subcommands/`.
- `/ae2 chunklogger` — toggle chunk-load logging (op 4)
- `/ae2 spatial info|tp <plotId>|tpback|givecell <plotId>` — inspect, teleport into or out of, and re-issue spatial plots (op 4)
- `/ae2 channelmode <infinite|default|x2|x3|x4>` — change channel capacity live and update every grid (op 4)
- `/ae2 tickmonitor <true|false>` — grid tick monitoring (op 4)
- `/ae2 grids export [gridSerial]` — export grid data to the client as a debugging dump (op 4); the network status "Export Grid" button runs it
- `/ae2 compass`, `/ae2 testmeteorites [force]`, `/ae2 setuptestworld` — **[dev-only]** (superflat and creative for the test world)
- `/ae2client highlight_gui_areas` — toggles GUI debug overlays **[dev-only]** — `J/client/commands/ClientCommands.java`

## Integrations

- REI — categories for Inscriber, Charger (and crank), Condenser, Entropy, In-World Transformation, P2P Attunement; storage-cell upgrade displays; info pages; transfer handlers for Pattern Encoding (encode) and Crafting Terminal (fill, Ctrl to craft missing); ghost-ingredient drag into filter slots; focused-stack lookup in AE screens; Inscriber click area; exclusion zones; facade group — `J/integration/modules/rei/ReiPlugin.java` **[config: recipeViewers.*]**
- EMI — the same categories and workstations (crafting terminals as crafting workstations); recipe handlers for encoding and crafting terminals; drag-drop and stack provider; add-upgrade recipes; descriptions; optional full network inventory exposure — `J/integration/modules/emi/AppEngEmiPlugin.java` **[config: recipeViewers.provideNetworkInventoryToEmi]**
- JEI — no native JEI plugin on this branch; the shared helpers mention JEI, but no `@JeiPlugin` exists and `neoforge.mods.toml` declares no JEI dependency — `J/integration/modules/itemlists/package-info.java`
- External search sync — terminal search can use, or stay in sync with, the REI/EMI search bar — `J/integration/abstraction/ItemListMod.java`
- Jade — providers: grid node state (online, offline, missing channel, booting, conflict, too many channels, nested P2P), channels used (X of Y), power stored, charger contents and charge %, crafting monitor item, pattern provider lock reason, P2P state, frequency and carried channels, storage monitor item and lock, annihilation plane enchants, crystal generator suppressed, part name/icon/mod/tooltip, debug — `J/integration/modules/igtooltip/`, `J/integration/modules/jade/`, `L` (`config.jade.plugin_ae2.*`)
- The One Probe — the same data through TOP — `J/integration/modules/theoneprobe/`
- WTHIT — the same data through WTHIT — `J/integration/modules/wthit/`
- Curios — wireless terminals and portable cells go in curio slots, open by hotkey, and count for job notifications — `J/integration/modules/curios/CuriosIntegration.java`, `J/menu/locator/CuriosItemLocator.java`, `src/generated/resources/data/curios/tags/item/curio.json`
- Create Ponder / client-only fake worlds — client visual state saved into NBT so AE blocks render in Ponder-like scenes; PonderJS cable-bus NBT interface — `J/hooks/VisualStateSaving.java`, `ponderjs.md`
- Common tags (`c:`) — wrench, gems, dusts, budding blocks, storage blocks, chests, glass, silicon — `src/generated/resources/data/c/tags/`

## In-world rendering and HUD

- Part and facade placement preview, and per-part block outline (drawn through blocks) — `J/hooks/RenderBlockOutlineHook.java` **[config: client.showPlacementPreview]**
- Facades hidden or see-through while holding a network tool — `J/client/render/cablebus/FacadeBuilder.java`, `G/items-blocks-machines/network_tool.md`
- Smart cable channel lines (exact paths with a controller, network-wide count when ad-hoc) — `J/client/render/cablebus/SmartCableTextures.java`
- Cell LEDs in drives and ME chests — `J/client/render/tesr/DriveLedBlockEntityRenderer.java`, `J/client/render/tesr/ChestBlockEntityRenderer.java`
- Storage and conversion monitors render the item and amount in world — `J/parts/reporting/AbstractMonitorPart.java:298`
- Crafting monitor renders the current job item; crafting CPU textures; assembler crafting animation — `J/client/render/crafting/`
- Charger, inscriber, crank, sky chest and sky tank animations and contents — `J/client/render/tesr/`
- P2P frequency colors — `J/client/render/cablebus/P2PTunnelFrequencyBakedModel.java`
- Spatial anchor loaded-chunk overlay — `J/client/render/overlay/OverlayManager.java` (`Settings.OVERLAY_MODE`)
- Spatial storage dimension sky — `J/client/render/SpatialSkyRender.java`
- Controller and energy cell state textures; QNB formed model; spatial pylon textures — `J/block/qnb/QnbFormedModel.java`
- Memory card, portable cell and color applicator tints and models — `J/client/render/model/`
- Effects — lightning arcs, energy particles, matter cannon trail, crafting fx, vibrant fx, annihilation-plane transition particles and sounds, mock explosions — `J/client/render/effects/`, `J/core/network/clientbound/*EffectPacket.java` **[config: client.enableEffects]**
- Toast — Auto-Crafting Complete — `J/client/gui/me/common/FinishedJobToast.java`
- Action-bar messages — memory card actions, monitor lock, facade property, out of range, and similar — `J/core/localization/PlayerMessages.java`
- Item tooltips — cell contents, pattern details, wireless link state, spatial cell size and serial, energy charge, compatible upgrades — various

## In-game guide

- GuideME-based guide (`ae2:guide`, folder `ae2guide`), about 125 pages: getting started, AE2 mechanics (channels, devices, energy, subnetworks, P2P, spatial IO, autocrafting, bytes and types, meteorites, certus growth), every item and block, about 20 example setups (autostocking, pipe subnets, ore fortuner, cobble generator, recursive crafting and others), tips and tricks — `G/`, `J/core/AppEngClient.java:228`
- Interactive 3D scenes — zoom, rotate (left-drag), pan (right-drag), annotation toggles; about 139 scenes with box, line and diamond annotations — `G/` (`<GameScene>`, `<ImportStructure>`)
- Inline recipes (`<RecipeFor>`), item links and images, category indexes and a sidebar table of contents; inscriber recipe layout — `J/client/guidebook/LytInscriberRecipe.java`, `J/client/guidebook/RecipeTypeContributions.java`
- Live config values in pages — `J/client/guidebook/ConfigValueTagExtension.java`
- Part-aware annotations — `J/client/guidebook/PartAnnotationStrategy.java`
- Entry points — Guide item, the "Open Guide" button in every AE screen (opens the page for that screen's machine), and the GuideME item hotkey — `J/client/gui/widgets/OpenGuideButton.java`, `J/core/AppEngClient.java:494`
- Search and navigation come from the GuideME library — `build.gradle` (`org.appliedenergistics:guideme`) **[guide-only]**

## Misc quality-of-life

- Wrench dismantle keeps a block's settings, upgrades and name (and an energy cell's charge) on the dropped item, restored on placement — `J/util/SettingsFrom.java`, `J/blockentity/AEBaseBlockEntity.java:335`
- Memory card copies the custom name, settings, filter, priority, upgrades, patterns, P2P type and frequency and level-emitter value; partial-restore message; accepts compatible block and part pairs — `L` (`exported_setting.*`, `chat.ae2.*Settings`)
- Name blocks and parts (anvil or name press) — names show in the pattern access terminal and the CPU list — `J/helpers/patternprovider/PatternProviderLogic.java:725`
- Swap part and block variants in the crafting grid (interface, pattern provider, energy acceptor) — `D/recipe/network/blocks/`
- Dyeable portable cells and memory cards; cauldron washing — `J/init/InitCauldronInteraction.java`
- Cable color clean via water-bucket recipe; snowball in the color applicator — `D/recipe/network/cables/*_clean.json`
- Number fields accept math (`3*64/2`) and validate min/max and integer input — `J/client/gui/MathExpressionParser.java`, `L` (`gui.ae2.InvalidNumber`)
- Fluid amounts shown and entered in buckets — `J/client/gui/NumberEntryType.java`
- Recipe-viewer exclusion zones keep REI/EMI from overlapping AE GUIs — `J/integration/modules/emi/EmiAeBaseScreenExclusionZones.java`
- Hidden-slot support — screen styles can hide slots and the server is told — `J/client/gui/AEBaseScreen.java:214`
- Creative tabs: main and Facades — `L` (`gui.ae2.CreativeTab*`)
- Advancements — Root, Charger, Charged Quartz, Compass, Controller, Crafting CPU, Crafting Terminal, Growth Accelerator, Facade, Fluix, Glass Cable, IO Port, Network Tool, Networking 8/128/2048 channels, P2P, Pattern Terminal, Portable Cell, Presses, QNB, Recursive, Spatial IO, Spatial Explorer, Storage Bus, Storage Cell — `L` (`achievement.ae2.*`), `J/init/InitAdvancementTriggers.java`
- Damage type "shot by matter cannon" — `L` (`death.attack.matter_cannon*`)
- Large terminal font option **[config: client.useTerminalUseLargeFont]**
- Unused lang leftovers — TransparentFacades, TrashController ("Destroy Items"), ReturnInventory, InventoryTweaks, MultipleOutputs, Lumen, OCTunnel, PressureTunnel, Fluid Storage/Import/Export Bus names **[lang-only]** — `L`
