# Feature parity plan

## Purpose and authority

This plan turns the 2026-10-24 parity audit against Applied Energistics 2 (AE2), Refined
Storage (RS) and Super Factory Manager (SFM) into ordered, loopable slices. The goal is
that a player arriving from any of those mods finds the everyday features they expect,
without giving up what Factory Core already does better: FE storage, per-player roles,
gateway subnetworks, bounded Rust/Wasm programs and crash recovery.

Parity means the **everyday loop**, not a clone. Deliberate differences stay: no channels
or cable-count puzzle (SPEC R11), no voiding, no implicit chunk loading, and every action
goes through the shared authorization, scheduling and accounting paths (AGENTS rule 4).

[SPEC.md](../SPEC.md) owns behavior. Slices marked **needs gate** change a SPEC or ADR
contract and must not start until the gate below is **resolved** by the user.
[IMPLEMENTATION.md](IMPLEMENTATION.md) keeps owning acceptance evidence; each slice
updates it when evidence changes. The [UX plan](UX_PLAN.md) is complete; this plan adds
gameplay, which the UX plan excluded.

## How to run one loop iteration

Same loop as the UX plan:

1. Pick the first `[ ]` slice in your lane whose gate is resolved (or has none).
2. Read the files and ADRs listed for it. Follow [AGENTS.md](../AGENTS.md): behavioral
   tests, affected docs, no weakened checks.
3. Run `python3 scripts/verify.py`. For any slice that changes a screen, block or in-world
   interaction, also run the graphical client under the lock and `caffeinate` (see
   [TESTING.md](TESTING.md)) and **look at** the scale 1 and 4 screenshots.
4. Commit on `main` with the slice ID in the subject (for example `P1: Readable terminal counts`).
5. Tick the box, add the commit and evidence path, and record follow-ups as new slices.
6. Stop and ask when the next slice needs an unresolved gate or reveals a SPEC/ADR conflict.

## Going fast: lanes

Slices in different lanes touch different files and can run as parallel worktree agents.
Slices in one lane run in order. Graphical client runs are serialized across agents
(one display, one lock), so batch the screenshot step at the end of a slice.

| Lane | Slices | Main files | Gate |
| --- | --- | --- | --- |
| T — Terminal and crafting UX | P1–P4, A1–A5 | `TerminalScreen`, `TerminalMenu`, `TerminalJobs`, `TerminalJobPackets` | None |
| F — Filters | P5, B1–B4 | `ResourceFilter`, `*SettingsMenu/Screen`, `WorldStorageRoutes`, `CellItem`, `DriveMenu` | None |
| R — Redstone | C1–C3 | `InterfaceBlock`, `InterfaceKind`, `WorldMachinePresets`, `WorldBufferImports` | PD1 |
| L — Language v2 | D1–D9 | `FriendlyCompiler`, `ProgramIr`, `ProgramEvaluator`, `WorldPrograms`, `sdk/rust` | PD2, PD3 |
| X — Crafting depth | E1–E3 | `Pattern`, `CraftingPlanner`, `WorldCraftingJobs`, `CrafterEntity` | PD4, PD5 |
| H — Human | V1 | Survival world | None |

Recommended start, on day one: lanes T and F in parallel (no gates), the user answers
PD1–PD5, and a human starts the V1 playtest. Lane L is the longest; start D1 (the ADR)
the moment PD2 is resolved, because D2–D9 depend on it.

## Audit findings this plan addresses

| ID | Finding | Compared with | Evidence |
| --- | --- | --- | --- |
| G1 | Item/fluid counts are drawn over the icon and are unreadable ("989k" over an emerald). | AE2/RS: small corner font | `build/reports/client-presentation/screenshots/terminal-scale-4.png`, `TerminalScreen.java:757-794` |
| G2 | Craft amounts are ×1/×8/×64 only. The server already accepts 1–4,096 (`TerminalJobPackets.MAX_AMOUNT`). | AE2/RS: typed amount | `jobs-preview-scale-2.png` |
| G3 | Preview shows only the first missing ingredient. | AE2/RS: full stored/missing/to-craft list | `jobs-missing-scale-4.png` |
| G4 | Craftable items are not in the Items grid; Jobs is a one-at-a-time list with Home/Next paging. | AE2/RS: craftables in the grid | `TerminalScreen.java:581-650` |
| G5 | Running jobs are small chips ("2/2 ⇩"); no per-step monitor. | AE2 crafting status, RS crafting monitor | `jobs-started-scale-2.png` |
| G6 | Storage-mode Interfaces have no item filter; cells cannot be partitioned. Priority is the only routing control. | AE2 storage bus/cell partition, RS filters | `ConnectorSettings.java` (no filter field) |
| G7 | Filters are typed into a text field; no ghost slots or JEI drag. Full-component matching exists only via **Sample** on presets, and not for Storage mode, cells or programs (`item("…")` is registry-only). | AE2/RS ghost slots, fuzzy toggle | `buffer-scale-4.png`, `ResourceFilter.Exact` |
| G8 | No redstone input or output anywhere. | AE2 level emitter, RS detector, SFM redstone triggers | No signal code in `src/main/java` |
| G9 | Every program `move` must have network storage on one side. | SFM: machine to machine | `FriendlyCompiler.java:386` |
| G10 | Program side and slots are fixed by the Interface binding, not per statement. | SFM: `from a top side slots 0-2` | ADR 0035 grammar |
| G11 | Program conditions only check network stock; no `else`, `and`, `or`, `not`, machine contents, wildcards or `except`. | SFM conditions and patterns | ADR 0035 grammar |
| G12 | Labelling N machines needs N Interfaces and N typed labels; SPEC R08 requires in-world binding/label tools and none exist. | SFM label gun | No label item registered |
| G13 | Crafting patterns encode exact items; no tag substitution ("any planks"). | AE2 substitutions | `Pattern.java`, no substitution code |
| G14 | No crafting speed or parallelism upgrade; SPEC R11 Advanced tier requires "more crafting workers". | AE2 co-processors, RS crafter speed upgrades | `WorldCraftingJobs.java` constants |
| G15 | Sort is Name/Qty only, no direction or mod sort. Fluids show bucket icons. | AE2/RS | `terminal-fluids-scale-2.png` |
| G16 | Buffer status truncates ("Online · Rea"); Machine slots are number fields, not the visual picker named in UD4; Access screen showed a raw UUID. | — | `buffer-scale-4.png`, `machine-scale-4.png`, `access-scale-4.png` |
| G17 | No human survival playtest has been run (A17). | — | IMPLEMENTATION A17 |

Deliberately not in this plan's core: world-interaction blocks (place/break/pick up),
view cells, portable cells and cross-dimension links. See Wave G.

## Decision gates (user)

| Gate | Question | Recommendation | Status |
| --- | --- | --- | --- |
| PD1 | How does redstone enter the mod? (a) a new **Signal** mode on the Interface (no container needed: emits redstone by a stock/crafting condition), plus a redstone setting on Machine/Buffer presets; or (b) a separate Level Emitter-style block. Amends SPEC R02. | (a): keeps the block count down after UD1 | Resolved 2026-10-25: (a) Signal mode |
| PD2 | Unfreeze the friendly language for a **version 2** that is a superset of version 1 (every v1 program compiles unchanged), with additive `factory_v1` Wasm imports rather than a new ABI? Amends ADR 0035/0037 and SPEC R07's examples. | Yes, superset + additive imports | Resolved 2026-10-25: yes, superset + additive imports |
| PD3 | Labelling model. (a) Keep one Interface per machine, and add a **Label Tool** that applies a label, and optionally copies Interface settings, to many Interfaces quickly; or (b) SFM-style: a labelled machine needs only to touch a cable, no Interface. (b) changes SPEC R02/R04 discovery and authority rules. | (a) now; revisit (b) after the playtest | Resolved 2026-10-25: (a) Label Tool |
| PD4 | Allow opt-in tag **substitution** in crafting-table patterns (inputs chosen from current stock at reservation time; the recipe is revalidated before crafting)? Processing patterns stay exact. Amends SPEC R06 and ADR 0029/0030. | Yes, crafting patterns only, per-pattern toggle | Resolved 2026-10-25: yes, crafting patterns, per-pattern toggle |
| PD5 | How do crafting workers scale (R11)? (a) Throughput/Overdrive upgrades installed in a Crafter raise its crafts per dispatch and concurrent jobs; or (b) a new Crafting Core block shared by the network. | (a): reuses existing items and ADR 0054 rules | Resolved 2026-10-25: (a) Crafter upgrades |
| PD7 | Chunk loads next to a network rebuild it. ADR 0011 invalidates every region in the four neighbouring chunk indexes whenever a chunk loads, so a player walking near a base briefly takes its networks offline (moves and programs refuse with "offline" for the rebuild). Narrow this to regions with a position on the shared chunk edge (bounded: 16 × build height lookups per side)? That amends ADR 0011 and its `firstNeighborChunkLoadInvalidatesExistingBoundaryPublication` test. | Yes; measure the callback cost first (ADR 0011 asks for P7 evidence) | Open (found 2026-10-25 during D2/D3: the same rebuilds made 4 older GameTests fail now and then). Related fix done: a program run that came due during a rebuild paused the program permanently ("Deployer lost Deploy permission"); it now skips that run (`MachineToMachineGameTests.rebuildsWhileRunningNeverPauseTheProgram`, which failed before the fix). |
| PD6 | Add world-interaction blocks (place, break, pick up, drop) in this release? | No; keep as Later | Resolved 2026-10-25: yes; each Wave G feature still gets its own gate and ADR |

## Wave 0 — quick wins (no gate)

- [x] **P1 — Readable counts (G1).** Size S. Lane T.
  Draw counts in a half-scale font, right-aligned in the slot's bottom-right corner, with
  a shadow, as AE2/RS do. Same for fluid mB and job chips. Keep the exact count in the
  tooltip.
  Files: `TerminalScreen.java` (`renderCount`, `compact`), `DriveScreen` if it shares the helper.
  Done when: the presentation tour shows legible 1k/989k/1.0M at scales 1–4 with no
  overlap on 3D block icons; a client-side unit test covers `compact` boundaries
  (999, 1,000, 999,999, 1,000,000, Long.MAX).
  Evidence: `CountTextTest`; `build/reports/client-presentation/screenshots/terminal-scale-4.png`
  shows 1.2k/999k/64 in the corner clear of the icons. From GUI scale 2 counts are half size;
  at scale 1 they stay full size (half-size glyphs would be under one pixel) and shrink to fit.
  Counts are truncated, never rounded up (`1.0k`, `989k`, `9.2E`).

- [x] **P2 — Sort and fluid icons (G15).** Size S. Lane T.
  Add **Mod** sort and an ascending/descending toggle, saved with the existing per-player
  search preferences (ADR 0052). Render fluids with their still texture and tint instead
  of a bucket.
  Files: `TerminalScreen.java`, `TerminalMenu.java`, terminal preference codec.
  Done when: native test round-trips the new preference fields; client tour shows each
  sort and a water/lava fluid grid at scales 1 and 4.
  Evidence: `TerminalSearchSupportTest`, `TerminalPacketsTest`, the extended
  `TerminalGameTests` sort test (reversed quantity persisted across reopen, invalid mode
  refused); presentation tour `terminal-sort-{qty,mod,mod-reversed,name-reversed,name}-scale-4.png`
  and `terminal-fluids-scale-{1,4}.png` (row orders logged in `client-presentation/result.txt`).
  The direction is a bit in the existing `Sort` preference, so the saved schema did not change.
  The terminal tour passed on a rerun after one real-cursor Buffer hover flake.

- [x] **P3 — Status and Access fixes (G16).** Size S. Lane T.
  Wrap or abbreviate the status strip so it never truncates mid-word (full text on hover
  stays). Show player names (from the server profile cache) on the Access screen, with
  the UUID only in the tooltip; verify with two real clients.
  Files: `StatusStrip.java`, `NetworkAccessMenu.java`, `NetworkAccessScreen.java`.
  Done when: buffer scale 4 shows a whole word; the two-client run shows the second
  player's name.
  Evidence: `FitTextTest` (whole parts dropped first, then whole words, "…" marks the cut);
  `buffer-scale-4.png` shows "Online …"; `runMultiplayerCheck` passes with the new
  `owner-access-names.png` step (rows and owner line named `FactoryGuest`/`FactoryOwner`).
  Names were already resolved server-side; a principal unknown to the profile cache now
  reads "Unknown b1c26c" with the full UUID on hover (`access-scale-{1,4}.png`).
  Also repaired the two-client check, stale since U3 removed the Take 1 button: it now sends
  the grid's withdraw payload, and its view-only step retries a stale-revision drop as
  `take()` already did. P1: 83f2b48. P2: f41f84c.

- [x] **P4 — Jobs list scrolls (G4, part).** Size S. Lane T.
  Replace Home/Next in Jobs with the same scrolling list as Items; remove the `HOME`/`NEXT`
  commands.
  Done when: native job-view tests page by window; client shows 20+ craftables scrolling.
  Evidence: `TerminalJobGameTests.craftableListScrollsByWindow` (22 craftables, offset 3
  shifts by three, past-the-end clamps to the last full window, KEEP index relative to the
  window, out-of-bound offset refused), `TerminalJobPacketsTest`; presentation
  `jobs-scroll-top-scale-4.png`, `jobs-scrolled-end-scale-{1,4}.png` (23 craftables, one
  notch moves one, last window offset 15). Payload protocol 4 → 5. P3: 1e3f5b1.

- [x] **P5 — Visual slot picker (G16, UD4 leftover).** Size M. Lane F.
  Replace First slot / Slot count fields on Machine and Storage modes with a clickable
  strip of the container's slots (shift-click to range-select). Fields stay for
  containers over 64 slots.
  Files: `MachineSettingsScreen.java`, `ConnectorSettingsScreen.java`, their menus.
  Done when: GameTests cover selection round-trip and the >64 fallback; scale 1/4 fit.
  Evidence: `ConnectorSettingsGameTests.viewsReportContainerSizeAndPickedWindowRoundTrips`
  (chest 27 slots, picked window 3+2 saved and shown again, a 100-slot fixture reports 100 so
  the fields stay, no container reports 0) and `machineViewReportsBoundFaceSize` (hopper 5);
  `SlotSelectionTest` (click/Shift-click, ≤64 rule, cell mapping with a second row past 32);
  `machine-scale-{1,4}.png`, `connector-scale-{1,4}.png`, `connector-picked-scale-4.png`
  (real click on slot 3); the terminal tour now picks slot 2 with a real click on the Machine
  strip and its server check confirms Apply saved first slot 2, count 1. Views carry `itemSlots`/`tanks` for the bound face (Machine
  payload protocol 2 → 3, Connector 1 → 2). Shift-click range was unit-tested only; the
  tour cannot hold a real Shift key. P4: fb86b1e.

## Wave A — crafting UX (no gate; lane T)

- [x] **A1 — Typed craft amount (G2).** Size S.
  Add an amount box beside ×1/×8/×64 (Enter previews). Decide in the slice whether to
  raise `MAX_AMOUNT` above 4,096; if raised, prove the planner's `LIMIT` result is shown
  as a readable message, not a silent cap, and add a large-amount planner test.
  Files: `TerminalScreen.java`, `TerminalJobPackets.java`, `TerminalJobs.java`.
  Done when: native tests cover typed, zero, over-limit and non-numeric input; the client
  previews and starts a typed 37.
  Decision: `MAX_AMOUNT` stays 4,096. Raising it would let one job reserve far more than a
  drive tier holds and lengthen planner work per request for no everyday need; over-limit
  input says "At most 4096 per job. Start several jobs for more." rather than capping.
  Evidence: `CraftAmountTest` (typed, padded, comma, zero, over-limit, overflow, negative,
  decimal, words), `TerminalJobGameTests.typedAmountPreviewsAndStartsExactly` (37 previews 15
  crafts, the job is for 37 and reserves 5 logs, delivers 40 sticks; 0 and 4,097 refused at
  decode); presentation `jobs-typed-37-scale-2.png` and `jobs-typed-37-started-scale-2.png`
  (typed into the real box, Enter, Start). P5: 5b9548b.

- [x] **A2 — Full plan breakdown (G3).** Size M.
  Send a bounded plan summary with the preview: one row per resource with **stored**,
  **to craft** and **missing**, plus cycle explanations. Bound it (for example 64 rows)
  and say "and N more" past the bound. Missing rows are highlighted; Start is disabled
  only when something is missing.
  Files: `CraftingPlanner` (already returns `steps`, `consumed`, `missing`, `cycles`),
  `TerminalJobs.java`, `TerminalJobPackets.java` (new bounded `PlanRow` list), `TerminalScreen.java`.
  Done when: native tests cover a two-level recipe, a shortage, a cycle and the row bound;
  client screenshots of each at scale 4.
  Evidence: `PlanRowsTest` (two-level stored/crafted units, shortage first, cycle, 4-row
  bound with remainder), `TerminalJobPacketsTest` (round trip, 65 rows refused),
  `TerminalJobGameTests.previewSendsFullPlanBreakdown` (8 sticks: 2 planks stored + 4
  crafted from 1 log; 64 sticks: log row first, 7 missing; coal/charcoal loop named, nothing
  reserved); presentation `jobs-plan-two-level-shortage-scale-4.png` (200 sticks: 48 planks
  stored, 52 crafted, 13 logs missing), `jobs-plan-cycle-scale-4.png`,
  `jobs-plan-bound-scale-4.png` and `-end-` (64 rows, "…and 27 more" of a 91-resource plan).
  Found and fixed on the way: the new button key duplicated `job.plan` and silently replaced
  the plan summary line (`LangKeysTest.everyKeyIsDefinedOnce` now guards this), and a
  rate-gated WINDOW request could freeze the craftable strip's pending offset (it now expires
  after 20 ticks). Start remains disabled whenever the preview has a reason (missing or limit).
  A1: 6974803.

- [x] **A3 — Craftables in the Items grid (G4).** Size M.
  Mark catalog rows that a visible pattern can produce. Show craftable-but-absent items
  as zero-count ghost entries when a **Show craftable** toggle is on (saved preference).
  Middle-click, or clicking a zero-count entry, opens the request with the amount box.
  Patterns from subnetworks appear only where gateway policy already publishes them (ADR 0033).
  Files: `WorldStorageSearch.java`, `TerminalMenu.java`, `TerminalScreen.java`, `WorldCrafting` index.
  Done when: native tests prove craftable flags respect gateway CRAFT policy and page
  bounds; client shows the toggle and opens a request from the grid.
  Evidence: `CraftableGridGameTests` (published branch planks/sticks appear as zero rows
  with the toggle, a REQUEST scrolls Jobs to the stick, a non-craftable row requests
  nothing, the toggle persists across reopen and hides zero rows when off; a gateway
  without branch CRAFT flags nothing; 4,097 keys refused; a one-row window at offset 2
  shows the flagged last row), `TerminalPacketsTest` (flag/toggle round trip, action
  bounds); presentation `items-craftable-scale-4.png` (+ ghosts, orange corners on stocked
  craftables) and `items-request-scale-4.png` (real click on the ghost Stick opens Jobs
  with Stick selected and the amount box focused). Terminal payload protocol 5 → 6.
  A2: 95d3b5c.

- [x] **A4 — Crafting monitor (G5).** Size M.
  A job detail view: each step with crafts done/total, the item it waits on and why
  (missing, busy machine, storage full), and Cancel. Players see their own jobs; Manage
  sees all jobs on the network.
  Files: `CraftingJob.java` (already holds steps and reasons), `TerminalJobs.java`,
  `TerminalJobPackets.java`, `TerminalScreen.java`.
  Done when: native tests cover visibility (own vs Manage), a waiting processing step and
  cancel from the detail view; client screenshots of a running and a waiting job.
  Decision: Manage shows other players' jobs read-only. SPEC R07 lets Manage administer
  grants, not cancel others' work, so cancel stays with the requester or an operator.
  Evidence: `TerminalJobGameTests.monitorShowsStepsAndManageSeesAllJobs` (a member lists
  only their job; the owner with Manage lists both, the member's named; the member's
  waiting step shows `machine_missing` and is not cancellable, and Cancel is refused; the
  owner cancels its own from the detail view, which closes when the job is gone),
  `TerminalJobPacketsTest`; presentation `jobs-monitor-running-scale-2.png` (37 planks,
  3/10 crafting) and `jobs-monitor-waiting-scale-4.png` (smelting with no machine, reason
  on its own line), each opened with a real click on the job row. A3: 2eba58f.

- [x] **A5 — Crafting done notification and request from JEI.** Size S.
  A toast instead of chat for "job finished" (chat stays when the screen is closed). With
  JEI, Ctrl-click on a recipe output that the network can craft opens the request.
  Files: `WorldCraftingJobs.java` notices, `platform/client/integration` (JEI stays optional and isolated).
  Done when: client run with and without JEI; ArchUnit rule still keeps JEI off mandatory loading.
  Evidence: presentation run without JEI, `jobs-finished-toast-scale-2.png` (toast over the
  open terminal; the tour now accepts the toast or the chat line); `runJeiClient`
  `jei-request-jobs.png` (JEI's own crafting-table recipe layout through `JeiRequests`
  opens Jobs with Crafting Table selected; the physical Ctrl key and button click are not
  simulated, as for the existing transfer check); `CraftableGridGameTests
  .recipeViewerRequestsOnlyCraftableItems` (diamond and a malformed ID ignored, stick
  opened); `JobNoticeTest`; `ArchitectureTest` passes (JEI stays in `integration`, the
  toast is reached through a sink). The no-screen chat path is unchanged code and was not
  separately captured. A4: 2b33116.

## Wave B — filters (no gate; lane F)

- [x] **B1 — Ghost-slot filter widget (G7).** Size M.
  One shared widget for Buffer, Machine and Storage screens: up to 16 ghost slots; click
  with an item, drag from JEI, or Sample from inventory. Each slot has a match toggle:
  **Item** (`Registry`), **Exact** (`Exact`, components included) or **Tag** (pick from the
  item's tags). Allow/Block toggle. Typed IDs move behind an "Add by ID" button.
  Files: new `platform/client/FilterSlots.java`, `BufferSettingsScreen.java`,
  `MachineSettingsScreen.java`, their menus (they already build `ResourceFilter` terms);
  JEI ghost-ingredient handler in `client/integration`.
  Done when: native tests round-trip each term type through both menus; client shows JEI
  drag and the three match modes at scales 1 and 4.
  Evidence: `BufferSettingsGameTests.ghostSlotTermsCycleAndApply` (cursor item → Item, a
  stale shown ID changes nothing, → Exact → first tag, Apply saves the tag term) and
  `MachineSettingsGameTests.fluidGhostSlotCyclesEveryMatch` (held water bucket → water,
  Item → Exact → tag(s) → Item); packet tests for the new rule rows and actions;
  presentation `buffer-scale-{1..4}.png` (saved Item/Exact/Tag slots) and
  `machine-presets-filter-scale-{1,4}.png` (three drops, then real clicks cycle planks to
  Tag and diamond to Exact); `runJeiClient` `jei-buffer-ghost.png` (JEI's ghost handler
  drops iron onto the Buffer slots). Found on the way: the Buffer menu sent every action
  ≥ TAKE to inventory handling, so new filter actions were ignored; it now bounds the
  range. The Storage-mode screen gets the same widget with B2. A5: fe4546b.

- [x] **B2 — Storage-mode filter (G6).** Size M.
  Add an optional `ResourceFilter` to `ConnectorSettings` (bounded by the existing 16-term,
  256 KiB rules). It limits what priority routes **insert** into that container; the
  terminal still lists and withdraws everything the container holds, so nothing becomes
  invisible. Record the choice in an ADR 0013 amendment.
  Files: `ConnectorSettings.java`, `ConnectorSettingsMenu/Screen`, `WorldStorageRoutes.java`
  (skip non-accepting backings when ordering by priority), connector codec + schema bump.
  Done when: GameTests prove a cobblestone-only chest receives only cobblestone, overflow
  goes to the next priority, a rejected deposit returns to the player, and saved filters
  reload; seeded conservation test still passes.
  Evidence: `ConnectorFilterGameTests` (a cobblestone-only chest at priority 10 takes the 4
  it has room for; the next 6 go to the priority-0 chest once it is full; logs skip it;
  dirt already inside stays listed and is withdrawn; with no other storage a log deposit
  is refused and stays with the player; the reopened screen shows the saved rule),
  `ConnectorDataTest.insertFilterUsesSchemasFiveAndSixAndDropsNothing` (round trip,
  unfiltered ports keep schemas 1/3, missing/future fields rejected), fast verify
  including the seeded conservation suites. ADR 0013 amended. One route still makes one
  transfer: a partial fill leaves the rest with the player (existing behaviour, now stated
  in the ADR), so overflow is shown on the next deposit. The upgrade path now keeps the
  filter. Screen: a **Filter…** tab (the screen cannot grow past 240 units at scale 4),
  `connector-filter-scale-{1,4}.png`. B1: 5b00a9d.

- [x] **B3 — Cell partitions (G6).** Size M.
  A cell carries an optional filter component, edited from its Drive bay (B1 widget).
  Same insertion-only rule as B2. Upgrades keep the filter. Partition and priority
  together let players build sorting systems without chests.
  Files: `CellItem.java`, `CellHandleData`, `DriveMenu.java`, `DriveScreen.java`,
  `WorldStorageRoutes.java`, cell persistence schema.
  Done when: GameTests cover partitioned insert, upgrade keeping the partition, and an
  existing cell outside the partition still being withdrawable.
  Evidence: `DrivePartitionGameTests` (the screen's own command makes a cobblestone-only
  partition; logs are refused and stay with the player while it is the only cell, then go
  to a second cell and never into the partitioned one; cobblestone goes in; dirt stored
  before the partition stays listed and is withdrawn; an in-place upgrade keeps the
  partition and contents; a fluid partition on an item cell is refused); presentation
  `drive-scale-4.png` (⚙ per bay) and `drive-partition-scale-{1,4}.png`. Stored as the
  `cell_partition` item component (documented in PERSISTENCE). The check sits in the
  digital endpoint's DEPOSIT permission, so every insertion path respects it. B2: 7fd1780.

- [x] **B4 — Drive and cell priority.** Size S.
  Add a per-drive priority (Storage-mode containers already have one) so partitioned cells
  win over general storage.
  Done when: GameTest shows a priority-10 partitioned cell filled before a priority-0 chest.
  Evidence: `DrivePartitionGameTests.drivePriorityFillsThePartitionedCellBeforeAChest`
  (priority set through the Drive screen's command; saved as `CellSchema` 2 and reloaded;
  12 cobblestone go to the priority-10 partitioned cell with the chest empty; logs skip it
  and go to the chest); presentation `drive-scale-{1,4}.png` (Priority field). Digital
  catalog rows now carry their drive's priority, so drives and Storage-mode Interfaces
  share one order. B3: 288f1c7.

## Wave C — redstone (needs PD1; lane R)

- [x] **C1 — Signal mode (G8).** Size M.
  Interface **Signal** mode: emits redstone when a condition holds, re-evaluated on the
  shared scheduler at a bounded interval (not every tick). Conditions: network stock of a
  filter compared with a number (VIEW-checked under the placer's grants), or "a job for
  this item is running". Strength 15 or proportional (comparator style).
  Files: `InterfaceKind.java`, `InterfaceBlock.java` (`isSignalSource`, `getSignal`), new
  settings screen, `InterfaceSwitch.java`.
  Done when: GameTests cover threshold crossing, revoked VIEW (signal off, reason shown),
  network offline (off) and reload; client screenshot of the screen and a lit lamp.
  Evidence (2026-10-25): `SignalRuleTest` (strength math); `SignalGameTests` — threshold
  crossing with a lit lamp and proportional 4/8 → 7, no network rebuild on strength change,
  revoked VIEW → off "denied", controller removed → off "offline", reload and Signal ↔
  Buffer switching, job condition on while a smelt job runs and off after cancel.
  `runPresentationClient` PASS with `signal-scale-4.png`, `signal-scale-1.png` and
  `signal-lamp-lit.png` (inspected). Deviation: one watched resource rather than a filter,
  and grants are those of whoever last applied (ADR 0059 amendment).

- [x] **C2 — Redstone control on presets.** Size S.
  Machine and Buffer presets get a redstone setting: Ignore, Active when powered, Active
  when unpowered, One run per pulse.
  Files: `MachinePresets.java`, `BufferPolicy.java`, `WorldMachinePresets.java`,
  `WorldBufferImports.java`, both settings screens, codecs.
  Done when: GameTests for each setting, including a pulse not repeating while held.
  Evidence (2026-10-25): `RedstoneGateTest` (each control, edges, collapse, restore);
  `RedstoneControlGameTests` — Powered machine import stops/starts with the input and
  reports "Waiting for redstone"; Pulse moves exactly one item per rising edge and none
  while held, and saves its state; Unpowered buffer holds while powered and imports when
  off; a player without EDIT is refused. Ignore is the unchanged default (all earlier
  preset GameTests). `runPresentationClient` PASS; `machine-presets-filter-scale-4.png`
  and `buffer-scale-4.png` show the Redstone button (inspected). Deviation: stored on the
  Interface entity rather than in the deployment codecs (ADR 0059 amendment).

- [x] **C3 — Redstone in programs.** Delivered by D6 (see its evidence).

## Wave D — friendly language version 2 (needs PD2, PD3; lane L)

Every statement still compiles to bounded native rules, runs under the deployer's current
grants and uses the shared transfer path. Existing v1 programs and templates must compile
to identical IR (a fixture test pins this).

- [x] **D1 — ADR 0062: language v2.** Size M.
  Freeze the v2 grammar, limits and diagnostics before code. Draft:

  ```text
  move filter from place [side SIDE] [slots RANGE] to place [side SIDE] [slots RANGE] [limit INT]
  if condition block [ else block ]
  condition = stock(filter) CMP INT | NAME has CMP INT of filter | redstone(NAME) | not condition
            | condition and condition | condition or condition
  filter    = … | item("*_ingot") | filter except filter
  emit redstone INT on NAME
  ```

  Record glob bounds (one `*` class, matched against registry IDs only, never component
  data), statement limits, and the decision on SFM-style round robin (`each` over a label).
  Done when: ADR accepted, grammar fixtures for every new form, v1 fixtures unchanged.
  Evidence (2026-10-25): [ADR 0062](decisions/0062-friendly-language-v2.md) accepted
  (grammar, precedence, glob and `except` bounds, side/slot rules, emit lifetime, limits,
  diagnostic keys; SFM `each` rejected with reasons). Nine v2 fixtures, one per form, each
  naming its slice; `FriendlyV1PinTest` pins the IR of an every-v1-form fixture and all 7
  templates (`program/v1-ir.txt`) and fails if a v2 fixture compiles before its slice is
  marked done. No compiler change.

- [x] **D2 — Machine-to-machine moves (G9).** Size M.
  Lift the "one side is storage" rule. The transfer layer already moves between two
  `External` endpoints (`WorldStorageTransfers.submit`), so this is compiler, evaluator and
  `WorldPrograms` work. Processing-job leases and fences (ADR 0053) still apply to both ends.
  Done when: GameTests move furnace output into a chest with no storage involved, are
  refused by a leased machine with the busy reason, and conserve items on a full target.
  Evidence (2026-10-25): `MachineToMachineGameTests` — 20 ingots from a furnace's output
  into a chest with storage never holding one; a full chest receives nothing, the count is
  conserved, and the ingots move once room frees; a chest leased by a processing job logs
  "busy: a crafting job is using this machine", then moves after the job is cancelled.
  `ProgramEvaluatorTest` covers source-major pairing, no self-move and the 64-pair trap.
  `FriendlyV1PinTest` marks D2 (its fixture compiles; v1 IR unchanged). The storage-to-
  storage compiler message changed with the requirement, and its test was updated.

- [x] **D3 — Side and slots per statement (G10).** Size L.
  `side` overrides the Interface's bound side for that statement (the Interface still
  must touch the machine). `slots` narrows inside the bound window and never widens it.
  Check overlap against ADR 0053 fixed partitions; refuse rather than guess.
  Done when: GameTests on a vanilla furnace (top input, bottom output, side fuel) and a
  refusal for a range outside the binding.
  Evidence (2026-10-25): `SideAndSlotsGameTests`. One Interface on a furnace's west side
  moves raw iron in through the top, coal through the north (fuel) and five ingots out
  through the bottom slot 0. A chest Interface narrowed to slots 0–3 logs "those slots are
  outside what this Interface may reach" for `slots 10` and "another side needs the
  Interface bound to every slot" for `side up`; `slots 2` fills slot 2 and nothing else.
  `FriendlyCompilerTest` covers parsing and errors (storage has no side, bad side name,
  reversed range, 1,024, reserved word); `FriendlyV1PinTest` marks D3 with v1 IR
  unchanged. Rule refined in ADR 0062: slot numbers follow the face actually used, and
  another face needs a whole-container binding, because sided containers re-index slots
  per face. 4 consecutive full GameTest runs passed afterwards. Flaky older tests seen
  in other runs, all caused by PD7 rebuilds: `BufferImportGameTests` (throws "network is
  offline" and crashes the run), `BufferTransferGameTests` (NPE on a missing transfer),
  `DrivePartitionGameTests.partitionLimitsInserts…`.

- [x] **D4 — Conditions (G11).** Size M.
  `else`, `and`, `or`, `not`, and `NAME has … of filter` reading machine contents through
  bounded observations (counted against the existing host-call budget).
  Done when: evaluator tests for each operator and short-circuit cost; a GameTest that
  refuels a furnace only when it has under 8 coal.
  Evidence (2026-10-25): `ProgramEvaluatorTest`: each operator; a false `and` and a true
  `or` skip the right side's host reads; `has` sums machines (one read each); `else if`
  picks the first true branch; a host without machine reads traps. `FriendlyCompilerTest`:
  `not` > `and` > `or`, a plain v1 `if stock` stays `ProgramIr.If`, the 16-operator limit,
  a bad atom. `SideAndSlotsGameTests.refuelsAFurnaceOnlyWhileItHasUnderEightCoal`: two
  moves of 4 bring the fuel slot to 8 (at most one in-flight move over, ≤ 12, as ADR 0062
  states), then it stops, coal is conserved, and the `else` branch logs. `FriendlyV1PinTest` marks D4 with v1 IR unchanged.
  Also fixed: a program never resumed its own unresolved (staged) move and then refused
  every later move on that machine. It now continues it first, as presets do (regression
  `SideAndSlotsGameTests.aProgramResumesItsOwnStagedMoveBeforeMovingMore`: 5 staged plus 3
  left all reach storage). Finding, not changed: each invocation drops its rule's
  still-queued commands and a program runs one request at a time, so a rule whose early
  statements outlast its interval starves its later ones. The D3 furnace test now gives
  output its own rule; D9 templates should do the same until this is revisited.

- [x] **D5 — Wildcards and `except` (G11).** Size S.
  Done when: compiler tests for the glob limits; a GameTest moving every `*_ingot` except iron.
  Evidence (2026-10-25): `ResourceFilterTest` (any-namespace and exact-namespace globs,
  no prefix/suffix overlap, invalid patterns, `except` bounds); `FriendlyCompilerTest`
  (two stars, a starred namespace, a tag glob, mixed kinds, energy, the 8-term limit);
  `SideAndSlotsGameTests.movesEveryIngotExceptIron` (gold and copper leave; iron and
  cobblestone stay). Preset codecs and `FilterEdits` refuse program-only terms.
  `FriendlyV1PinTest` marks D5 with v1 IR unchanged.

- [x] **D6 — Redstone in programs (G8, needs PD1).** Size M.
  `redstone(NAME)` reads the signal at a labelled Interface; `emit` sets a Signal-mode
  Interface's output while the program is deployed (cleared on pause/stop).
  Done when: GameTests for read, emit, and clearing on Stop.
  Evidence (2026-10-25): `ProgramRedstoneGameTests`. A relay program holds a labelled
  Signal Interface at 0 ("set by a program") while its Machine Interface input is
  unpowered and lights the lamp at 15 when a redstone block powers that input. Stop hands
  the Interface back to its own (unset) rule. `ProgramEvaluatorTest` (any-powered,
  stopping at the first), `FriendlyCompilerTest` (strength 0–15, emit needs a label),
  `ProgramPreviewTest` (regression: the preview wrapper now forwards `has` and `redstone`
  reads; D4 previews with `has` used to trap). The Signal screen gained a Label row
  (`signal-scale-4.png`, inspected; the tour now captures a few frames after the
  evaluated view arrives). `FriendlyV1PinTest` marks D6; every v2 fixture now compiles.

- [x] **D7 — Rust SDK parity.** Size M.
  Additive `factory_v1` imports for every v2 operation; SDK wrappers, docs and an example.
  The friendly twin test grows to cover v2 command-for-command.
  Files: `sdk/rust/factorycore/src/{sys,rule}.rs`, `core/wasm/WasmProgram.java`, `WasmProgramTest`.
  Done when: old guest artifacts still load and run; the new example builds with the pinned toolchain.
  Evidence (2026-10-25): five additive imports in `HostImport` (`with_access`, `move2`, `has`,
  `redstone_in`, `emit`). Filter text gains globs and ` except `, which used to trap, so no
  old guest changes meaning. SDK: `Side`, `Machine::{side, slots, move_to, has, powered}`,
  `Rule::emit_redstone`, and mock-host parity (`Command::Move`/`Emit`, `World::contents`/
  `powered`). New `examples/furnace` (Rust 1.95.0, `wasm32v1-none`) has its own cargo tests.
  `WasmProgramTest.languageV2GuestMatchesItsFriendlyTwinCommandForCommand` runs 3 rules × 4
  coal levels × 2 button states, and the old smelter twin still passes (old artifacts load
  and run). `HostImportTest.rustSdkDeclaresExactlyTheHostImports` passes.

- [x] **D8 — Label Tool (G12, needs PD3).** Size M.
  Option (a): a handheld tool. Use on an Interface to apply the current label; sneak-use
  to copy an Interface's full settings and use on others to paste (EDIT-checked per
  Interface, one confirmation for a batch). Its screen sets the label.
  Done when: GameTests cover label, copy/paste, a denied paste on a foreign network and
  a stale configuration generation; recipe added to the progression graph test.
  Evidence (2026-10-25): `LabelToolGameTests`: applying a label; sneak-copy; the first
  paste only asks, the second pastes settings and label, and a third Interface in the batch
  pastes directly; a target changed after the prompt is refused as stale and then pastes on
  confirmation; another player's network refuses both paste and label.
  `RecipeProgressionTest` has `label_tool` at STARTER. `RecipeGameTests` counts 23 recipes
  (the new item's recipe; the old 22 was the previous total). Texture from
  `tools/art/generate_art.py`; recipe and model regenerated by `runData`. ADR 0059 amended.
  Not done: an in-game screenshot of the tool screen.

- [x] **D9 — Editor, templates and guide.** Size S.
  Autocomplete and highlighting for new keywords; templates for machine-to-machine
  smelting, refuel-when-low and a redstone-triggered export; guide and PLAYER_GUIDE pages.
  Done when: template compile test covers every template; client screenshot of highlighting.
  Evidence (2026-10-25): `ProgramSyntax.KEYWORD_WORDS` is now the one list for highlighting and
  the editor's keyword picker, including every v2 keyword (`ProgramSyntaxTest`). Three new
  templates: Machine to machine, Refuel when low, Redstone-triggered export. Each slow move
  has its own rule, per the D4 finding. `ProgramTemplatesTest` compiles all 10; the v1 pin
  still covers exactly the 7 v1 templates. The in-game guide page 10 and PLAYER_GUIDE
  describe the v2 forms. `runPresentationClient` PASS; `program-v2-scale-4.png` shows the
  highlighting (inspected).

## Wave E — crafting depth (needs PD4, PD5; lane X)

- [x] **E1 — Pattern substitution (G13, needs PD4).** Size L.
  A per-pattern **Allow substitutes** toggle when encoding a crafting-table pattern. Each
  input stores the recipe's ingredient (tag or alternatives). The planner picks concrete
  inputs from available stock at reservation time (stable order: most available, then
  registry ID), and the Crafter revalidates the real recipe with the chosen items before
  crafting. Reservation stays single (SPEC R06).
  Files: `Pattern.java`, `PatternContents.java`, `CraftingPlanner.java`, `TerminalCrafting.java`, `CrafterEntity.java`.
  Done when: planner tests mix oak and birch planks into one sticks job; GameTest crafts
  chests from mixed planks; a non-substitute pattern still rejects birch.
  Evidence (2026-10-25): `CraftingPlannerTest`: oak and birch mixed into one sticks plan,
  most available first; an exact pattern reports birch as missing; ties by registry ID; a
  remainder crafted as the primary; alternatives may not overlap inputs.
  `CraftingJobTest`: allocation spent per craft and cannot be overspent.
  `CraftingJobPersistenceTest`: schema 6 round trip, and the unknown-schema case moved
  from 6 to 7 because 6 is now defined. `PatternContentsTest`: substitute codecs, and
  shared cells keep only common alternatives. `SubstituteCraftingGameTests`: two chests
  from 4 oak + 12 birch, the second craft on a mixed grid revalidated by the vanilla
  recipe; an exact chest pattern refuses 16 birch; the real terminal encodes alternatives
  only with Subst. on. `crafting-table-scale-4.png` shows the toggle (inspected).
  [ADR 0063](decisions/0063-pattern-substitution.md).

- [ ] **E2 — Crafter speed (G14, needs PD5).** Size M.
  Option (a): Throughput and Overdrive in a Crafter raise crafts per dispatch (for example
  1 → 8 → 64) and concurrent processing jobs, under the same fair server budget (R11:
  paid speed never alters fairness).
  Done when: GameTests measure crafts per dispatch per tier; the throughput evidence file
  gets a crafting row.

- [ ] **E3 — Parallel Crafters.** Size M.
  First measure whether two Crafters holding the same pattern already split one job. If
  not, let the planner split a step's crafts across every Crafter offering that pattern.
  Done when: GameTest shows two Crafters finishing a 128-plank job in about half the
  dispatches of one.

## Wave V — verification

- [ ] **V1 — Survival playtest (G17, A17).** Human. Start now, in parallel.
  Play from a fresh world to Infinite with only the in-game guide. Record recipe pain,
  power demand, what each upgrade was for, and where the player reached for an AE2/RS/SFM
  feature that was missing. Feed findings back as new slices here.
  Done when: an evidence file under `docs/evidence/` and A17 updated.

- [x] **V2 — Parity re-audit.** Size S. After Waves 0, A and B.
  Re-take terminal, crafting, filter and drive screenshots; repeat the comparison table;
  close or re-open findings.
  Evidence: [parity-v2-2026-10-25.md](evidence/parity-v2-2026-10-25.md) with 26 screenshots
  and SHA256SUMS. G1–G7, G15 and G16 are closed. G8–G14 wait on PD1–PD5, and G17 waits on V1.
  B4: 2ce3929.

## Wave G — world interaction (PD6: yes)

World-interaction modes (place, break, pick up, drop items), view cells, portable cells
and cross-dimension links. Each needs its own gate and ADR.

## Not in scope

- Channels, cable-count limits, voiding or chunk loading.
- Weakening the shared authorization, scheduling or conservation rules for speed.
- Save migrations for schema bumps beyond the existing versioned codecs (UD7 still applies
  to removed blocks).
