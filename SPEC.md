# Minecraft Storage and Automation Mod

## Authority and implementation status

This file is the authoritative product contract for the first playable release. It consolidates user requirements and design defaults chosen under delegated authority. All requirements below apply unless explicitly marked **Later** or assigned to a decision gate in [IMPLEMENTATION.md](docs/IMPLEMENTATION.md).

The repository is establishing its P0 loader/build bootstrap; gameplay is not implemented. Requirements are not evidence of implementation. This spec owns behavior and scope; the implementation plan owns sequence and unresolved technical decisions; [AGENTS.md](AGENTS.md) and engineering/testing documents own contributor rules. An ADR explains a choice and cannot silently override this contract. Resolve conflicts and update all affected contracts before implementing different behavior.

## R01 — Release scope

Build accessible shared storage and factory automation inspired by Refined Storage and Super Factory Manager, with large-server performance as a measured requirement.

The first playable release includes:

- Digital item cells, digital fluid cells, energy banks, and external inventory/tank/energy-provider connections.
- Limited affordable starter item storage with upgrades through an earned infinite-capacity item tier.
- A unified storage terminal with item search/deposit/withdrawal, a manual crafting grid, fluid/container actions, energy status, and autocrafting requests/job monitoring.
- Item/fluid/energy transport with meaningful filters, priorities, rate limits, stock targets, and actionable blocked states.
- Interfaces and buffered import/export presets that work without scripts, including hopper input.
- Segmented branches with local machine labels, controlled shared-storage access, and published crafting recipes.
- Crafting-table patterns, deterministic external-machine processing, recursive crafting, reservations, cancellation, and documented recovery.
- Program Controllers, bounded WebAssembly execution with a Rust SDK, a friendly language, templates, and diagnostics.
- Friendly editing, resource/label completion, JEI ingredient dragging, and manual selection without JEI.
- Local programs and central coordination through published branch crafting jobs.
- Multiplayer permissions, bounded work, profiling, and measured acceptance workloads.
- Same-dimension wireless terminal access and later-tier paired wireless branch links, under R11's costs and limits.

“Scripting first” describes development order relative to a full visual automation editor. The storage terminal, pattern editor, presets, and friendly script editor ship in the first release. Internal milestones are incomplete development slices, not permission to shrink this scope.

**Later:** visual automation graphs; additional guest SDKs; Mekanism-specific chemicals/gases; reactor telemetry/control; cross-dimensional links; infinite fluid/energy storage; general-purpose cross-branch RPC. Arbitrary Wasm does not need to round-trip through a visual editor.

One Minecraft/loader combination is required. Platform/runtime selection follows D01/D04 in the implementation plan.

## R02 — Block roles and basic experience

Names are working names; responsibilities are binding.

| Role | Behavior |
| --- | --- |
| Network Controller | Own root identity and coordinate accounting, scheduling, and crafting. |
| Storage Terminal | Present the current user's permitted storage and crafting view. |
| Storage Drive | Host digital item/fluid cells, preserving resource-specific capacities. |
| Energy Bank | Store finite real energy with configured capacity and input/output rates. |
| Fuel Generator | Convert supported vanilla furnace fuels into finite energy so powered progression works without another technology mod. Preserve fuel-container remainders. |
| Storage Connector | Register external inventories, tanks, or energy providers with access policy and priority. |
| Network Cable | Connect devices within a segment. |
| Machine Interface | Bind sides, slots, tanks, and energy ports to local labels; offer import/export/stock presets. |
| Buffered Interface | Receive/expose real staged items to adjacent automation such as hoppers. |
| Crafting Interface | Bind processing patterns to machine ports and execute their jobs. |
| Crafting Unit | Execute supported crafting-table patterns, including deterministic remainders. |
| Gateway | Separate parent/child segments and mediate storage access and published jobs. |
| Program Controller | Run one deployed program in its segment, with a separately editable draft. |
| Wireless Access Point | Permit authorized handheld terminal access within configured range. |
| Wireless Link Endpoint | Form one explicit paired connection between parent and child gateways under R11. |

Gateways and Program Controllers are separate blocks. A branch can have no program or multiple controllers. Root programs may coordinate published jobs without a gateway of their own.

A hopper feeds a Buffered Interface in Import mode; the network drains its buffer into permitted storage. Full storage backs up the buffer/hopper without voiding. This setup requires no program or private branch. D06 specifies buffer sizes, recipes, and rates before implementation.

## R03 — Storage identity, capacities, and views

Store resources by exact identity and quantity. Item identity includes components/NBT or the platform equivalent; fluid identity preserves relevant metadata. Filters may intentionally match more broadly than storage identity.

| Resource | First-release storage model |
| --- | --- |
| Items | Finite cells measured in total item units, upgraded to an infinite-capacity item tier. |
| Fluids | Finite cells and external tanks, measured in the selected platform's documented fluid unit. Mixed identities are cataloged separately within a cell's total volume. |
| Energy | Finite banks and external providers with explicit capacity and input/output rates. No implicit energy storage in item/fluid cells. |
| Chemicals | Later typed adapters and storage; not silently treated as ordinary fluids. |

Starter, Expanded, Advanced, and Infinite define item-capacity progression. No gameplay type-count limit applies; technical/administrative limits are separate. D06 sets capacities and survival recipes. Fluid/energy storage is expandable but finite in the first release.

Infinite item storage retains only deposited resources. It does not generate a supply, remove throughput limits, or bypass budgets/costs/permissions. Representation, memory, and disk limits must reject unsupported operations with an explanation. Administrative quotas are visible and distinguished from tier capacity.

Use exact checked quantities in accounting, scripts, serialization, and detailed UI inspection. Display abbreviations may round; the ledger must not. D02 selects numeric representations, exact ABI encoding, resource units, and technical ceilings. Preflight space for the transfer amount before extraction; retain/return the exact remainder on partial acceptance. Never wrap counts, silently void overflow, or use floating-point accounting.

Upgrades preserve device identity, contents, and reservations. Reject a downgrade that cannot hold existing contents. Moving/breaking a device leaves exactly one owner of its data. D03 defines portable identity, cloning, restoration, and persistence before implementation.

### Authorized storage view

- Each segment owns its explicitly connected storage. A branch sees local storage plus the permitted view inherited through its gateway. The parent does not enumerate child-private storage.
- Nested gateway policies intersect with all ancestors. Child policy can narrow inherited access.
- Deduplicate backing storage across views. Machine buffers are not globally available stock unless explicitly registered; reject or coordinate overlapping registrations/leases.
- Queries distinguish total, available, reserved, and offline. Transfers/crafting use available loaded stock.
- For priority distribution, try eligible endpoints in descending priority and stable ID order. Round-robin distribution instead uses a persisted rotating cursor over stable IDs and skips unavailable endpoints. The selected mode is explicit.
- A published child recipe does not add its private buffers/storage to the parent's catalog.
- Unloaded storage may remain in a marked cached listing but cannot serve transfers. No automatic chunk loading.
- Energy views count unique authorized banks/providers once and report stored/capacity values only when observable. Estimated or unavailable external capacities are labeled.

## R04 — Segments, topology, and multiplayer authority

~~~text
Root controller + storage + terminal
    |
    +-- Buffered Interface <- Hopper
    |
    +-- Gateway A -- Branch A cables
    |                    +-- Program Controller
    |                    +-- Machine/Crafting Interfaces
    |
    +-- Gateway B -- Branch B cables
                         +-- Program Controller
                         +-- Machine/Crafting Interfaces
~~~

Gateways have explicit upstream/downstream ports visible on placement and inspection. Downstream is the child segment up to the next gateway, not every descendant or the nearest blocks.

- Programs discover/control interfaces only within their current segment. The storage handle refers to their authorized storage view.
- Labels are case-sensitive local strings selecting groups. Multiple interfaces may share a label; every interface also has a stable unique ID. Empty selection is a visible no-op; an operation requiring one target errors on multiple matches.
- Central coordination uses published crafting jobs. Direct cross-gateway machine access and arbitrary program messaging are Later, even for owners. Ownership allows configuration, not a script-scope bypass.
- A valid network has exactly one root Network Controller and one parent per branch. Nested gateways are supported with a finite server-configured depth limit from D06.
- Same-segment cable loops are valid. Multiple roots, gateway cycles, bypasses, or multiple parents invalidate affected connected components. Suspend routing/programs there, preserve ownership/state, and show the connections to fix. Never widen discovery during rebuilding.
- Membership/label handles have generations. Revalidate scope, permissions, and handles before mutation. Rebuild affected regions under a budget.
- A detached component remains offline until reconnected or deliberately commissioned as a new network. Only physically attached local storage changes ownership; inherited views are not copied. Automatic merges of independent controller networks are Later.
- Root/gateway disconnection or unload pauses affected branch automation, including local routes. Reconnect only after revalidation. No implicit chunk loading.

Networks are private by default. Separate grants cover viewing, deposits, withdrawals, craft requests, interface/pattern editing, program deployment, and permission management. A deployed program acts under its deployer's current grants intersected with gateway policy. Revocation applies before queued mutations. Operators may administer grants; ownership transfer is explicit.

Server validation applies to client requests, presets, scripts, and crafting. Physical access by players or foreign pipes is outside these script permissions; this mod is not a block-claim protection system.

## R05 — Transfer, accounting, and scheduling

Resource-specific adapters share scheduling, permission, and accounting paths. Document units, sides, slots, identity, simulation, partial acceptance, errors, and unsupported operations. D01/D02 select concrete APIs. Exact conversions or explicit rejection are required.

- Revalidate availability, reservations, capacity, and authority at execution. Simulation is advisory.
- Account for actual extraction/acceptance; retain recoverable remainder in owned staging. Energy transfer also needs a bounded accounting path/buffer, not unrecorded consumption.
- Serialize world mutations on the platform's required thread. Background planning uses immutable snapshots.
- Reservations and crafting port leases take precedence over background access to those reserved resources. Other stock/ports remain usable.
- Conflicting stock targets are best-effort goals subject to shared reservations and fair scheduling. Report contention; never duplicate or steal reserved stock.
- Server/network/program work shares prevent starvation. Local priority cannot bypass global quotas.
- Return actual amounts and typed rejection reasons. Faulty external handlers are diagnosed/quarantined; no fabricated rollback guarantee.
- Presets, terminal actions, crafting, and language frontends all use these operations.

Mekanism's generic item/fluid/energy ports may work through normal adapters; this does not establish chemical support or reactor telemetry.

## R06 — Autocrafting and production

Normal autocrafting uses saved recipes, with no script required. Crafting-table patterns bind to Crafting Units; processing patterns bind to machine ports and declare deterministic item/fluid inputs, outputs, and returns/byproducts. Energy is supplied through configured routes; opaque external machines need not report a recipe energy cost.

- Requests preview dependencies/shortages, then create a job on submission. Reserve against current state; preview is advisory.
- Plan recursively under a budget. Explain dependency cycles. Pattern priority chooses alternatives; stable pattern IDs break ties. Global optimality is not required.
- Reserve once in the shared ledger. External inventories remain mutable; revalidate reservations until extraction.
- Lease relevant machine ports before dispatch. Mod-owned jobs/scripts wait or return busy. Foreign pipes, players, and machines are not blocked by this lease.
- Require clean output ports and record pre-dispatch state. Complete jobs from collected leased outputs or a job-specific return buffer. A shared-storage count increase is not proof of completion.
- Initial processing recipes require deterministic outputs and exclusive external use of their ports during a job. Document this requirement in the UI. Probabilistic outputs or foreign insertion of identical outputs have no exact attribution guarantee.
- Published recipes expose versioned IDs and input/output contracts. Parent reservations pass to child execution as job-scoped claims; do not reserve twice. Validate gateway policy before dispatch.
- New requests use the current pattern version. In-flight jobs retain the accepted version; removal/disconnection invokes pause/cancellation/recovery.
- Keep-stock crafting counts expected in-flight output and deduplicates by owner/rule/target. Bound outstanding work.
- Custom script processing follows the same accept/start/progress/return/fail lifecycle. Callback success is not resource delivery.

Cancellation stops undispatched work and releases unused reservations. Return recoverable staging; do not undo consumed ingredients or running external processes. Keep a bounded owned cleanup record for in-flight outputs. D03 defines persistent states and recovery.

Continuous production supports low/high thresholds and backpressure. It does not require repeated manual crafting requests; full outputs pause work without voiding.

## R07 — Program lifecycle and language contracts

Both authoring paths use one versioned host API: local label resolution, typed filters, bounded queries, rule registration, transfers/craft requests, callbacks, bounded logs, and quota-limited persistent state.

- Rust is the first tested Wasm SDK. Publish supported features/imports and ABI versions; other language compatibility is conditional on those contracts.
- Enforce compute, memory, stack, host-call, event-queue, compilation, and logging limits. Guest metering does not bound host-side work.
- No general WASI filesystem/network access is required. D04 selects a runtime using a real Rust artifact, interruption tests, and memory/startup/host-call measurements.
- The friendly language compiles to validated native rules and bounded callback instructions using the same API. It does not need to emit Wasm. D05 freezes grammar, types, conditions, iteration, functions, errors, and limits before the parser.
- Persistent rules use stable deployment/rule keys. Registering the same key replaces the rule instead of accumulating tasks.
- Distinguish collection loops, recurring schedules, and events. Coalesce missed invocations and change notifications; never busy-loop or create unbounded catch-up work.
- Callbacks stage commands/state writes; publish only after successful return and validation. Traps discard that batch. Accepted commands may partially succeed; a callback is not a multi-inventory transaction.
- Pause, removal, replacement, or budget failure disables the deployment's rules and undispatched commands. Already-started transfers/crafts follow completion/cleanup accounting.
- Budget failure pauses with diagnostics. No suspended Wasm stack survives reload. Restore only versioned persistent state into a fresh instance and initialize with idempotent rule keys.
- Validate replacement artifacts before deactivating the old deployment. State migration/reset must preserve ownership of resources and outstanding jobs.

Design pseudocode, not a frozen grammar:

~~~text
every 10 ticks {
    for machine in machines("smelters") {
        keep machine.input stocked with 64 of item("minecraft:raw_iron") from storage
        move item("minecraft:iron_ingot") from machine.output to storage limit 64
    }
}
~~~

Within a callback, keep requests the current deficit for that invocation; move requests at most its limit. Neither creates another periodic task. Top-level declarative rules and Rust registration create persistent engine rules separately.

## R08 — Unified terminal, crafting grid, and usability

The main terminal opens to searchable Items. Additional views are Fluids, Energy, and Crafting Jobs. Preserve search/sort state per player, support keyboard navigation, and expose useful tooltips and exact counts. A player's permissions apply to every view and action.

### Items and manual crafting

- Search by display name, registry ID, mod, and supported tags; sort by name/quantity and show availability/reservations.
- Deposit/withdraw normal stacks through server-authoritative requests. Bulk actions are bounded and display partial results.
- A built-in 3-by-3 ghost recipe grid fills from owned inventory and authorized network stock. JEI recipe transfer fills the grid without consuming items.
- Craft-on-click revalidates the recipe and reserves/extracts exactly the current ingredients. Cursor/player inventory receives the output; owned staging retains anything that cannot be returned. Recipe remainders follow the same accounting.
- Bulk crafting processes a bounded number per request and stops cleanly on missing ingredients or output space. No unlimited shift-click loop on the server thread.
- Grid inputs are ghost selections, not hidden stacks that can disappear when the screen closes.
- Manual crafting of available ingredients requires no saved autocrafting pattern or Crafting Unit. Missing intermediate ingredients require an explicit autocraft request using known patterns.
- Save supported recipes as patterns through the pattern editor; do not silently register every viewed JEI recipe.

### Fluids and energy

- Fluids show exact identity, quantity, capacity, and available space. Provide explicit fill/empty-container actions using compatible items and real player inventory/container changes.
- A fluid click never hands the player a fictional fluid item. Handle partial container transactions through the adapter contract and owned staging.
- Energy shows stored amounts, known capacities, measured input/output rates, and providers/banks. Unknown external values are marked, never invented.
- Energy is not withdrawn as an inventory stack. Compatible energy items use an explicit charge/discharge interaction with configured limits.
- The mod supplies a rechargeable handheld terminal for its own charging/progression path. Unsupported external containers/energy items are rejected with a reason; their compatibility is not assumed.
- Item, fluid, and energy tabs share navigation but retain different units and actions. Operations preserve resource type and ownership.

### Automation authoring

- Friendly editor: syntax highlighting, inline errors, undo/redo, draft/deployed state, start/pause, templates, visible scopes, and autocomplete for permitted labels/resources/ports.
- JEI drops insert correctly escaped friendly-language resource expressions or ghost filter entries. Registry-identity matching is the default; tags or full data matching are explicit options.
- Provide built-in resource selection without JEI and in-world binding/label tools.
- Templates cover import/export, stock maintenance, round-robin distribution, fluids, energy, and craft requests.
- Native-rule previews show resolved targets/actions without mutation. Wasm validation checks metadata/imports; optional bounded snapshot traces cannot predict all future behavior.
- Diagnose empty selection, shortages, full destinations, busy machines, unloaded endpoints, scope denial, and exceeded budgets. State machine-specific power problems only when observable.
- Rust is edited/compiled externally with a starter project and documented commands, then explicitly uploaded as a bounded Wasm artifact. No in-game Rust compiler/editor is required.
- Paginate catalogs and send bounded client deltas. Reject unauthorized/stale client mutations server-side.

## R09 — Persistence and failure guarantees

Supported normal operation conserves resources across source, destination, and owned staging, including partial rejection, cancellation, orderly save/restart, and chunk unload. Jobs, reservations, programs, and storage have versioned owners and explicit transitions.

Hard crashes may interrupt independent Minecraft/mod saves or external mutations. Universal exactly-once durability across arbitrary third-party inventories is not promised. D03 must define durable owned-state boundaries, save ordering, uncertain external operations, migration, and recovery before persistence implementation. Diagnose/pause uncertain work; never blindly replay or fabricate refunds.

Player/admin docs and release notes must state actual guarantees and third-party limits. Incompatible saved data never silently resets.

## R10 — Performance and engineering

Use indexes, notifications where available, bounded fallback polling, and explicit invalidation. Avoid whole-network scans each tick, one thread per program, or repeatedly copying entire inventories. Idle native rules should not require continuous guest execution.

Bound server/network/program work, memory, and queues, and expose throttling/latency. D07 defines reference hardware, numerical budgets, workloads, and measurement procedure before performance is called complete. Huge counts of identical items and many unique metadata-heavy items need separate workloads.

Core accounting/topology/crafting/scheduling remain independent of mutable platform objects. Frontends use the shared API; adapters own world interaction. Optional integrations must not become mandatory class-loading dependencies. Detailed policies are in [ENGINEERING.md](docs/ENGINEERING.md) and [TESTING.md](docs/TESTING.md).

## R11 — Wired/wireless balance and survival progression

Storage capacity, transfer throughput, crafting concurrency, and connection convenience are separate upgrade axes. Infinite storage does not grant infinite transfer speed or free wireless infrastructure.

| Stage | Capacity and automation | Connection model |
| --- | --- | --- |
| Starter | Small item cell, terminal/manual grid, hopper imports, basic finite fluid/energy storage. Starter storage and wired basic routing have no upkeep requirement. | Cheap cables and physical interfaces. |
| Expanded | More capacity, basic patterns/crafting units, stock presets, accessible scripting/controllers. | Wired branches plus a powered, limited-range wireless terminal access point. |
| Advanced | Higher per-interface throughput, more crafting workers, larger finite fluid/energy stores. | Powered paired wireless links for separated sites in the same dimension. |
| Endgame | Expensive infinite item capacity and high-throughput factory upgrades. | Upgraded same-dimension links in v1; cross-dimensional link upgrades Later. |

- Wireless terminal access changes player access, not machine discovery, chunk loading, or routing throughput. It requires authorization, same dimension, configured range, and locally available power at the access point.
- Transport links are explicit pairs connecting parent/child gateways. Each pair has its own direction, permissions, resource-specific rate caps, range, and operating power buffer. They do not create an all-to-all wireless machine mesh.
- Pairing/re-pairing uses the normal topology generation/cycle/bypass checks. Wireless connectivity never bypasses a gateway's visibility policy.
- Both link endpoints must be loaded. Loss of endpoint, range, power, or root connectivity pauses affected operations without losing staged contents.
- Equivalent-tier wired connections cost less to build/operate and offer at least the throughput of a wireless pair. Cables do not incur per-hop resource loss or an arbitrary machine-count/channel puzzle.
- Wireless energy delivery accounts separately for operating power and delivered energy. Bootstrap wireless endpoints from local power; they cannot conjure energy to power their own first transfer.
- The Fuel Generator provides the baseline power source without optional mods. D06 specifies fuel conversion, finite internal buffering, throughput, and container remainders. External compatible generators may replace it.
- Paid gameplay speed upgrades never alter fair server execution budgets. A server may throttle a high-tier factory visibly.
- D06 freezes actual recipes, capacities, range, rates, and power curves with playtest evidence. Do not copy another mod's numerical caps as our performance targets.

## Acceptance criteria

Stable IDs are the release checklist. Record test/evidence paths per stage in [IMPLEMENTATION.md](docs/IMPLEMENTATION.md). A checked document is not proof of passing behavior.

| ID | Observable acceptance |
| --- | --- |
| A01 | Hopper imports without code; full storage retains all items across storage/buffer/source. |
| A02 | Finite capacity and populated upgrades preserve counts/IDs/reservations; infinite item capacity removes gameplay caps; unsafe downgrade and overflow reject safely. |
| A03 | Partial transfers/concurrent demands preserve exact accounting; overlapping backing stores never double available stock. |
| A04 | Item/fluid/energy storage and transfer respect type, units, sides, rates, reservations, permissions, and provider availability. |
| A05 | Reused labels remain private across branches; nesting/inherited policies hold; invalid rewiring suspends affected work. |
| A06 | Recursive crafting explains cycles/shortages, reserves once, accounts for outputs, and handles cancellation/cleanup. |
| A07 | Root requests a published branch recipe without private machine handles or double reservations. |
| A08 | Competing scripts/crafts obey port leases and reservations with actionable busy states. |
| A09 | Rust and friendly language perform equivalent supported operations; registration, pause, replacement, trap, and reload semantics hold. |
| A10 | Infinite loops, compilation, host work, events, and memory are bounded while unrelated work retains its fair share. |
| A11 | Terminal search, manual grid, JEI filling, fluid containers, and energy-item operations work without duplicating/losing resources, including full inventory and screen closure. |
| A12 | Unauthorized client/program actions fail; permission revocation and stale handles prevent later mutation. |
| A13 | Device movement, orderly reload, migration, unload, and injected interruptions meet D03's recovery contract. |
| A14 | Core works without JEI/Mekanism; representative platform inventories/tanks/energy handlers pass integration checks. |
| A15 | D07 small/large/overload workloads meet recorded budgets with raw evidence. |
| A16 | Wireless terminal/link range, pairing, power loss, permissions, unload, and throughput limits work; equivalent-tier wired throughput is at least as high. |
| A17 | Survival playtest reaches useful starter storage, scripting/crafting, expanded factory links, and infinite item storage without compulsory optional mods or circular unlock recipes. |

Later reactor-fuel scenarios add chemicals, continuous-production thresholds, and supported telemetry/control freshness. They are not first-release acceptance gates.

## Research context

[LogisticsNetworks comparison](docs/LOGISTICSNETWORKS.md) records observed overlap at a fixed revision. Its capabilities do not automatically become our requirements. No comparative performance claim is established.

Primary research starting points, to verify during implementation decision gates:

- Refined Storage: https://refinedmods.com/refined-storage/guides/storing-externally.html
- Refined Storage autocrafting: https://refinedmods.com/refined-storage/guides/getting-started-with-autocrafting.html
- Super Factory Manager: https://github.com/TeamDman/SuperFactoryManager
- WebAssembly: https://webassembly.org/docs/portability/
- Chicory: https://chicory.dev/docs/usage/host-functions/
- Wasmtime: https://docs.wasmtime.dev/examples-interrupting-wasm.html
- JEI: https://github.com/mezz/JustEnoughItems
- NeoForge documentation: https://docs.neoforged.net/
- Mekanism: https://github.com/mekanism/Mekanism
