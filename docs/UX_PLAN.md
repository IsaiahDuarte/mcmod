# Player experience simplification plan

## Purpose and authority

This plan turns the 2026-10-20 end-user review into ordered, loopable slices. Its goal is a
mod that a player can learn without the docs: fewer blocks, standard Minecraft controls,
screens instead of chat, and player-facing words.

[SPEC.md](../SPEC.md) still owns behavior. Slices marked **needs gate** change a SPEC or ADR
contract (for example the R02 block roles) and must not start until the gate below is
**resolved** by the user. [IMPLEMENTATION.md](IMPLEMENTATION.md) keeps owning release stages
and acceptance evidence; each slice here updates it when acceptance evidence changes.

## How to run one loop iteration

1. Read this file. Pick the first slice whose box is `[ ]` and whose gate is resolved (or
   which has no gate). Do one slice per iteration.
2. Read the files listed for the slice and the ADRs it names. Follow [AGENTS.md](../AGENTS.md):
   behavioral tests, affected docs, no weakened checks.
3. Run `python3 scripts/verify.py`. For a slice that changes a screen, block appearance or
   in-world interaction, also run the graphical client under the lock and `caffeinate` (see
   [TESTING.md](TESTING.md)), then **look at** the screenshots at GUI scales 1 and 4. A
   screen that does not fit is a failure.
4. Commit on `main` with the slice ID in the subject (for example `U3: Drive screen`).
5. Tick the box, add the commit hash and evidence path, and note any follow-up as a new
   slice rather than widening this one.
6. Stop and ask the user when the next slice needs an unresolved gate, or when a slice
   reveals a real conflict with SPEC/ADRs.

## Review findings this plan addresses

| ID | Finding | Evidence |
| --- | --- | --- |
| F1 | Four adjacent-inventory blocks (Storage Connector, Machine Interface, Buffered Interface, Crafting Interface) with four different control schemes. | `platform/storage/*Block.java`, PLAYER_GUIDE |
| F2 | Hidden offhand-item controls: redstone, iron/gold stack size as priority, compass, chest, clock, paper, name tag. | `ConnectorBlock.java:104-175`, `BufferedInterfaceBlock.java:60-180` |
| F3 | Terminal shows one row of 8 items, select-then-button withdrawal, Home/Next paging, Search to refresh, cycle buttons that hide states. | `build/reports/client-terminal/screenshots/terminal-grid-scale-2.png`, `en_us.json:102-117` |
| F4 | Device status is printed into chat on empty-hand use. | `message.factorycore.*` lang keys |
| F5 | Engineering words in player text ("Held", "staging", "principal", "segment", "First index / Window", "server budget"). PLAYER_GUIDE reads as a development log. | `en_us.json`, `docs/PLAYER_GUIDE.md` |
| F6 | Gateway faces described inconsistently. Textures: amber cross is upstream, blue dot is downstream. The tooltip matches; the in-game guide page 11 and PLAYER_GUIDE ("eye face", "red output face") do not. | `en_us.json:579,651`, `textures/block/gateway_*.png` |
| F7 | Friction defaults: buffers start Paused; machine presets need Deploy after Apply; upgrades pause presets; the Drive removes only its first cell; cell upgrades need the cell removed and held in the offhand. | PLAYER_GUIDE, ADR 0014, ADR 0054 |
| F8 | Gateway policy is 17 toggles in two same-labelled columns; access is seven letter toggles per player. | `presentation-v1-2026-10-19/gateway-policy-scale-4.png` |
| F9 | The Drive and the Storage Connector have no screen. | Block classes |

## Decision gates (user)

| Gate | Question | Recommendation | Status |
| --- | --- | --- | --- |
| UD1 | Merge Storage Connector + Machine Interface + Buffered Interface into one **Interface**, and Crafting Unit + Crafting Interface into one **Crafter**? Amends SPEC R02's role table (responsibilities stay) and needs a save migration. | Yes | **Resolved 2026-10-20: merge both** |
| UD2 | Fold Wireless Link Endpoint into a Gateway upgrade and Wireless Access Point into a Controller upgrade? | Yes for the link; keep the Access Point as a block for now | **Resolved 2026-10-20: link becomes a Gateway upgrade; Access Point stays a block** |
| UD3 | Energy: keep Fuel Generator + three Energy Bank tiers, or give the Controller a small internal buffer and make the Bank optional? | Keep as-is; revisit after a playtest | Open |
| UD4 | Player-facing display names (registry IDs never change). See the names table below. | Adopt the table | **Resolved 2026-10-20: adopt the table** |
| UD5 | Remove the offhand-item controls entirely once screens cover them, or keep them as hidden shortcuts? | Remove | **Resolved 2026-10-20: remove** |
| UD6 | Add optional Jade/WTHIT look-at status (new optional dependency, ADR required)? | Yes, Jade only | **Resolved 2026-10-20: no** |

Proposed display names for UD4:

| Current | Proposed |
| --- | --- |
| Buffered Interface / Storage Connector / Machine Interface | Interface (modes: Storage, Machine, Import, Export, Stock) |
| Crafting Unit / Crafting Interface | Crafter |
| Gateway; "segment" / "branch" in text | Gateway; "subnetwork" in text |
| Preset "Deploy" | Apply |
| "Held" / "Free" in terminal counts | Reserved / Available |
| "First index" / "Window" | Slots (picked visually) |

Wave A keeps the current blocks and names; U15 applies the new names after the merges.

## Wave A — no contract change

- [x] **U1 — Copy fixes and gateway faces (F5, F6).** Done 2026-10-20 (commit subject
  `U1:`). 80 strings rewritten ("in transit", "Reserved/Available", "container", "server
  load", "unsaved changes"); `LangKeysTest.playerTextAvoidsInternalWords` covers `gui`,
  `tooltip`, `guide`, `status` and `message` keys ("draft" stays allowed in the program
  editor, where it is a real concept). `runTerminalClient` passed all 98 stages; terminal and
  machine screens checked at scales 2–4. Guide page 11 is length-tested but was not
  re-screenshotted.
  Fix guide page 11 and PLAYER_GUIDE to "amber cross face is upstream, blue dot face is
  downstream", consistent with the tooltip. Rewrite the player-visible strings that leak
  internals: terminal counts, "server: OK" / "server budget", "Draft: Apply saves; Cancel
  discards", "staging", "principal", "known staged". Before renaming a count, confirm its
  meaning in the terminal menu code. Add a lang test that rejects a short list of internal
  words in `gui.*`, `tooltip.*` and `guide.*` keys.
  Files: `en_us.json`, `docs/PLAYER_GUIDE.md`, guide pages.
  Done when: no screen, tooltip or guide page uses the banned words; the gateway text agrees
  with the textures; screenshots of the terminal and the buffer/machine screens are re-taken.

- [ ] **U2 — Drive screen (F7, F9).**
  Empty-hand use opens a screen with the four bays, each showing tier, used/capacity bar and
  type count. Click a bay to take that cell out (no more first-slot-only). Drop a module on a
  bay to upgrade the cell in place, with the same preserve-identity/contents/reservations rule
  as the offhand path. Keep sneak-use removal working for compatibility.
  Files: Drive block/entity, new menu + `DriveScreen`, CellUpgradeItem logic reuse.
  Done when: GameTests cover choosing a slot, in-place upgrade, a rejected downgrade and a
  full drive; screenshots at scales 1 and 4.

- [ ] **U3 — Terminal grid rebuild (F3).**
  Replace the 8-item row with a scrolling grid sized to the screen (at least 9×4 at GUI
  scale 4, more when it fits), with a scrollbar and mouse wheel. Use standard clicks: left
  takes a stack, right takes half, shift-click goes to the inventory, clicking with a carried
  stack deposits it. Search updates as you type. Items/Fluids/Energy/Jobs become tabs. Show
  a network capacity bar (used/total item units). Remove Home/Next/Search/Take 1/Take 64.
  Every request still goes through the existing authorized transfer path; only the client
  and the page request shape change.
  Files: `TerminalScreen.java`, terminal menu/packets, `STORAGE_TERMINAL.md`, ADR 0017
  amendment.
  Done when: native tests cover the new click actions (including partial and denied ones);
  the graphical client shows the grid at scales 1–4; the wireless terminal uses the same
  screen.

- [ ] **U4 — Terminal crafting panel layout (F3).**
  Move the crafting grid into a collapsible side panel or its own tab. Make Table / Inputs /
  Outputs visible as three labelled tabs, and show fluid amounts in mB instead of quarter
  buckets. Keep ghost semantics and JEI transfer.
  Done when: crafting, encoding and processing-pattern authoring work in the graphical
  client and through JEI.

- [ ] **U5 — Storage Connector screen (F2, F9).**
  Empty-hand use opens a screen in the style of the Machine Interface Binding screen:
  resource, access, priority (a number field, not a stack size), rate, status line.
  Files: `ConnectorBlock.java`, new menu/screen, `CONNECTORS.md`.
  Done when: every offhand control has an equivalent on the screen, with GameTests for edits
  and permission denial.

- [ ] **U6 — Better defaults (F7).**
  (a) A Buffered Interface placed by a player who holds the needed grants starts in Import,
  deployed under that player; others still start Paused. Amend ADR 0014/0018 with the
  authority reasoning.
  (b) Machine preset **Apply** saves and deploys in one step; remove the separate Deploy.
  (c) Installing a Throughput/Overdrive upgrade keeps the preset running when the installer
  is the deployer. Otherwise show a one-click **Resume** instead of requiring the preset to be
  re-entered. Check ADR 0054's reason for pausing first; keep the pause if it protects
  authority, and say so in the ADR.
  Done when: GameTests cover each default and its permission-denied case.

- [ ] **U7 — Status on screens, not chat (F4).**
  Every device screen gets the same one-line status strip at the top (state, reason, power).
  Empty-hand use opens the screen for every device that has one; chat output stays only for
  devices without a screen. Inspection results that are now duplicated in chat are removed.
  Depends on U2 and U5.

- [ ] **U8 — Gateway and access presets (F8).**
  The gateway screen opens on four choices: Shared (default), Read-only, Isolated, Custom.
  Custom shows the current toggles, with clearer column titles ("This subnetwork can…" and
  "…using parent storage"). The access screen gets Guest / Member / Builder / Admin role
  buttons per player, with the letter toggles behind an Advanced view.
  Done when: each preset maps to an exact policy in a unit test, and screenshots fit.

- [ ] **U9 — Player and admin guides.**
  Split `PLAYER_GUIDE.md` into a short player guide (play order, one section per block,
  no schema or quota detail) and `ADMIN_GUIDE.md` (recovery commands, audits, quotas,
  server config). Give the in-game guide recipe and block pictures by rendering item stacks
  in `GuideScreen`. Do this after the U-slices that change controls, so it describes the
  final controls.

## Wave B — needs gates

- [ ] **U10 — Migration spike (needs UD1).**
  Prove that an old world with every merged block and item loads into the new IDs, using
  registry aliases plus a block-entity data converter, with contents, ownership, leases,
  presets and staged transfers preserved. Record the approach in an ADR before U11.
  Done when: a fixture world from the current build loads in the spike build with exact
  conservation, under the existing restart/interruption harness.

- [ ] **U11 — Unified Interface (needs UD1, U10).**
  One block faces a container. Modes: Storage (registers the container as network storage,
  today's Connector), Machine (labelled for presets/programs, today's Machine Interface), and
  Import/Export/Stock. With no container attached it uses its own 9-slot buffer, so the
  hopper path (today's Buffered Interface) keeps working. One screen, one upgrade path.
  Amend SPEC R02, the three module docs and the relevant ADRs.

- [ ] **U12 — Crafter (needs UD1, U10).**
  One block holds patterns. Crafting-table patterns run inside it; processing patterns are
  pushed into the machine it faces. Same leases and output attribution as today.

- [ ] **U13 — Link as a Gateway upgrade (needs UD2).**
  Replace the Wireless Link Endpoint block with an upgrade installed in a Gateway, keeping
  pairing, power, range and shared throughput rules. Migrate placed endpoints into their
  gateway's upgrade slot.

- [x] **U14 — Jade status (needs UD6).** Dropped: UD6 resolved as no; U7's on-screen status covers this.
  Optional integration showing device state, label, mode and the blocking reason when looking
  at a block. It stays off mandatory class loading (ArchUnit rule).

- [ ] **U15 — Apply display names (needs UD4).**
  Lang-only rename plus guide and docs. Registry IDs stay.

- [ ] **U16 — Remove offhand controls (needs UD5, after U2, U5, U6).**
  Delete the offhand-item branches in `ConnectorBlock` and `BufferedInterfaceBlock`, their
  lang keys and their tests. Replace that coverage with screen-path tests.

## Not in scope

- Changing storage, crafting, program or permission semantics beyond the defaults in U6.
- New gameplay features.
- Removing the recovery commands: they move to the admin guide but stay.
