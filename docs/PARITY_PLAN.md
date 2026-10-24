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
| PD1 | How does redstone enter the mod? (a) a new **Signal** mode on the Interface (no container needed: emits redstone by a stock/crafting condition), plus a redstone setting on Machine/Buffer presets; or (b) a separate Level Emitter-style block. Amends SPEC R02. | (a): keeps the block count down after UD1 | Open |
| PD2 | Unfreeze the friendly language for a **version 2** that is a superset of version 1 (every v1 program compiles unchanged), with additive `factory_v1` Wasm imports rather than a new ABI? Amends ADR 0035/0037 and SPEC R07's examples. | Yes, superset + additive imports | Open |
| PD3 | Labelling model. (a) Keep one Interface per machine, and add a **Label Tool** that applies a label, and optionally copies Interface settings, to many Interfaces quickly; or (b) SFM-style: a labelled machine needs only to touch a cable, no Interface. (b) changes SPEC R02/R04 discovery and authority rules. | (a) now; revisit (b) after the playtest | Open |
| PD4 | Allow opt-in tag **substitution** in crafting-table patterns (inputs chosen from current stock at reservation time; the recipe is revalidated before crafting)? Processing patterns stay exact. Amends SPEC R06 and ADR 0029/0030. | Yes, crafting patterns only, per-pattern toggle | Open |
| PD5 | How do crafting workers scale (R11)? (a) Throughput/Overdrive upgrades installed in a Crafter raise its crafts per dispatch and concurrent jobs; or (b) a new Crafting Core block shared by the network. | (a): reuses existing items and ADR 0054 rules | Open |
| PD6 | Add world-interaction blocks (place, break, pick up, drop) in this release? | No; keep as Later | Open |

## Wave 0 — quick wins (no gate)

- [ ] **P1 — Readable counts (G1).** Size S. Lane T.
  Draw counts in a half-scale font, right-aligned in the slot's bottom-right corner, with
  a shadow, as AE2/RS do. Same for fluid mB and job chips. Keep the exact count in the
  tooltip.
  Files: `TerminalScreen.java` (`renderCount`, `compact`), `DriveScreen` if it shares the helper.
  Done when: the presentation tour shows legible 1k/989k/1.0M at scales 1–4 with no
  overlap on 3D block icons; a client-side unit test covers `compact` boundaries
  (999, 1,000, 999,999, 1,000,000, Long.MAX).

- [ ] **P2 — Sort and fluid icons (G15).** Size S. Lane T.
  Add **Mod** sort and an ascending/descending toggle, saved with the existing per-player
  search preferences (ADR 0052). Render fluids with their still texture and tint instead
  of a bucket.
  Files: `TerminalScreen.java`, `TerminalMenu.java`, terminal preference codec.
  Done when: native test round-trips the new preference fields; client tour shows each
  sort and a water/lava fluid grid at scales 1 and 4.

- [ ] **P3 — Status and Access fixes (G16).** Size S. Lane T.
  Wrap or abbreviate the status strip so it never truncates mid-word (full text on hover
  stays). Show player names (from the server profile cache) on the Access screen, with
  the UUID only in the tooltip; verify with two real clients.
  Files: `StatusStrip.java`, `NetworkAccessMenu.java`, `NetworkAccessScreen.java`.
  Done when: buffer scale 4 shows a whole word; the two-client run shows the second
  player's name.

- [ ] **P4 — Jobs list scrolls (G4, part).** Size S. Lane T.
  Replace Home/Next in Jobs with the same scrolling list as Items; remove the `HOME`/`NEXT`
  commands.
  Done when: native job-view tests page by window; client shows 20+ craftables scrolling.

- [ ] **P5 — Visual slot picker (G16, UD4 leftover).** Size M. Lane F.
  Replace First slot / Slot count fields on Machine and Storage modes with a clickable
  strip of the container's slots (shift-click to range-select). Fields stay for
  containers over 64 slots.
  Files: `MachineSettingsScreen.java`, `ConnectorSettingsScreen.java`, their menus.
  Done when: GameTests cover selection round-trip and the >64 fallback; scale 1/4 fit.

## Wave A — crafting UX (no gate; lane T)

- [ ] **A1 — Typed craft amount (G2).** Size S.
  Add an amount box beside ×1/×8/×64 (Enter previews). Decide in the slice whether to
  raise `MAX_AMOUNT` above 4,096; if raised, prove the planner's `LIMIT` result is shown
  as a readable message, not a silent cap, and add a large-amount planner test.
  Files: `TerminalScreen.java`, `TerminalJobPackets.java`, `TerminalJobs.java`.
  Done when: native tests cover typed, zero, over-limit and non-numeric input; the client
  previews and starts a typed 37.

- [ ] **A2 — Full plan breakdown (G3).** Size M.
  Send a bounded plan summary with the preview: one row per resource with **stored**,
  **to craft** and **missing**, plus cycle explanations. Bound it (for example 64 rows)
  and say "and N more" past the bound. Missing rows are highlighted; Start is disabled
  only when something is missing.
  Files: `CraftingPlanner` (already returns `steps`, `consumed`, `missing`, `cycles`),
  `TerminalJobs.java`, `TerminalJobPackets.java` (new bounded `PlanRow` list), `TerminalScreen.java`.
  Done when: native tests cover a two-level recipe, a shortage, a cycle and the row bound;
  client screenshots of each at scale 4.

- [ ] **A3 — Craftables in the Items grid (G4).** Size M.
  Mark catalog rows that a visible pattern can produce. Show craftable-but-absent items
  as zero-count ghost entries when a **Show craftable** toggle is on (saved preference).
  Middle-click, or clicking a zero-count entry, opens the request with the amount box.
  Patterns from subnetworks appear only where gateway policy already publishes them (ADR 0033).
  Files: `WorldStorageSearch.java`, `TerminalMenu.java`, `TerminalScreen.java`, `WorldCrafting` index.
  Done when: native tests prove craftable flags respect gateway CRAFT policy and page
  bounds; client shows the toggle and opens a request from the grid.

- [ ] **A4 — Crafting monitor (G5).** Size M.
  A job detail view: each step with crafts done/total, the item it waits on and why
  (missing, busy machine, storage full), and Cancel. Players see their own jobs; Manage
  sees all jobs on the network.
  Files: `CraftingJob.java` (already holds steps and reasons), `TerminalJobs.java`,
  `TerminalJobPackets.java`, `TerminalScreen.java`.
  Done when: native tests cover visibility (own vs Manage), a waiting processing step and
  cancel from the detail view; client screenshots of a running and a waiting job.

- [ ] **A5 — Crafting done notification and request from JEI.** Size S.
  A toast instead of chat for "job finished" (chat stays when the screen is closed). With
  JEI, Ctrl-click on a recipe output that the network can craft opens the request.
  Files: `WorldCraftingJobs.java` notices, `platform/client/integration` (JEI stays optional and isolated).
  Done when: client run with and without JEI; ArchUnit rule still keeps JEI off mandatory loading.

## Wave B — filters (no gate; lane F)

- [ ] **B1 — Ghost-slot filter widget (G7).** Size M.
  One shared widget for Buffer, Machine and Storage screens: up to 16 ghost slots; click
  with an item, drag from JEI, or Sample from inventory. Each slot has a match toggle:
  **Item** (`Registry`), **Exact** (`Exact`, components included) or **Tag** (pick from the
  item's tags). Allow/Block toggle. Typed IDs move behind an "Add by ID" button.
  Files: new `platform/client/FilterSlots.java`, `BufferSettingsScreen.java`,
  `MachineSettingsScreen.java`, their menus (they already build `ResourceFilter` terms);
  JEI ghost-ingredient handler in `client/integration`.
  Done when: native tests round-trip each term type through both menus; client shows JEI
  drag and the three match modes at scales 1 and 4.

- [ ] **B2 — Storage-mode filter (G6).** Size M.
  Add an optional `ResourceFilter` to `ConnectorSettings` (bounded by the existing 16-term,
  256 KiB rules). It limits what priority routes **insert** into that container; the
  terminal still lists and withdraws everything the container holds, so nothing becomes
  invisible. Record the choice in an ADR 0013 amendment.
  Files: `ConnectorSettings.java`, `ConnectorSettingsMenu/Screen`, `WorldStorageRoutes.java`
  (skip non-accepting backings when ordering by priority), connector codec + schema bump.
  Done when: GameTests prove a cobblestone-only chest receives only cobblestone, overflow
  goes to the next priority, a rejected deposit returns to the player, and saved filters
  reload; seeded conservation test still passes.

- [ ] **B3 — Cell partitions (G6).** Size M.
  A cell carries an optional filter component, edited from its Drive bay (B1 widget).
  Same insertion-only rule as B2. Upgrades keep the filter. Partition and priority
  together let players build sorting systems without chests.
  Files: `CellItem.java`, `CellHandleData`, `DriveMenu.java`, `DriveScreen.java`,
  `WorldStorageRoutes.java`, cell persistence schema.
  Done when: GameTests cover partitioned insert, upgrade keeping the partition, and an
  existing cell outside the partition still being withdrawable.

- [ ] **B4 — Drive and cell priority.** Size S.
  Add a per-drive priority (Storage-mode containers already have one) so partitioned cells
  win over general storage.
  Done when: GameTest shows a priority-10 partitioned cell filled before a priority-0 chest.

## Wave C — redstone (needs PD1; lane R)

- [ ] **C1 — Signal mode (G8).** Size M.
  Interface **Signal** mode: emits redstone when a condition holds, re-evaluated on the
  shared scheduler at a bounded interval (not every tick). Conditions: network stock of a
  filter compared with a number (VIEW-checked under the placer's grants), or "a job for
  this item is running". Strength 15 or proportional (comparator style).
  Files: `InterfaceKind.java`, `InterfaceBlock.java` (`isSignalSource`, `getSignal`), new
  settings screen, `InterfaceSwitch.java`.
  Done when: GameTests cover threshold crossing, revoked VIEW (signal off, reason shown),
  network offline (off) and reload; client screenshot of the screen and a lit lamp.

- [ ] **C2 — Redstone control on presets.** Size S.
  Machine and Buffer presets get a redstone setting: Ignore, Active when powered, Active
  when unpowered, One run per pulse.
  Files: `MachinePresets.java`, `BufferPolicy.java`, `WorldMachinePresets.java`,
  `WorldBufferImports.java`, both settings screens, codecs.
  Done when: GameTests for each setting, including a pulse not repeating while held.

- [ ] **C3 — Redstone in programs.** Delivered by D6.

## Wave D — friendly language version 2 (needs PD2, PD3; lane L)

Every statement still compiles to bounded native rules, runs under the deployer's current
grants and uses the shared transfer path. Existing v1 programs and templates must compile
to identical IR (a fixture test pins this).

- [ ] **D1 — ADR 0062: language v2.** Size M.
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

- [ ] **D2 — Machine-to-machine moves (G9).** Size M.
  Lift the "one side is storage" rule. The transfer layer already moves between two
  `External` endpoints (`WorldStorageTransfers.submit`), so this is compiler, evaluator and
  `WorldPrograms` work. Processing-job leases and fences (ADR 0053) still apply to both ends.
  Done when: GameTests move furnace output into a chest with no storage involved, are
  refused by a leased machine with the busy reason, and conserve items on a full target.

- [ ] **D3 — Side and slots per statement (G10).** Size L.
  `side` overrides the Interface's bound side for that statement (the Interface still
  must touch the machine). `slots` narrows inside the bound window and never widens it.
  Check overlap against ADR 0053 fixed partitions; refuse rather than guess.
  Done when: GameTests on a vanilla furnace (top input, bottom output, side fuel) and a
  refusal for a range outside the binding.

- [ ] **D4 — Conditions (G11).** Size M.
  `else`, `and`, `or`, `not`, and `NAME has … of filter` reading machine contents through
  bounded observations (counted against the existing host-call budget).
  Done when: evaluator tests for each operator and short-circuit cost; a GameTest that
  refuels a furnace only when it has under 8 coal.

- [ ] **D5 — Wildcards and `except` (G11).** Size S.
  Done when: compiler tests for the glob limits; a GameTest moving every `*_ingot` except iron.

- [ ] **D6 — Redstone in programs (G8, needs PD1).** Size M.
  `redstone(NAME)` reads the signal at a labelled Interface; `emit` sets a Signal-mode
  Interface's output while the program is deployed (cleared on pause/stop).
  Done when: GameTests for read, emit, and clearing on Stop.

- [ ] **D7 — Rust SDK parity.** Size M.
  Additive `factory_v1` imports for every v2 operation; SDK wrappers, docs and an example.
  The friendly twin test grows to cover v2 command-for-command.
  Files: `sdk/rust/factorycore/src/{sys,rule}.rs`, `core/wasm/WasmProgram.java`, `WasmProgramTest`.
  Done when: old guest artifacts still load and run; the new example builds with the pinned toolchain.

- [ ] **D8 — Label Tool (G12, needs PD3).** Size M.
  Option (a): a handheld tool. Use on an Interface to apply the current label; sneak-use
  to copy an Interface's full settings and use on others to paste (EDIT-checked per
  Interface, one confirmation for a batch). Its screen sets the label.
  Done when: GameTests cover label, copy/paste, a denied paste on a foreign network and
  a stale configuration generation; recipe added to the progression graph test.

- [ ] **D9 — Editor, templates and guide.** Size S.
  Autocomplete and highlighting for new keywords; templates for machine-to-machine
  smelting, refuel-when-low and a redstone-triggered export; guide and PLAYER_GUIDE pages.
  Done when: template compile test covers every template; client screenshot of highlighting.

## Wave E — crafting depth (needs PD4, PD5; lane X)

- [ ] **E1 — Pattern substitution (G13, needs PD4).** Size L.
  A per-pattern **Allow substitutes** toggle when encoding a crafting-table pattern. Each
  input stores the recipe's ingredient (tag or alternatives). The planner picks concrete
  inputs from available stock at reservation time (stable order: most available, then
  registry ID), and the Crafter revalidates the real recipe with the chosen items before
  crafting. Reservation stays single (SPEC R06).
  Files: `Pattern.java`, `PatternContents.java`, `CraftingPlanner.java`, `TerminalCrafting.java`, `CrafterEntity.java`.
  Done when: planner tests mix oak and birch planks into one sticks job; GameTest crafts
  chests from mixed planks; a non-substitute pattern still rejects birch.

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

- [ ] **V2 — Parity re-audit.** Size S. After Waves 0, A and B.
  Re-take terminal, crafting, filter and drive screenshots; repeat the comparison table;
  close or re-open findings.

## Wave G — Later (only if PD6 says yes)

World-interaction modes (place, break, pick up, drop items), view cells, portable cells
and cross-dimension links. Each needs its own gate and ADR.

## Not in scope

- Channels, cable-count limits, voiding or chunk loading.
- Weakening the shared authorization, scheduling or conservation rules for speed.
- Save migrations for schema bumps beyond the existing versioned codecs (UD7 still applies
  to removed blocks).
