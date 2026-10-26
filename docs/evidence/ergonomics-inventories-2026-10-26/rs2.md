# Refined Storage 2 — player-facing feature inventory

**Repo:** https://github.com/refinedmods/refinedstorage2
**Branch inventoried:** `support/2.x` @ `0dec63affe8147702b020843c22748483ef18c01` (2026-06-07), mod version 2.0.9, **Minecraft 1.21.1**, NeoForge + Fabric.
The default branch `develop` (`d33a173`) is v3.x for **Minecraft 26.1.2**; it was cloned separately only to note 3.x-only additions (see the end of Misc).
**Method:** read `en_us.json` in full, the content registries, every screen and menu, the C2S and S2C packets, key mappings, both config implementations, platform initializers, recipes, the in-repo docs (`docs/pages/**`) and `CHANGELOG.md`. Nothing was built or run.

**Path legend (all repo-relative):**
- `C/` = `refinedstorage-common/src/main/java/com/refinedmods/refinedstorage/common/`
- `N/` = `refinedstorage-neoforge/src/main/java/com/refinedmods/refinedstorage/neoforge/`
- `F/` = `refinedstorage-fabric/src/main/java/com/refinedmods/refinedstorage/fabric/`
- `L` = `refinedstorage-common/src/main/resources/assets/refinedstorage/lang/en_us.json`
- `R/` = `refinedstorage-common/src/main/resources/data/refinedstorage/recipe/` (hand-written) and `refinedstorage-common/src/generated/resources/data/refinedstorage/recipe/` (generated)
- `D/` = `docs/pages/`

**Tags used below:** [NeoForge-only], [Fabric-only], [config: `<key>`], [addon: `<name>`]. Lines with no tag apply to both platforms.

---

## Blocks

- Controller — powers the network from an external energy source (FE on NeoForge, Team Reborn Energy on Fabric); you can use several per network; keeps its stored energy when broken; the block state shows energy level (off / nearly_off / nearly_on / on); available in 16 colours — `C/controller/ControllerBlock.java`, `C/controller/ControllerEnergyType.java`, `D/networking/controller.adoc`
- Controller not needed when energy is disabled — with [config: `requireEnergy`=false] networks run without a Controller, and the tooltip says the Controller is not needed — `L` (`item.refinedstorage.controller.not_needed_because_energy_is_not_required`)
- Creative Controller — infinite energy; creative-only; hidden when energy is not required; 16 colours — `C/controller/CreativeControllerBlock.java`, `D/networking/creative-controller.adoc`
- Cable — connects devices; devices that touch connect without cable; coloured cable connects only to the same colour or to default gray; full blocks connect to any colour; waterloggable — `C/networking/CableBlock.java`, `L` (`item.refinedstorage.cable.help`), `C/support/AbstractDirectionalCableBlock.java`
- Disk Drive — 8 storage-disk slots, adds the disks' storage to the network — `C/storage/diskdrive/AbstractDiskDriveBlockEntity.java` (`AMOUNT_OF_DISKS = 8`)
- Item Storage Blocks — 1k / 4k / 16k / 64k / Creative; a placeable storage that keeps its contents when broken — `C/storage/storageblock/StorageBlock.java`, `C/storage/storageblock/StorageBlockLootItemFunction.java`
- Fluid Storage Blocks — 64B / 256B / 1024B / 4096B / Creative (capacities in buckets) — `C/storage/storageblock/FluidStorageBlockProvider.java`, `L`
- Machine Casing — crafting component and part of storage blocks — `C/AbstractModInitializer.java` (`registerBlocks`)
- Grid — view, insert and extract all network resources (items and fluids); 16 colours — `C/grid/GridBlock.java`
- Crafting Grid — a Grid with a 3x3 crafting matrix; 16 colours — `C/grid/CraftingGridBlock.java`
- Pattern Grid — a Grid that encodes patterns; 16 colours — `C/autocrafting/patterngrid/PatternGridBlock.java`
- Importer — pulls resources from the inventory it faces into the network; cable-shaped; 16 colours — `C/importer/ImporterBlock.java`
- Exporter — pushes resources from the network into the inventory it faces; cable-shaped; 16 colours — `C/exporter/ExporterBlock.java`
- Interface — 9 export-config slots plus 9 buffer slots that external automation can insert into and extract from; supports items and fluids — `C/iface/InterfaceBlockEntity.java` (`EXPORT_SLOTS = 9`)
- External Storage — exposes an adjacent inventory or tank (several resource types at once) as network storage; 16 colours — `C/storage/externalstorage/ExternalStorageBlock.java`
- Detector — emits redstone by comparing a resource count; can be placed in any orientation; 16 colours — `C/detector/DetectorBlock.java`
- Constructor — places blocks or fluids, launches fireworks or drops items in front of it; 16 colours — `C/constructordestructor/ConstructorBlock.java`
- Destructor — breaks blocks, picks up fluid sources or picks up item entities in front of it; 16 colours — `C/constructordestructor/DestructorBlock.java`
- Wireless Transmitter — gives wireless items network access within a range; connects only through its base; 16 colours — `C/networking/WirelessTransmitterBlock.java`, `C/networking/WirelessTransmitterConnectionStrategy.java`
- Storage Monitor — shows one resource and its count on its face; you can insert and extract by clicking it — `C/storagemonitor/StorageMonitorBlock.java`
- Network Transmitter — sends the network to a Network Receiver wirelessly, across dimensions too; needs a bound Network Card; 16 colours — `C/networking/NetworkTransmitterBlock.java`, `D/wireless-networking/network-transmitter.adoc`
- Network Receiver — receiving end for a Network Transmitter; 16 colours — `C/networking/NetworkReceiverBlock.java`
- Portable Grid (block form) — a Grid over one storage disk with no network; crouch to place; has a redstone mode — `C/storage/portablegrid/PortableGridBlock.java`, `C/storage/portablegrid/AbstractPortableGridBlockEntity.java`
- Creative Portable Grid — needs no energy; hidden when energy is not required — `C/AbstractModInitializer.java` (`CREATIVE_PORTABLE_GRID`)
- Security Manager — locks the network down; holds 18 Security Cards plus 1 Fallback card; does not connect on its top face; 16 colours — `C/security/SecurityManagerBlockEntity.java` (`CARD_AMOUNT = 18`), `C/security/SecurityManagerConnectionStrategy.java`
- Relay — passes the network from its input sides to its output side, either fully or per component (energy, storage, security, autocrafting); can be redstone-gated; 16 colours — `C/networking/RelayBlock.java`, `D/networking/relay.adoc`
- Disk Interface (formerly Disk Manipulator) — 3 input and 3 output disk slots; moves resources between the disks and the network, then moves finished disks to the output slots; 16 colours — `C/storage/diskinterface/AbstractDiskInterfaceBlockEntity.java` (`AMOUNT_OF_DISKS = 6`)
- Autocrafter — holds 9 patterns; inserts processing inputs into the machine it faces; can be chained; 16 colours — `C/autocrafting/autocrafter/AutocrafterBlockEntity.java` (`PATTERNS = 9`)
- Autocrafter Manager — one UI over every Autocrafter's patterns; 16 colours — `C/autocrafting/autocraftermanager/AutocrafterManagerBlock.java`
- Autocrafting Monitor — lists running autocrafting tasks and can cancel them; 16 colours — `C/autocrafting/monitor/AutocraftingMonitorBlock.java`
- Coloured variants — 20 block families × 16 dyes (autocrafter, autocrafter_manager, autocrafting_monitor, cable, constructor, controller, crafting_grid, destructor, detector, disk_interface, exporter, external_storage, grid, importer, network_receiver, network_transmitter, pattern_grid, relay, security_manager, wireless_transmitter); shown in a separate "Refined Storage (colorized)" creative tab — `R/coloring/*.json` (320 files), `C/content/Blocks.java`, `C/content/CreativeModeTabItems.java`, `L` (`mod.refinedstorage.colorized`)
- Colour-independent connection — Detector, Network Receiver, Network Transmitter and Security Manager connect regardless of colour — `CHANGELOG.md` (milestone.3.6)
- Directional blocks — can face up and down; they neither send nor accept the network signal through their facing side — `CHANGELOG.md` (milestone.2.2), `C/support/AbstractDirectionalBlock.java`

## Items and tools

- Quartz Enriched Iron — crafting material; in the `c:ingots` tag — `C/AbstractModInitializer.java` (`registerSimpleItems`)
- Quartz Enriched Copper — crafting material for cables; in `c:ingots` — same
- Silicon — smelted from `c:gems/quartz` — `R/silicon.json`
- Processor Binding — crafting component — `C/AbstractModInitializer.java`
- Raw Basic / Improved / Advanced Processor → Basic / Improved / Advanced Processor — processor chain — `C/misc/ProcessorItem.java`
- Construction Core / Destruction Core — components for the Constructor and Destructor — `C/AbstractModInitializer.java`
- Storage Housing — an empty disk shell; housing + part makes a disk — `R/*_disk_from_storage_housing.json`, `D/storage/storage-housing.adoc`
- Storage Parts 1k / 4k / 16k / 64k — `C/AbstractModInitializer.java` (`registerItemStorageItems`)
- Fluid Storage Parts 64B / 256B / 1024B / 4096B — `C/AbstractModInitializer.java` (`registerFluidStorageItems`)
- Storage Disks 1k / 4k / 16k / 64k / Creative — 1024 / 4096 / 16384 / 65536 / unlimited items — `C/storage/storagedisk/ItemStorageDiskItem.java`, `D/storage/1k-storage-disk.adoc`
- Fluid Storage Disks 64B / 256B / 1024B / 4096B / Creative — capacities in buckets; fluid disks use a different model — `C/storage/storagedisk/FluidStorageDiskItem.java`
- Wrench — use to rotate a block; crouch-use to dismantle it into an item that keeps its configuration and contents; plays a sound; any item tagged `c:tools/wrench` works too — `C/misc/WrenchItem.java`, `C/support/AbstractBaseBlock.java` (`tryUseWrench`, `dismantle`), `D/utility/wrench.adoc`
- Pattern — blank, or encoded as crafting, processing, stonecutter or smithing-table; encoded patterns get their own name and texture — `C/autocrafting/PatternItem.java`, `L` (`misc.refinedstorage.pattern.*`)
- Wireless Grid / Creative Wireless Grid — portable Grid that connects through a Wireless Transmitter; bind it by using it on any network device — `C/grid/WirelessGridItem.java`
- Wireless Autocrafting Monitor / Creative variant — portable Autocrafting Monitor — `C/autocrafting/monitor/WirelessAutocraftingMonitorItem.java`
- Portable Grid (item form) — use from the inventory, or crouch-use to place it; holds a disk and energy — `C/storage/portablegrid/PortableGridBlockItem.java`
- Configuration Card — crouch-use on a device to copy its configuration and upgrade list; crouch-use on a device of the same type to apply it (pulls matching upgrades from your inventory); crouch-use in the air to clear — `C/configurationcard/ConfigurationCardItem.java`, `L`
- Network Card — crouch-use on a Network Receiver to bind it (stores position and dimension); goes into a Network Transmitter; crouch-use to clear — `C/networking/NetworkCardItem.java`, `L`
- Security Card — use to open its permission UI and bind it to yourself; a button cycles the binding to other online players; crouch-use clears the permissions and binding — `C/security/SecurityCardItem.java`, `C/security/SecurityCardScreen.java`
- Fallback Security Card — permissions for players who have no matching card; crouch-use clears it — `C/security/FallbackSecurityCardItem.java`
- Upgrade (base item) — crafting base for every upgrade — `C/AbstractModInitializer.java` (`registerUpgrades`)
- Debug Stick — use on a device to dump its network containers to chat; use in the air to get a creative disk holding every registered item (crouch adds every damage value) [NeoForge-only] [config: `debug`] — `N/debug/DebugStickItem.java`
- Item tags exposed — `refinedstorage:cables`, `grids`, `crafting_grids`, `pattern_grids`, `controllers`, `creative_controllers`, `storage_disks`, `fluid_storage_disks`, `importers`, `exporters`, `external_storages`, `detectors`, `constructors`, `destructors`, `wireless_transmitters`, `network_receivers`, `network_transmitters`, `security_managers`, `relays`, `disk_interfaces`, `autocrafters`, `autocrafter_managers`, `autocrafting_monitors` (each with a translated name) — `C/content/Tags.java`, `L` (`tag.item.*`)

## Upgrades (with the devices each fits)

- Upgrade slots — 4 per device; at most one upgrade type per inventory; slot tooltips list the accepted upgrades as item icons, in order — `C/upgrade/UpgradeContainer.java`, `C/upgrade/UpgradeItemClientTooltipComponent.java`
- "Supported by" tooltip on upgrade items — lists the devices that accept the upgrade, with icons — `C/upgrade/UpgradeDestinationClientTooltipComponent.java`, `C/upgrade/UpgradeDestinations.java`
- Speed Upgrade (up to 4) — Importer, Exporter, Constructor, Destructor, Disk Interface, Autocrafter; base work interval is 9 ticks, minus 2 ticks per upgrade; in the Autocrafter it gives 1→5 insertion steps per tick — `C/AbstractModInitializer.java` (`registerUpgradeMappings`), `C/upgrade/UpgradeContainer.java` (`DEFAULT_WORK_TICK_RATE = 9`), `C/autocrafting/autocrafter/AutocrafterBlockEntity.java` (`getSteps`)
- Stack Upgrade (1) — Importer, Exporter, Constructor (drop mode drops whole stacks), Disk Interface; moves 64× the single amount per operation — `C/importer/ImporterTransferQuotaProvider.java`, `C/exporter/ExporterTransferQuotaProvider.java`, `C/constructordestructor/ItemDropConstructorStrategyFactory.java`
- Regulator Upgrade (up to 4) — Importer, Exporter; use the item to set one resource and amount. In an Exporter it tops the target up to that amount; in an Importer it leaves that amount in the source. Crouch-use clears it (2.0.9). With an Autocrafting Upgrade it triggers autocrafting — `C/upgrade/RegulatorUpgradeItem.java`, `C/upgrade/RegulatorUpgradeScreen.java`, `D/upgrades/regulator-upgrade.adoc`
- Autocrafting Upgrade (1) — Exporter, Constructor, Interface; starts an autocrafting task when the configured resource is missing — `C/constructordestructor/AutocraftOnMissingResourcesConstructorStrategy.java`, `D/upgrades/autocrafting-upgrade.adoc`
- Fortune I / II / III Upgrade (1) — Destructor; crafted with a Fortune enchanted book of the matching level — `C/upgrade/FortuneUpgradeItem.java`, `R/fortune_1_upgrade.json` (`upgrade_with_enchanted_book`)
- Silk Touch Upgrade (1) — Destructor; crafted with a Silk Touch book — `C/upgrade/UpgradeWithEnchantedBookRecipe.java`
- Range Upgrade (up to 4) — Wireless Transmitter; +8 blocks each (default) — `C/networking/RangeUpgradeWirelessTransmitterRangeModifier.java`, config `upgrade.rangeUpgradeRange`
- Creative Range Upgrade (1) — Wireless Transmitter; unlimited range in the same dimension; creative-only — `C/networking/CreativeRangeUpgradeWirelessTransmitterRangeModifier.java`, `L`
- Per-upgrade energy cost — each upgrade adds energy usage to its device (configurable) — `C/content/DefaultEnergyUsage.java`, config `upgrade.*EnergyUsage`

## Storage (disks, blocks, external storage, partition, priority, access, fuzzy)

- Capacities — items 1024 / 4096 / 16384 / 65536 / creative; fluids 64 / 256 / 1024 / 4096 buckets / creative; one stack can exceed 2^31 — `D/storage/*.adoc`, `CHANGELOG.md`
- Disk disassembly — crouch-use an empty disk to get its part back (plus the housing); crouch-use an empty storage block item to get the part and a Machine Casing — `C/storage/StorageContainerItemHelperImpl.java` (`tryDisassembly`), `L`
- Tier upgrade by crafting — combine a disk or storage block with a higher-tier part; contents carry over and the old part is returned — `C/storage/StorageContainerUpgradeRecipe.java`, `R/*_upgrade.json`
- Craft a disk from a housing — Storage Housing + part → disk — `R/*_from_storage_housing.json`
- Disk tooltip — "Stored: X / Y (Z%)"; item disks also show stacks; colour-coded; advanced tooltips (F3+H) show the storage UUID — `C/storage/StorageTooltipHelper.java`, `C/storage/StorageContainerItemHelperImpl.java` (`appendToTooltip`)
- Server-synced disk info — the client asks the server for disk fill so tooltips stay accurate — `C/support/packet/c2s/StorageInfoRequestPacket.java`, `C/storage/ClientStorageRepository.java`
- Storage blocks and portable grids keep contents when broken — via loot functions — `C/storage/storageblock/StorageBlockLootItemFunction.java`, `C/storage/portablegrid/PortableGridLootItemFunction.java`
- Creative middle-click on a disk gives a fresh, unlinked disk — prevents two disks sharing one storage (mixin on both platforms) — `N/mixin/AbstractContainerMenuMixin.java`, `F/mixin/AbstractContainerMenuMixin.java`
- Filter (partition) — 9 filter slots, Allowlist or Blocklist, on Disk Drive, Storage Blocks and External Storage; the button warns when an allowlist is empty — `C/storage/AbstractStorageScreen.java`, `C/storage/StorageConfigurationContainerImpl.java`, `L` (`filter_mode.empty_warning`)
- Fuzzy mode — compare filters while ignoring data components; available on almost every filtered device — `C/support/FilterWithFuzzyMode.java`, `L` (`fuzzy_mode.*`)
- Insert and extract priority — click sets both; CTRL/CMD+click sets insert only; ALT+click sets extract only; highest first; reset button; ±1/5/10 buttons — `C/support/widget/StoragePrioritySideButtonWidget.java`, `C/support/amount/PriorityScreen.java`, `L`
- Access mode — Insert and extract / Insert only / Extract only; insert-only storages still show their contents in the Grid — `C/storage/AccessModeSideButtonWidget.java`, `L`
- Void excess — a full storage keeps accepting allowlisted resources and destroys them; the button warns if not in allowlist mode — `C/storage/VoidExcessSideButtonWidget.java`, `L`
- Redstone mode on storages — when inactive, the contents are unavailable to the network — `L` (`storage.redstone_mode_help`)
- Fill bar in storage GUIs — Disk Drive and Storage Block screens show a progress bar with a stored/capacity tooltip — `C/storage/AbstractProgressStorageScreen.java`
- External Storage polling — rescans the external inventory every 5 to 40 ticks, speeding up when it changes and slowing when idle — `C/storage/externalstorage/ExternalStorageWorkRate.java`
- External Storage change tracking — external changes feed the Grid's "last modified" data — `C/storage/externalstorage/ExternalStorageTrackedStorageRepository.java`
- External Storage on an Interface — an Interface can act as an External Storage source but will not pull from other Interfaces (or itself) — `C/iface/InterfaceProxyExternalStorageProvider.java`, `D/input-and-output/interface.adoc`
- Disk Drive exposes its disk slots to automation (hopper or pipe disks in and out) — `N/ModInitializer.java` (ItemHandler on DiskDrive), `F/ModInitializerImpl.java`
- Disk Interface exposes its slots to automation — input slots accept insertion, output slots allow extraction — `N/ModInitializer.java` (`InsertExtractItemHandler`)
- Storage type registry — item and fluid storage types; addons can add more (Mekanism chemicals) — `C/AbstractModInitializer.java` (`registerStorageTypes`)

## Grids and interaction

- Left-click a resource with an empty hand — takes up to one full stack onto the cursor — `C/grid/screen/AbstractGridScreen.java` (`getExtractMode`), `D/viewing-resources/grid.adoc`
- Right-click a resource with an empty hand — takes half a stack — same
- SHIFT+click a resource — extracts into your inventory instead of the cursor — `AbstractGridScreen.java` (`shouldExtractToCursor`)
- Left-click with a container (bucket) on a fluid — fills the container — `D/viewing-resources/grid.adoc`, `N/grid/strategy/FluidGridExtractionStrategy.java`
- Fluid extraction uses a bucket from your inventory or from the network — `N/grid/strategy/FluidGridExtractionStrategy.java`
- Left-click the grid area while holding a stack — inserts the whole stack — `AbstractGridScreen.java` (`mouseClickedInGrid`)
- Right-click the grid area while holding a stack — inserts one item, or empties a fluid container into the network ("try alternatives") — same
- SHIFT+click an inventory slot — inserts the stack into the network — `C/grid/AbstractGridContainerMenu.java` (`quickMoveStack`)
- CTRL/CMD+click a resource — opens the autocrafting request even if the resource is in stock — `AbstractGridScreen.java` (`canExtract`), `L` (`grid.ctrl_click_to_autocraft`, `cmd_click_to_autocraft`)
- Click a resource you have none of but can autocraft — opens the autocrafting preview — `AbstractGridScreen.java` (`tryStartAutocrafting`)
- SHIFT+scroll up over a grid resource or an inventory slot — moves one item into the network — `AbstractGridScreen.java` (`getScrollModeWhen…`), `C/api/grid/GridScrollMode` (`INVENTORY_TO_GRID`)
- SHIFT+scroll down over a grid resource or an inventory slot — moves one item to your inventory — same (`GRID_TO_INVENTORY`)
- CTRL/CMD+scroll down over a grid resource — puts one item on the cursor — same (`GRID_TO_CURSOR`)
- Insertion and extraction hints — hovering the grid while holding an item shows what will be inserted (item, or the fluid inside a container) — `C/grid/screen/hint/*`, `C/AbstractClientModInitializer.java` (`registerAlternativeGridHints`)
- Search bar — free text matches display names, including renamed items — `C/grid/query/GridQueryParser.java` (`parseLiteral`)
- Search syntax: `@mod` (mod id or name), `#tag` (also block tags on block items), `$tooltip`, `!` NOT, `&&` AND, `||` OR, parentheses, implicit AND, `"quoted strings"`, count filters `>N`, `>=N`, `<N`, `<=N`, `=N`; underscores and slashes allowed — `C/grid/query/GridQueryParser.java`, `refinedstorage-query-parser/src/main/java/com/refinedmods/refinedstorage/query/lexer/LexerTokenMappings.java`
- Search syntax highlighting — the query is coloured by token, and parse errors are highlighted as invalid — `C/grid/screen/GridSearchBoxWidget.java`, `refinedstorage-query-parser/.../SyntaxHighlighter.java`
- Search history — UP and DOWN cycle earlier queries; ENTER saves to history — `C/support/widget/SearchFieldWidget.java`, `C/support/widget/History.java`
- Right-click the search bar — clears it — `SearchFieldWidget.java`
- TAB (rebindable) — toggles search-bar focus when it is not auto-selected — `SearchFieldWidget.java`, `N/ClientModInitializer.java`
- Auto-selected search box — side-button toggle, global [config: `searchBoxAutoSelected`] — `C/support/widget/AutoSelectedSideButtonWidget.java`
- Remember search query [config: `grid.rememberSearchQuery`] — `D/viewing-resources/grid.adoc`
- Search-box sync with a recipe viewer (Off / one-way / two-way); the button only appears when an addon registers a synchroniser [addon: JEI/REI/EMI integration] — `C/grid/screen/SynchronizationModeSideButtonWidget.java`, `AbstractGridScreen.java` (`onlyHasNoopSynchronizer`)
- Sorting type — Quantity / Name / ID / Last modified — `C/grid/GridSortingTypes.java`
- Sorting direction — Ascending / Descending — `C/grid/screen/SortingDirectionSideButtonWidget.java`
- Sorting pauses while SHIFT is held, so items don't move while you shift-click or scroll [config: `grid.preventSortingWhileShiftIsDown`] — `AbstractGridScreen.java` (`keyPressed`/`keyReleased`)
- View type — All / Autocraftable / Non-autocraftable — `C/grid/GridViewType.java`
- Resource type filter — All / Item / Fluid (plus addon types); warns when the chosen type has nothing to show — `C/grid/screen/ResourceTypeSideButtonWidget.java`, `L` (`resource_type.empty_warning`)
- Screen size — Stretch / Small / Medium / Large / Extra large (12 rows); stretch row cap [config: `maxRowsStretch`] — `C/support/stretching/*`, `L` (`screen_size.*`)
- Grid display settings are client-side and global (not per block) — `CHANGELOG.md` (milestone.1.4), config `grid.*`
- Autocraftable highlight — autocraftable resources get an orange backdrop and an "autocraftable" tooltip line; this includes patterns that are only in your inventory ("There is a pattern in your inventory…") — `C/grid/AutocraftableResourceHint.java`, `C/grid/screen/AutocraftableClientTooltipComponent.java`, `L`
- Detailed tooltip — total amount plus "Last modified N ago by <player or device>" [config: `grid.detailedTooltip`] — `AbstractGridScreen.java` (`addDetailedTooltip`), `C/grid/screen/LastModified.java`
- Large-font quantities [config: `grid.largeFont`] — `D/viewing-resources/grid.adoc`
- Smooth scrolling [config: `smoothScrolling`] — `C/support/widget/ScrollbarWidget.java`
- Fluid amounts are shown with a "B" suffix (buckets) and amounts are compacted with units — `CHANGELOG.md` (milestone.4.14)
- Inactive grid — shows disabled slots when the network or device is off — `C/support/packet/s2c/GridActivePacket.java`
- Inaccurate-extraction fallback — if an item can't be matched exactly, extraction retries fuzzily, warns in chat and on screen, and links a help page — `C/grid/FuzzyGridOperations.java`, `L` (`grid.inaccurate_extraction.*`), `D/troubleshooting/inaccurate-extraction.adoc`
- Grid update throttling — at most 4 updates per second per resource type, with queued flushes — `C/grid/PendingGridUpdates.java`, `CHANGELOG.md` 2.0.7
- Crafting Grid: 3x3 matrix with result; crafting refills the matrix from the network — `C/grid/CraftingGrid.java`, `C/grid/CraftingGridResultSlot.java`
- Crafting Grid: SHIFT+click the result — crafts repeatedly up to one stack, pulling ingredients from the network — `CraftingGridResultSlot.java` (`onQuickCraft`)
- Crafting Grid: "Move items to network" and "Move items to inventory" buttons — `C/grid/screen/CraftingGridScreen.java`, `L` (`crafting_grid.move.*`)
- Crafting Grid: CTRL+X clears the matrix to the network; an unbound key clears it to your inventory [NeoForge-only key mappings; on Fabric these are null, so the buttons are the way] — `N/ClientModInitializer.java`, `F/ClientModInitializerImpl.java` (`registerKeyMappings`)
- Crafting Grid: hold SHIFT+CTRL/CMD on the result slot to show only the recipe's ingredients in the grid — `C/grid/AbstractCraftingGridContainerMenu.java` (`filterBasedOnCraftingMatrixItems`), `L`
- Crafting Grid: what happens to the matrix on close — keep / clear to network / clear to inventory [config: `craftingGrid.craftingMatrixCloseBehavior`] — `C/grid/CraftingGridMatrixCloseBehavior.java`
- Crafting Grid: matrix slots show an orange "autocraftable" backdrop — `CHANGELOG.md` (milestone.4.8)
- Crafting Grid: recipe transfer packet (used by recipe-viewer addons); fills what you have and offers autocrafting for the rest — `C/support/packet/c2s/CraftingGridRecipeTransferPacket.java`, `D/viewing-resources/crafting-grid.adoc`
- Wireless Grid — the Grid UI from an item; uses energy to open, insert and extract; works only within a Wireless Transmitter's range in the same dimension; always opens (shown inactive) when unbound or out of range — `C/grid/WirelessGridItem.java`, `C/grid/WirelessGridOperations.java`, `CHANGELOG.md` (milestone.3.0)
- Portable Grid — the Grid UI over one disk with no network; energy bar in the GUI; uses energy to open, insert and extract; holds the disk in a slot — `C/storage/portablegrid/PortableGridScreen.java`, `C/storage/portablegrid/PortableGridOperations.java`
- Open wireless items from anywhere in your inventory — the keybind finds the item in inventory or an addon slot (Curios/Trinkets) and refuses if there is more than one — `C/AbstractClientModInitializer.java` (`handleInputEvents`), `C/support/packet/c2s/UseSlotReferencedItemPacket.java`, `L` (`network_item.cannot_open_with_shortcut_due_to_duplicate`)
- GUIs close automatically when you move out of reach or the block is destroyed — `CHANGELOG.md` (beta.6)
- Long GUI titles scroll as a marquee — `C/support/widget/TextMarquee.java`

## Autocrafting

- Pattern types — Crafting, Processing, Stonecutter, Smithing Table — `C/autocrafting/patterngrid/PatternType.java`
- Pattern Grid: mode tabs (crafting-table, furnace, stonecutter and smithing-table icons) — `C/autocrafting/patterngrid/PatternTypeButton.java`
- Pattern Grid: "Create pattern" button turns one blank pattern from the input slot into an encoded pattern in the output slot — `C/autocrafting/patterngrid/PatternGridBlockEntity.java` (`createPattern`)
- Pattern Grid: "Clear" button — `C/support/packet/c2s/PatternGridClearPacket.java`
- Pattern Grid: put an encoded pattern in the output slot to load it back for editing — `D/autocrafting/pattern-grid.adoc`
- Pattern Grid: the pattern output always renders in the result slot — `CHANGELOG.md` (milestone.4.6)
- Pattern Grid: blank-pattern input slot exposed to hoppers and pipes — `N/ModInitializer.java` (ItemHandler on PatternGrid)
- Crafting pattern: Fuzzy mode checkbox — when on, the pattern accepts input alternatives (tags) — `C/autocrafting/patterngrid/CraftingPatternGridRenderer.java`, `L` (`pattern_grid.fuzzy_mode.*`)
- Processing pattern: 81 input and 81 output slots in a scrollable matrix, with items and fluids mixed freely — `PatternGridBlockEntity.java` (`createProcessingMatrixInputContainer(81)`), `C/autocrafting/patterngrid/ProcessingPatternGridRenderer.java` (the docs say "up to 9")
- Processing pattern: left-click a slot with an item to set the item; right-click to set the fluid inside a container — `C/support/resource/ResourceContainerImpl.java` (`change`, `tryAlternatives`)
- Processing pattern: click a set slot to open the amount and allowed-alternatives screen (per-input tag alternatives, searchable, expandable tag contents, translated tag names); fluids up to 64 buckets — `C/autocrafting/patterngrid/AlternativesScreen.java`, `C/support/packet/c2s/PatternGridAllowedAlternativesChangePacket.java`, `CHANGELOG.md` 2.0.7
- Processing pattern: input tooltip lists the allowed alternatives, cycling every second — `C/autocrafting/patterngrid/ProcessingMatrixInputClientTooltipComponent.java`
- Stonecutter pattern: pick the output from a scrollable recipe list (4 per row, 3 rows visible) — `C/autocrafting/patterngrid/StonecutterPatternGridRenderer.java`, `C/autocrafting/VanillaConstants.java`
- Smithing table pattern: template, base and addition slots, with vanilla slot hints — `C/autocrafting/patterngrid/SmithingTablePatternGridRenderer.java`
- Recipe-transfer packets for crafting, processing, stonecutter and smithing patterns (used by JEI/REI/EMI addons) — `C/support/packet/c2s/PatternGrid*RecipeTransferPacket.java`
- Pattern tooltip draws the recipe (grid → output), cycling alternatives — `C/autocrafting/*PatternClientTooltipComponent.java`, `C/autocrafting/PatternTooltipCache.java`
- Pattern icon shows its output while SHIFT is held, and always inside the Autocrafter and Pattern Grid — `C/autocrafting/PatternRendering.java`, `N/mixin/AbstractGuiGraphicsMixin.java`
- Crouch-use a pattern to clear it — `C/autocrafting/PatternItem.java` (`use`), `L`
- Autocrafter: 9 pattern slots; extra energy per pattern — `AutocrafterBlockEntity.java`, config `autocrafter.energyUsagePerPattern`
- Autocrafter: inserts processing inputs into the inventory or tank it faces; the task finishes when the outputs come back into the network by any route (Importer, Interface, …) — `C/autocrafting/CompositePatternProviderExternalPatternSink*.java`, `N/autocrafting/*ExternalPatternProviderSink*.java`, `F/autocrafting/*`
- Autocrafter: crafting, stonecutter and smithing patterns craft internally; no machine needed — `D/guides/getting-started-with-autocrafting.adoc`
- Autocrafter lock modes — Never; Lock until redstone pulse; Lock until connected machine is empty; Lock until all outputs are received; Lock until high redstone signal; Lock until low redstone signal — `C/autocrafting/autocrafter/LockMode.java`, `L`
- Autocrafter "currently locked" indicator in the GUI — `C/support/packet/s2c/AutocrafterLockedUpdatePacket.java`, `L`
- Autocrafter chaining — Autocrafters facing each other form a chain of up to 8 to share one machine; the GUI shows Chained / Head / Not chained; only the head sets the lock mode — `AutocrafterBlockEntity.java` (`MAX_CHAINED_AUTOCRAFTERS = 8`), `C/autocrafting/autocrafter/AutocrafterConnectionStrategy.java`, `L`
- Autocrafter priority — when several patterns make the same output, the highest-priority Autocrafter's pattern is used — `C/autocrafting/autocrafter/AutocrafterPrioritySideButtonWidget.java`, `L`
- Autocrafter naming — Edit button renames it in the GUI; the default name is the connected machine's name; the name groups patterns in the Manager — `AutocrafterScreen.java`, `C/support/packet/c2s/AutocrafterNameChangePacket.java`
- Autocrafter "Visible to the Autocrafter Manager" toggle — `C/autocrafting/autocrafter/VisibleToTheAutocrafterManagerSideButtonWidget.java`
- Autocrafter Manager: patterns grouped by Autocrafter name; insert and remove patterns in place; search modes All / Pattern inputs / Pattern outputs / Autocrafter names; view types Visible / Not full / All — `C/autocrafting/autocraftermanager/*`, `L`
- Request flow: preview screen with amount field, ±1/10/64 buttons and scroll-wheel adjustment; the preview updates live; Start button; shows "Pending" while calculating — `C/autocrafting/preview/AutocraftingPreviewScreen.java`
- Preview cells show "Available / To craft / Missing", with numbers compacted — `L` (`autocrafting_preview.*`)
- "Max" button — fills in the largest amount you can craft now — `C/support/packet/c2s/AutocraftingPreviewMaxAmountRequestPacket.java`, `L`
- "Notify" checkbox — shows a toast when the task finishes (the default comes from [config: `autocraftingNotification`]) — `C/autocrafting/TaskCompletedToast.java`, `C/support/packet/s2c/AutocraftingTaskCompletedPacket.java`
- Preview style — List or Tree (zoom 0.5x–4x, drag to pan, full-screen tree view) [config: `autocraftingPreviewStyle`] — `C/autocrafting/preview/TreePreviewWidget.java`, `C/autocrafting/preview/FullscreenTreePreviewScreen.java`
- Multi-resource requests — when several resources are requested at once (e.g. missing ingredients from a recipe viewer), a side list lets you step through each — `AutocraftingPreviewScreen.java` (`AutocraftingRequestButton`)
- Preview errors — missing resources; pattern cycle (shows the outputs involved); request too large (overflow); took too long (cancelled after about 5 s, "too many subcomponents"); system busy ("not available") — `refinedstorage-autocrafting-api/.../preview/PreviewType.java`, `L`, `CHANGELOG.md` (beta.3)
- Closing the preview cancels its calculation; ESC force-closes while cancelling — `C/support/packet/c2s/AutocraftingPreviewCancelRequestPacket.java`, `L` (`cancelling.force_close`)
- Calculations run on a background autocrafting pool, and the request queue is bounded — `C/util/ServerListener.java` (`getAutocraftingPool`), `C/autocrafting/PendingAutocraftingRequests.java`
- Running tasks persist across world reloads — `C/autocrafting/autocrafter/TaskSnapshotPersistence.java`
- Autocrafting Monitor: task sidebar with output, progress %, running time and state (Ready / Extracting initial resources / Running / Returning internal storage / Completed) — `C/autocrafting/monitor/AutocraftingTaskButton.java`, `L`
- Autocrafting Monitor: per-resource cells (Extracting / Stored / Missing / Processing (with machine name) / Scheduled / Crafting) with error flags (machine doesn't accept resource, no machine found, autocrafter locked) — `C/autocrafting/monitor/AutocraftingMonitorScreen.java`, `L`
- Autocrafting Monitor: Cancel and Cancel all; won't cancel, or cancels partly, if the network has no room for the task's internal storage — `C/support/packet/c2s/AutocraftingMonitorCancel*Packet.java`, `D/autocrafting/autocrafting-monitor.adoc`
- Wireless Autocrafting Monitor — uses energy to open, cancel and cancel all — config `wirelessAutocraftingMonitor.*`
- Autocrafting from the Storage Monitor — clicking a monitor showing zero of an autocraftable resource opens the request screen — `C/storagemonitor/StorageMonitorBlockEntity.java` (`tryAutocrafting`), `C/storagemonitor/AutocraftingStorageMonitorContainerMenu.java`
- Autocrafting on demand — the Autocrafting Upgrade in an Exporter, Constructor or Interface; the filter slot shows "Autocrafting was started" or "missing resources" — `C/support/exportingindicator/*`, `L` (`exporting_indicator.*`)
- Autocrafting across a Relay — tasks run in the output network using patterns and Autocrafters from the input network — `D/networking/relay.adoc`
- Fluid autocrafting — processing patterns with fluid inputs and outputs; fluids can be requested from the Grid — `D/guides/fluid-autocrafting.adoc`
- Autocrafting permission — the Security "Autocrafting" permission covers start, cancel and view — `C/security/BuiltinPermission.java`

## Import/export and world interaction

- Importer — every work tick, pulls as much of one resource type as its quota allows (items or fluids); 9 filter slots, Allowlist/Blocklist (default Blocklist), fuzzy; respects the side it faces — `C/importer/AbstractImporterBlockEntity.java`, `D/input-and-output/importer.adoc`
- Exporter — 9 filter slots naming what to export, fuzzy; scheduling Default (first available) / Round robin / Random; each slot shows status (destination does not accept, resource missing, autocrafting started, autocrafting missing resources) — `C/exporter/AbstractExporterBlockEntity.java`, `C/support/SchedulingModeType.java`, `C/support/exportingindicator/*`
- Interface — exports the configured resources and amounts into its own buffer at once; anything inserted into the buffer goes straight to the network; buffer holds items and fluids; fluids can be taken with an empty bucket; exposed to automation — `C/iface/InterfaceBlockEntity.java`, `C/iface/ExportedResourcesContainer.java`, `N/ModInitializer.java`
- Interface filter slots carry an amount (click to set) and show status indicators — `C/iface/InterfaceContainerMenu.java` (`FILTER_WITH_AMOUNT`)
- Constructor — places a block, places a fluid, launches fireworks, or (toggle) drops items; 9 filter slots; scheduling Default / Round robin / Random; fuzzy; status indicators — `C/constructordestructor/Place*ConstructorStrategy.java`, `C/constructordestructor/ItemDropConstructorStrategy*.java`
- Destructor — breaks blocks (Fortune or Silk Touch via upgrades), picks up fluid sources, or (toggle) picks up item entities instead; block filter Allowlist/Blocklist (an empty blocklist breaks everything) — `C/constructordestructor/BlockBreakDestructorStrategy.java`, `FluidBreakDestructorStrategy.java`, `ItemPickupDestructorStrategy.java`
- Disk Interface — transfer mode Insert into network / Extract from network; 9-slot filter with Allowlist/Blocklist and fuzzy; finished disks move to the output slots; the per-operation quota depends on storage type and Stack Upgrade — `C/storage/diskinterface/*`, `L`
- Storage Monitor — SHIFT+right-click to configure; left-click takes a stack; SHIFT+left-click takes one; right-click inserts the held item; right-click again within 0.5 s inserts every matching item from your inventory; fluids extract into a bucket from the network or your inventory, and right-click with a fluid container inserts it — `C/storagemonitor/StorageMonitorBlock.java`, `C/storagemonitor/StorageMonitorInsertTracker.java` (`MAX_DELAY = 500`), `L`
- Detector — one filter resource and amount (fluids in buckets); modes Under / Equal / Above; fuzzy; redstone output changes at most once per second — `C/detector/DetectorBlockEntity.java`, `C/detector/FuzzyDetectorAmountStrategy.java`, `CHANGELOG.md` (milestone.2.9)
- Platform transfer backends — Item and Fluid handlers [NeoForge]; Transfer API ItemStorage/FluidStorage [Fabric] — `N/importer/*`, `N/exporter/*`, `F/importer/*`, `F/exporter/*`
- Extension points for addon resource types — importer, exporter, external storage, destructor, constructor, storage monitor and pattern sink strategies are registries (used by the Mekanism addon) — `C/AbstractModInitializer.java` (`register*Strategy*`)

## Redstone

- Redstone mode — Ignore / High / Low, with per-device help text; on Grid, Crafting Grid, Pattern Grid, Controller, storages, Importer, Exporter, Constructor, Destructor, Interface, External Storage, Disk Interface, Relay, Wireless Transmitter, Network Transmitter, Security Manager, Storage Monitor, Autocrafter Manager, Autocrafting Monitor and the Portable Grid block — `C/support/RedstoneMode.java`, `C/support/widget/RedstoneModeSideButtonWidget.java`, `C/support/AbstractFilterScreen.java`
- Controller redstone — an inactive Controller stops supplying energy — `L` (`controller.redstone_mode_help`)
- Security Manager redstone — when inactive the network is unprotected — `L`
- Relay as a redstone switch — turns whole network sections on or off — `L` (`item.refinedstorage.relay.help`)
- Detector output — see Import/export — `C/detector/*`
- Autocrafter redstone lock modes — pulse / high / low — `C/autocrafting/autocrafter/LockMode.java`

## Wireless and distance

- Wireless Transmitter range — base 16 [config: `wirelessTransmitter.baseRange`] plus Range Upgrades; Creative Range Upgrade is unlimited in the same dimension; several transmitters add coverage; the GUI shows the range or "Inactive" with an animation — `C/networking/BaseWirelessTransmitterRangeModifier.java`, `C/networking/WirelessTransmitterScreen.java`, `C/support/packet/s2c/WirelessTransmitterDataPacket.java`
- Wireless item binding — use on any network device; the tooltip shows "Bound to x, y, z" or "Unbound"; the model changes when bound or active — `C/support/network/item/*`, `L` (`network_item.*`)
- Network Transmitter / Receiver / Card — cable-free links within or across dimensions; status Inactive / Missing Network Card / Transmitting (distance) / Unreachable; retries automatically; both ends must be chunk-loaded — `C/networking/NetworkTransmitterBlockEntity.java`, `C/networking/NetworkTransmitterState.java`, `C/support/packet/s2c/NetworkTransmitterStatusPacket.java`
- Relay — Pass-through (default on); when off, separately: Pass energy (hidden if energy isn't required), Pass storage (with filter, fuzzy, access mode, insert/extract priority), Pass security, Pass autocrafting (pattern filter); cycle detection — `C/networking/RelayBlockEntity.java`, `C/networking/RelayScreen.java`, `D/networking/relay.adoc`
- Relay can be rotated with the Wrench — `CHANGELOG.md` (beta.1 fixed)

## Security/permissions

- Lock-down on placement — an active Security Manager blocks every action for every player, including whoever placed it, until cards are added; the placer can always open the Manager; server ops bypass everything — `C/security/PlatformSecurityNetworkComponentImpl.java` (`isOp`), `D/security/security-manager.adoc`, `L` (`security_manager.help`)
- Permissions — Insert, Extract, Autocrafting (start/cancel/view), Open (device GUIs), Build (place, break, rotate, dismantle), Security (manage security) — `C/security/BuiltinPermission.java`, `L` (`permission.*`)
- Permission registry — addons can add permissions — `C/AbstractModInitializer.java` (`registerPermissions`)
- Security Card GUI — a checkbox per permission, a "Reset" button per changed permission, and "Modified" markers; the tooltip shows which permissions changed — `C/security/AbstractSecurityCardScreen.java`, `C/support/packet/c2s/SecurityCard*Packet.java`
- Bind a card to another online player — `C/support/packet/c2s/SecurityCardBoundPlayerPacket.java`
- Fallback Security Card slot — permissions for players with no card; without one, everything is denied — `L` (`security_manager.fallback_security_card_slot_hint`, `no_fallback_security_card_consequence`)
- Several Managers or cards combine — an action is denied if any of them denies it — `D/security/security-manager.adoc`
- Enforced actions with denial messages — open, insert, extract, place, break, rotate, dismantle — `L` (`misc.refinedstorage.no_permission.*`), `N/ModInitializer.java` (`registerSecurityBlockBreakEvent`), `F/security/NetworkNodeBreakSecurityEventListener.java`
- Security cards cost energy (per card, configurable) — config `securityCard.energyUsage`, `fallbackSecurityCard.energyUsage`

## Energy

- Energy toggle — [config: `requireEnergy`] (default true); when false, Controllers are optional, creative variants are hidden and wireless and portable items need no charge — `L` (`requireEnergy.tooltip`), `CHANGELOG.md` 2.0.3
- Per-device energy draw per tick, all configurable — Cable 0, Importer 2, Exporter 2, Interface 4, External Storage 6, Detector 2, Grid 10, Crafting Grid 14, Pattern Grid 14, Disk Drive 10 + 4/disk, Disk Interface 6 + 4/disk, Constructor 3, Destructor 3, Storage Monitor 4, Network Receiver 8, Network Transmitter 32, Security Manager 16, Relay 8 in + 8 out, Autocrafter 4 + 2/pattern, Autocrafter Manager 16, Autocrafting Monitor 16, Wireless Transmitter 16, Storage Blocks 2/4/6/8/16, Fluid Storage Blocks 2/4/6/8/16 — `C/content/DefaultEnergyUsage.java`
- Upgrade energy — Speed 4 (each), Stack 16, Fortune 10/12/14, Silk Touch 16, Regulator 16, Range 8, Creative Range 0, Autocrafting 8 — `C/content/DefaultEnergyUsage.java`
- Capacities — Controller 1000; Wireless Grid, Portable Grid and Wireless Autocrafting Monitor 1000 each, costing 5 per open, insert, extract or cancel — `C/content/DefaultEnergyUsage.java`
- Controller fallback — a drained Controller hands over to another; with none left the network shuts down — `D/networking/controller.adoc`
- Items that hold energy can be charged in other mods' chargers (Controller item, Wireless Grid, Portable Grid, Wireless Autocrafting Monitor) — `N/ModInitializer.java` (`Capabilities.EnergyStorage.ITEM`), `F/ModInitializerImpl.java` (`EnergyStorage.ITEM`)
- Energy bar on items; creative items show ∞; a fully charged Controller is in the creative tab — `C/support/energy/EnergyItemHelperImpl.java`, `CHANGELOG.md`
- Energy bar with a stored/capacity tooltip in the Controller and Portable Grid GUIs — `C/support/energy/EnergyInfo.java`, `C/controller/ControllerScreen.java`
- Energy backends — FE [NeoForge]; Team Reborn Energy, bundled [Fabric] — `N/support/energy/*`, `F/support/energy/*`, `refinedstorage-fabric/build.gradle.kts`

## Screens (every setting/button per screen)

- Controller — energy bar and redstone mode — `C/controller/ControllerScreen.java`
- Disk Drive — 8 disk slots, fill bar, 9 filter slots; Redstone, Filter mode (empty-allowlist warning), Fuzzy, Access mode, Priority (insert/extract), Void excess (warning) — `C/storage/diskdrive/DiskDriveScreen.java`, `C/storage/AbstractStorageScreen.java`
- Storage Block (item and fluid) — fill bar, 9 filters; same buttons as the Disk Drive — `C/storage/storageblock/StorageBlockScreen.java`
- External Storage — 9 filters; Redstone, Filter mode, Fuzzy, Access, Priority, Void excess — `C/storage/externalstorage/ExternalStorageScreen.java`
- Grid / Crafting Grid / Pattern Grid / Wireless Grid / Portable Grid — search box with help icon (@ # $ syntax), Redstone (when the device has it), View type, Resource type, Sorting direction, Sorting type, Auto-selected search, Synchronisation mode (addon-gated), Screen size; Crafting Grid adds matrix, result and 2 clear buttons; Pattern Grid adds type tabs, pattern in/out slots, Create pattern, Clear, a per-type editor, and (crafting) a Fuzzy checkbox; Portable Grid adds a disk slot and energy bar — `C/grid/screen/*`, `C/autocrafting/patterngrid/PatternGridScreen.java`, `C/storage/portablegrid/PortableGridScreen.java`
- Alternatives / amount screen (processing slot) — amount field, ±1/10/64, Reset, Set, tag checkboxes, expand per tag, tag search — `C/autocrafting/patterngrid/AlternativesScreen.java`, `L`
- Generic amount screens — Reset and Set (confirm), ±1/10/64 buttons, scroll wheel changes the value, ENTER confirms, ESC cancels, invalid amounts shown in red; used for Interface slots, the Regulator Upgrade and the Detector — `C/support/amount/*`
- Priority screen — ±1/5/10, Reset, Set — `C/support/amount/PriorityScreen.java`
- Importer — 9 filters, upgrade slots; Redstone, Filter mode, Fuzzy — `C/importer/ImporterScreen.java`
- Exporter — 9 filters with status icons, upgrades; Redstone, Fuzzy, Scheduling mode — `C/exporter/ExporterScreen.java`
- Constructor — 9 filters with status, upgrades; Redstone, Fuzzy, Scheduling mode, Drop items — `C/constructordestructor/ConstructorScreen.java`
- Destructor — 9 block filters, upgrades; Redstone, Filter mode, Pickup items — `C/constructordestructor/DestructorScreen.java`
- Interface — 9 amount filters with status, 9 buffer slots, upgrade slot; Redstone, Fuzzy — `C/iface/InterfaceScreen.java`
- Disk Interface — 3 in and 3 out disk slots, 9 filters, upgrades; Redstone, Transfer mode, Filter mode, Fuzzy — `C/storage/diskinterface/DiskInterfaceScreen.java`
- Detector — one filter, amount field (amount screen); Fuzzy, Mode (under/equal/above) — `C/detector/DetectorScreen.java`
- Storage Monitor — one filter; Redstone, Fuzzy — `C/storagemonitor/StorageMonitorScreen.java`
- Relay — 9 filters; Redstone, Pass-through, Pass energy, Pass security, Pass autocrafting, Pass storage, Filter mode, Fuzzy, Access mode, Priority (buttons hide or show with pass-through and component state) — `C/networking/RelayScreen.java`
- Wireless Transmitter — range or "Inactive" display, 4 upgrade slots; Redstone — `C/networking/WirelessTransmitterScreen.java`
- Network Transmitter — Network Card slot, status line with animation; Redstone — `C/networking/NetworkTransmitterScreen.java`
- Security Manager — 18 card slots, a Fallback card slot with hint; Redstone — `C/security/SecurityManagerScreen.java`
- Security Card / Fallback Security Card — permission checkboxes, per-permission Reset, bound-player button (Security Card only), screen size — `C/security/AbstractSecurityCardScreen.java`
- Regulator Upgrade — one filter plus amount — `C/upgrade/RegulatorUpgradeScreen.java`
- Autocrafter — 9 pattern slots, 4 upgrade slots, editable name (Edit button), chained/locked indicators; Lock mode (head only), Priority, Visible to Manager — `C/autocrafting/autocrafter/AutocrafterScreen.java`
- Autocrafter Manager — search box, groups per Autocrafter, pattern slots; Redstone, View type, Search mode, Auto-selected, Screen size — `C/autocrafting/autocraftermanager/AutocrafterManagerScreen.java`
- Autocrafting Monitor (block and wireless) — task sidebar, task detail grid, Cancel, Cancel all; Redstone (block only) — `C/autocrafting/monitor/AutocraftingMonitorScreen.java`
- Autocrafting Preview — amount field, ±1/10/64, Max, Notify, List/Tree toggle, Zoom (full-screen tree), Start, request list for multi-requests — `C/autocrafting/preview/AutocraftingPreviewScreen.java`
- Side-button help — hold SHIFT over a side button or slot for help text ("Press SHIFT for help") — `C/support/tooltip/HelpClientTooltipComponent.java`, `L` (`misc.refinedstorage.press_shift_for_help`)
- Filter-slot hints — the tooltip shows what left-click (item) and right-click (fluid in a container) will set; "Click to clear", "SHIFT click to clear", "Click to configure amount"; SHIFT-clicking items from your inventory fills filter slots — `C/support/AbstractBaseScreen.java`, `C/support/containermenu/TransferManager.java`, `L` (`filter_slot.*`)
- Upgrade-slot tooltip — "Empty upgrade slot" plus the list of applicable upgrades — `L`, `C/upgrade/UpgradeSlot.java`
- Tenth-anniversary screen — opened from the Grid title ribbon during March; enables or disables the cape — `C/cape/TenthAnniversaryScreen.java`, `AbstractGridScreen.java` (`shouldDisplayTenthAnniversaryRibbon`)

## Keybindings

- Focus search bar — TAB; toggles focus when the box is not auto-selected — `N/ClientModInitializer.java`, `F/ClientModInitializerImpl.java`
- Clear Crafting Grid matrix to network — CTRL+X (GUI context) [NeoForge-only] — `N/ClientModInitializer.java`
- Clear Crafting Grid matrix to inventory — unbound [NeoForge-only] — `N/ClientModInitializer.java`
- Open Wireless Grid — unbound; also works with the creative variant — `C/AbstractClientModInitializer.java` (`handleInputEvents`)
- Open Portable Grid — unbound — same
- Open Wireless Autocrafting Monitor — unbound — same
- Mac — CMD replaces CTRL in click modifiers and help text — `L` (`cmd_click_*`), `C/util/ClientPlatformUtil` (`isCommandOrControlDown`)
- Fixed (not rebindable) inputs — UP and DOWN for search history, ENTER and ESC in search and amount fields, right-click to clear search, R and U over slots [addon: JEI/REI/EMI] — `C/support/widget/SearchFieldWidget.java`, `C/support/amount/AbstractAmountScreen.java`

## Config options

- File and UI — one common TOML on NeoForge with an in-game config screen; AutoConfig/Cloth Config on Fabric, opened from ModMenu — `N/PlatformImpl.java` (`ModConfig.Type.COMMON`), `F/ConfigImpl.java`, `F/modmenu/ModMenuApiImpl.java`
- `screenSize` (STRETCH default), `maxRowsStretch` (3–256, default 256), `smoothScrolling` (true), `searchBoxAutoSelected` (false), `requireEnergy` (true), `autocraftingNotification` (true), `autocraftingPreviewStyle` (LIST/TREE), `tenthAnniversaryCape` (false) — `N/ConfigImpl.java`, `F/ConfigImpl.java`
- `debug` (false) — network debug overlay and Debug Stick in the creative tab; restart required [NeoForge-only; always false on Fabric] — `N/ConfigImpl.java`, `F/ConfigImpl.java` (`isDebug` returns false)
- `grid.*` — `largeFont`, `preventSortingWhileShiftIsDown`, `detailedTooltip`, `rememberSearchQuery`, `energyUsage`, `synchronizer`, `resourceType`, `sortingDirection`, `sortingType` (QUANTITY default), `viewType` (ALL) — `N/ConfigImpl.java`
- `craftingGrid.energyUsage`, `craftingGrid.craftingMatrixCloseBehavior` (NONE / CLEAR_TO_NETWORK / CLEAR_TO_INVENTORY) — same
- `patternGrid.energyUsage` — same
- `controller.energyCapacity` (minimum 1) — same
- `cable`, `importer`, `exporter`, `iface`, `externalStorage`, `detector`, `constructor`, `destructor`, `storageMonitor`, `networkReceiver`, `networkTransmitter`, `securityCard`, `fallbackSecurityCard`, `securityManager`, `autocraftingMonitor` `.energyUsage` — same
- `diskDrive.energyUsage`, `diskDrive.energyUsagePerDisk`; `diskInterface.energyUsage`, `diskInterface.energyUsagePerDisk` — same
- `storageBlock.{1k,4k,16k,64k,creative}EnergyUsage`; `fluidStorageBlock.{64b,256b,1024b,4096b,creative}EnergyUsage` — same
- `upgrade.*` — energy for speed, stack, fortune1/2/3, silkTouch, regulator, range, creativeRange and autocrafting upgrades; `rangeUpgradeRange` — same
- `wirelessGrid.{energyCapacity, openEnergyUsage, insertEnergyUsage, extractEnergyUsage}` — same
- `wirelessTransmitter.{energyUsage, baseRange}` — same
- `portableGrid.{energyCapacity, openEnergyUsage, insertEnergyUsage, extractEnergyUsage}` — same
- `relay.{inputNetworkEnergyUsage, outputNetworkEnergyUsage}` — same
- `autocrafter.{energyUsage, energyUsagePerPattern}` — same
- `autocrafterManager.{energyUsage, searchMode, viewType}` — same
- `wirelessAutocraftingMonitor.{energyCapacity, openEnergyUsage, cancelEnergyUsage, cancelAllEnergyUsage}` — same
- Client UI state is stored in the same config — the Grid sort, view, resource type and sync choices are saved there per player — `D/viewing-resources/grid.adoc`

## Commands

- None — no `/` commands are registered on either platform (no `Commands.literal`, `RegisterCommandsEvent` or `CommandRegistrationCallback` in the source) — searched across `refinedstorage-*/src`
- Debug tooling instead — Debug Stick and network debug overlay [NeoForge-only] [config: `debug`] — `N/debug/*`

## Integrations

- In the main repo: ModMenu entry and config button [Fabric-only] — `F/modmenu/ModMenuApiImpl.java`, `refinedstorage-fabric/src/main/resources/fabric.mod.json`
- In the main repo: Cloth Config / AutoConfig screen, bundled [Fabric-only] — `refinedstorage-fabric/build.gradle.kts`
- In the main repo: Team Reborn Energy, bundled [Fabric-only] — same
- In the main repo: NeoForge config screen [NeoForge-only] — `CHANGELOG.md` (milestone.4.5)
- In the main repo: capabilities and transfer API exposure (Interface item and fluid handler, Disk Drive and Disk Interface item handler, Pattern Grid input, Controller and Portable Grid energy, item energy) — `N/ModInitializer.java`, `F/ModInitializerImpl.java`
- In the main repo: any `c:tools/wrench` item acts as a wrench — `D/utility/wrench.adoc`
- In the main repo: `@mod` search uses the mod-list display name — `N/grid/view/ForgeItemResourceRepositoryMapper.java` (`ModList`)
- In the main repo: public API registries for addons — resource types, storage types, permissions, slot references (Curios/Trinkets), grid synchronisers, insertion hints, resource rendering, disk models, upgrade destinations, wireless range modifiers, importer/exporter/constructor/destructor/external-storage/pattern-sink strategies — `refinedstorage-common-api/`, `C/AbstractModInitializer.java`, `C/AbstractClientModInitializer.java`
- JEI [addon: refinedstorage-jei-integration] — recipe transfer into the Crafting Grid (partial fill plus autocraft missing) and the Pattern Grid (shows autocraftable ingredients); R/U on grid and filter slots; one-way or two-way search sync; ghost-ingredient drag into filter slots; exclusion zones — `D/addons/just-enough-items.adoc` (from the main repo's docs; addon repo not cloned)
- REI [addon: refinedstorage-rei-integration] — same as JEI plus item grouping (collapsible entries) — `D/addons/roughly-enough-items.adoc`
- EMI [addon: refinedstorage-emi-integration] — same feature set as JEI — `D/addons/emi.adoc`
- Curios [addon, NeoForge] — two dedicated slots for Wireless Grid / Crafting Grid (incl. creative) and Portable Grid; open with keybinds — `D/addons/curios.adoc`
- Trinkets [addon, Fabric] — the same two slots — `D/addons/trinkets.adoc`
- Mekanism [addon: refinedstorage-mekanism-integration] — Chemical Storage Disks and Blocks 64B / 256B / 1024B / 8192B / Creative (uncraft and upgrade like others); Importer, Exporter, External Storage and Storage Monitor handle chemicals; chemical processing patterns — `D/addons/mekanism.adoc`
- Quartz Arsenal [addon] — Wireless Crafting Grid and Creative Wireless Crafting Grid (energy for open, insert, extract and craft; keybind) — `D/addons/quartz-arsenal.adoc`, `D/wireless-networking/wireless-crafting-grid.adoc`
- Quartz Accessories [addon, unreleased] — Block of Quartz Enriched Iron and Block of Quartz Enriched Copper (these blocks were removed from the main mod) — `D/addons/quartz-accessories.adoc`
- Community addons listed in docs (not official) — Extra Disks, Interdimensional Wireless Transmitter, Universal Grid, Advanced Peripherals (CC:Tweaked), ExtraStorage (tiered autocrafters, 18-slot importer/exporter, bigger storage), Refined Types (FE / Source / Souls resources), Cable Tiers, Omni Grid, Ender IO conduits, RS: Requestify (stock keeper), Pipe Connector, Step Crafter, Replication RS2 Bridge, Refined Equivalence (EMC) — `D/addons/community-addons.adoc`
- Not present in the main repo — Jade, WTHIT or TOP probe overlays; ComputerCraft peripherals; Patchouli (removed); covers or facades — searched code and build files

## In-world rendering and HUD

- Disk LEDs on the Disk Drive, Disk Interface and Portable Grid — per-disk state: none / inactive / normal / near capacity (≥75%) / full — `C/storage/diskdrive/AbstractDiskDriveBlockEntityRenderer.java`, `refinedstorage-storage-api/.../StorageState.java`, `StateTrackedStorage.java` (`NEAR_CAPACITY_THRESHOLD = .75`)
- Custom disk models per disk item, with fluid disks looking different; addons can register their own — `C/AbstractClientModInitializer.java` (`registerDiskModels`)
- Storage Monitor face — renders the configured resource and its live count — `C/storagemonitor/StorageMonitorBlockEntityRenderer.java`
- Active and inactive block states with emissive textures on active devices — `C/support/AbstractActiveColoredDirectionalBlock.java`, `CHANGELOG.md` (milestone.2.1)
- Controller block shows four energy levels — `C/controller/ControllerEnergyType.java`
- Connected cable models (baked; IO heads attach to cable) — `N/networking/CableBakedModel.java`, `F/networking/CableBakedModel.java`
- Item model predicates — Controller item energy level; Wireless Grid and Wireless Autocrafting Monitor active state; Security Card, Network Card and Configuration Card bound or configured state — `N/ClientModInitializer.java` (`registerItemProperties`), `C/**/*ItemPropertyFunction.java`
- Pattern item shows its output in GUIs while SHIFT is held — `N/mixin/AbstractGuiGraphicsMixin.java`, `F/mixin/AbstractGuiGraphicsMixin.java`
- Task-completed toast (resource icon and amount) — `C/autocrafting/TaskCompletedToast.java`
- Network debug overlay — colours devices by network id [NeoForge-only] [config: `debug`] — `N/debug/NetworkDebugRenderer.java`
- Tenth-anniversary cape layer on players [config: `tenthAnniversaryCape`] — `C/cape/TenthAnniversaryCapeRenderer.java`, `N/cape/*`, `F/cape/*`
- Wrench sound "Wrench used" (with subtitle) — `C/content/Sounds.java`, `L` (`refinedstorage.subtitle.wrench`)
- Storage Monitor plays the item-pickup sound on extract — `C/storagemonitor/StorageMonitorBlockEntity.java`
- No HUD overlay — nothing persistent is drawn on the in-game HUD apart from toasts — client initializers

## Misc quality-of-life

- Help text — every block and item has a help tooltip (shown with SHIFT) — `L` (`item.refinedstorage.*.help`), `C/support/tooltip/HelpClientTooltipComponent.java`
- Custom names — every device keeps its anvil name, shown as the GUI title — `C/support/network/AbstractBaseNetworkNodeContainerBlockEntity.java` (`Nameable`)
- Recolouring — crouch-right-click with a dye recolours a placed device and uses up the dye (not in creative); crafting device + dye also recolours and keeps the configuration — `C/content/BlockColorMap.java` (`updateColor`), `C/support/AbstractBaseBlock.java` (`tryUpdateColor`), `C/support/RecoloringRecipe.java`
- Dismantle keeps everything — the dismantled item stores the block entity's data (disks, patterns, filters, upgrades) — `C/support/AbstractBaseBlock.java` (`dismantle`)
- Configuration Card can copy and transfer a configured Regulator Upgrade — `CHANGELOG.md` (milestone.4.8)
- Advancements (27, including root) — root, Connecting, Drives, Interfacing with disks, Storing items, Viewing your storage, Upgrading your Grid, Storing fluids, Importing, Exporting, Upgrading, Interface to the world, Storing externally, Detecting, Construction, Destruction, Wireless, Better than a barrel, No cables required, Portable storage, Security, Conditional connecting, Autocrafting, Managing patterns, Monitoring, Wireless monitoring, Autocrafting on demand — `L` (`advancements.*`)
- Recipes use common `c:` tags — `R/*.json`
- Grid upgrade recipes — Grid + Advanced Processor + crafting table → Crafting Grid; Grid + Advanced Processor + Pattern → Pattern Grid — `R/crafting_grid.json`, `R/pattern_grid.json`
- In-repo player documentation and guides (getting started, autocrafting, processing, fluid autocrafting, filled buckets, keeping in stock, storing externally, long distances) — `D/guides/*`
- Translations (Crowdin) — `crowdin.yml`
- Not in 2.x (3.x / MC 26.1 `develop` only) — pinned resources row in the Grid (drag to pin or unpin); autocrafting tasks auto-pinned with progress on hover; Detector debounced to 1 s of stable state — `rs2-develop/CHANGELOG.md` (3.1.0, 3.2.0, Unreleased)
