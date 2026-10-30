# Machine-mode Interfaces

> Since U11 ([ADR 0059](../decisions/0059-unified-interface.md)) this device is the **Machine** mode of the single `factorycore:interface` block (`InterfaceBlock`, `kind=machine`); the old standalone block and item ids are gone. The entity, lease kind, persisted schema and recovery tools described below are unchanged.

Implemented P3 binding prerequisite; see [ADR 0019](../decisions/0019-local-machine-bindings.md).
Automatic machine preset runtime is implemented through a trusted server API.
Player preset authoring and explicit selected-tank/fixed-partition adapters are implemented.
Integrated selected-port/processing contention and complete release verification remain unfinished.

Successful automatic transfers queue another bounded step through the shared
scheduler. Each item step requests at most one native maximum stack; it does not
combine uncertain foreign writes. Every continuation rechecks deployment authority
and fences and still pays normal scheduling and device/provider rate limits.
Rate-limit exhaustion retries next tick; shortage/fault backoff remains 20 ticks.
Paid tiers can improve achieved speed, but global catalog discovery and shared
budgets may dominate in large worlds. The isolated required workload is described
in [TESTING.md](../TESTING.md).

## Player controls

Craft ICI / CRC / ICI using iron I, copper C and redstone R. Place against the
provider; the socket queries its opposite face and the remaining faces connect
wires. Use it with an empty main hand to open its screen and type a case-sensitive
label; an empty label clears it. Held items no longer relabel it (U16).
Actual authenticated nearby VIEW access and current EDIT permission are required.
Empty labels select no group. Multiple local interfaces can share a label.

Empty-hand inspection reports label, item window, resource, side, access, rate and
binding status. Existing connector controls cycle resource/access/priority.
With both hands empty, normal use opens the settings screen for label, type,
access, priority, slot window and rate. Apply & Close saves once; Reset restores
latest saved values; Cancel or closing discards the local draft. Enter in a field
also applies. Fluid/energy bind whole capabilities, so their item window is fixed
to0/64. The block
holds no machine contents, and terminal stock excludes its provider. Foreign
inventory ownership stays with the provider, even during interface movement.

## Server API and ownership

All methods require the Minecraft server thread. `WorldNetworks.machines(actor,
origin,label,after,limit)` resolves only the current local segment with VIEW,
case-sensitive label and UUID continuation; limit follows the topology's bounded
page contract. EMPTY means no matches; INVALID_SCOPE/DENIED return no targets.
A consumer requiring one match must reject multiple matches, never choose silently.
Returned scopes are observations; revalidate before mutation.

For example, after resolving exactly one local interface and reacquiring its
loaded entity, `machine.configure(actor,new ConnectorSettings(ResourceKind.ITEM,
ConnectorSettings.Access.READ_ONLY,0,1,1,8))` selects zero-based slot1 only at8 items/tick. `machine.relabel(actor,
"smelter")` retains those settings and publishes the local label. Bind through
`runtime.connectors().discover(actor,origin,machine.handle(),exactKey)` and
submit `new WorldStorageTransfers.External(handle)` through the shared transfer
service. That service checks current grants/scope/configuration, stages actual
units and preserves uncertainty. This is a trusted server API, not client-supplied
actor/root authority. No direct provider mutation is authorized by label lookup.

The same adapter supports whole sided fluid capabilities in mB and FE capabilities
in FE. Defaults are64 items or1000 mB/FE per tick, independently each direction;
absolute request/rate ceilings are 1,024 items / 16,000 mB / 32,000 FE.
Physical tiers enforce smaller per-device limits (ADR 0054).
Item/tank windows cover1–64 positions. Fluid0/64 keeps whole-capability semantics;
other fluid windows require genuine `IndexedFluidHandler` support. Unsupported
capabilities/backing identity are rejected. Overlap remains conservative across
an entire backing group/resource kind unless the provider explicitly declares
fixed canonical partitions under [ADR0053](../decisions/0053-selected-tanks-and-fixed-machine-ports.md).
Only exact disjoint declared partitions may bind concurrently; their stock/holds
stay separate and physical provider rates stay shared.
Inherited storage access never authorizes cross-segment machine access.

Relabel/configuration invalidates prior handles; relabel also schedules a bounded
wire publication rebuild, so another immediate edit may return INVALID_SCOPE.
Wait for current membership before retrying. No resource mutation occurs during
that wait. Rates/claims/staging and known recovery use the existing
[connector contract](CONNECTORS.md); no second accounting path is introduced.

## Persistence and verification

World schema11 introduced the distinct lease; current schema12 also stores
canonical preset authority. Machine reference schema2 owns
label, settings, configuration and fault; ordinary connector schema1 bytes remain
unchanged. Movement advances lease generation; wrong casing, clones, stale/future
metadata remain inert/preserved. Recovery derives EXTERNAL kind from canonical
metadata and rechecks saved generation/configuration/backing. Pending calls stay
uncertain, with no replay/refund. Actual independent saves/restart remain unverified.

Three JVM tests and six native tests cover the selected contracts, including
item/mB/FE conservation and native controls. Headless checks do not prove model
appearance, GUI usability, actual chunks/restart, multiplayer, survival balance
or performance. Those remain required release evidence.

A compiled working setup/transfer example is
[MachineInterfaceGameTests](../../src/gameTest/java/dev/izzy/factorycore/testing/storage/MachineInterfaceGameTests.java):
`localLabelSelectedSlotAndScheduledTransferExcludeGlobalCatalog` waits for the
label rebuild, discovers the selected port and submits an eight-item guarded move.
The utility examples use the same path with actual mB/FE capabilities.


## Authenticated settings menu

The client keeps an unsaved form. Apply sends only bounded values plus menu ID,
opaque opening nonce and expected configuration generation. The server derives
the principal/operator from the actual registered player; current body, range,
VIEW, EDIT, structural lease, full segment scope, selected face and active menu
must match. Wrong sessions cannot save. A changed generation reloads current
values with a stale status before retry. EDIT revocation disables publication;
VIEW/range/lease/scope/face loss closes the menu. Cancel/close sends no form and
changes no settings. Apply validates every field before one existing configure
call, then closes intentionally because relabeling rebuilds wired publication.

The menu has no resource/container slots and makes no provider inventory calls.
Native initialization is passive until Minecraft installs the new menu. Display
polls are bounded to one per five server ticks and publish only changed state.
Payload version2 uses label0–64/no control characters, resource/access enums,
priority-1000..1000, nonnegative first slot, count1–64 and rate up to 1,024 items,
16,000 mB or 32,000 FE. Installed hardware further bounds accepted rates.
Energy requires first0/count64; fluids accept bounded tank windows with
explicit provider support and keep0/64 as whole capability. Provider availability
is validated by the shared adapter at execution. No persistence
world schema changed; upgraded machine references use schema4, while base
machines retain schema2. See [ADR 0054](../decisions/0054-throughput-upgrades.md).

Four native menu cases exercise open/cancel/complete atomic save, nonce/stale
rejection, range/closed-session rejection and live EDIT/VIEW revocation. Two packet
cases cover real bounded codecs and malformed fields/enum rejection. The graphical
fixture includes Reset/Cancel/Apply, form typing and draft retention at scales2–4;
actual run evidence is recorded separately when completed.


## Canonical preset authority prerequisite

[ADR 0020](../decisions/0020-machine-preset-authority.md) defines world12
MachinePresets schema1. This was the original authority prerequisite. The runtime extension below now
attaches automatic machine Import/Export/Stock; player controls are described below.

On the server thread, after establishing current machine membership, call
`machine.deployPreset(actor, MachinePresets.Mode.IMPORT,
ResourceFilter.all(ResourceKind.ITEM), 64)` to deploy a scheduled item preset. The port's
current kind must match the filter; target uses that kind's item/mB/FE units and
accepts0..Long.MAX_VALUE. The API requires VIEW/EDIT/DEPLOY; active modes also
require WITHDRAW/DEPOSIT. Actor data must come from an authenticated frontend or
trusted program context, never a client principal field. Missing authority/scope
throws IllegalStateException; invalid kind/goal/selector throws
IllegalArgumentException; revision overflow throws ArithmeticException. No provider
resource method is called. The shared port settings still own rates/access/windows.

`presetAuthorized(capturedDeployment)` checks the entire canonical record,
physical lease/configuration/selected face, root/segment, current grant generation and current
rights for the original principal. It is a necessary guard, not proof of provider
availability or a resource reservation. Resource mutation must additionally use
fresh guarded shared transfers. Mode PAUSED never authorizes work. Unrelated grant
changes conservatively invalidate a saved generation until explicit redeployment;
regrant does not silently resume old intent. Replacing a deployment advances its
revision and does not adopt old staged resources. Binding, label and upgrade edits
pause canonical intent; old chunk NBT cannot undo that pause. Since U6 an edit by the
preset's own deployer redeploys it through `deployPreset` (upgrades defer until the
network is VALID); other editors leave it paused with the interrupted mode remembered
in memory for a one-Apply resume (ADR 0054). A different provider face is ineligible until the orientation matches the saved
intent or that face is explicitly redeployed, even when the port generation is
unchanged. Structural movement leaves
old deployment identity inactive until an explicit redeployment.

The portable registry admits256 owners/16MiB of charged metadata, including paused
records. Filter bounds remain sixteen terms/256KiB. Removal reclaims only metadata,
never resource ownership. Loading validates canonical machine/root/gateway kinds and named portable faces,
allows only past/current structural generations and preserves rejected raw worlds.
World1–11 migration adds an empty table; machine/connector reference bytes remain
unchanged. Independent save interruption and actual restart remain unverified.

Compiled examples are in
[MachinePresetAuthorityGameTests](../../src/gameTest/java/dev/izzy/factorycore/testing/storage/MachinePresetAuthorityGameTests.java).
They exercise original-principal authorization, old chunk replay, revocation,
regrant, replacement and a new root. Core/persistence suites also cover quotas,
overflow, strict fixed fixtures and compressed disk round trips. Those authority tests alone prove no automatic transfer or throughput; the runtime
evidence below adds dispatch, while client preset/full release acceptance remain open.


## Scheduled Import, Export and Stock

[ADR 0021](../decisions/0021-machine-preset-dispatch.md) owns the runtime extension.
`WorldNetworks.machinePresets()` manages loaded holders and provides typed status.
Deploying through the trusted API wakes the holder; valid saved deployments register
on loaded reference binding. Import routes provider→storage, Export storage→provider,
and Stock fills aggregate matching resident units to the nonnegative goal. All
support current registry/exact/tag selectors for their kind (FE has no tags).
Rates, access, slot windows and provider identity remain the shared connector
contract. Invalid/currently unavailable scopes or providers block work without
resources being guessed, copied into a ledger or silently discarded.

Eight metadata visits/tick admit bounded preparation into the shared scheduler.
Import observes16 positions per step; Export/Stock discover bounded storage pages.
Only one request per holder runs at a time. Cursors share2MiB and32768 unsuccessful
pages, reset on changes/reload and on a refused submission (a cursor from before a network
rebuild is refused at once; M145), and full/empty/goal retries wait20 ticks. Stock
live reads cap64 selected item positions/tanks (one FE provider); larger fluid
capabilities reject Stock as UNSUPPORTED. Physical automation can overfill a goal;
all matching network insertions still cap to the current deficit. Matching reads
revalidate after foreign callbacks. Known-owned return bypasses only the goal.

Pending/uncertain transfers stop new extraction. Compatible current-configuration
original-owner staging resumes delivery without extraction. Active replacement
gets a new physical configuration and cannot adopt the old request, even for the
same principal. Pause preserves the endpoint for explicit known-owner cleanup:
`runtime.transfers().progress(id, originalActor, true, completion)` returns existing
staging only. A changed/unsupported original source may remain blocked and needs
the later recovery workflow; never replay or invent a refund. Metadata removal
stops the cached holder and does not delete resource ownership.

Schema2 configuration marks survive program removal and chunk replay. New active
ports/deployments advance beyond the greatest canonical/observed value; prepare
checks metadata admission before the physical write. Current native validation
may raise the mark without lowering it. Restore requires the full
`MachinePresets(nodes, deployments, configurations, changed)` constructor;
the deployment-only overload is for fresh or legacy data. Schema1 migration also
accounts for accepted transfer contexts. Separate marks are bounded by structural
owners, up to4096/512KiB, in addition to deployment/selector budgets.

Nineteen runtime GameTests and core/compressed-disk regressions cover the stated
boundaries. See
[working runtime examples](../../src/gameTest/java/dev/izzy/factorycore/testing/storage/MachinePresetGameTests.java).
Actual restart/independent saves,
multiplayer, selected tanks and performance remain required release evidence.


## Player preset drafts and cleanup

The existing physical menu now has Binding/Presets tabs. The server owns a bounded
per-opening draft (sixteen terms, existing256KiB filter ceiling); close discards it.
Mode/filter/goal/registry/tag/exact sample/remove/clear/reset commands change only
that draft. Apply (the DEPLOY action; the button was "Deploy" before U6) writes the
existing canonical registry through `deployPreset`. The typed goal is validated and
saved with it, so there is no separate Set step. Binding still uses its
separate complete-form Apply; unsaved binding changes do not alter preset scope.
No persistence or ABI schema changes are needed.

`MachinePresetPackets` version1 commands contain only menu/nonce/draft revision,
action0–10, selection0–35, text≤256 and a32-byte exact identity fingerprint. The
server reads sample slots from the authenticated player's main inventory; fluid
inspection uses a one-item copy, and FE selects the canonical identity. Samples
consume nothing. Display packets contain at most16 bounded human-readable rules,
exact long goal/staging counts and status, never raw component data or authority.

Commands revalidate current body/menu, physical range/VIEW, structural lease,
face and full scope. Draft editing/deployment also requires live EDIT/DEPLOY;
active deployment rechecks WITHDRAW/DEPOSIT. Wrong nonce/revision cannot publish.
External canonical/configuration changes reload the draft before another action.
Commands admit at most one per two ticks; changed display state polls every five.
Known-owner Return uses guarded scheduled progress, checks accepted origin/root/
segment and actual principal, and cancels queued work when the menu closes.
Uncertain records remain non-replayable. Only known complete empty records retire.

The transfer registry maintains a separate UUID-sorted unresolved-device index,
including restored/unbound/uncertain contexts. It grants no authority and never
aliases the backing index. The UI conservatively considers the first record;
another principal's earlier unresolved record can block later cleanup discovery.
No all-record or provider scan occurs during menu polling. All mutable state stays
on the Minecraft server thread; the client projection owns no resources.

Native tests cover draft isolation/deployment, wrong nonce/stale revision/config,
non-consuming item/fluid/FE samples, unsupported tags, current DEPLOY revocation
and actual VIEW-only original-owner return. Packet/core tests cover bounded codecs,
detached fingerprints and restored device indexing. The isolated graphical fixture
covers actual Stock authoring/deployment and selected-slot conservation at scales2–4.
See the [graphical evidence record](../evidence/machine-preset-client-v1-2026-10-17.md). This remains partial A04/A12/A13/A14;
restart, independent saves, multiplayer, broader recovery and performance are open.


## Small-window layout regression

The preset panel is300×236 GUI pixels, with four visible rule rows and Prev/Next
across the existing sixteen-term limit. This fixes clipping of the previous272-pixel
panel at the default854×480 window with a427×240 scaled GUI. Return now shares the
footer with Cancel/Reset/Deploy. The separate-process restart fixture requires every
visible widget to fit the actual screen and captures the reopened native editor;
it does not resize that second window to conceal a failed layout.


## Orderly restart evidence added 2026-10-17

The [separate-JVM world experiment](../evidence/world-restart-v1-2026-10-17.md)
now verifies a small nonempty integrated-server save/reopen: complete canonical
state, physical/provider identity, mixed fluid stock and a retained400mB hold,
500FE and resumed item Stock without redeployment. This narrows earlier restart
limitations for that fixture only. Independent save-order injection, forced chunk
unload, interrupted calls, dedicated-server/multiplayer and full acceptance remain open.


A [native distance-driven one-chunk lifecycle experiment](../evidence/chunk-lifecycle-v1-2026-10-17.md)
now additionally verifies real removal/reconstruction, unchanged canonical state/
leases, retained quantities/holds, unavailable stock and inert cached capabilities
while offline, and resumed saved Stock afterward. It does not prove split-chunk
networks, staged unload or independent save-order/interrupt recovery.


The [native split-root experiment](../evidence/split-root-v1-2026-10-17.md) now
keeps a child gateway/drive/machine loaded while the upstream root actually
unloads. Storage/preset work stops, stock/holds persist, probes do not reload the
root, and the original preset resumes only after the original root revalidates.
It also covers the finite cable-run trigger for the flat retained-seed fix.
This does not complete staged-unload, independent-save or release recovery gates.


Machine-mode Interface ownership conflicts use the shared
[connector repair](CONNECTORS.md#audited-conflict-repair). Repair keeps the accepted
preset, revision, filter, goal and configuration fence, refuses a mismatched
physical fence and requires explicit native redeployment under current DEPLOY
grants; queued old-lease work stays inert. Native evidence covers revoked and
restored grants with19 items conserved.


## Selected tanks and coordinated fixed partitions

The Binding tab's First index/Window fields apply to item slots and fluid tanks.
Fluid0/64 is the existing whole-capability setting; select another window only
when the provider supports indexed mutation. Unsupported adapters block before
mutation. Stock/Import/Export and program endpoints use the same selected catalog
and shared transfers; no separate mutation path is introduced.

A provider declaring fixed canonical partitions permits concurrent bindings to
exact disjoint windows. Their backing IDs remain stable when an interface
reconfigures or reloads, so the original partition's holds stay protected. A
duplicate, overlapping, undeclared subrange or union is refused. Per-connector
rates remain configured locally; all partitions share physical-backing spending
per direction, with each call limited by its installed tier ceiling and local rate. Item/fluid/energy capabilities remain distinct
resources on the same machine. Ordinary providers and vanilla sided wrappers
retain conservative whole-resource exclusivity. See the [adapter/API contract](CONNECTORS.md#selected-tanks-and-fixed-provider-partitions).

Focused JVM/native regressions are authored; integrated run evidence is tracked
in the implementation plan. Full processing lease interoperability, real
third-party adapters, graphical/restart/multiplayer and performance acceptance
remain separate requirements.

## Importer and Exporter (H6)

The Importer and Exporter blocks ([ADR 0066](../decisions/0066-importer-exporter.md)) are
Machine ports with a fixed role. They use this module's lease, policy, preset, staging and
recovery paths unchanged. Two differences:

- **Role.** An Importer deploys only Import and an Exporter only Export or Stock. Any other
  preset is refused.
- **Backing.** Each port's backing is derived from the container group and the face it touches,
  so ports on different faces of one container coexist. Machine-mode Interfaces and storage
  connectors keep one owner per group.

