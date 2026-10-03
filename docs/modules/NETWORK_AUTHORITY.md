# Topology and current network authority

Portable P2 implementation supporting A05/A12 in part. Physical discovery,
controller/cable/gateway blocks, stored grants and client/guest endpoints are
pending. [ADR 0007](../decisions/0007-topology-authority.md) owns this design.
This core does not activate installed cell ledgers by itself.

## Graph ownership and rebuilding

`NetworkTopology(regionId, depthLimit)` belongs to its constructing server thread.
Region identity is a UUID; depth is 1 through 32, with balance default eight.
Use a fresh region UUID when constructing a replacement runtime/region so a
generation-one handle from a previous instance cannot become current again.
Nodes have stable UUIDs, role, loaded flag and local label. Only interfaces have
labels: case-sensitive, at most 64 UTF-16 characters, no control characters.
Empty labels have no searchable group. Node/gateway UUID namespaces are disjoint.
The world adapter must supply validated physical identities and invalidate every
affected region; a client/guest cannot supply graph mutations or an origin node.
`invalidate()` revokes the current generation immediately when world edits are
known but budgeted discovery has not captured their concrete edge changes. Start
validation only after the authoritative graph input is ready. A completed old
job also reports unpublished after a later generation change.

`putNode`, `removeNode`, `connect`, `disconnect`, `putGateway`, `removeGateway`
update the authoritative graph. Ordinary links are undirected, gateways have
explicit upstream/downstream endpoints and immutable policies. Unknown endpoints,
self ordinary links, identity collisions and admission violations throw before
a change. Identical updates/removing absent edges do not change generation.
Removal touches only the vertex's bounded incident edges. Changes increment a
checked positive generation before any new scope can be used.

`beginValidation().advance(limit)` accepts 1 through 256 charged visits per call,
returning work/done/published. Source capture, ordinary union, weak connectivity,
segment collection, gateway connection, topological walk and publication all
advance incrementally. Union-by-rank bounds each internal parent search by the
4,096-node limit. A large segment's children are visited individually. Result
publication transfers privately owned completed tables in O(1); the job stops
without publication if its captured generation changes. No partially built
memberships are visible. Fixed-size array allocations/map housekeeping are
bounded separately; visit counts are not measured nanosecond guarantees.

Ordinary loops are valid. A weak network must have exactly one root, with the
root segment at the head of an acyclic gateway tree and one parent per branch.
Bypasses, cycles, multiple parents/roots, upstream-root violations and excessive
depth suspend the affected component. Diagnostic precedence when several apply
is bypass, cycle, multiple parents, depth, upstream root, then root count.
An unloaded root/cable suspends its segment and descendants. An unloaded gateway
suspends downstream descendants; upstream and siblings remain valid. Unloaded
storage/interfaces/programs/terminals are individually unavailable. Known graph
state remains for revalidation; no chunk load is performed. Removing the only
root/parent leaves detached uncommissioned components with `NO_ROOT`.

Limits: 4,096 nodes, total degree eight/node, 4,096 gateways/region, at most 32
ancestor steps. These are technical defaults, not measured world-scale budgets.
Mutation invalidates the whole supplied affected region until publication;
partitioning unrelated regions and fair world neighbor-probing are future adapter
work. Old jobs/results cannot preserve access during rebuilding.

## Scope and query contracts

`membership(node)` returns typed status and a scope only for a valid loaded node.
`Scope(region, generation, network, segment, node)` is a revalidated descriptor,
not a grant. Root segments use their root ID; child segments use the gateway ID.
`validate(scope)` rejects foreign/stale/forged descriptors and current invalid or
unloaded membership. Missing nodes are `UNKNOWN`; unsettled graphs are
`REBUILDING`. Callers cannot retain a scope through topology edits.

`machines(scope, label, afterId, limit)` returns up to 64 loaded local interface
scopes in stable UUID order, with a more flag. Cursor is exclusive; null starts
the page. The generation must still match on every page. Reused labels never
cross gateways and case mismatch/empty selection yields an empty page.
`ancestors(scope)` returns at most 32 nearest-parent-first segment/policy pairs,
never children. These topology queries are trusted core APIs; external adapters
must use `NetworkAuthority` and bind origin to the actual controller/terminal.

## Current permissions and policy

`NetworkPermissions(network, owner, changed)` starts private. Permissions are
VIEW, DEPOSIT, WITHDRAW, CRAFT, EDIT, DEPLOY and MANAGE. `Actor(id, operator)` is
created by server authentication, never from a client operator flag. Resolve it
from current server state at execution: queued work may retain the principal UUID
but must not retain an earlier operator/admin flag. Owner has
implicit grants. Operators have implicit MANAGE only and can explicitly change
grants; they have no implicit stock/discovery/deployment bypass.

`setGrants(administrator, principal, permissions)` replaces one principal's
explicit grants; empty revokes/removes it. `transferOwnership(administrator,
nextOwner)` is explicit, removes the new owner's redundant grants, and removes
the previous owner's implicit rights. Both require current MANAGE. Typed results
are OK/DENIED/LIMIT/OWNER_IMPLICIT; owner grants cannot be narrowed through an
explicit entry. Limit 256 explicit principals; checked generation overflow
rejects before mutation. Successful changes call trusted `changed`; the callback
must not throw. `snapshot()` is deeply immutable. Restoring its validated `State`
preserves owner/current grants/generation; a disk schema/adapter is still pending.

`GatewayPolicy` separately restricts branch permissions and inherited storage
operations/resource kinds. `NetworkAuthority.register` admits at most 1,024
network permission owners and rejects replacement. `authorize(actor, origin,
operation)` checks current topology, registered grants and every ancestor's
branch policy at execution. Policy/ownership grants cannot bypass machine scope.
`authorizeMachine` additionally requires a loaded interface in the exact origin
network/segment. The trusted operation implementation chooses the required
permission; a caller-supplied permission enum must not authorize another action.
The authority's `setGrants`/`transferOwnership` wrappers require current MANAGE
in the actual origin scope before calling the permission owner's mutation.
Branch policy, stale scope or grant denial returns DENIED without a state change.
Player endpoints use these wrappers rather than invoking the lower-level owner
directly; trusted server administration may explicitly use the owner's global
management operation.

`machines` and `oneMachine` require VIEW and return ALLOWED/DENIED/INVALID_SCOPE/
EMPTY/AMBIGUOUS as applicable. One-target lookup rejects multiple matches.
`storage(actor, origin, key, operation)` supports VIEW/DEPOSIT/WITHDRAW/CRAFT and
returns the local segment plus permitted ancestors, nearest first. At each edge,
inherited operation/kind restrictions intersect; a denied intermediate edge
cannot be bypassed to reach a grandparent. Parent results exclude children.
Endpoint deduplication, exact filtered backing catalogs and mutation accounting
remain the resource/world adapter's obligations.

All methods enforce the same owner thread. No frozen grant snapshot authorizes
queued work: query/authorize again immediately before each mutation. Stale scope
results are INVALID_SCOPE; unauthorized results are DENIED and return no private
query data. No client request, guest host call or player permissions screen is
introduced by this foundation slice.

Example: a root and two child gateways have a local interface labeled `smelters`
in each segment. A deployed branch controller's current scope resolves only its
local `smelters`, even for the network owner. Storage lookup may include root
stock if all inherited policies allow it. Revoking the deployer's withdrawal
grant after scheduling but before execution denies that queued withdrawal.

Standalone core example (imports from `core.network` and `java.util`):

```java
var root = new UUID(0, 1);
var program = new UUID(0, 2);
var owner = new NetworkPermissions.Actor(new UUID(0, 4), false);
var guest = new NetworkPermissions.Actor(new UUID(0, 5), false);
var topology = new NetworkTopology(UUID.randomUUID(), 8);
topology.putNode(new NetworkTopology.Node(root, NetworkTopology.Role.ROOT, "", true));
topology.putNode(new NetworkTopology.Node(program, NetworkTopology.Role.PROGRAM, "", true));
topology.putGateway(new NetworkTopology.Gateway(
    new UUID(0, 3), root, program, GatewayPolicy.open(), true));
// This two-node fixture finishes in one call. Real jobs reschedule unfinished advances.
if (!topology.beginValidation().advance(64).published()) throw new IllegalStateException();
var grants = new NetworkPermissions(root, owner.id(), () -> {});
var authority = new NetworkAuthority(topology);
authority.register(grants);
var scope = topology.membership(program).scope();
grants.setGrants(owner, guest.id(), Set.of(NetworkPermissions.Permission.WITHDRAW));
var allowed = authority.authorize(guest, scope, NetworkPermissions.Permission.WITHDRAW);
grants.setGrants(owner, guest.id(), Set.of());
var denied = authority.authorize(guest, scope, NetworkPermissions.Permission.WITHDRAW);
// allowed is ALLOWED; denied is DENIED. No stock mutation was performed.
```

## Evidence and remaining work

Topology/authority tests cover ordinary loops, independent/multiple roots,
gateway bypass/cycle/parents/depth, branch suspension/recovery, changed-job
publication, stale/foreign/forged scopes, full graph/degree admission and 3,072
gateway fanout with one visit/call. Generated 300 unload/recovery sequences use
seed 0x70F0106. Private grants, operators, transfer/overflow, reused labels,
pagination/ambiguity, inherited intersections and real FairScheduler queued
revocation are exercised. World discovery, persisted graph identities/grants,
real chunk events, network/device activation, clients and server performance
remain required P2/P3/P7 evidence. Full A05/A12 is not proved by these core tests.
