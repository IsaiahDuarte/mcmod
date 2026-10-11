# Factory Core release asset inventory

[SPEC.md](../SPEC.md) R08 owns presentation requirements. This inventory tracks
delivery; [IMPLEMENTATION.md](IMPLEMENTATION.md) owns sequence and acceptance
evidence. Initial audit: 2026-10-11. No finished visual family or actual client
visual check is claimed by this document.

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
The current four blocks and fourteen item models reference vanilla textures;
custom texture/audio assets are absent in the initial audit.

| Family / variants | Required presentation and feedback | Stage | Asset status | Client evidence |
| --- | --- | --- | --- | --- |
| Network Controller + block item | Recognizable hub; ready/rebuilding/offline/fault inspection | P2–P3 | Placeholder: iron cube | Pending |
| Network Cable + block item | Connected segments/junctions; matching hit/selection shapes | P2–P3 | Placeholder: full redstone cube | Pending |
| Storage Drive + block item | Four bays; installed-cell and unavailable/recovery inspection | P2–P3 | Placeholder: iron/furnace textures | Pending |
| Gateway + block item | Upstream/downstream on all six orientations; policy/offline/fault inspection | P2–P3 | Placeholder: observer textures | Pending |
| Item cells: Starter, Expanded, Advanced, Infinite | Unique cell icons, tier markings, exact capacity/ownership tooltips | P2 | Placeholder: vanilla item icons | Pending |
| Fluid cells: Starter, Expanded, Advanced | Fluid symbol, tier markings, mB capacity tooltips | P2 | Placeholder: vanilla bucket icons | Pending |
| Expansion, Advanced, Infinite modules | Related upgrade icons; discoverable compatible tier/application instructions | P2 | Placeholder: vanilla material icons | Pending |
| Energy Banks: Starter, Expanded, Advanced + block items | Tier/charge identity; observable stored/capacity/rate state | P2 | Missing | Pending |
| Storage Connector + block item | Connection face, resource/access/priority inspection | P2–P3 | Missing | Pending |
| Storage Terminal + block item | Recognizable access screen and connection state | P3 | Missing | Pending |
| Machine Interface + block item | Port/side/label identification and preset state | P3 | Missing | Pending |
| Buffered Interface + block item | Buffer/input/output identity and full/blocked state | P3 | Missing | Pending |
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
| Terminal Items | Search/sort, exact counts, available/reserved/offline, bounded actions, full inventory and stale state | P3 | Missing / Pending |
| Terminal manual crafting and patterns | Ghost grid, remainders, JEI/manual selection, pattern editing | P4, P6 | Missing / Pending |
| Crafting jobs | Preview/shortages/cycles, progress, cancellation, busy/cleanup state | P4–P6 | Missing / Pending |
| Fluids and Energy | Real container/charge actions, units, observable capacities/rates and unsupported errors | P3, P6 | Missing / Pending |
| Presets and machine binding | Sides/slots/labels, filters, priorities, rates, stock targets and preview | P3 | Missing / Pending |
| Permissions and gateway policies | Authorized view/edit/ownership transfer, inherited limits and revocation feedback | P2–P6 | Missing / Pending |
| Friendly editor and deployment | Highlighting, completion, errors, undo, templates, draft/deployed, logs/quotas, bounded Wasm upload | P5–P6 | Missing / Pending |
| Wireless setup | Pairing, direction, range, authorization, power loss and reconnection | P6 | Missing / Pending |
| Device diagnostics and recovery | Safe next actions, preserved evidence, authorized reconciliation and unresolved quarantine | P2–P6 | Partial literal-English messages; workflow missing / Pending |
| In-game help and progression | First-network diagram, hopper walkthrough, offhand upgrades, factory examples and next unlocks | P3–P6 | Missing; external player guide and recipe unlocks exist / Pending |
| Shared UI assets and localization | Consistent controls/icons/tooltips/focus, translation keys, readable scales and non-color status cues | P3–P6 | Only registered names translated / Pending |

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
