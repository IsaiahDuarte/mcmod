# ADR 0016 — Authenticated player inventory transfer boundary

Status: accepted P3 prerequisite design; server endpoint/headless verification passed as recorded in the plan.
Terminal menus/screens and independent-file interruption evidence remain pending.

## Decision

Add an explicit PLAYER endpoint to the existing shared transfer/resource-port path.
Its stable backing UUID derives from canonical world UUID, authenticated player UUID
and inventory schema one. A runtime connection nonce binds queued work to the exact
current ServerPlayer object. Only PlayerList's currently registered live player can
receive a new binding; no packet-selected actor/operator/root can authenticate one.
A captured resource port rechecks that object/binding on every call.

Source selection is one exact main-inventory slot (0–35); destination selection may
be that slot or the whole same 36-slot inventory. Armor, offhand, cursor and crafting
slots remain excluded. Native simulation/actual calls share bounded scanning and
exact component identity; unsupported keys reject before extraction. Face/menu/
request wrappers share 128-item input/output allowances per backing/tick. Only known
actual counts spend rates. Player inventory is not network stock or a provider that
can be globally enumerated, reserved or claimed by another principal.

Local player source/destination access requires the same authenticated operation
actor and loaded, live connection, current dimension and physical proximity to the
server-derived wired origin. Moving player items into storage needs DEPOSIT at the
network destination; moving stock into the player needs WITHDRAW at the network
source. Reading network catalogs additionally needs VIEW. Local ownership is not
an extra network WITHDRAW requirement for depositing the player's own items.
Future terminal controls additionally guard the current menu, terminal lease,
selection token and revision before queued mutation.

World schema ten admits PLAYER context schema three without rewriting prior
DIGITAL/EXTERNAL schema-one or BUFFER schema-two contexts/tables. Persist player UUID,
derived backing, accepted connection nonce and selected slot/window. Validate item
kind, matching operation principal and exact derived world/player backing. Context
metadata is evidence of accepted intent, never a saved credential or inventory copy.

Connection replacement makes old queued endpoints unusable. Explicit progress by
the original authenticated operation owner may bind the current live connection to
known same-world/same-player staging, without extracting again or adopting another
principal. Original context stays unchanged; normal grants/origin/root/segment
checks and actual loaded inventory admission remain mandatory. Unknown pending
calls stay quarantined and cannot replay or receive a refund.

## Alternatives and consequences

Treating a player's inventory as a generic foreign connector would expose it through
catalog/claim registration and confuse source-slot/menu authority. A second local
inventory copy or buffer pretending to be player stock would create ownership/save
boundaries without solving authentication. Direct GUI remove/add calls would bypass
shared scheduler, preflight, rates and conserved partial staging. All are rejected.

Player files and canonical world data save independently. This slice records intent
and known accounting but does not prove crash atomicity, blind-replay safety across
all independently saved files or a full reconnect/restart recovery guarantee.
Pending-call quarantine is mandatory; full interrupted-save evidence and durable
reconciliation remain D03/P7 requirements before release. Headless mock-login tests
use the pinned platform's real PlayerList registration, not an authentication bypass.
