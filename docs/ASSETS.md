# Factory Core release asset inventory

[SPEC.md](../SPEC.md) R08 owns presentation requirements. This inventory tracks
delivery; [IMPLEMENTATION.md](IMPLEMENTATION.md) owns sequence and acceptance
evidence. Initial audit: 2026-10-11. Energy-bank native model geometry is now
implemented; actual client visual checks remain pending for every family.

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

Existing models are under [models](../src/main/resources/assets/factorycore/models),
with [blockstates](../src/main/resources/assets/factorycore/blockstates) and
[English names](../src/main/resources/assets/factorycore/lang/en_us.json).
The current ten blockstate families and twenty item models reference vanilla
textures; custom texture/audio assets remain absent. The initial four-block audit
has been extended by three bank tiers, the connector, Buffered Interface and Storage Terminal.

The bank family establishes a native-model direction: dark polished-deepslate
casings, black inset panels, iron/gold/diamond resource symbols, and one/two/three
geometric tier bars. Geometry uses a 16-unit block grid and the game's existing
16-pixel texture materials. The symbol and bar count carry meaning independent
of color. Original editable JSON geometry is in the
[Starter](../src/main/resources/assets/factorycore/models/block/starter_energy_bank.json),
[Expanded](../src/main/resources/assets/factorycore/models/block/expanded_energy_bank.json),
and [Advanced](../src/main/resources/assets/factorycore/models/block/advanced_energy_bank.json)
models, with corresponding item parents and blockstates in the directories above.
These files are original repository assets under the repository license; referenced
Minecraft textures are supplied by the game and are not copied into the mod.
No custom bitmap, audio, animation or charge-state renderer is used for banks;
inspection provides exact charge and routing feedback. The assembled appearance
and model inventory transforms remain pending real-client inspection.

| Family / variants | Required presentation and feedback | Stage | Asset status | Client evidence |
| --- | --- | --- | --- | --- |
| Network Controller + block item | Recognizable hub; ready/rebuilding/offline/fault inspection | P2–P3 | Placeholder: iron cube | Pending |
| Network Cable + block item | Connected segments/junctions; matching hit/selection shapes | P2–P3 | Placeholder: full redstone cube | Pending |
| Storage Drive + block item | Four bays; installed-cell and unavailable/recovery inspection | P2–P3 | Placeholder: iron/furnace textures | Pending |
| Gateway + block item | Upstream/downstream on all six orientations; policy/offline/fault inspection | P2–P3 | Placeholder: observer textures | Pending |
| Item cells: Starter, Expanded, Advanced, Infinite | Unique cell icons, tier markings, exact capacity/ownership tooltips | P2 | Placeholder: vanilla item icons | Pending |
| Fluid cells: Starter, Expanded, Advanced | Fluid symbol, tier markings, mB capacity tooltips | P2 | Placeholder: vanilla bucket icons | Pending |
| Expansion, Advanced, Infinite modules | Related upgrade icons; discoverable compatible tier/application instructions | P2 | Placeholder: vanilla material icons | Pending |
| Energy Banks: Starter, Expanded, Advanced + block items | Lightning symbol and tier bars; observable stored/capacity/rate/routing inspection | P2 | Implemented native JSON models and item parents; appearance not yet reviewed in client | Pending |
| Storage Connector + block item | Connection face, resource/access/priority inspection | P2–P3 | Implemented native six-direction socket model, matching shape, tooltips and translated chat inspection; advanced settings screen remains pending | Pending |
| Storage Terminal + block item | Recognizable access screen and connection state | P3 | Implemented native four-facing casing/screen/contact JSON and translated access tooltip; rendering pending | Pending |
| Machine Interface + block item | Port/side/label identification and preset state | P3 | Missing | Pending |
| Buffered Interface + block item | Buffer/input/output identity and full/blocked state | P3 | Implemented native nine-slot top geometry, item parent, tooltip and authorized item/slot/mode/status inspection and Import/Paused/owned-cleanup controls; full preset/inventory screens remain pending | Pending |
| Crafting Interface + block item | Processing ports and busy/blocked state | P4 | Missing | Pending |
| Crafting Unit + block item | Distinct crafting role and working/blocked state | P4 | Missing | Pending |
| Pattern items, if used by selected implementation | Distinguish recipe/processing identity and invalid patterns | P4 | Missing; physical representation to be selected | Pending |
| Program Controller + block item | Distinct program role; deployed/paused/trapped/power state | P5 | Missing | Pending |
| Fuel Generator + block item | Fuel/remainder access and observable generation/buffer state | P5–P6, before powered demonstration | Missing | Pending |
| Wireless Access Point + block item | Range/power/access status | P6 | Missing | Pending |
| Rechargeable handheld terminal | Inventory/held appearance, charge and connection status | P6 | Missing | Pending |
| Wireless Link Endpoint + block item | Pair/direction/range/power status | P6 | Missing | Pending |
| Binding/pairing/configuration tools, if physical items are selected | Mode/target feedback, safe cancel and scoped selections | P3–P6 | Missing; representation to be selected | Pending |
| Throughput/concurrency/link upgrades selected by D06 | Distinguish function/tier; explain rates versus server quotas | P4–P6 | Missing; variants to be selected | Pending |

## Screens, text and help

These rows describe user flows; they do not require a separate screen for every
row. Share navigation and components where appropriate.

| Flow | Required coverage | Stage | Implementation / client evidence |
| --- | --- | --- | --- |
| Terminal Items | Search/sort, exact counts, available/reserved/offline, bounded actions, full inventory and stale state | P3 | Item search, Home/Next paging, exact counts, deposit/withdraw/owned-return and typed status implemented; sorting/offline cache missing / Partial graphical evidence at scales2–4; manual Shift/hover/focus and additional states pending |
| Terminal manual crafting and patterns | Ghost grid, remainders, JEI/manual selection, pattern editing | P4, P6 | Missing / Pending |
| Crafting jobs | Preview/shortages/cycles, progress, cancellation, busy/cleanup state | P4–P6 | Missing / Pending |
| Fluids and Energy | Real container/charge actions, units, observable capacities/rates and unsupported errors | P3, P6 | Missing / Pending |
| Presets and machine binding | Sides/slots/labels, filters, priorities, rates, stock targets and preview | P3 | Basic item Import selection/rate has translated physical controls and headless evidence; basic buffered Export/Stock implemented; full settings/other bindings missing / new-control client checks Pending |
| Permissions and gateway policies | Authorized view/edit/ownership transfer, inherited limits and revocation feedback | P2–P6 | Missing / Pending |
| Friendly editor and deployment | Highlighting, completion, errors, undo, templates, draft/deployed, logs/quotas, bounded Wasm upload | P5–P6 | Missing / Pending |
| Wireless setup | Pairing, direction, range, authorization, power loss and reconnection | P6 | Missing / Pending |
| Device diagnostics and recovery | Safe next actions, preserved evidence, authorized reconciliation and unresolved quarantine | P2–P6 | Partial literal-English messages; workflow missing / Pending |
| In-game help and progression | First-network diagram, hopper walkthrough, offhand upgrades, factory examples and next unlocks | P3–P6 | Missing; external player guide and recipe unlocks exist / Pending |
| Shared UI assets and localization | Consistent controls/icons/tooltips/focus, translation keys, readable scales and non-color status cues | P3–P6 | Registered names and bank/connector/buffer tooltips/chat feedback translated; native item terminal screen implemented; other shared screens pending / Pending |

## Client completion checklist

Record revision, client/runtime/mod list, resolution/GUI scale, actions, observed
results and evidence paths in the implementation plan. Screenshots can establish
appearance; interaction logs/tests must establish behavior.

- [ ] Select style/reference sheet and inventory all final variants.
- [ ] Inspect block/item/held appearance, connected cables and six gateway orientations in a real client; verify interaction shapes.
- [ ] Exercise relevant ready/working/paused/full/offline/rebuilding/fault states; confirm text/symbol meaning and permitted information.
- [ ] Verify screens, tooltip edges, focus, keyboard actions and supported resolution/scale combinations without clipping.
- [ ] Verify JEI present/absent paths and built-in selection; check translation keys and readable fallback text.
- [ ] Follow onboarding from a fresh survival setup, including cell upgrades and recovery instructions.
- [ ] Inspect packaged resources for missing models/textures, remaining stand-ins and provenance; record optional audio/animation decisions.
- [ ] Link passing evidence for every required family and flow before P6/P7 completion.


Connector assets: [block model](../src/main/resources/assets/factorycore/models/block/storage_connector.json),
[item parent](../src/main/resources/assets/factorycore/models/item/storage_connector.json),
[six-facing blockstates](../src/main/resources/assets/factorycore/blockstates/storage_connector.json)
and English keys in the language file above. Original JSON uses deepslate casing,
copper socket and three iron contacts, with matching six-way selection/collision
shapes. Vanilla texture references are supplied by Minecraft, not copied assets.
Status/type/access/side/rate/priority inspection wraps through native chat; short
tooltip lines document controls. Headless shape/interaction/recipe checks exist;
real rendering, tooltip wrapping/GUI scales and onboarding remain unverified.


The [Buffered Interface model](../src/main/resources/assets/factorycore/models/block/buffered_interface.json)
uses the established polished-deepslate/black/copper/iron materials with a recessed
tray and nine raised slot markers. Its [item parent](../src/main/resources/assets/factorycore/models/item/buffered_interface.json)
and [blockstate](../src/main/resources/assets/factorycore/blockstates/buffered_interface.json)
are wired into the creative tab and recipe. Original editable JSON uses the same
16-unit grid and repository license; vanilla textures are supplied by Minecraft.
Translated inspection reports exact item and occupied-slot counts. Headless recipe,
hopper and ownership tests passed; actual client appearance/transforms/GUI-scale
checks remain pending. Import/Paused and owned-cleanup controls now have translated
tooltips/chat states; basic registry/exact/tag include/exclude and shared rate controls now have translated tooltips/chat with headless native evidence; basic Export/Stock and compass/chest controls now have translated inspection/help and headless evidence; native [settings/inventory screen](../src/main/java/dev/izzy/factorycore/platform/client/BufferSettingsScreen.java) and translated draft/rule/inventory controls are implemented; actual new-control client checks remain pending.


Storage Terminal: [editable model](../src/main/resources/assets/factorycore/models/block/storage_terminal.json),
[item parent](../src/main/resources/assets/factorycore/models/item/storage_terminal.json),
[four-facing blockstate](../src/main/resources/assets/factorycore/blockstates/storage_terminal.json),
and [native screen](../src/main/java/dev/izzy/factorycore/platform/client/TerminalScreen.java).
The dark casing, copper bezel, blue inset screen and three iron contacts reuse
Minecraft-supplied textures with original repository geometry. The 280×232 item
screen uses native slots/widgets, translatable search/actions/status/help, focusable
buttons, keyboard row selection and exact-count hover text. [Graphical screen evidence](evidence/terminal-client-v1-2026-10-13.md) now covers
scales2–4, search, Take1, paging and a real deposit payload. Block geometry/held
transforms, physical Shift gesture, hover/focus completeness and remaining states
are still unverified.
