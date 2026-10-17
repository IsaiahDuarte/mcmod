# ADR 0019 — Local machine bindings

Status: accepted for the P3 binding prerequisite. Presets, processing jobs and
complete player editors remain unfinished release work.

## Decision

A Machine Interface is a distinct canonical MACHINE_INTERFACE structural lease,
not a storage registration. Reuse the existing sided connector capability cache,
foreign backing identity, overlap exclusion, shared rates, claims, guarded transfer
scheduler and quarantine semantics. Its selected provider face supports bounded
item windows, whole fluid capability and FE. Do not copy foreign resources into
an owned ledger or include machines in either storage catalog enumeration.

Publish the physical interface as topology INTERFACE with its case-sensitive
local label (0–64 characters, no control characters). Multiple interfaces may
share a label. Resolution uses the existing bounded local topology index and
VIEW authority, never inherited storage visibility. Direct machine endpoints also
require origin and target to have the same current network and segment; shared
storage permission through a gateway does not publish its machines. EDIT is
required to change a label/settings. Relabeling advances configuration and
invalidates the wired publication and provider handle before later mutation.

World schema11 admits the new structural kind; worlds1–10 migrate without
resource changes. Machine policy/reference NBT uses strict connector schema2
with Label; ordinary connector schema1 remains unchanged. A casing cannot adopt
the other kind of lease. Saved EXTERNAL transfer context recovers the kind from
canonical structural metadata while retaining saved lease generation,
configuration and backing checks. Unknown world12/policy3 data stays inert/raw;
no uncertain resource call is replayed. Independent chunk/world save atomicity
and actual restart verification remain open.

## Alternatives and limits

Treating a machine as ordinary registered storage would expose its working inputs
and outputs to global routing. A separate duplicate adapter/transfer system would
split ownership/rate/authorization rules. Both are rejected. Reusing the foreign
boundary retains conservative exclusive ownership of a backing group per resource
kind, including disjoint windows; coordinating several simultaneous ports on the
same backing remains future work within this release.

The generic NeoForge fluid API has no guaranteed indexed mutation contract.
Selecting an arbitrary observed tank while draining/filling the whole handler can
mutate another tank. This implementation therefore binds a whole sided fluid
capability only; selected tanks require a specific adapter before being offered.
This limitation is explicit, not completion of the spec's tank-binding requirement.

The native casing uses the connector socket geometry with a blue inset and iron
contacts. Recipe: four iron, four copper and one redstone. Offhand renamed paper
or name tag sets a label without consumption; sneak-paper clears it. Current
resource/access/priority shortcuts remain available. Advanced windows/rates are
trusted server APIs; settings screens, import/export/stock on machine ports,
processing bindings and actual client/multiplayer evidence remain required.

## Verification

MachinePersistenceTest covers compressed lease/policy recovery, old-world/new-kind
rejection, future raw preservation, label bounds and unchanged connector encoding.
MachineInterfaceGameTests cover actual selected-slot scheduled conservation,
global exact-key catalog exclusion, reused/case-sensitive gateway-local labels,
queued relabel/unauthorized edits, real sided mB/FE conservation, authenticated
paper controls and exact vanilla recipe costs. These are component evidence;
full A04/A05/A12/A13/A14 and first-playable acceptance stay incomplete.
