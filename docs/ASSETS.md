# Factory Core release asset inventory

[SPEC.md](../SPEC.md) R08 owns presentation requirements. This inventory tracks
delivery; [IMPLEMENTATION.md](IMPLEMENTATION.md) owns sequence and acceptance
evidence. Initial audit: 2026-10-11; original-art integration: 2026-10-18. Native models and
original textures are implemented; [gallery and item-icon client inspection passed](evidence/art-client-v1-2026-10-18.md), with remaining orientation/state/held checks open.

## Style and implementation direction

Use a consistent Minecraft-scale industrial appearance: recognizable device
silhouettes, legible ports, four drive bays, and related cell/module tier shapes.
Choose and record a palette, texture resolution and reference sheet with the
first starter-device implementation. These art choices are delegated; they do
not require a new gameplay system or permission gate.

Use custom textures and model geometry where they improve recognition. Ordinary
Minecraft model JSON and simple cubes are sufficient for suitable devices.
Distinct resource symbols and tier markings must work without color alone.
Cable geometry should show eligible connections and match interaction shapes.
Status details can use inspection/screens instead of expensive block animation.
Custom sounds, particles and animations are optional; add them only with a
specific feedback purpose and bounded rendering cost.

Registry-derived resources are generated, not hand-written: `./gradlew runData`
(`platform/client/data/FactoryCoreData`) writes every item model except the
cable's hand-authored geometry, the `mineable/pickaxe` and `needs_iron_tool`
block tags, and every crafting recipe (one row each in `FactoryCoreData.Recipes`)
with its unlock advancement, and block loot tables, into
`src/generated/resources`. Loot follows each block's own drop policy:
`NetworkedBlock.dropsLeasedItem()` devices drop their lease item from code and
get no table, and `CarriedComponents` blocks copy exactly the components they
list. That tree is
committed; `checkGeneratedResources` (part of `check`) fails when it is stale.
A file lives in exactly one of `src/generated` and `src/main/resources`.

Keep editable sources when used, exported game assets, and attribution/license
information discoverable. Record actual paths when created; do not add empty
files to satisfy this inventory. Register new variants/screens here as they land.

## Status and evidence rules

- **Missing:** no production asset/feature exists.
- **Placeholder:** production resources exist but reuse development stand-ins.
- **Implemented:** finished assets and behavior are wired; client verification may still be pending.
- **Verified:** actual supported-client inspection/interactions passed with evidence.

For each family, track asset paths, variant coverage, implementation status and
client evidence separately. Current **Pending** evidence is not a pass. Split
rows when variants reach different statuses. The table groups families, but all
listed tiers and block-item forms must be covered before closing a row.

## Blocks and items

Original assets now live under [textures](../src/main/resources/assets/factorycore/textures),
[models](../src/main/resources/assets/factorycore/models), and
[blockstates](../src/main/resources/assets/factorycore/blockstates). The editable
[Python generator](../tools/art/generate_art.py) uses only the standard library and
creates deterministic original 16×16 RGBA textures plus a
[reference sheet](art/reference-sheet.png). Assets and source use the repository
license; no Minecraft image is copied. Run `python3 tools/art/generate_art.py --check`
to check reproducibility.

The palette uses dark steel casing (44/47/54), copper (181/104/60), cyan signal
(54/197/240), amber energy (255/181/46), and blue fluid (58/123/213), in sRGB.
Cube/droplet/lightning symbols identify resource kinds; tier bars and the Infinite
lemniscate distinguish tiers without color alone. Original JSON geometry uses a
16-unit block grid. Sounds, particles and animation remain vanilla; no custom
sound or animated renderer is required for these status contracts.

| Family / variants | Implemented presentation | Client evidence |
| --- | --- | --- |
| Network Controller | Hub texture and status inspection | [Gallery/icons inspected](evidence/art-client-v1-2026-10-18.md); remaining held/state/orientation coverage open |
| Network Cable | Six reciprocal eligible arms, cached matching shape, junction item model | [Gallery/icons inspected](evidence/art-client-v1-2026-10-18.md); remaining held/state/orientation coverage open |
| Storage Drive | Four bays, dark casing, cell ownership/status inspection | [Gallery/icons inspected](evidence/art-client-v1-2026-10-18.md); remaining held/state/orientation coverage open |
| Gateway | Six-direction upstream/downstream markings and policy inspection | [Gallery/icons inspected](evidence/art-client-v1-2026-10-18.md); remaining held/state/orientation coverage open |
| Item cells: Starter/Expanded/Advanced/Infinite | Original icons with tier bars/Infinite symbol | [Gallery/icons inspected](evidence/art-client-v1-2026-10-18.md); remaining held/state/orientation coverage open |
| Fluid cells: Starter/Expanded/Advanced | Original droplet icons with tier bars | [Gallery/icons inspected](evidence/art-client-v1-2026-10-18.md); remaining held/state/orientation coverage open |
| Expansion/Advanced/Infinite modules | Original upgrade icons and tier markings | [Gallery/icons inspected](evidence/art-client-v1-2026-10-18.md); remaining held/state/orientation coverage open |
| Throughput/Overdrive upgrades | Generated `textures/item/throughput_upgrade.png` and `textures/item/overdrive_upgrade.png`, item models, recipes and translated installation help | Implemented; native recipes/installation checked and [graphically verified](evidence/throughput-v1-2026-10-19.md) (survival install/consumption, synced rates, scales 1–4, tooltips, guide page 16) |
| Energy Banks: Starter/Expanded/Advanced | Original lightning textures and tier geometry | [Gallery/icons inspected](evidence/art-client-v1-2026-10-18.md); remaining held/state/orientation coverage open |
| Storage- and Machine-mode Interface | Six-direction socket geometry, distinct textures, translated side/type/status inspection | [Gallery/icons inspected](evidence/art-client-v1-2026-10-18.md); remaining held/state/orientation coverage open |
| Storage Terminal | Casing, screen and contacts with original textures | [Gallery/icons inspected](evidence/art-client-v1-2026-10-18.md); remaining held/state/orientation coverage open |
| Buffer-mode Interface | Nine-slot tray geometry and original texture | [Gallery/icons inspected](evidence/art-client-v1-2026-10-18.md); remaining held/state/orientation coverage open |
| Signal-mode Interface (C1) | Buffer tray geometry; original `signal_slot.png` (red slot face from `tools/art/generate_art.py`) on its slot faces; emits redstone on all sides | Presentation screenshot with a lit lamp (parity C1 evidence); held/orientation coverage open |
| Crafter | Distinct process/crafting symbols, ports and original textures | [Gallery/icons inspected](evidence/art-client-v1-2026-10-18.md); remaining held/state/orientation coverage open |
| Blank/Crafting/Processing Patterns | Distinct original icons; invalid/exclusive-use tooltips | [Gallery/icons inspected](evidence/art-client-v1-2026-10-18.md); remaining held/state/orientation coverage open |
| Program Controller | Program symbol, original texture, deployment status | [Gallery/icons inspected](evidence/art-client-v1-2026-10-18.md); remaining held/state/orientation coverage open |
| Fuel Generator | Facing and lit variants with original textures; FE/fuel feedback | [Gallery/icons inspected](evidence/art-client-v1-2026-10-18.md); remaining held/state/orientation coverage open |
| Label Tool (D8) | Original item icon from `tools/art/generate_art.py` (paper tag on a copper-tipped handle) | Reference sheet regenerated; in-game icon screenshot open |
| Wireless Access Point and Wireless Link | Original antenna textures with power status; link upgrade item icon (ADR 0061) | [Gallery/icons inspected](evidence/art-client-v1-2026-10-18.md); remaining held/state/orientation coverage open |
| Wireless Terminal | Original handheld icon, binding and charge tooltip | [Gallery/icons inspected](evidence/art-client-v1-2026-10-18.md); remaining held/state/orientation coverage open |
| Factory Guide | Original guide icon, translated progression/help pages | [Gallery/icons inspected](evidence/art-client-v1-2026-10-18.md); remaining held/state/orientation coverage open |

The new-art gallery is wired into `runTerminalClient`: front/top device views,
connected cables and item inventory views at GUI scales 2 and 4. The integrated
tour passed all 35 captures and was [visually inspected](evidence/art-client-v1-2026-10-18.md).
`AssetReferencesTest`, `AssetCoverageGameTests` and `CableConnectionGameTests` passed
in the combined 327-JVM / 289-GameTest gate. The recovered worktree's earlier
exit-137 attempt remains historical. The first integrated restart failed reopening the editor; a revised readiness barrier then passed the complete separate-process restart and saved editor check. Both outcomes and limits are [recorded](evidence/art-client-v1-2026-10-18.md).

## Screens, text and help

These rows describe user flows; they do not require a separate screen for every
row. Share navigation and components where appropriate.

| Flow | Required coverage | Stage | Implementation / client evidence |
| --- | --- | --- | --- |
| Drive bays | Tier/type counts, exact amounts and fill bars, chosen-bay removal and in-place upgrades | P2, P6 | Implemented with four keyboard-focusable bays; [native behavior and real client pickup/reinstall, scale 1–4 fit and inspected captures](evidence/ux-u2-drive-2026-10-20.md) |
| Terminal Items | Search/sort, exact counts, available/reserved/offline, bounded actions, full inventory and stale state | P3 | U3 adaptive grid, live search, Name/Qty, wheel/scrollbar, cursor pickup/deposit, inventory withdrawal, tabs and capacity summary implemented; native partial/denied/recovery cases and graphical scale 1–4/focus evidence; offline cache, physical multiplayer Shift and additional states pending |
| Terminal manual crafting and patterns | Ghost grid, remainders, JEI/manual selection, pattern editing | P4, P6 | U4 collapsible Table/Inputs/Outputs panel, mB fluid display and Use selected/+/- controls authored; updated graphical/JEI checks are blocked by the sleeping display ([status](evidence/ux-u4-crafting-panel-2026-10-20.md)). Native ghost/result/Craft/×64/Clear, translated status/tooltips and C/Shift+C remain implemented; JEI transfer and pattern encoding implemented; [grid client](evidence/terminal-grid-client-v1-2026-10-18.md) and [JEI callbacks](evidence/jei-client-v1-2026-10-18.md) recorded; physical JEI drag/+ gestures remain unverified |
| Crafting jobs | Preview/shortages/cycles, progress, cancellation, busy/cleanup state | P4–P6 | Native terminal Jobs mode: preview/Start/progress/Cancel, translated states/reasons/tooltips; headless menu checks pass; [real client preview/Start/delivery/shortage interactions and scale-4 fit](evidence/jobs-client-v1-2026-10-19.md) pass; client Cancel and processing busy/cleanup states remain native-only |
| Fluids and Energy | Real container/charge actions, units, observable capacities/rates and unsupported errors | P3, P6 | Explicit fill/empty and charge/discharge actions implemented with native conservation checks; [Fluids/Energy client captures](evidence/terminal-grid-client-v1-2026-10-18.md); capacity/rate presentation and wider state coverage remain incomplete |
| Presets and machine binding | Sides/slots/labels, filters, priorities, rates, stock targets and preview | P3 | Native buffered Import/Export/Stock rules/goal/inventory and machine label/type/access/windows/priority/rate editor implemented / Actual settings controls at scales2–4 recorded; machine preset authoring/Stock client flow now verified; selected tanks, multiport/preview and broader states remain pending |
| Permissions and gateway policies | Authorized view/edit/ownership transfer, inherited limits and revocation feedback | P2–P6 | Gateway policy editor implemented and [graphically exercised](evidence/terminal-grid-client-v1-2026-10-18.md); scoped grant commands implemented (ADR 0049), [integrated two-client multiplayer verified](evidence/multiplayer-v1-2026-10-18.md); Network Access screen with grant toggles, gateway limits, confirmed ownership transfer and read-only revocation ([ADR 0055](decisions/0055-access-and-recovery-screens.md), [graphical fit](evidence/access-recovery-ui-v1-2026-10-19.md)); two-client UI transfer pending |
| Friendly editor and deployment | Highlighting, completion, errors, undo, templates, draft/deployed, logs/quotas, bounded Wasm upload | P5–P6 | Draft/deploy/pause/stop/templates, diagnostics/logs and bounded Wasm upload implemented; [editor client](evidence/terminal-grid-client-v1-2026-10-18.md) and [JEI expression insertion](evidence/jei-client-v1-2026-10-18.md) evidence; highlighting/completion/undo and wider keyboard checks remain incomplete |
| Wireless setup | Pairing, direction, range, authorization, power loss and reconnection | P6 | Access point, handheld binding and paired links implemented; [handheld client capture](evidence/terminal-grid-client-v1-2026-10-18.md); graphical pairing/fault workflow remains unverified |
| Device diagnostics and recovery | Safe next actions, preserved evidence, authorized reconciliation and unresolved quarantine | P2–P6 | Partial translated operator inspection/known cleanup/canonical cell/bank/buffer/controller/gateway/connector/machine repair; broader reconciliation missing / Native command behavior checked; Transfer Recovery screen (return/settle/retire) implemented with [graphical fit](evidence/access-recovery-ui-v1-2026-10-19.md); device repair/adoption screens and a populated client walkthrough pending |
| In-game help and progression | First-network diagram, hopper walkthrough, offhand upgrades, factory examples and next unlocks | P3–P6 | 16-page in-game guide implemented; earlier [guide captures](evidence/terminal-grid-client-v1-2026-10-18.md) predate the new speed-upgrade page; new page client review and survival walkthrough remain unverified |
| Screen panel kit ([ADR 0064](decisions/0064-panel-kit.md)) | Panel, inset, slot, selected-choice and checkbox sprites in `textures/gui/sprites`, generated by `tools/art/generate_art.py` with nine-slice `.mcmeta` | H1 | Implemented; Gateway screen ported; terminal and other screens follow in H2/H3 |
| Shared UI assets and localization | Consistent controls/icons/tooltips/focus, translation keys, readable scales and non-color status cues | P3–P6 | Native terminal, settings, policy, editor and book screens implemented with translatable controls; client evidence exists for each screen; complete focus, tooltip-edge, keyboard and blocked-state coverage remains pending |

## Client completion checklist

The recovered presentation continuation adds generated lamp overlays for network,
program and wireless devices, visible four-slot drive occupancy, translated device
descriptions and recovery names, and a cable item model with held transforms.
[ADR 0058](decisions/0058-device-presentation.md) documents refresh/migration rules.
The [32-capture presentation run](evidence/presentation-v1-2026-10-19.md) now adds
actual six-direction/four-direction gallery views, day/night state glyphs,
representative first/third-person held items, individual tooltip corners and the
six existing screens at scales 1–4 in the minimum 1708×960 framebuffer. Expanded
native coverage validates every registered block-state model and local texture
reference. These checks narrow the open family/flow gaps above: every held item,
complete populated/blocked states, keyboard/focus and survival remain unfinished.

Record revision, client/runtime/mod list, resolution/GUI scale, actions, observed
results and evidence paths in the implementation plan. Screenshots can establish
appearance; interaction logs/tests must establish behavior.

- [x] Select style/reference sheet and inventory all current variants (remaining gameplay upgrades still need assets).
- [ ] Inspect block/item/held appearance, connected cables and six gateway orientations in a real client; verify interaction shapes.
- [ ] Exercise relevant ready/working/paused/full/offline/rebuilding/fault states; confirm text/symbol meaning and permitted information.
- [ ] Verify screens, tooltip edges, focus, keyboard actions and supported resolution/scale combinations without clipping.
- [ ] Verify JEI present/absent paths and built-in selection; check translation keys and readable fallback text.
- [ ] Follow onboarding from a fresh survival setup, including cell upgrades and recovery instructions.
- [ ] Inspect packaged resources for missing models/textures, remaining stand-ins and provenance; record optional audio/animation decisions.
- [ ] Link passing evidence for every required family and flow before P6/P7 completion.


Existing screen and integration evidence is indexed in the implementation plan.
New artwork does not replace the required interaction, accessibility, onboarding
and survival evidence. Recovery chat layout and full walkthrough remain unverified.
