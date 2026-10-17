# Machine Interfaces

Implemented P3 binding prerequisite; see [ADR 0019](../decisions/0019-local-machine-bindings.md).
Automatic machine presets, selected-tank adapters, multiport coordination,
processing jobs and complete settings/client verification remain unfinished.

## Player controls

Craft ICI / CRC / ICI using iron I, copper C and redstone R. Place against the
provider; the socket queries its opposite face and the remaining faces connect
wires. With an empty main hand, use offhand renamed paper or a name tag to copy
a case-sensitive label. Sneak with paper clears it. Neither consumes the tool.
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
current connector technical maxima are128 items/1000 mB/FE per request/rate.
Item windows cover1–64 positions. Unsupported capabilities/backing identity are
rejected; overlap is conservative across an entire backing group/resource kind.
Indexed tank mutation and simultaneous disjoint-window bindings are not promised.
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
Payload version1 uses label0–64/no control characters, resource/access enums,
priority-1000..1000, nonnegative first slot, count1–64 and rate1–128 items or1–1000
mB/FE. Whole utility capabilities require first0/count64. Provider availability,
selected tanks and automatic presets remain separate contracts. No persistence
schema changed; applying writes existing machine reference schema2/world11.

Four native menu cases exercise open/cancel/complete atomic save, nonce/stale
rejection, range/closed-session rejection and live EDIT/VIEW revocation. Two packet
cases cover real bounded codecs and malformed fields/enum rejection. The graphical
fixture includes Reset/Cancel/Apply, form typing and draft retention at scales2–4;
actual run evidence is recorded separately when completed.


## Canonical preset authority prerequisite

[ADR 0020](../decisions/0020-machine-preset-authority.md) defines world12
MachinePresets schema1. This stores intent and guards only: no automatic machine
Import/Export/Stock dispatcher or player preset controls are attached yet.

On the server thread, after establishing current machine membership, call
`machine.deployPreset(actor, MachinePresets.Mode.IMPORT,
ResourceFilter.all(ResourceKind.ITEM), 64)` to record an item preset. The port's
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
revision and does not adopt old staged resources. Binding or label edits pause
canonical intent; old chunk NBT cannot undo that pause. A different provider face is ineligible until the orientation matches the saved
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
overflow, strict fixed fixtures and compressed disk round trips. These prove no
automatic transfer, throughput, client preset flow or full release acceptance.
