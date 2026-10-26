# Ergonomics plan

## Purpose and authority

This plan turns the 2026-10-26 ergonomics audit against Applied Energistics 2 (AE2), Refined
Storage 2 (RS) and Super Factory Manager (SFM) into ordered, loopable slices. It lists **every**
gap the audit found that the [UX plan](UX_PLAN.md), the [parity plan](PARITY_PLAN.md) and the
[playtest plan](PLAYTEST_PLAN.md) do not already cover. The goal is that a player who knows any
of those mods finds each tool they reach for, in the place they expect it, and that the
SFM-style program path keeps its depth.

The audit compared code at `7002c7b` with a feature inventory read from each mod's own source
for Minecraft 1.21.1 (registries, screens, packets, key mappings, configs, integrations and the
English lang file). The inventories, with a source path for every line, are evidence for this
plan:

| Mod | Branch and commit | Inventory |
| --- | --- | --- |
| AE2 | `1.21.1` @ `db17504` (NeoForge 21.1.169) | [ae2.md](evidence/ergonomics-inventories-2026-10-26/ae2.md) |
| RS2 | `support/2.x` @ `0dec63a` (mod 2.0.9) | [rs2.md](evidence/ergonomics-inventories-2026-10-26/rs2.md) |
| SFM | `main` @ `e2ba833`, release projection `sfm-4.35.0/mc-1.21.1` | [sfm.md](evidence/ergonomics-inventories-2026-10-26/sfm.md) |

Findings M1–M49 came from a first pass (RS2 docs, SFM grammar and templates, AE2 from memory)
and were corrected against the inventories; M59–M118 come from the inventories. Lines tagged
experimental, dev-only, lang-only or addon-only in an inventory were not counted as gaps unless
a row says so.

[SPEC.md](../SPEC.md) still owns behavior. Slices marked **needs gate** change a SPEC or ADR
contract, or add a dependency, and must not start until their gate is **resolved** by the user.
[IMPLEMENTATION.md](IMPLEMENTATION.md) keeps owning acceptance evidence. The deliberate
differences under [Not in scope](#not-in-scope) stay.

## How to run one loop iteration

Same loop as the earlier plans:

1. Pick the first `[ ]` slice in your lane whose gate is resolved (or has none).
2. Read the files and ADRs listed for it. Follow [AGENTS.md](../AGENTS.md): behavioral tests,
   affected docs, no weakened checks. A slice that says "check first" starts by confirming the
   gap still exists. If it does not, tick the box with the evidence and move on.
3. Run `python3 scripts/verify.py`. For a slice that changes a screen, block or in-world
   interaction, also run the graphical client under the lock (see [TESTING.md](TESTING.md))
   and **look at** the scale 1 and 4 screenshots. Screens use the panel kit
   ([ADR 0064](decisions/0064-panel-kit.md)); a screen off the kit's grid fails.
4. Commit on `main` with the slice ID in the subject (for example `Q3: Crafter screen`).
5. Tick the box, add the commit and evidence path, and record follow-ups as new slices.
6. Stop and ask when the next slice needs an unresolved gate or reveals a SPEC/ADR conflict.

## Going fast: lanes

Slices in different lanes touch different files and can run as parallel worktree agents.
Slices in one lane run in order. Graphical client runs are serialized across agents (one
display, one lock), so batch the screenshot step at the end of a slice.

| Lane | Slices | Main files | Gates |
| --- | --- | --- | --- |
| R — Reliability | Q1 | `WorldNetworks`, `WorldWireReader`, ADR 0011 | PD7 |
| K — Code health | Q52–Q60 | `WorldCraftingJobs`, `CraftingJobs`, `TerminalScreen`, `client/ui`, energy buffers, `WasmProgram` | None |
| C — Crafting | Q2–Q8, Q68–Q71 | `CrafterBlock`, `CrafterEntity`, `TerminalCrafting`, `Pattern`, `CraftingPlanner`, `WorldMachinePresets`, `client/integration/jei` | QD3, QD4, QD18, QD19, QD21 |
| L — Labels and copying | Q9–Q13, Q94–Q95 | `LabelToolItem`, `LabelToolContent`, `LabelToolScreen`, `CablePortBlock`, new client overlays | None |
| T — Terminal and wireless | Q14–Q18, Q61–Q67 | `TerminalScreen` (after Q55), `TerminalMenu`, `TerminalJobs`, `platform/wireless`, `client/integration` | QD1, QD2, QD20 |
| D — Device consistency | Q19–Q27, Q72–Q79 | `platform/upgrade`, `EnergyBankBlock`, `DriveMenu`, `CellItem`, `SignalMenu`, `OverviewMenu`, `BufferPolicy`, `WorldStorageRoutes`, new wrench | UD3, QD13, QD14, QD23 |
| B — New blocks | Q28–Q33, Q96–Q98 | `NetworkCableBlock`, `WorldWireReader`, new blocks | QD5–QD8, QD14, QD24 |
| G — World interaction | Q34–Q35 | new blocks | QD9 |
| X — Distance | Q36–Q37 | `platform/wireless`, `LinkModule`, `LinkPairing` | QD10 |
| S — Language v3 and editor | Q38–Q47, Q88–Q93 | `FriendlyCompiler`, `ProgramIr`, `ProgramEvaluator`, `WorldPrograms`, `ProgramScreen`, `ProgramTextEditor`, `sdk/rust` | QD11, QD12, QD15 |
| I — Optional integrations | Q48–Q49 | new optional modules | QD16, QD17 |
| H — Help and onboarding | Q50, Q81–Q84 | `GuideScreen`, `GuideContent`, advancements data, lang files | None |
| A — Admin and config | Q85–Q87 | `FactoryCoreConfig`, admin commands, `ADMIN_GUIDE.md` | QD22 |
| N — Nits | Q99–Q103 | spread across lanes T, C, D, L/S and A | None |
| V — Verification | Q51 | evidence | None |

Recommended start: Q52 (a crafting bug) first, then lanes L and the ungated parts of C, T and
D in parallel, and the user answers PD7 and the QD gates. Q1 goes first in lane R once PD7 is
resolved, because network flicker breaks every other lane's GameTests now and then.

Cross-lane order: Q52 and Q53 before Q8 (all three change keep-stock in `WorldCraftingJobs`).
Q55 before any slice that adds to the terminal screen (Q3, Q7, Q14, Q16–Q18, Q61–Q67), so those
features land on the split, kit-based screen rather than growing the current one.

## Audit findings

"Not found" means a search of `src/main/java` at `7002c7b` found no code for it.

| ID | Finding | Compared with | Evidence |
| --- | --- | --- | --- |
| M1 | A chunk loading next to a network rebuilds it, so a player walking nearby briefly takes it offline. Moves and programs fail with "offline", and older GameTests fail now and then. | AE2/RS networks stay up | PARITY_PLAN PD7 (open) |
| M2 | The Crafter has no screen. Its patterns, upgrades, served machines and idle reason are hidden; empty-hand use prints an action-bar line, and sneak-use takes back only the **last** pattern. | AE2 Pattern Provider, RS Autocrafter | `CrafterBlock.java` `useItemOn`/`useWithoutItem` |
| M3 | There is no network-wide list of patterns. Finding which Crafter holds a pattern means visiting each one. | AE2 Pattern Access Terminal, RS Autocrafter Manager | Not found |
| M4 | An encoded pattern cannot be loaded back into the terminal to edit, or wiped back to a blank. | AE2: put a pattern in the encoding terminal; clear by crafting | `TerminalCrafting.java` only encodes onto blanks |
| M5 | JEI transfer fills only the crafting-table grid. Furnace, blasting, smoking and modded machine recipes are typed into Inputs/Outputs by hand. | AE2/RS: any recipe category into a processing pattern | `FactoryCoreJeiPlugin.java`: `RecipeTypes.CRAFTING` only |
| M6 | No stonecutter or smithing patterns. | AE2 has both; RS Pattern Grid has both | Not found |
| M7 | Jobs has no "as many as possible" amount. A player who has enough for 37 must find 37 by trial. | RS "max" in the craft dialog; AE2 shows what is possible | `TerminalJobs.java` |
| M8 | An Exporter's **Keep** waits when storage runs out; it never asks for a craft. | AE2 Crafting Card on export bus/interface; RS Autocrafting upgrade and Regulator upgrade | No crafting call in `WorldMachinePresets.java` |
| M9 | Labelling N machines takes N clicks. There is no "label everything touching this cable run". | SFM Label Gun contiguous modifier | `LabelToolItem.java` |
| M10 | The Label Tool cannot pick the label off a labelled machine; the player retypes it. | SFM Label Gun pick-block modifier | `LabelToolItem.java` |
| M11 | Labels are invisible in the world. A player cannot see which machines carry which label, or which are unlabelled. | SFM Label Gun overlay | No world-render code in `src/main/java` |
| M12 | There is no view of what a cable run reaches: cables, ports and the machines a program can use. | SFM Network Tool overlay; AE2 Network Tool | Not found |
| M13 | Settings copy covers cable ports, Importers, Exporters and Storage Interfaces only. Buffer, Signal, Drive, Crafter, Gateway and Program Controller settings cannot be copied. | AE2 Memory Card, RS Configuration Card | PLAYER_GUIDE "Label Tool"; `LabelToolContent.java` |
| M14 | No key opens the Wireless Terminal and there is no accessory slot: the handheld must be in the hand. | AE2 hotkeys (pressing again closes) and Curios; RS2 keybinds, with Curios/Trinkets slots from add-ons | No `KeyMapping` in `src/main/java`; no Curios code |
| M15 | Wireless range is fixed at 64 blocks with no upgrade. | RS Range Upgrade; AE2 Wireless Booster | `AccessPointEntity.RANGE = 64` |
| M16 | JEI only; no EMI or REI support. | AE2 (1.21.1) supports REI and EMI and has **no** JEI plugin; RS2 supports JEI, REI and EMI through add-ons | `client/integration/jei` only |
| M17 | Terminal search is not synced with the recipe viewer's search box. | AE2 sync with REI/EMI search; RS2 one-way or two-way sync (add-ons) | No JEI filter-text calls in `platform/client` |
| M18 | Missing terminal conveniences (check each first): pinned or recently crafted items at the top; Shift-double-click to deposit every matching stack; an auto-focus-search option; middle-click pick-block or restock from the network while the terminal is closed. | AE2 pins up to 9 auto-crafted items while their jobs run; AE2 Shift-double-click; AE2/RS auto-focus option. Pick-block restock is in neither inventory; keep it as our own idea | Not found |
| M19 | Upgrades are used on the block and come back only when it is broken. No screen shows an upgrade slot, and none can be taken out. | AE2/RS upgrade slots in each screen | `ThroughputContent.java` ("installed in place") |
| M20 | Energy Banks are upgraded by breaking them and using a module from the offhand; Drives upgrade cells in place. The bank has no screen. | AE2/RS upgrade in a slot | PLAYER_GUIDE "Energy Bank" |
| M21 | The Controller has no energy buffer of its own; a first network also needs a Fuel Generator and an Energy Bank. | AE2 and RS controllers store energy | UX_PLAN UD3 (open) |
| M22 | No wrench: an Importer, Exporter or Interface cannot be turned to face another block, and nothing picks a device up in one click. | AE2 and RS wrenches | Not found |
| M23 | A cell partition cannot be filled from what the cell already holds. | AE2 Cell Workbench "partition from contents" | `DriveMenu.java` |
| M24 | A Signal Interface watches one resource, not a filter or tag. | AE2 Level Emitter with fuzzy card; RS Detector | PARITY_PLAN C1 deviation |
| M25 | Check first: the Overview may not show energy use per tick, or which devices use it. | AE2 network status; RS controller | `OverviewMenu.java` |
| M26 | No creative items: creative energy source, creative item/fluid cells, creative wireless terminal. | RS Creative Controller, disks, grid; AE2 creative cells and energy cell | Not found |
| M27 | No Infinite fluid cell. | AE2 and RS have top-tier fluid storage | PLAYER_GUIDE cell table; ADR 0048 |
| M28 | No in-world storage display that shows a count and takes or inserts on click. | AE2 Storage Monitor, RS Storage Monitor | Not found |
| M29 | Cables cannot be coloured. Two networks whose cables touch become one network with two controllers and stop. | AE2 and RS coloured cables | No `DyeColor` in `src/main/java` |
| M30 | No facades or covers to hide cables in walls. | AE2 facades, RS covers, SFM cable facades | Not found |
| M31 | No redstone-switched cable segment. | RS Relay | Not found |
| M32 | No portable storage. | RS Portable Grid, AE2 portable cells | Not found |
| M33 | No bulk cell tools: a block to empty or fill cells, or a placeable storage block. | RS Disk Interface and Storage Blocks; AE2 IO Port | Not found |
| M34 | No block placing, item dropping, block breaking or item pickup. | RS Constructor/Destructor; AE2 Formation/Annihilation Planes | PARITY_PLAN Wave G (PD6 yes, no slices) |
| M35 | Links stop at 256 blocks in one dimension. | RS Network Transmitter/Receiver; AE2 Quantum Bridge | PLAYER_GUIDE "Wireless links" |
| M36 | No point-to-point tunnels that carry items, fluids, FE or redstone between two spots. | AE2 P2P tunnels | Not found |
| M37 | A rule runs only on a timer; there is no rule that runs once on a redstone pulse. | SFM `every redstone pulse do` | `FriendlyCompiler.java`: only `every` |
| M38 | Redstone in programs is on/off; strength cannot be compared. | SFM `redstone >= 7` | ADR 0062 |
| M39 | A move cannot leave some behind in the source ("take all but the last 5"). `keep … stocked` works only at the target. | SFM `input retain 5 from a` | `ProgramSyntax.KEYWORD_WORDS` |
| M40 | A move cannot be limited to empty target slots. | SFM `to empty slots in` | Not found |
| M41 | `has` sums a label's machines; there is no "every machine", "some", "exactly one" or "at most one", and `has` cannot look at one side or slot range. | SFM `every/some/one/lone/overall … has` with sides and slots | ADR 0062 |
| M42 | Sides are absolute only; no `front`, `back`, `left`, `right`. | SFM relative sides | `FriendlyCompiler.java` side parser |
| M43 | `slots` takes one range; check first whether one statement can name several labels. | SFM `slots 0-2,5` and `from a, b` | `FriendlyCompiler.java` `slots` parser |
| M44 | Rule timers cannot be offset or aligned across rules. | SFM `every 20 ticks offset by 5 ticks`, `global` | Not found |
| M45 | A program lives inside its controller: no item to carry or share it, and no clipboard export or import. | SFM Disk (program and labels; tooltip shows the program) | `ProgramScreen.java` |
| M46 | No rotating round robin. | SFM `round robin by block/label` | ADR 0062 rejected it pending a playtest finding |
| M47 | No Mekanism chemicals. | SFM natively; AE2 via AppMek; RS2 Mekanism integration | Not found |
| M48 | No ComputerCraft peripheral. | SFM has CC: Tweaked integration | Not found |
| M49 | No "open the guide page for the item under the cursor" key. | AE2 guide hotkey | `GuideScreen.java` |

Code-review findings (2026-10-26, same commit). These are not feature gaps; they are bugs and
maintainability issues that the ergonomics work will build on. Each was checked against the code.

| ID | Finding | Evidence |
| --- | --- | --- |
| M50 | **Bug (probable): keep-stock can start a duplicate job.** `keep()` makes a new rule ID every time (`UUID.randomUUID()`), `CraftingJobs.setRule` replaces the old rule, and `evaluate` counts a rule as in flight only when a job's `rule()` equals that ID. Turning Keep off and on, or changing its amount, while its job runs starts a second job and overshoots the target. This breaks R06 / [ADR 0034](decisions/0034-keep-stock-requests.md) deduplication by owner, rule and target. | `WorldCraftingJobs.java` `keep()` and `evaluate()` (the `in_flight` check); `CraftingJobs.java` `setRule` |
| M51 | `ruleDue` is never cleaned up, and `ruleStatus` is removed in `unkeep` but not when a rule is replaced. Both grow by one entry per rule ever set in a server session; with M50 every replacement adds one. | `WorldCraftingJobs.java` fields `ruleStatus`, `ruleDue` |
| M52 | Job and rule reasons are about 25 string literals ("machine_full", "uncertain", "offline", …) compared with `.equals` on client and server. Each has a lang key today, but a typo compiles and fails silently. ENGINEERING.md asks for typed results; `OperationResult.Reason` is the pattern to follow. | `"uncertain"` in `BufferSettingsScreen.java`, `TerminalMenu.java`, `RecoveryScreen.java`, `RecoveryActions.java`, `ProcessingJournal.java` |
| M53 | `TerminalScreen` is 2,070 lines covering items, fluids, energy, the crafting panel, Jobs and the JEI hand-off. It is the one screen still largely off the panel kit: 10 raw `fill` calls, 14 hard-coded ARGB colours and its own scrollbar drawing although `Panels.scrollbar` exists. Lane T adds to it. | `client/TerminalScreen.java` |
| M54 | UI drawing is duplicated: the selection highlight and row colours are copied across Terminal, Drive, Network Access, Recovery and Buffer screens. `Panels` has text colours but no highlight or row colours. | `client/ui/Panels`, those screens |
| M55 | A consume-only, rate-limited FE input is written three times, as anonymous energy storages that already differ in where they clamp at zero. | `AccessPointEntity.java`, `ProgramControllerEntity.java`, `LinkModule.java` |
| M56 | Checking that a resource key resolves (build a `PlatformResourceCodec`, make a stack, catch, return `WRONG_RESOURCE`) is repeated inline, and `getMessage() == null ? getClass().getSimpleName() : getMessage()` appears 9 times. | `WorldStorageTransfers.java`, `WorldStorageRoutes.java` |
| M57 | Small inconsistencies: `TerminalScreen.viewerRequest` is static and is not cleared when the screen closes, so a later terminal with the same container ID could pick up a stale JEI request; `LabelToolContent` uses `context.enqueueWork` where every `MenuPayloads.toServer` handler runs directly; `FairScheduler.Admission` is written fully qualified inline; `GuideItem` and `LabelToolItem` open client screens behind `isClientSide()` rather than through a client-only class. | `TerminalScreen.java` (`viewerRequest`), `LabelToolContent.java`, `GuideItem.java`, `LabelToolItem.java` |
| M58 | The Wasm time limit reads `System.nanoTime` on every instruction (up to 65,536 per rule). [ADR 0004](decisions/0004-wasm-feasibility.md) documents this. Checking every 256 instructions would keep the same bound at lower cost, if a measurement shows the cost matters. | `WasmProgram.java` deadline check |

The same review found no issue with module boundaries, packet admission (`MenuSession.admitCommand`,
bounded string reads), checked arithmetic and quarantine of uncertain transfers, the planner,
compiler and Wasm bounds, or test hygiene.

Source-inventory findings (2026-10-26). Each was checked against our code; "Not found" has the
same meaning as above.

| ID | Finding | Compared with | Evidence |
| --- | --- | --- | --- |
| M59 | Terminal search knows `@mod`, `#tag` and names only: no `$tooltip`, `*id`, OR, NOT, parentheses, quoted text or count filters (`>64`). | AE2 `$`, `*`, `\|`, spaces for AND; RS2 `$`, `!`, `&&`, `\|\|`, `( )`, quotes, `>N`/`<=N`/`=N` | `TerminalSearchSupport.java` |
| M60 | The search box has no history (Up/Down), no right-click to clear, no focus key and no syntax colouring. | RS2 history, right-click clear, Tab focus, highlighting; AE2 right-click clear | Not found |
| M61 | Sort has Name, Qty and Mod only: no registry ID or "recently changed", and no "last changed by" in the tooltip. | RS2 ID and Last modified sorts, detailed tooltip | `TerminalSearchSupport.java` sort bits |
| M62 | No view filter of All / Stored only / Craftable only; the **⚒** toggle only adds craftable ghosts. | AE2 View All/Stored/Craftable; RS2 All/Autocraftable/Non-autocraftable | `TerminalScreen.java` |
| M63 | Grid shortcuts missing: Shift+scroll moves one item in or out, Ctrl+scroll puts one on the cursor, Space-click deposits a whole inventory region, number keys swap with the hotbar, and holding Shift freezes the sort while you click. | AE2 (all but Ctrl+scroll); RS2 Shift/Ctrl+scroll and Shift freeze | Not found in `TerminalScreen.java` |
| M64 | No insertion hint when hovering the grid with an item, and no "showing X of Y" when a search hides entries. | RS2 hints; AE2 count | Not found |
| M65 | From a recipe viewer, only one output can be requested. Neither "fill what I have and craft the rest" nor "request every missing ingredient" exists. | AE2 Ctrl+transfer queues missing crafts; RS2 multi-resource request list | `JeiRequests.java` |
| M66 | The amount box takes a plain integer. No arithmetic (`3*64`), no "up to N stored" (`=N`), no ± step buttons or scroll, and no start-without-confirm. | AE2 math, `=N`, ±1/10/100/1000, Shift+Next; RS2 ±1/10/64 and scroll | `CraftAmount.java` (`Integer.parseInt`) |
| M67 | Preview is a list only. There is no tree view, no per-request "notify me" choice, and no Replan/Retry after a failure. | RS2 tree with zoom and Notify checkbox; AE2 error screen with Replan/Retry | `TerminalScreen.java` Jobs |
| M68 | A player cannot suspend and resume a running job, or cancel all their jobs at once. | AE2 Suspend/Resume; RS2 Cancel all | `WorldCraftingJobs.java` (resume is internal) |
| M69 | A pattern's tooltip is text. It does not draw the recipe, and holding Shift does not show the output on the icon. | AE2 and RS2 both | `PatternItem.java` `appendHoverText` |
| M70 | A cell's tooltip shows capacity only, not fill or contents, and Drive bays only show that a cell is present. | AE2 content preview and 5 LED states; RS2 "Stored X / Y (Z%)" and near-full/full LEDs | `CellItem.java`, drive bay textures |
| M71 | Check first: crafting recipes that need a filled bucket may not use stored fluid. | AE2 fluid substitution; RS2 "autocrafting with filled buckets" | Not found in `CraftingPlanner` |
| M72 | Processing patterns take at most 9 input types and 3 outputs, and their inputs cannot accept tag alternatives. | AE2 81 inputs / 27 outputs; RS2 81 / 81 with per-input tag alternatives | `Pattern.MAX_INPUT_KEYS = 9`, `ProcessingContents.MAX_OUTPUTS = 3`; PD4 kept processing exact |
| M73 | The Crafter has no lock modes: no "wait until the machine is empty", "until the outputs came back", or "until a redstone pulse/high/low". | AE2 blocking mode and 5 lock modes; RS2 6 lock modes | ADR 0060 (lease plus output-slot check only) |
| M74 | Crafters have no priority when several hold patterns for the same output, and no name. | AE2 provider priority and anvil/name-press names; RS2 priority and rename | Not found |
| M75 | A Buffer's Stock mode has one goal for its whole filter; it cannot stock 64 coal and 16 iron in one block. | AE2 Interface and RS2 Interface: an amount per slot | `BufferPolicy.java` (one goal 0–576) |
| M76 | An Exporter with several filter entries always tries them in order; no round robin or random. | AE2 Export Bus scheduling; RS2 Exporter and Constructor scheduling | Not found |
| M77 | Storage routing lacks separate insert and extract priorities, a redstone switch per storage, "filter on extract", and "report inaccessible". Check first whether same-priority storage that already holds an item is preferred. | RS2 insert/extract priority and storage redstone mode; AE2 filter-on-extract, report-inaccessible, prefer-existing | `WorldStorageRoutes.java` |
| M78 | Filters cannot match "damaged below 25/50/75/99%". | AE2 fuzzy damage modes | `ResourceFilter` |
| M79 | No terminal view presets (saved display filters). | AE2 View Cells | Not found |
| M80 | No player advancements; only recipe-unlock advancements exist. | AE2 about 25; RS2 27 that walk the progression | `data/factorycore/advancement/recipes` only |
| M81 | Devices ignore anvil names: screens, pattern lists and job steps show the block name. | AE2 and RS2 show custom names as titles and in pattern lists | No `Nameable` on device entities |
| M82 | Screens have no button that opens their guide page, and guide pictures are flat. | AE2 "Open Guide" on every screen; GuideME interactive 3D scenes | `GuideScreen.java` |
| M83 | The server config has one option (`network.gatewayDepth`). Energy costs, wireless range, tick rates, battery sizes, rule interval limits, "energy not required" and an emergency stop for programs are fixed. | AE2 wireless/power/tick-rate/battery groups; RS2 per-device energy and `requireEnergy`; SFM `disableProgramExecution`, minimum interval, disallowed resource types | `FactoryCoreConfig.java` |
| M84 | Missing client options: large count font, smooth scrolling, auto-focus search, notify on finished jobs. | AE2 and RS2 client configs | Not found |
| M85 | English only. | SFM ships 6 languages; RS2 uses Crowdin | `lang/en_us.json` only |
| M86 | Creative middle-click on a cell copies its handle. ADR 0005 already counts the copy as a duplicate reference, not extra stock, but the creative player gets a cell that conflicts. | RS2 gives a fresh, unlinked disk | ADR 0005 |
| M87 | Check first: not every item and screen button may have a hold-Shift help line. | RS2 "Press SHIFT for help" on every block, item and side button | `ItemDescriptions.java` |
| M88 | Upgrade items do not say which devices take them, and devices do not list the upgrades they accept. | AE2 compatible-upgrades panel; RS2 "Supported by" and slot tooltips | `ThroughputUpgradeItem.java` |
| M89 | Upgrades must be carried loose; no tool holds a set of upgrades that every screen can install from. | AE2 Network Tool "toolbelt" | Not found |
| M90 | No statistics for items stored or taken. | AE2 `stat.ae2.*` | Not found |
| M91 | Check first (with M25): the Overview may not list each device's power use or offer an AE/FE-style unit toggle. | AE2 network status machine list and unit toggle | `OverviewScreen.java` |
| M92 | Signal mode cannot watch stored energy, and cannot "emit to craft" (drive a machine by redstone as a craft step). | AE2 Energy Level Emitter; Level Emitter with Crafting Card | `SignalMenu.java` |
| M93 | Nothing stops a cable connecting on one side, so two networks built side by side merge. | AE2 Cable Anchor; RS2 colour rules | Not found (with M29) |
| M94 | Planned world-interaction blocks would miss what the others do: placing and picking up fluids, launching fireworks, and enchanting the breaker. | RS2 Constructor/Destructor fluids and fireworks; AE2 planes take pickaxe enchantments | PARITY_PLAN Wave G |
| M95 | Programs cannot align rules to the world clock, and the minimum interval (10 ticks) is a constant, also for energy-only rules. | SFM `GLOBAL`/`20G+1`, configurable minimum, 1-tick minimum for energy-only rules | `ProgramIr.MIN_INTERVAL = 10` |
| M96 | A move has one limit. It cannot give different limits per item (`5 iron, 3 gold`), or a limit per matched item (`5 EACH *_ingot`). | SFM resource-limit lists, `OR` with a shared limit, `EACH` per resource | `FriendlyCompiler.java` |
| M97 | Tag terms are a single tag. No tag path wildcards (`#c:ores/*`) and no AND/NOT of tags. | SFM `WITH #a AND NOT #b`, `*` and `**` in tag paths | `FriendlyCompiler.java` (tag globs refused) |
| M98 | A face list (`side up, north`) or every face (`each side`) is not available in one statement. | SFM `TOP, WEST SIDE`, `EACH SIDE` | `FriendlyCompiler.java` side parser |
| M99 | The editor finds no problems that need the world: a label used in code but on no machine, a label on machines but unused, a labelled block off the cables or with no inventory, an unknown item ID, a move whose slots match nothing. No one-click fix. | SFM linters, Shift-click auto-fix, diagnostics copied to the clipboard | `ProgramScreen.java` diagnostics are compile-only |
| M100 | No inspections: listing a label's machines and positions, evaluating a condition now, or predicting each branch's moves. **Preview next rule** covers one rule. | SFM Ctrl+Space inspections | `ProgramScreen.java` |
| M101 | Editor gaps (check each first): toggle comments, line numbers, Shift+Enter to save, a confirm before closing with unsaved edits. | SFM editor | `ProgramTextEditor.java` |
| M102 | Program logs have no level, no copy, no clear and no run-time graph. | SFM per-manager log levels, logs screen, tick-time graph | `ProgramScreen.java` log area |
| M103 | No slot-index overlay for other mods' containers, so players guess `slots` numbers; nothing writes the move lines for a container's sides. | SFM Ctrl+I container inspector and Export Inspector; Network Tool block inspection | Not found |
| M104 | The Label Tool has no list of labels in use with counts, no way to remove a label from machines, no scroll or key to switch labels, and no view modes. | SFM Label Gun screen, clear modifier with confirm, scroll and next/previous keys, three view modes | `LabelToolScreen.java` |
| M105 | Templates are built in; resource packs cannot add them. | SFM templates from any resource pack | `ProgramTemplates.java` |
| M106 | Programs cannot fill or drain cauldrons. | SFM cauldron fluid capability | Not found |
| M107 | No cable that passes a machine's inventory through to the block behind it, and no explosion-proof cable. | SFM Tunnelled and Tough cables | Not found |
| M108 | A program carrier would lack what SFM's disk shows: labels with counts, errors and warnings in the tooltip, editing from the item in hand, and a reset recipe. | SFM Disk | Q46 scope |
| M109 | No admin conveniences: viewing the server config in game, a starter-kit command, an in-game changelog. | SFM `/sfm config show`, `/sfm kit`, `/sfm changelog` | Admin commands cover recovery only |

Small gaps (nits) from the same inventories. Each is minor alone; together they are what makes
the other mods feel finished.

| ID | Finding | Compared with | Evidence |
| --- | --- | --- | --- |
| M110 | The Jobs tab shows no count of running jobs. | AE2 badge on the crafting-status tab | `TerminalScreen.java` |
| M111 | Search sync (Q17) has no "clear the recipe viewer's search when the terminal opens" option. | AE2 terminal settings | Not found |
| M112 | Long screen titles are cut with "…" rather than scrolled. | RS2 marquee titles | `FitText.java` |
| M113 | Encoding from a recipe viewer does not prefer plentiful or undamaged items, and does not warn "needs a processing pattern" or "larger than 3×3". | AE2 encoding helper | `TerminalTransferHandler.java` |
| M114 | Processing slots have no exact-amount box, and there is no way to pick which output is the main one. | AE2 amount sub-screen and Cycle Outputs | `TerminalScreen.java` Outputs |
| M115 | The planned Patterns view (Q3) would lack search by input, output or Crafter name, a Visible / Not full / All filter, a per-Crafter "hide from this list" toggle, and Shift-click to put a pattern in the first free Crafter. | AE2 Pattern Access Terminal; RS2 Autocrafter Manager | Q3 scope |
| M116 | The job monitor does not name the machine a processing step is in. | RS2 "Processing (machine name)" | `TerminalJobs.java` |
| M117 | Exporter filter slots show no status (target refuses, missing in storage, craft started, craft missing ingredients). | RS2 per-slot exporting indicators | `PortScreen.java` |
| M118 | Blank patterns cannot be fed to encoding by automation. | RS2 Pattern Grid blank slot takes hoppers | `TerminalCrafting.java` (encodes from the player inventory) |
| M119 | Drives expose no item handler, so hoppers cannot insert or remove cells. | AE2 and RS2 drives | No `Capabilities.ItemHandler.BLOCK` registration for the Drive |
| M120 | **Check first; may be a bug.** Nothing visibly stops a cell from storing a filled shulker box, another cell, or other items with large contents. | AE2 "does not fit in storage cells" guard | Not found in `CellItem`/`CanonicalStorage` |
| M121 | Cells cannot be taken apart to recover their materials. | AE2 and RS2 Sneak-use on an empty cell | Not found |
| M122 | A cell cannot split its space evenly across its partitioned types. | AE2 Equal Distribution Card | Not found |
| M123 | Check first: the Storage Interface screen may not name the attached container ("Attached to: Chest"). | AE2 Storage Bus | `ConnectorSettingsScreen.java` |
| M124 | Check first: a Buffer in Stock mode may accept other items into its stocked slots from outside automation. | AE2 Interface blocks foreign insertion | `BufferedItemHandler.java` |
| M125 | Fluid amounts are in mB only; no bucket display option. | AE2 and RS2 show buckets | `CountText.java` |
| M126 | The Wireless Terminal costs the same at any distance. | AE2 drain grows with range | ADR 0039 |
| M127 | A finished job is a chat line unless a screen is open. | AE2 toast when a linked wireless terminal is carried | `JobNotice.java` |
| M128 | The Label Tool's item name does not show its current label. | SFM `Label Gun: "label"` | `LabelToolItem.java` (no `getName`) |
| M129 | Row labelling (Q9) should also reach diagonal neighbours, as SFM's does. | SFM contiguous fill includes diagonals | Q9 scope |
| M130 | The network view (Q12) cannot be narrowed to one network by clicking a block. | SFM Network Tool attune | Q12 scope |
| M131 | Program keywords are case-sensitive (`EVERY` is an error). | SFM keywords are case-insensitive | `FriendlyCompiler.java` (no case folding) |
| M132 | Check first: Shift+Tab may not outdent in the editor. | SFM Tab/Shift+Tab on a selection | `ProgramTextEditor.java` |
| M133 | Check first: lists may not allow a trailing comma. | SFM allows trailing commas | `FriendlyCompiler.java` |
| M134 | A program's name is not shown outside its controller. | SFM disk item takes the program NAME | Q46 scope |
| M135 | Few teaching templates. Ours are practical builds; SFM also ships templates that teach filtering, limits, tags, timers and a truth table for conditions. | SFM template set | `ProgramTemplates.java` |
| M136 | The program editor cannot be opened outside a world, for drafting. | SFM Ctrl+E on the title screen | Not found |
| M137 | Check first: our blocks and items may lack item tags for pack makers (for example `factorycore:cables`, and `c:tools/wrench` for Q22's wrench). | RS2 item tags for every device family; AE2 and RS2 honour `c:tools/wrench` | `src/generated/resources/data` |
| M138 | Guide pages cannot show live config values once Q85 makes numbers configurable. | AE2 `<ae2:ConfigValue>` in guide pages | `GuideContent.java` |
| M139 | A crash report has no section on this mod's state. | SFM crash-report details | Not found |
| M140 | Facade details for Q30: a wrench cycles the facade block's states, facades turn see-through while the network view (Q12) is on, and a facade can be spread over a run or a whole network with a confirm. | AE2 wrench and network tool; SFM spread modes and confirms | Q30 scope |
| M141 | A cell's advanced tooltip (F3+H) does not show its handle ID for support. | RS2 storage UUID, AE2 serial in advanced tooltips | `CellItem.java` |

M142 onward are follow-ups from slices and findings from Q51.

| ID | Finding | Evidence |
| --- | --- | --- |
| M142 | Q54 typed the job, rule and staging reasons only. Other menus still send a status string that their screens compare with `.equals` (`"ready"`, `"applied"`, `"valid"`, `"filled"`, `"running"`, …): Signal, Port, Drive, Network Access, Program, Connector, Buffer and the Terminal's grid and search status. A typo there still compiles. | `grep 'equals("' platform/client` after Q54 |
| M143 | Raw `fill` calls and colour literals remain outside the Terminal: Drive bay fill bars, `FilterSlots` (inactive veil, tag badge), `SlotPicker`, the `StatusStrip` lamp. The program editor's dark code field is deliberate (ADR 0064 H3c). | Q60 evidence |
| M144 | `runAutomaticThroughputServer` (in `--full`) requires Endgame import to beat Advanced on one 1,728-item chest, where fixed overhead leaves 1–3 ticks between the tiers; a tie fails it. Measured 2026-10-26: 2 failures in 12 runs at `ca56f9c` (ticks Endgame 14–16, Advanced 15–18) and 0 in 11 at `9c155c7` (14–15, 15–17). The distributions overlap, so this is a marginal assertion, not a regression; it is the flaky-test kind PD7 also covers. | `AutomaticThroughputGameTests`, `MachinePresetGameTests.paidTiersIncreaseAutomaticChestImport` |

## Decision gates (user)

Earlier open gates that this plan depends on:

| Gate | Question | Status |
| --- | --- | --- |
| PD7 | Narrow chunk-load invalidation to regions on the shared chunk edge ([parity plan](PARITY_PLAN.md#decision-gates-user)). | Open |
| UD3 | Give the Controller a small internal energy buffer and make the Bank optional ([UX plan](UX_PLAN.md#decision-gates-user)). | Open |

New gates:

| Gate | Question | Recommendation | Status |
| --- | --- | --- | --- |
| QD1 | Add Curios as an **optional** dependency so the Wireless Terminal works from an accessory slot, with a key to open it? Needs an ADR; stays off mandatory class loading. | Yes; the key works without Curios (it searches the inventory) | Open |
| QD2 | Add EMI and REI as optional integrations beside JEI ([ADR 0043](decisions/0043-jei-integration.md) amendment)? | EMI yes now; REI only if asked | Open |
| QD3 | Add stonecutter and smithing patterns? Amends SPEC R06 and [ADR 0030](decisions/0030-patterns-and-crafting-units.md): new pattern kinds that run inside the Crafter, like crafting-table patterns. | Yes | Open |
| QD4 | Let an Exporter's Keep request a craft when storage runs out? The job runs under the grants of whoever last applied the Exporter, counts against that player's job limit, and makes at most one job per Exporter at a time. Amends [ADR 0066](decisions/0066-importer-exporter.md) and [ADR 0034](decisions/0034-keep-stock-requests.md). | Yes, opt-in **Craft missing** toggle | Open |
| QD5 | Coloured cables: a dyed cable joins only cables of its own colour or uncoloured ones. Amends SPEC R02 and [ADR 0010](decisions/0010-bounded-wire-discovery.md). | Yes, 16 dyes plus uncoloured | Open |
| QD6 | Facades on cables (any full block's look; no change to connections)? | Yes, after QD5 | Open |
| QD7 | A Relay cable that disconnects while unpowered? It changes topology by redstone, so it rebuilds the network on each change. Amends [ADR 0007](decisions/0007-topology-authority.md). | Yes, rate-limited to one rebuild per second | Open |
| QD8 | Portable storage: a handheld that holds one cell and opens a terminal on it without a network. A cell outside a drive becomes reachable, which amends [ADR 0005](decisions/0005-persistence-ownership.md) and ADR 0023 ownership. | Yes, one cell, owner-only | Open |
| QD9 | World interaction design for the Constructor and Destructor (PD6 asked for a gate per feature). Recommended: one block each, acting as a fake player owned by whoever applied it, firing break/place events so protection mods can refuse, and never voiding: drops go to storage or stay put. Fortune and Silk Touch as upgrades. | Yes, as described | Open |
| QD10 | Cross-dimension links and P2P tunnels. No chunk loading (deliberate), so both ends must be loaded or the link waits. Amends [ADR 0040](decisions/0040-paired-wireless-links.md). | Cross-dimension links yes; P2P tunnels later | Open |
| QD11 | Friendly language **v3** as a superset of v2 with additive `factory_v1` Wasm imports (as PD2 did): pulse-triggered rules, redstone strength, source retain, empty slots, `has` quantifiers with sides and slots, relative sides, several slot ranges, labels and faces, timer offsets and world-clock alignment, per-item and per-match limits, and tag expressions. Needs an ADR. | Yes | Open |
| QD12 | A **Program Disk** item: a program's source (and optionally its machine labels) on an item, used on a Program Controller to load it, with the program in its tooltip. Changes where program state lives ([ADR 0036](decisions/0036-program-controller-runtime.md), ADR 0044). | Yes: the controller keeps running its copy; the disk is a carrier, not the store | Open |
| QD13 | Creative items (creative energy source, creative item and fluid cells, creative Wireless Terminal) in the creative tab only, with no recipes. | Yes | Open |
| QD14 | Infinite fluid cell, and bulk cell tools (a block that empties or fills cells, and placeable storage blocks). Balance change to [ADR 0048](decisions/0048-final-balance.md). | Infinite fluid cell yes; bulk cell tools no for now | Open |
| QD15 | Reopen rotating round robin (SFM `round robin by block`), rejected in ADR 0062 until a playtest asks for it. | No, unless H12 or Q51 finds a need | Open |
| QD16 | Mekanism chemicals as a fourth resource kind through an optional integration. Touches the resource model in the portable core. | Later; only if a modpack asks | Open |
| QD17 | A ComputerCraft peripheral on the Program Controller (read stock, request crafts, run moves under the placing player's grants). | Later | Open |
| QD18 | Raise processing-pattern limits (for example 27 input types and 9 outputs) and allow tag alternatives on processing inputs? The second reopens PD4 ("processing patterns stay exact"). Amends [ADR 0032](decisions/0032-processing-patterns.md) and SPEC R06. | Raise limits yes; alternatives no for now | Open |
| QD19 | Crafter lock modes (blocking, until outputs return, redstone pulse/high/low) and Crafter priority. Amends [ADR 0060](decisions/0060-crafter.md) and ADR 0032 dispatch. | Yes | Open |
| QD20 | Let a player suspend and resume their own jobs, keeping reservations while suspended? Amends [ADR 0031](decisions/0031-crafting-jobs.md). | Yes; a suspended job still counts against the job limits | Open |
| QD21 | Fluid substitution: a crafting pattern needing a filled bucket may use stored fluid plus an empty bucket. Amends [ADR 0063](decisions/0063-pattern-substitution.md). | Yes, if M71 is confirmed | Open |
| QD22 | Which numbers become server config (energy costs, wireless range, tick rates, rule interval limits, "energy not required", program emergency stop)? Configurable balance amends [ADR 0048](decisions/0048-final-balance.md) and the admin guide. | Yes, a bounded list with the current values as defaults | Open |
| QD23 | Separate insert and extract priority, and a redstone switch per storage. Amends [ADR 0013](decisions/0013-external-connectors.md) routing. | Yes | Open |
| QD24 | Tunnelled and explosion-proof cables (M107). | No for now | Open |

## Wave 0 — reliability (lane R)

- [ ] **Q1 — Networks stay online when chunks load nearby (M1, needs PD7).** Size M.
  Narrow chunk-load invalidation to regions with a position on the shared chunk edge, as PD7
  proposes, after measuring the callback cost ([ADR 0011](decisions/0011-live-region-coordination.md)
  asks for P7 evidence).
  Files: `WorldNetworks.java`, `WorldWireReader.java`, ADR 0011,
  `firstNeighborChunkLoadInvalidatesExistingBoundaryPublication`.
  Done when: a GameTest loads a neighbouring chunk that touches no cable and the network stays
  online; one whose edge holds a cable still revalidates; the flaky tests named under PD7
  pass 10 consecutive full GameTest runs; the measurement goes in `docs/performance/`.

## Wave K — code health (lane K)

- [x] **Q52 — Keep-stock never doubles a job (M50).** Size S. Done 2026-10-26 (commit subject
  `Q52:`). `KeepStockGameTests.keepTurnedOffAndOnMidJobStartsNoSecondJob` and
  `keepAmountChangedMidJobStartsNoSecondJob` failed before the fix with 128 and 144 sticks (two
  jobs) and pass after it with 64 and 80; `KeepStockRuleTest` checks a replacement keeps the
  first rule's ID; ADR 0034 states the dedup key. All 416 GameTests pass.
  Replacing a rule for the same owner, network, subnetwork and resource keeps its ID, and
  `evaluate` also treats a job as in flight when it matches the rule's owner, network,
  subnetwork and resource, so an older job still counts after Keep is turned off and on.
  Files: `WorldCraftingJobs.java`, `CraftingJobs.java`, ADR 0034 (state the dedup key).
  Done when: a regression GameTest that fails before the fix sets Keep 64 sticks, lets a job
  start, turns Keep off and on (and separately changes the amount) mid-job, and finds exactly one
  job and no overshoot; persistence tests still round-trip rules.

- [x] **Q53 — Rule maps are cleaned up (M51).** Size S. Done 2026-10-26 (commit subject
  `Q53:`). `unkeep` drops both entries and each tick prunes entries of rules that are gone;
  replacements keep their ID since Q52. Networks are never removed from saved data, so there is
  no network-removal path to clean.
  `KeepStockGameTests.ruleStateStaysAtLiveRulesAcrossSetReplaceAndRemove` (4 rounds of 5 rules
  set, replaced and removed) failed before the fix with 7 entries for 5 live rules and passes
  after it.
  `ruleDue` and `ruleStatus` drop an entry when its rule is removed or replaced, and on network
  removal.
  Done when: a test sets, replaces and removes rules many times and both maps stay at the number
  of live rules.

- [x] **Q54 — Typed job and rule reasons (M52).** Size M. Done 2026-10-26 (commit subject
  `Q54:`). `JobReason`, `JobStatus`, `TerminalJobPackets.StepStatus` and `StagingClass`, keyed
  by `EnumKeys` and sent through `WireEnums` (bounded, unknown refused); job state travels as
  `CraftingJob.State`; the Terminal and Buffer views gained a typed `uncertain` flag. Saved
  reason keys are unchanged. Found and fixed: ten movement-refusal job reasons (`rate_limit`
  and others) and the `no_power` status had no lang entry and showed raw keys;
  `LangKeysTest.everyTypedReasonAndStatusHasItsKey` failed on them first. Packet tests
  round-trip every value and refuse unknown keys; `CraftingJobPersistenceTest` loads every
  reason and refuses an unknown one. ADR 0031 and ADR 0055 amendments. Other menus' status
  strings (Signal, Port, Drive, Access, Program, Connector, grid) are follow-up M142.
  An enum (or enums) for crafting job, rule and staging reasons, mapped to lang keys in one
  place, sent over the wire by ordinal or name with a bounded decoder. Client screens compare
  enum values, not strings.
  Done when: `LangKeysTest` checks that every enum value has a key; packet tests round-trip each
  value and refuse an unknown one; no `"uncertain"`-style string comparison remains in `platform/client`.

- [x] **Q55 — Split the terminal screen onto the kit (M53, M54).** Size L. Done 2026-10-26
  (commit subject `Q55:`). Shell plus `TerminalStorageView`, `TerminalCraftingPanel` and
  `TerminalJobsView`; selection and row colours in `Panels`; all graphical tours pass unchanged
  and panels match the previous commit's screenshots pixel for pixel.
  [Evidence](evidence/ergonomics-q55-terminal-split-2026-10-26.md).
  Split `TerminalScreen` into a shell plus per-mode parts (items/fluids/energy grid, crafting
  panel, Jobs), drawn with the panel kit. Add the selection highlight and row colours to
  `Panels` and use them in Terminal, Drive, Network Access, Recovery and Buffer screens. No
  behaviour change.
  Done when: every existing terminal GameTest and client tour passes unchanged; screenshots at
  scales 1 and 4 match the H2 evidence; no raw `fill` or hard-coded colour remains in the
  terminal classes.

- [x] **Q56 — One rate-limited energy input (M55).** Size S. Done 2026-10-26 (commit subject
  `Q56:`). `platform.power.RateLimitedEnergyInput`; `RateLimitedEnergyInputTest` (4 tests)
  covers rate, capacity, a refused accept, off-server input and the zero clamp. All 417
  GameTests pass. Two edge changes are documented in `modules/WIRELESS.md`.
  A shared `RateLimitedEnergyInput` (capacity, per-tick rate, dirty callback, accept gate) used
  by the Access Point, Program Controller and Wireless Link.
  Done when: a unit test covers rate, capacity, a refused accept and the zero clamp, and the
  three devices' existing GameTests pass.

- [x] **Q57 — Shared resource checks (M56).** Size S. Done 2026-10-26 (commit subject
  `Q57:`). `PlatformResourceCodec.resolvable` replaces the three inline checks in
  `WorldStorageTransfers` and `WorldStorageRoutes`; `core.Failures.message` replaces the eight
  copies (core and platform). `PlatformResourceTest.resolvableRefusesUnknownItemsAndFluids` and
  `FailuresTest`; all GameTests pass. `ThroughputUpgradeItem` keeps its own empty fallback.
  `PlatformResourceCodec.resolvable(key)` replaces the inline try/catch checks, and one helper
  replaces the 9 exception-message copies.
  Done when: the transfer and route GameTests pass and a unit test covers an unknown item and an
  unknown fluid.

- [x] **Q58 — Small consistency fixes (M57).** Size S. Done 2026-10-26 (commit subject
  `Q58:`). Clearing in `removed()` alone would not have fixed it: the recipe viewer replaces the
  terminal screen before the request is made. The request now belongs to its terminal menu
  object (`ViewerRequests`, held weakly), and `removed()` drops it only when the terminal
  itself closes; `ViewerRequestsTest` covers a request left pending not reaching a later
  terminal. The Label Tool handler runs like `MenuPayloads`; `FairScheduler.Admission` is
  imported; items open screens through `platform.ClientScreens`, installed by the client, and
  ArchUnit now forbids common code from naming `platform.client`. `runJeiClient`,
  `runPresentationClient`, `runTerminalClient` and the dedicated-server suites pass.
  Clear `viewerRequest` in `removed()`; make the Label Tool handler match `MenuPayloads`; import
  `FairScheduler.Admission`; open the Guide and Label Tool screens through a client-only class.
  Done when: a client test (or `runJeiClient` step) shows a JEI request not carried into a later
  terminal, and the dedicated-server GameTests still pass.

- [x] **Q59 — Cheaper Wasm deadline (M58).** Size S. Done 2026-10-26 (commit subject
  `Q59:`). Measured first: the per-instruction clock read was 47–50 % of a full-budget rule
  (1.55 ms). Now read every 256 instructions: 0.80 ms; worst-case overrun 255 instructions
  (about 3 µs), recorded in the ADR 0004 amendment.
  [Measurement](performance/wasm-deadline-2026-10-26.md).
  Benchmark a 65,536-instruction rule with the per-instruction clock read. Only if the clock
  read is a measurable share, check every 256 instructions and record the new worst-case
  overrun in ADR 0004.
  Done when: the measurement is in `docs/performance/` either way; if changed, `WasmProgramTest`
  still traps a runaway guest within the documented bound.

- [x] **Q60 — Code-health re-check.** Size S. After Q52–Q58. Done 2026-10-26 (commit subject
  `Q60:`). Counts before and after in
  [evidence](evidence/ergonomics-q60-code-health-2026-10-26.md); follow-ups M142/Q104 and M143.
  Re-run the review's searches (string reason comparisons, raw fills, duplicated energy
  storages, inline codec checks) and record the counts.

- [ ] **Q104 — Typed menu statuses (M142).** Size M. After Q54.
  The same `EnumKeys`/`WireEnums` pattern for each remaining menu status (one enum per menu, or
  `OperationResult.Reason` plus the menu's own states), with a LangKeysTest entry per enum.
  Done when: no string-status `.equals` remains in `platform/client`; packet tests refuse an
  unknown status.

- [ ] **Q106 — A throughput check with a real margin (M144).** Size S.
  Keep the strict Base > Advanced > Endgame ordering, but measure a workload where the tiers'
  rates dominate fixed overhead (for example refilling the chest until a fixed total such as
  13,824 items has been imported, or timing steady-state ticks after the first transfer), so the
  expected gaps are many ticks. Record the measured ticks per tier.
  Done when: 20 consecutive `runAutomaticThroughputServer` runs pass and the evidence lists
  their ticks; the assertion is no weaker than today's.

- [ ] **Q105 — Remaining screen fills on the kit (M143).** Size S.
  Move the Drive bay bars, `FilterSlots` marks, `SlotPicker` and the `StatusStrip` lamp to
  `Panels` calls (reuse `Panels.lamp` and `meter`), with screenshots matching before and after.
  Done when: no raw `fill` or colour literal remains in `platform/client` outside `ui/` and the
  program editor's code field.

## Wave A — Crafter and patterns (lane C)

- [ ] **Q2 — Crafter screen (M2).** Size M.
  Empty-hand use opens a kit screen: nine pattern slots (click to take any one, cursor to
  insert), the installed speed upgrade, each adjacent machine it serves with its state (free,
  busy with job N, output blocked), and the idle reason. Sneak-use keeps taking the last
  pattern for compatibility. Moving a pattern needs Edit.
  Files: `CrafterBlock.java`, `CrafterEntity.java`, `PatternHolderEntity.java`, new
  `CrafterMenu`/`CrafterScreen`, [ADR 0060](decisions/0060-crafter.md) amendment.
  Done when: GameTests cover taking the third of three patterns, inserting from the cursor,
  denial without Edit and a busy machine shown as busy; screenshots at scales 1 and 4.

- [ ] **Q3 — Patterns view (M3).** Size M. After Q2.
  A **Patterns** tab in the terminal lists every Crafter visible to the player (gateway CRAFT
  policy applies, as for craftables), its label or position, and its patterns. Players with
  Edit take or insert patterns there through the same path as Q2. Search filters by output.
  Files: `TerminalMenu.java`, `TerminalScreen.java`, `WorldCrafting.java` index, packets.
  Done when: GameTests cover two Crafters listed, one on a gateway subnetwork that forbids
  CRAFT hidden, a pattern taken remotely, and denial without Edit; the list is bounded and
  scrolls.

- [ ] **Q4 — Edit and clear patterns (M4).** Size S.
  Placing an encoded pattern in the crafting panel's pattern slot loads its grid (Table,
  Substitutes, or Inputs/Outputs with amounts); **Encode** overwrites it. Crafting an encoded
  pattern alone in a crafting table returns a blank.
  Files: `TerminalCrafting.java`, `TerminalCraftingPackets.java`, a recipe in the data generator.
  Done when: GameTests round-trip a crafting and a processing pattern through load → change →
  encode, and the blank recipe works; `RecipeGameTests` count updated.
  Also: sneak-use an encoded pattern in the air to wipe it to a blank, as AE2 and RS2 do.

- [ ] **Q5 — Recipe-viewer transfer for processing patterns (M5).** Size M.
  JEI's **+** on any non-crafting recipe fills Inputs and Outputs, including fluids and amounts,
  for every category JEI knows. Recipes with more than three outputs keep the first three and
  say so.
  Files: `FactoryCoreJeiPlugin.java`, a new universal transfer handler, `TerminalTransferHandler.java`.
  Done when: `runJeiClient` transfers a furnace recipe and a blasting recipe into a processing
  pattern; a native test covers the output truncation message.

- [ ] **Q6 — Stonecutter and smithing patterns (M6, needs QD3).** Size M.
  Two new pattern modes in the crafting panel, encoded from the vanilla recipe and run inside
  the Crafter, revalidated before each craft like crafting-table patterns.
  Files: `Pattern.java`, `PatternContents.java`, `TerminalCrafting.java`, `CrafterEntity.java`.
  Done when: GameTests craft stone bricks by stonecutting and a netherite upgrade by smithing
  through a job; JEI transfer covers both.

- [ ] **Q7 — Craft as many as possible (M7).** Size S.
  A **Max** button beside ×64 asks the planner for the largest amount it can make from stock
  (bounded by 4,096 and the planner's limits) and previews it.
  Files: `TerminalJobs.java`, `CraftingPlanner`, `TerminalJobPackets.java`, `TerminalScreen.java`.
  Done when: native tests cover an exact maximum, zero possible, and the 4,096 cap message.

- [ ] **Q8 — Exporter crafts what it is missing (M8, needs QD4).** Size M. After Q52 and Q53.
  An Exporter with **Keep** set gets a **Craft missing** toggle. When stock is short and a
  pattern exists, it starts one job for the shortfall under the applier's grants, waits for
  it, then exports. It never holds more than one job.
  Files: `WorldMachinePresets.java`, `PortContent.java`, `PortScreen.java`, `WorldCraftingJobs.java`.
  Done when: GameTests cover a craft started and exported, no second job while one runs, the
  job limit refusing it with a reason on the Exporter, and denial without Craft.

## Wave B — labels and copying (lane L)

- [x] **Q9 — Label a whole row (M9).** Size M. Done 2026-10-26 (commit subject `Q9:`).
  `LabelRowGameTests` (8 furnaces in one use with a chest skipped; a 68-furnace tower stopping
  at 64; a stranger refused on all 8) and the presentation tour's step that holds the key and
  fires the client's real use event (`label-row.png`). Pending port labels now wait by time
  (1,200 ticks) rather than 20 retries, found when only 20 of 64 labels landed. Diagonals (M129)
  are not a separate case: the row follows the cable run. ADR 0065 amendment.
  Holding a key (default Left Alt, rebindable) while using the Label Tool labels every machine
  of the same block type that touches the same connected cable run, up to 64 machines, each
  through the existing per-machine authorization. The action-bar says how many were labelled
  and how many were refused. This adds the mod's first `KeyMapping`.
  Files: `LabelToolItem.java`, `LabelToolContent.java`, `CablePortBlock.java`, a key-mapping
  registry, a packet carrying the modifier.
  Done when: GameTests label 8 furnaces on one run in one use, stop at 64, skip a chest, and
  refuse machines on another player's network.

- [ ] **Q10 — Pick a label (M10).** Size S.
  Using the Label Tool with the pick key (default middle mouse, rebindable) on a labelled
  machine, port or Signal Interface sets the tool's label to that one.
  Files: `LabelToolItem.java`, the key mapping from Q9.
  Done when: a GameTest picks a label and applies it to a second machine.

- [ ] **Q11 — Label overlay (M11).** Size M.
  While a Label Tool is held, labels float over labelled machines and Signal Interfaces within
  32 blocks, and unlabelled machines touching a cable show a faint marker. The server sends a
  bounded list (at most 256 entries) when the tool is selected and when labels change; the
  client renders it. A key toggles the overlay.
  Files: new `client/LabelOverlay.java` (render event), a label snapshot packet, `LabelToolItem.java`.
  Done when: screenshots show labels and markers; a native test bounds the snapshot; another
  player's network sends nothing.

- [ ] **Q12 — Network view (M12).** Size M. After Q11.
  A second overlay mode on the Label Tool outlines the cables, ports, Importers, Exporters and
  Crafters of the network the player looks at, and the machines programs can reach. No new
  item.
  Files: `client/LabelOverlay.java`, `OverviewMenu.java` data source.
  Using the tool on a machine with the view mode on opens a text view (SFM Network Tool block
  inspection): the machine's inventory kinds per side, its labels, and ready-to-paste `move`
  lines for each side (M103).
  Done when: screenshots of a two-branch network with a gateway show both branches coloured
  apart; the snapshot is bounded and VIEW-checked; a GameTest checks the generated lines for a
  furnace.

- [ ] **Q13 — Copy settings everywhere (M13).** Size M.
  Extend Label Tool copy/paste to Buffer (mode, filter, rate, goal, redstone), Signal (watch,
  condition, output, label), Drive (priority), Crafter (upgrade tier is not copied; nothing
  else to copy until Q2 adds settings), Gateway (policy, needs Manage) and Program Controller
  (draft source; deploying stays a separate step). Same confirm-once rule as today.
  As AE2's Memory Card and RS2's Configuration Card do, a paste also installs the copied
  upgrades from the player's inventory (reporting any missing), and a Crafter paste re-encodes
  its patterns from blank patterns in the inventory.
  Files: `LabelToolContent.java`, each device's settings codec.
  Done when: GameTests paste each kind, refuse a paste across kinds, refuse a gateway paste
  without Manage, install an upgrade from the inventory, and report a missing blank pattern.

## Wave C — terminal and wireless (lane T)

- [ ] **Q14 — Open the Wireless Terminal with a key (M14; Curios part needs QD1).** Size M.
  A key (default unbound, as AE2 and RS2 do) opens the first charged, bound Wireless Terminal
  found in the inventory, then in a Curios slot when Curios is present. Pressing it again closes
  the terminal (AE2). If two terminals are found, it says so rather than guessing (RS2).
  Files: `platform/wireless`, a key mapping, new optional `client/integration/curios`, an ADR.
  Done when: client runs with and without Curios open the terminal by key; the ArchUnit rule
  keeps Curios off mandatory loading.

- [ ] **Q15 — Wireless range upgrade (M15).** Size S.
  Throughput and Overdrive installed on an Access Point raise its range (for example 64 → 128
  → 256) and its idle cost. Same install rules as other devices (ADR 0054).
  Files: `AccessPointEntity.java`, `WirelessAccess.java`, ADR 0054 amendment, balance table.
  Done when: GameTests cover range per tier and the extra cost; PLAYER_GUIDE table updated.

- [ ] **Q16 — EMI support (M16, needs QD2).** Size M.
  Optional EMI plugin with the same features as JEI: crafting and processing transfer (after
  Q5), ghost-slot drag into filters, program-editor drag, and Ctrl-click to request.
  Files: new `client/integration/emi`.
  Done when: a client run with EMI covers each feature; ArchUnit keeps EMI isolated.

- [ ] **Q17 — Recipe-viewer search sync (M17).** Size S.
  A terminal setting (saved with ADR 0052 preferences) copies the terminal search into JEI (and
  EMI after Q16) and back.
  Done when: a JEI client run shows the search mirrored both ways; the preference round-trips.

- [ ] **Q18 — Terminal conveniences (M18).** Size M. Check each first.
  (a) Items crafted in the last session are pinned to the top of the grid until the screen
  closes. (b) Shift-double-click on an inventory stack deposits every matching stack. (c) An
  auto-focus-search option. (d) Pick-block with a Wireless Terminal in the inventory pulls the
  block from the network into the hotbar when the player has none.
  Files: `TerminalScreen.java`, `TerminalMenu.java`, `platform/wireless`.
  Done when: each part has a GameTest or client step, and (d) charges the handheld's normal
  move cost and needs Withdraw.

## Wave D — device consistency (lane D)

- [ ] **Q19 — Upgrade slots (M19).** Size M.
  Every device that takes an upgrade shows its slot on its screen (Importer, Exporter, Storage
  Interface, Buffer, cable port, Crafter after Q2, Gateway link). Click with an upgrade on the
  cursor installs it; click an installed one to take it out (Overdrive first). Removing one
  follows ADR 0054's pause rules and lowers the rate to fit.
  Upgrade items list the devices that take them, and each upgrade slot lists what it accepts
  (M88). Optional, after the rest: a tool that holds a set of upgrades and offers them in every
  upgrade slot (M89, AE2 toolbelt).
  Files: `platform/upgrade/*`, the device screens, ADR 0054 amendment.
  Done when: GameTests cover install, removal order, a rate clamped on removal, and denial
  without Edit; tooltips are checked in a lang test.

- [ ] **Q20 — Energy Bank screen and in-place upgrade (M20).** Size S.
  Empty-hand use opens a kit screen with charge, input and output per tick, and a module slot
  that upgrades the bank in place, keeping its energy. The offhand path stays.
  Files: `EnergyBankBlock.java`, `EnergyBankEntity.java`, new menu/screen.
  Done when: GameTests cover an in-place upgrade keeping energy and a refused downgrade.

- [ ] **Q21 — Controller energy buffer (M21, needs UD3).** Size S.
  As UD3 decides.

- [ ] **Q22 — Wrench (M22).** Size M.
  A Wrench item. Use on an Importer, Exporter, Storage Interface or Crafter to turn its socket
  to the next face that touches a block; sneak-use to pick the device up with its settings and
  contents, as breaking does today. Turning rebinds through the existing Interface-switch rules
  (Edit, nothing in transit). Recipe in the Starter tier.
  Files: new `WrenchItem`, `InterfaceBlock.java`, `PortBlock.java`, `InterfaceSwitch.java`.
  Done when: GameTests cover a turned Exporter now feeding the new face, a refused turn while a
  move is in transit, and a pick-up keeping settings; `RecipeProgressionTest` updated.

- [ ] **Q23 — Partition from contents (M23).** Size S.
  A **From contents** button in the Drive's partition editor fills the slots from the cell's
  first 16 stored types.
  Files: `DriveMenu.java`, `DriveScreen.java`.
  Done when: a GameTest fills a partition from a cell holding three types.

- [ ] **Q24 — Signal watches a filter (M24).** Size S.
  Signal mode takes the same ghost-slot filter as Buffers (items or tags, up to 16) and compares
  the summed stock. Also: watch stored network energy (M92, AE2 Energy Level Emitter), an
  **equal to** comparison (RS2 Detector), and **emit to craft**: a craft step that turns the
  signal on until the item arrives, so a redstone-driven machine can be a crafting step (M92,
  AE2 Level Emitter with Crafting Card; the last part needs a QD19-style ADR amendment).
  Files: `SignalMenu.java`, `SignalScreen.java`, `WorldSignals.java`, ADR 0059 amendment.
  Done when: GameTests cover a tag filter crossing its threshold and a reload.

- [ ] **Q25 — Energy use in the Overview (M25).** Size S. Check first.
  Show the network's FE use per tick and the five largest users, and a list of devices with
  their count and use (M91, AE2 network status).
  Files: `OverviewMenu.java`, `OverviewScreen.java`.
  Done when: a GameTest with a Program Controller and an Access Point reports both.

- [ ] **Q26 — Creative items (M26, needs QD13).** Size S.
  Creative energy source block, creative item and fluid cells, creative Wireless Terminal (no
  charge, no range limit in one dimension). Creative tab only.
  Done when: GameTests cover each; no recipe exists for them.

- [ ] **Q27 — Infinite fluid cell (M27, needs QD14).** Size S.
  Done when: GameTests cover the upgrade path and a reload; the cell table and ADR 0048 are updated.

## Wave E — new blocks (lane B)

- [ ] **Q28 — Storage Monitor (M28).** Size M.
  A block placed on a cable shows one item's count on its face. Use with an item to set what
  it shows (needs Edit); use with an empty hand to take a stack; use with the shown item to
  deposit. Every take and deposit goes through the shared player-transfer path.
  Files: new block, block-entity renderer, `TransferEndpoints.java`.
  Also: a second use within half a second deposits every matching item from the inventory
  (RS2, AE2 Conversion Monitor); Sneak-use locks what it shows (AE2); using it at zero on a
  craftable item opens a craft request (both).
  Done when: GameTests cover take, deposit, deposit-all, a refused take without Withdraw and full
  storage; a screenshot shows the count.

- [ ] **Q29 — Coloured cables (M29, needs QD5).** Size M.
  Dye a cable in hand or in the world; a water bucket recipe or a wet sponge clears it. Bounded
  discovery treats different colours as not connected. Also a **cable anchor** that blocks one
  side of a cable from connecting (M93, AE2).
  Done when: GameTests cover two coloured runs side by side staying two networks, uncoloured
  joining both, and recolouring rebuilding the network.

- [ ] **Q30 — Facades (M30, needs QD6).** Size M. After Q29.
  Done when: a facade hides a cable without changing its connections, breaks off on its own,
  and renders at scale 1 and 4 screenshots.

- [ ] **Q31 — Relay (M31, needs QD7).** Size S.
  Done when: GameTests cover the branch going offline when unpowered, back online when powered,
  and the rebuild rate limit.

- [ ] **Q32 — Portable storage (M32, needs QD8).** Size L.
  Done when: GameTests cover opening, taking, depositing, charge use and another player
  refused.

- [ ] **Q33 — Bulk cell tools (M33, needs QD14).** Size M. Only if QD14 says yes.

## Wave G — world interaction (lane G, needs QD9)

- [ ] **Q34 — Constructor (M34).** Size L.
  Places blocks or fluids, drops items, or launches fireworks from storage in front of it, with
  a filter, redstone control, scheduling (M76) and speed upgrades (M94, RS2). Own ADR.
  Done when: GameTests cover placing, dropping, a protected position refusing (a cancelled
  place event), and nothing lost when the target is blocked.

- [ ] **Q35 — Destructor (M34).** Size L.
  Breaks blocks, picks up fluid sources, or picks up items in front of it into storage, with a
  filter, Fortune and Silk Touch upgrades. Full storage leaves the block in place; nothing is
  voided. Own ADR.
  Done when: GameTests cover breaking, pickup, full storage leaving the block, and a cancelled
  break event.

## Wave X — distance (lane X, needs QD10)

- [ ] **Q36 — Cross-dimension links (M35).** Size M.
  A link pair may span dimensions with a higher FE cost. While either end's chunk is unloaded
  the link waits; it never loads chunks.
  Done when: GameTests cover a pair across the Overworld and the Nether, and the far end
  unloading.

- [ ] **Q37 — Point-to-point tunnels (M36).** Size L. Only if QD10 approves P2P.

## Wave S — friendly language v3 (lane S, needs QD11)

Every v1 and v2 program and template must compile to identical IR (extend `FriendlyV1PinTest`
with a v2 pin). Each new form gets an additive Wasm import and an SDK wrapper in the same slice.

- [ ] **Q38 — ADR: language v3.** Size M.
  Freeze grammar, limits and diagnostics for Q39–Q45 before code, as D1 did. Fixtures for every
  new form; v1 and v2 IR pinned.

- [ ] **Q39 — Pulse-triggered rules (M37).** Size M.
  `on redstone pulse at machines("button") as "…" { … }` runs once per rising edge at that
  port, using C2's edge rules (several pulses while busy count as one).
  Done when: GameTests cover one run per pulse and none while held.

- [ ] **Q40 — Redstone strength (M38).** Size S.
  `redstone(machines("x")) >= 7`.

- [ ] **Q41 — Leave some in the source (M39).** Size S.
  `move item("stone") from machines("a") leaving 5 to storage` keeps the last 5 per machine.
  Done when: a GameTest leaves exactly 5 in each of two machines.

- [ ] **Q42 — Empty slots only (M40).** Size S.
  `to empty slots in machines("x")`.

- [ ] **Q43 — `has` quantifiers, sides and slots (M41).** Size M.
  `every machine in machines("f") has < 8 of …`, `some`, `one`, `no`; and `has` after `side` or
  `slots`. Reads stay within the host-call budget.

- [ ] **Q44 — Relative sides, several ranges and labels (M42, M43).** Size M. Check M43's labels
  part first.
  `front`, `back`, `left`, `right` from the machine's facing (refused for blocks without one);
  `slots 0..2, 5`; `machines("a", "b")`.

- [ ] **Q45 — Timer offsets (M44).** Size S.
  `every 20 ticks offset 5` spreads rules apart.

- [ ] **Q46 — Program Disk and clipboard (M45, needs QD12).** Size M.
  **Copy program** and **Paste program** buttons in the editor use the system clipboard. A
  Program Disk item carries a program's source (and the labels it uses, on request); using it
  on a controller loads it as a draft; its tooltip shows the labels it uses with machine counts,
  errors and warnings, and (holding Shift) the program. It can be edited from the hand, and
  crafting it alone wipes it (M108, SFM Disk).
  Done when: GameTests cover writing, loading, a too-large program refused, and denial without
  Edit; a client step copies and pastes.

- [ ] **Q47 — Rotating round robin (M46, needs QD15).** Size S. Only if QD15 reopens it.

## Wave I — optional integrations (lane I)

- [ ] **Q48 — Mekanism chemicals (M47, needs QD16).** Size L.
- [ ] **Q49 — ComputerCraft peripheral (M48, needs QD17).** Size L.


## Wave H — help (lane H)

- [ ] **Q50 — Guide hotkey (M49).** Size S.
  Holding a key (default G, rebindable) over an item from this mod opens its Factory Guide page.
  Every device screen gets a **?** button in its title bar that opens that device's page (M82,
  AE2).
  Files: `GuideScreen.java`, `GuideContent.java`, a key mapping, `client/ui` title bar.
  Done when: a client step opens the Importer page from a hovered Importer and from the
  Importer screen's button.

## Wave T2 — terminal depth (lane T, after Q55)

- [ ] **Q61 — Search syntax (M59, M60).** Size M.
  Add `$tooltip`, `*id`, OR (`|`), NOT (`!`), parentheses, quoted text and count filters (`>64`,
  `<=10`, `=0`), parsed by a bounded parser in `core` (query length and node count capped).
  Up/Down history (last 16), right-click clears, a rebindable focus key, and token colours with
  an invalid query marked rather than ignored.
  Done when: a parser test covers each operator, precedence, a malformed query and the bounds;
  a client step shows a coloured query.

- [ ] **Q62 — Sort and view filters (M61, M62).** Size S.
  Add **ID** and **Recently changed** sorts and a **View: All / Stored / Craftable** button,
  saved with ADR 0052 preferences. The tooltip says when and by whom (or which device) an entry
  last changed, if the catalog tracks it; record the decision if it does not.
  Done when: preference round trip and sort-order GameTests; screenshots of each view.

- [ ] **Q63 — Grid shortcuts (M63, M64).** Size M.
  Shift+scroll moves one item in or out; Ctrl+scroll puts one on the cursor; Space-click on an
  inventory slot deposits that whole region (hotbar or main); number keys swap a hovered grid
  entry with the hotbar; holding Shift freezes the sort order; hovering the grid with an item
  shows what a click will deposit; a "showing X of Y" line under a search. Every move goes
  through the existing authorized transfer paths.
  Done when: GameTests cover the one-item moves and a region deposit (with a partial fill); a
  client step shows the freeze.

- [ ] **Q64 — Amount entry and bulk requests (M65, M66).** Size M.
  The amount box accepts `+ - * /` and parentheses (bounded, integer result), `=N` meaning "craft
  only what is needed to reach N stored", ±1/10/64 buttons (Shift/Ctrl for larger steps) and
  scroll. Shift+Start starts without the plan view. From JEI, Ctrl+click on **+** fills what the
  player has and opens one request per missing craftable ingredient, stepped through in a side
  list.
  Done when: `CraftAmountTest` covers expressions, `=N`, overflow and junk; a JEI client step
  requests two missing ingredients.

- [ ] **Q65 — Plan tree and notifications (M67).** Size M.
  A **Tree** toggle in Plan draws the steps as a zoomable tree (bounded by the plan's row cap).
  A **Notify** checkbox per request controls the finish toast. A failed preview offers
  **Retry**.
  Done when: screenshots of a two-level tree at scales 1 and 4; a GameTest checks a job started
  with Notify off sends no notice.

- [ ] **Q66 — Suspend, resume and cancel all (M68, needs QD20).** Size M.
  **Pause** and **Resume** on a job in the monitor keep its reservations; **Cancel all** cancels
  the player's own jobs. Recorded in ADR 0031.
  Done when: GameTests cover a paused job making no progress, resuming, persistence across
  reload, and Cancel all leaving other players' jobs alone.

- [ ] **Q67 — Saved views (M79).** Size S. Low priority.
  Up to 5 saved search-and-filter presets per player, chosen from a row of buttons.

## Wave A2 — crafting depth (lane C)

- [ ] **Q68 — Pattern tooltips (M69).** Size S.
  The pattern tooltip draws its recipe (grid → output; inputs → outputs for processing) with
  alternatives cycling, and holding Shift shows the output on pattern icons in any screen.
  Done when: screenshots of a crafting and a processing pattern tooltip.

- [ ] **Q69 — Larger processing patterns (M72, needs QD18).** Size M.
  Raise the limits as QD18 decides, with a scrolling Inputs/Outputs editor and a planner bound
  test at the new size.
  Done when: GameTests run a 12-input pattern; persistence round-trips it; old patterns load.

- [ ] **Q70 — Crafter lock modes, priority and names (M73, M74, needs QD19).** Size M. After Q2.
  On the Crafter screen: **Lock** (never, until the machine is empty, until outputs return,
  while powered, while unpowered, until a pulse) and **Priority** (−1000 to 1000). The Crafter's
  anvil name (Q77) labels it in the Patterns view and job steps; without one it uses the
  machine's name.
  Done when: GameTests cover each lock mode, priority choosing between two Crafters, and the
  name shown in a job step.

- [ ] **Q71 — Fluid substitution (M71, needs QD21).** Size S. Check first.
  Done when: a GameTest crafts a cake with water from fluid storage and empty buckets.

## Wave D2 — devices and storage (lane D)

- [ ] **Q72 — Cell and drive feedback (M70).** Size S.
  Cell tooltip: stored/capacity, types, percent, and the first 8 stored entries with icons. Drive
  bays: empty, partial, near full (≥ 75%), full and offline lamps.
  Done when: a GameTest checks the tooltip data; screenshots of the bay states.

- [ ] **Q73 — Stock per slot (M75).** Size M.
  Buffer Stock mode takes an amount per filter slot (click a slot to set it), so one Buffer
  stocks several items to different levels.
  Done when: GameTests stock 64 coal and 16 iron in one Buffer and survive a reload.

- [ ] **Q74 — Exporter scheduling (M76).** Size S.
  **Order:** In order (default), Round robin, Random, for Exporters with several filter
  entries.
  Done when: a GameTest shows round robin alternating two items.

- [ ] **Q75 — Storage routing options (M77, needs QD23).** Size M.
  Separate insert and extract priority on Drives and Storage Interfaces; a redstone setting
  that takes a storage out of the network while off; **Filter on extract**; **Report
  inaccessible**. Check first, and add if missing, that same-priority storage already holding
  an item is preferred.
  Done when: GameTests for each option and the seeded conservation suite.

- [ ] **Q76 — Durability matching (M78).** Size S.
  A filter slot's match cycle gains **damaged below 25/50/75/99%**.
  Done when: filter tests and a GameTest that exports only worn tools.

- [ ] **Q77 — Device names (M81).** Size S.
  Every device keeps its anvil name, shows it as its screen title and in lists (Patterns view,
  job steps, Overview, Access), and keeps it when picked up (Q22) or copied (Q13).
  Done when: GameTests cover naming, reload and pick-up.

- [ ] **Q78 — Fresh cell on creative pick (M86).** Size S.
  Creative middle-click on a cell gives a new, empty, unbound cell rather than a copy of the
  handle.
  Done when: a GameTest picks a stored cell in creative and finds a fresh one.

- [ ] **Q79 — Statistics (M90).** Size S. Low priority.
  "Items stored" and "Items taken" player statistics.

Q80 is not used: the upgrade toolbelt (M89) is part of Q19.

## Wave H2 — help and onboarding (lane H)

- [ ] **Q81 — Advancements (M80).** Size M.
  A progression tree that teaches the play order: first network, first cell, terminal, first
  Importer/Exporter, auto smelter, first pattern, first job, keep-stock, gateway, wireless,
  first program, label tool, each tier. Triggers fire from real actions, not from crafting.
  Done when: GameTests grant each advancement from its action; lang test covers titles.

- [ ] **Q82 — Guide scenes (M82).** Size L. After Q50.
  Guide pages for multi-block builds (auto smelter, gateway, wireless link) show a rotatable 3D
  scene of the build instead of a flat picture. Investigate GuideME as an optional dependency
  first (needs an ADR if adopted).
  Done when: the auto smelter page shows a rotatable scene at scales 1 and 4.

- [ ] **Q83 — Hold-Shift help (M87).** Size S. Check first.
  Every item and every screen control has a one-line hold-Shift help.
  Done when: a lang test finds a help key for every registered item and screen button.

- [ ] **Q84 — Translations ready (M85).** Size S.
  Make every player string translatable (no literals), document how to add a language, and add
  a lang test that other language files only use known keys.
  Done when: the test passes with a stub second language.

## Wave A3 — admin and config (lane A, needs QD22)

- [ ] **Q85 — Server config (M83, M95).** Size M.
  The QD22 list as `FactoryCoreConfig` entries with today's values as defaults and bounds, a
  `programs.enabled` emergency stop (programs pause and say why), and a minimum rule interval
  with a faster minimum for energy-only rules. Documented in the admin guide.
  Done when: a config test covers bounds; GameTests cover the emergency stop and a changed range.

- [ ] **Q86 — Client options (M84).** Size S.
  Large count font, smooth scrolling, auto-focus search, finished-job toasts on or off.
  Done when: each option has a client step or unit test.

- [ ] **Q87 — Admin conveniences (M109).** Size S. Low priority.
  `/factorycore config show`, `/factorycore kit` (operators), and the changelog as a guide page.

## Wave S2 — program tools (lane S; Q88 needs QD11)

- [ ] **Q88 — Limits per item, per match and tag expressions (M96, M97, M98).** Size M.
  `move 5 of item("iron_ingot"), 3 of item("gold_ingot") from …`, `limit 5 each` for globs and
  tags, `tag("c:ores/*")`, `tag("a") and not tag("b")`, and `side up, north` / `each side`.
  Part of the v3 ADR (Q38).
  Done when: compiler and evaluator tests for each; v1/v2 IR pins unchanged.

- [ ] **Q89 — World-aware checks (M99).** Size M.
  **Check** also reports, against the live network: labels used in code with no machine,
  labels on machines that no rule uses, labelled machines off the cables or with no inventory,
  unknown IDs, and moves whose side or slots match nothing. **Fix** removes stale labels after a
  confirm. **Copy report** puts the program, versions and problems on the clipboard.
  Done when: GameTests produce each warning; the fix removes only stale labels.

- [ ] **Q90 — Inspections (M100).** Size M.
  Ctrl+click in the editor: on a label, list its machines with positions and loaded state; on
  a condition, evaluate it now; on a move, show what it would move in each branch. Read-only,
  VIEW-checked, bounded output.
  Done when: GameTests for each inspection, including an unloaded machine.

- [ ] **Q91 — Editor conveniences (M101).** Size S. Check each first.
  Ctrl+/ toggles `#` comments, optional line numbers, Shift+Enter saves the draft, and closing
  with unsaved edits asks first.
  Done when: editor unit tests and a client step.

- [ ] **Q92 — Program logs and timing (M102).** Size S.
  A log level per program (off, errors, info, detail; detail turns itself off after one run),
  **Copy** and **Clear**, and a small graph of the last 20 run costs.
  Done when: GameTests cover levels and the auto-off; a screenshot of the graph.

- [ ] **Q93 — Templates from resource packs (M105).** Size S.
  Load extra templates from `factorycore/program_templates/*.fcp` in any resource pack; ones that
  do not compile show "(does not compile)".
  Done when: a test pack adds one template that appears and compiles.

## Wave L2 — labels (lane L)

- [ ] **Q94 — Label list and removal (M104).** Size M. After Q9–Q11.
  The Label Tool screen lists the network's labels with machine counts; clicking one selects it.
  Shift-use removes the tool's label from the machine (all machines with the row key, after a
  confirm). Shift+scroll and next/previous keys switch labels. View modes: all labels, the
  current label only, the targeted block only.
  Done when: GameTests cover removal with confirm and denial on another network; client steps
  for scroll switching and each view mode.

- [ ] **Q95 — Slot inspector (M103).** Size S.
  A key (default Ctrl+I) in any container screen overlays slot numbers as automation sees them,
  with a note that screen slots and automation slots can differ; **Copy move lines** writes
  `move … side … slots …` lines for that container.
  Done when: a client step on a furnace screen; a GameTest checks the generated lines.

## Wave B2 — cables and world (lane B)

- [ ] **Q96 — Cable anchor (M93, needs QD5).** Size S. With Q29.
- [ ] **Q97 — Cauldrons (M106).** Size S. Low priority.
  Programs, Importers and Exporters can fill and drain cauldrons.
  Done when: a GameTest fills a cauldron with water from storage and drains it back.
- [ ] **Q98 — Tunnelled and tough cables (M107, needs QD24).** Size M. Only if QD24 says yes.

## Wave N — nits (each lane picks up its own; no gate)

- [ ] **Q99 — Terminal nits (M110–M112, M125, M127).** Size S. Lane T, after Q55.
  A running-jobs count on the Jobs tab; a "clear recipe-viewer search on open" option (with
  Q17); scrolling long titles; a buckets/mB toggle saved with the terminal preferences; a
  finished-job toast while a charged, bound Wireless Terminal is carried.
  Done when: each has a client step or GameTest.

- [ ] **Q100 — Crafting nits (M113–M118).** Size M. Lane C, after Q3 and Q5.
  Recipe-viewer encoding prefers plentiful, undamaged items and warns about the wrong mode or a
  recipe over 3×3; an amount box and a "main output" choice for processing slots; the Patterns
  view gets search modes, the Visible / Not full / All filter, a per-Crafter hide toggle and
  Shift-click into the first free Crafter; job steps name their machine; Exporter filter slots
  show status icons; check whether encoding should also take blanks from a container beside the
  terminal, and record the decision.
  Done when: GameTests for the Patterns view options, step machine names and Exporter status.

- [ ] **Q101 — Device and storage nits (M119–M124, M126, M141).** Size M. Lane D.
  Drives expose cell slots to hoppers (insert into an empty bay, extract whole cells; through
  the same ownership path as the screen). **Check M120 first:** if a cell accepts a filled
  shulker box or another cell, refuse it and add a regression test. Sneak-use on an empty cell
  returns its materials. An optional per-cell "spread evenly" partition setting. The Storage
  Interface names its container. Stock-mode slots refuse foreign items. Wireless drain scales
  with distance (balance change; record it in ADR 0039). F3+H shows a cell's handle ID.
  Done when: GameTests for hopper cell moves, the nesting refusal (or evidence that it is
  already refused), cell disassembly and foreign insertion.

- [ ] **Q102 — Label and program nits (M128–M136).** Size S. Lanes L and S.
  The Label Tool's name shows its label; row labelling includes diagonals (with Q9); clicking a
  block narrows the network view (with Q12); keywords are case-insensitive (a v1/v2 pin test
  proves no program changes meaning); Shift+Tab outdents; trailing commas are allowed in lists;
  the program name shows on the controller and the disk (with Q46); four teaching templates
  (filters, limits, tags and timers, conditions); a scratch editor from the title screen.
  Done when: compiler and editor tests, and `ProgramTemplatesTest` covers the new templates.

- [ ] **Q103 — Pack-maker and support nits (M137–M140).** Size S. Lane A.
  Item tags for every device family and `c:tools/wrench` on the wrench; guide pages that show
  live config values (after Q85); a crash-report section with network, job and program counts.
  Facade details (M140) go with Q30.
  Done when: a data test lists the tags; a guide page shows a config value.

## Wave V — verification

- [ ] **Q51 — Ergonomics re-audit.** Size S. After Waves A–C.
  Repeat the comparison table with screenshots, close or reopen M1–M141, and feed findings from
  the [H12 playtest](PLAYTEST_PLAN.md) into new slices here.
  Done when: an evidence file under `docs/evidence/` lists each finding's state.

## Not in scope

Deliberate differences that stay unless the user reopens their gate:

- Channels and cable-count limits (SPEC R11), voiding (AE2 Void Card), and chunk loading.
- Jade/WTHIT look-at status: UD6 resolved no; screens carry status.
- SFM `each` as a per-inventory total: `for machine in machines("x")` covers it (ADR 0062).
- AE2 Spatial IO, meteorites, the Inscriber/processor progression and Matter Cannon: outside a
  logistics mod.
- AE2 Spatial Anchor and other chunk loaders; AE2 `/ae2 channelmode`; AE2 Matter Condenser,
  RS2 Void excess and AE2 Void Card (voiding).
- Jade, The One Probe and WTHIT: AE2 supports all three, RS2 none; UD6 stays no.
- RS2 security "Build" permission (who may break or place network blocks) and AE2's
  world-interact gating: SPEC R04 keeps this mod out of claims; protection mods own that.
- RS2 several Controllers per network: the one-controller rule stays (two connected controllers
  stop the network on purpose).
- AE2 crafting CPUs (storage, co-processors, CPU selection): jobs use Crafters, job limits and
  the fair scheduler instead.
- AE2 parts on cables (placing a part on either side, swapping part and block forms) and Quartz
  Fiber: devices here are blocks, and gateways share energy between networks.
- SFM regular expressions in programs: ADR 0062 keeps globs bounded. SFM's "inputs build up
  until FORGET" model: every `move` here names its source.
- SFM printing press, XP shards, anvil disenchanting and water tank; AE2 quartz tools, Entropy
  Manipulator, Charged Staff, Color Applicator, Inscriber, Charger, Growth Accelerator, Sky
  Stone and decorative blocks; RS2 processors and the anniversary cape. They are not logistics.
- Experimental SFM features behind flags that are off in its 1.21.1 release (client-side
  programs, packet computation, command palette, CC: Tweaked peripheral).
- Weakening the shared authorization, scheduling or conservation rules for convenience.
- Save migrations for removed blocks or modes (UD7 still applies).
