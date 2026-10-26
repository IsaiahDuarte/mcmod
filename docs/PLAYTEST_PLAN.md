# Playtest plan

## Purpose and authority

This plan turns the first survival playtest (2026-10-26, parity V1, partial) into ordered,
loopable slices. The player found the screens badly spaced, could not work out how to set
up a network, and **never found import or export at all**. They also could not build an
auto smelter. The goal is that a player who knows AE2, Refined Storage or Super Factory
Manager (SFM) can build a working network and a smelter without reading anything. The
deep SFM-style control (programs, sides, slots, whole-inventory moves) must keep working.

[SPEC.md](../SPEC.md) still owns behavior. Slices marked **needs gate** change a SPEC or ADR
contract and must not start until their gate is **resolved**. [IMPLEMENTATION.md](IMPLEMENTATION.md)
keeps owning acceptance evidence. The [UX plan](UX_PLAN.md) and the
[parity plan](PARITY_PLAN.md) are complete apart from V1. This plan revisits two of their
decisions, PD3 (labelling) and part of UD1 (import/export as Interface modes), because the
playtest showed that they did not work for players.

## How to run one loop iteration

Same loop as the UX and parity plans:

1. Pick the first `[ ]` slice whose gate is resolved (or has none).
2. Read the files and ADRs listed for it. Follow [AGENTS.md](../AGENTS.md): behavioral
   tests, affected docs, no weakened checks.
3. Run `python3 scripts/verify.py`. For a slice that changes a screen, block or in-world
   interaction, also run the graphical client under the lock (see [TESTING.md](TESTING.md))
   and **look at** the scale 1 and 4 screenshots. A screen that does not fit, or whose
   spacing breaks the kit's grid, fails.
4. Commit on `main` with the slice ID in the subject (for example `H1: Panel kit`).
5. Tick the box, add the commit and evidence path, and record follow-ups as new slices.
6. Stop and ask when the next slice needs an unresolved gate or reveals a SPEC/ADR conflict.

## Playtest findings

| ID | Finding | Evidence |
| --- | --- | --- |
| K1 | Spacing and whitespace feel wrong on every screen. Each screen sets its own width (for example 300 px in `MachineSettingsScreen`) and places every widget at a hand-written pixel offset over flat `fill` rectangles. No two screens share a grid, row height, button size or footer. At GUI scale 4 the Interface screens fill the whole window, with full-width buttons. | `MachineSettingsScreen.java:57,477`; `machine-scale-4.png`, `machine-presets-filter-scale-4.png` |
| K2 | The terminal draws a 26×8 grid for five items. The player inventory is pinned to the left with an empty right half. Tabs are ordinary buttons in a second header row. | `terminal-scale-2.png` |
| K3 | Screens carry instruction sentences inside the panel ("Apply saves your changes; Cancel discards them"). Apply, Reset and Cancel sit in a different place on each screen, and the Mode row is at the bottom. | `connector-scale-1.png`, `machine-scale-4.png` |
| K4 | Import/export is undiscoverable. It lives under Interface → (Machine mode, chosen silently by what you placed it against) → **Presets** tab → a **Paused** button that cycles to Import. The player never found it. AE2 and RS name the blocks **Import Bus** and **Importer**, so the item itself says what it does. | Playtest; PLAYER_GUIDE "Machine mode" |
| K5 | Every machine needs its own Interface, which has a socket that must face the machine. The label goes on the Interface, not the machine. Programs reach another face only if the Interface is bound to the whole container. In SFM a machine only has to touch a cable, the label goes on the machine, and a program may use any side. | ADR 0059, ADR 0062 D3, PD3(a) |
| K6 | Setting up a network was not intuitive. Using the Controller opens Access, not an overview. Nothing tells the player what is connected or what is missing (no storage, no power, two controllers). | Playtest; PLAYER_GUIDE "Network Controller" |
| K7 | An auto smelter needs a Crafter *facing* the furnace, a second Interface on another face with a Stock preset for fuel, and the knowledge that the output slot must stay clear. A program alternative needs sides and labels. None of this is shown in-game. | ADR 0060, PLAYER_GUIDE "Patterns and the Crafter" |
| K8 | Moving a whole inventory works only through an undocumented trick (`item("*")` in a program). Presets have no plain "everything" choice that a player would recognise. | `FriendlyCompiler.java:725`, ADR 0062 D5 |

## Decision gates (user)

| Gate | Question | Recommendation | Status |
| --- | --- | --- | --- |
| HD1 | How do machines join the network? SFM-style: any block with an item, fluid or energy handler that touches a network cable is reachable. The Label Tool labels the **machine block**, and programs and Importers/Exporters may use any side of it. Revisits PD3 (option b) and amends SPEC R02/R04 and ADR 0062 D3. | Yes | **Resolved 2026-10-26: SFM-style cables** |
| HD2 | How do players import and export? **Importer** and **Exporter**: two named blocks that attach to a machine like a hopper or an RS Importer. An empty filter means everything, and a filter narrows it. They have redstone control and speed upgrades. The Exporter has an optional "keep N" in place of the Stock preset. They work on the face they touch by default, with a side override on their screen for SFM-style control. Revisits UD1 for import/export (the Interface keeps its other roles). | Separate blocks, face-by-default | **Resolved 2026-10-26: separate Importer and Exporter blocks** |
| HD3 | Visual direction for screens. | Vanilla/AE2 fixed-size textured panels at normal GUI scale, inventory docked below, one spacing grid, tabs on the panel edge, buttons bottom-right | **Resolved 2026-10-26: vanilla/AE2 panels** |
| HD4 | With HD1 and HD2, the Interface's **Machine** mode has no remaining job. Delete it, leaving Storage, Buffer and Signal? Under UD7 there is no migration: a placed Machine Interface is unsupported after the change. | Delete it | **Resolved 2026-10-26: delete Machine mode only; Storage, Buffer and Signal stay** |
| HD5 | The Crafter stops facing one block, like AE2's Pattern Provider. Processing patterns go to whichever adjacent machine accepts the inputs, through the face the Crafter touches, and outputs are collected from that machine. A machine that two Crafters touch stays exclusive to one dispatch at a time through the existing processing lease. Amends SPEC R02 and ADR 0060/0032. | Yes | **Resolved 2026-10-26: yes** |

## Wave H0 — screens (HD3)

Order note (2026-10-26): after H2, run H6 → H8 → H9 → H4/H5 → H7, then H3 and Wave H2. H3
would otherwise port the Machine-mode screen that H7 deletes. Starting with H6 also fixes K4 (no
import/export found) first. H7 must follow H5, because programs need cable-adjacent machines
before Machine-mode ports go away.

- [x] **H1 — Panel kit.** Done 2026-10-26 (commit subject `H1:`). [Evidence](evidence/playtest-h1-panel-kit-2026-10-26.md).
  The Gateway screen is the first screen on the kit. Tabs are specified in ADR 0064 and built with H2.
  One shared layout kit replaces hand-placed offsets: a nine-slice panel texture, fixed width
  (vanilla 176 px, 230 px for wide screens), the vanilla 7 px edge, 18 px rows and slots with
  4 px gaps, a title line, a tab strip on the panel's top edge (built with H2), and a footer
  (status lamp and text left, actions right: **Cancel**, **Apply**). Panels never stretch with
  the window; vanilla GUI scale handles size. Instruction sentences move to tooltips.
  Record the kit in an ADR (0064) with the vanilla/AE2 comparison.
  Files: new `platform/client/ui/*`, `StatusStrip`, `assets/factorycore/textures/gui/`.
  Done when: a unit test lays out every kit row type and finds no overlap and nothing outside
  the panel; a sample screen fits at scales 1–4.

- [x] **H2 — Terminal on the kit (K2).** Done 2026-10-26 (commit subject `H2:`). [Evidence](evidence/playtest-h2-terminal-2026-10-26.md).
  Tall is a bit in the saved sort preference.
  9-column item grid with the player inventory docked and centred below it, like AE2/RS. A
  **Tall** toggle (saved with ADR 0052 preferences) adds rows to fill the window height.
  Items/Fluids/Energy/Jobs become edge tabs, and crafting opens as a side panel. Every action
  keeps the existing authorized transfer paths.
  Done when: the existing terminal GameTests and client tours pass unchanged in behavior;
  screenshots at scales 1–4 in normal and tall.

- [ ] **H3 — Device screens on the kit (K1, K3).** Size L; one commit per group.
  (a) Interface (Storage, Buffer, Signal), Drive; (b) Access, Recovery (Gateway moved in H1);
  (c) Program Controller, Label Tool, Guide. Mode buttons become tabs at the top. Remove the
  in-panel instruction lines.
  Done when: every screen's tour screenshot fits at scales 1 and 4 on the kit grid.

## Wave H1 — machines on cables (HD1)

- [ ] **H4 — ADR 0065: cable-adjacent machines.** Size M.
  Decide and record:
  - discovery: a cable records its non-network neighbours with a handler during the bounded
    wire discovery (ADR 0010), at most 6 per cable, with no extra scan;
  - identity: a stable machine ID per (dimension, position, block), dropped when the block
    is broken or replaced;
  - labels: stored on the network's saved data, not the block, so foreign machines need no
    block entity changes;
  - authority: reaching a machine needs the same grants as a Machine Interface today, and R04's
    "this mod is not a claim system" still holds;
  - default access: the whole container on any face; rate is per machine, with the base
    Interface rate;
  - recovery and staging for moves to and from a cable-adjacent machine, reusing the
    External endpoint path.
  Amend SPEC R02/R04 and ADR 0062 D3 (side/slots no longer need a whole-container binding).

- [ ] **H5 — Machines on cables and labels on machines.** Size L.
  Implement H4. Using the Label Tool on any machine that touches a cable labels it; looking
  at it with the tool shows its label. `machines("x")` in programs finds labelled machines.
  Done when: GameTests cover a furnace labelled through a cable with moves on three faces, a
  machine removed mid-move (nothing lost or replayed), a block replaced at the same position
  (label dropped), a machine touching two networks, and permission denial.

- [x] **H6 — Importer and Exporter (K4, K8; HD2).** Done 2026-10-26 (commit subject `H6:`). [Evidence](evidence/playtest-h6-importer-exporter-2026-10-26.md).
  The Side override is not built; several ports on different faces of one machine cover it, and programs keep `side`.
  Two blocks that attach to a machine, with cables on their other faces. Their screen: a filter
  (B1 ghost slots, empty means **Everything**), allow/deny, redstone, **Side** (Auto = the
  touching face, or any of six), and for the Exporter **Keep** (stock up to N, replacing
  Stock). Speed upgrades as on the Interface. Both start running for a placer with the
  grants (U6 rule). Recipes in the Starter tier. ADR 0066; SPEC R02 role table.
  Done when: GameTests cover an empty filter moving a whole chest, a filtered Exporter, Keep 8,
  full storage backpressure, redstone modes and permission denial; screens fit at scales 1–4.

- [ ] **H7 — Remove Machine mode (HD4).** Size M.
  Delete Machine mode, its menu, presets and screen. Move the Machine-mode tests to H5/H6
  equivalents. No migration (UD7).

- [ ] **H8 — Directionless Crafter (HD5).** Size M.
  The Crafter dispatches processing patterns to any adjacent machine that accepts them,
  rotating between machines, through the face it touches. ADR 0060/0032 amendments.
  Done when: GameTests cover one Crafter between two furnaces, two Crafters sharing one furnace
  (one dispatch at a time) and a machine broken mid-job (existing interruption rules).

- [ ] **H9 — Whole-inventory moves in programs (K8).** Size S.
  `everything` as a filter (`move everything from machines("a") to storage`), plus `items`,
  `fluids` and `energy` as kind-wide shorthands. Same IR as `item("*")`, so the v1 and v2 pins
  are unchanged. Editor completion and a template.

## Wave H2 — first steps (K6, K7)

- [ ] **H10 — Network overview.** Size M.
  Using the Controller opens **Overview** (Access becomes a tab): connected counts (drives,
  storage, machines, Importers/Exporters, Crafters), storage used, energy, and a checklist of
  problems with what to do ("No storage — place a Drive with a cell", "Two controllers —
  break one"). Cables show a small port where they touch a machine.

- [ ] **H11 — Auto smelter in one step (K7).** Size M.
  The guide's first pages become a build checklist with pictures: Controller → cable →
  Drive + cell → Terminal → Importer/Exporter. Add an **Auto smelting** page: furnace,
  Exporter on top (filter `#c:raw_materials` or the player's choice), Exporter on the side
  (coal, Keep 8), Importer below. A GameTest builds exactly that from a fresh network and
  smelts 64 raw iron into storage. Add the matching program template for the SFM route.

- [ ] **H12 — Second playtest.** Human.
  Fresh world, guide only. Done when an evidence file records network setup time, the
  smelter build and any new findings, and A17 is updated.

## Not in scope

- Channels, cable-count limits, voiding or chunk loading.
- Weakening the shared authorization, scheduling or conservation rules.
- Save migrations for removed blocks or modes (UD7 still applies).
