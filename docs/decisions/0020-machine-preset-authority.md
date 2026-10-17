# ADR 0020 — Canonical machine preset authority

Status: accepted P3 deployment prerequisite. Automatic dispatch and preset UI are
not implemented by this record.

## Decision

Store machine preset intent in the canonical world registry, not in portable or
chunk policy NBT. A deployment records structural owner/generation, monotonically
increasing revision, expected port configuration and selected face, deploying principal, root and
segment, network grant generation, mode, typed filter and nonnegative signed-long
stock goal. Resource units come from the filter kind. Rates remain on the existing
shared connector port rather than a per-program budget that could multiply calls.

Use a separate bounded table: at most256 deployments and16MiB of conservative
metadata charges (512 bytes plus selector charge per record). Paused deployments
retain their charge until explicitly removed. Admission/replacement rejects
before changing the previous record, revision, principal or dirty state. Revision
overflow prevents edits before the physical configuration changes. No inventory,
resource claim, transfer ownership, operator override or permission grant is saved
in a deployment.

World schema12 adds MachinePresets schema1; worlds1–11 acquire an empty table and
retain prior data. Ordinary machine reference schema2/connector schema1 stay
unchanged. Strict codecs preserve exact selector components and long goals;
unknown fields, malformed types, duplicate/unknown owners, future leases, unknown
root/segment identities and future versions preserve the whole rejected world.
A stale lease from legitimate movement remains saved but inactive. Incompatible
world13 and table2 are rejected rather than silently broadened or reset.

Only explicit deployment checks current VIEW/EDIT/DEPLOY and, for active modes,
WITHDRAW/DEPOSIT at the actual machine scope. Runtime authorization requires the
entire captured immutable record to equal the current canonical record, current
lease/configuration/face/root/segment/grant generation to match, and every required
permission to remain allowed for the original principal. A metadata match is not
itself a grant. Regrant, root replacement or another principal's replacement does
not reactivate an earlier snapshot. Background authority does not require the
owner to remain logged in; it never adopts a different principal.

Every successful binding/label configuration API edit pauses the canonical deployment and
advances its revision. Thus reloading an older chunk cannot restore an active
preset after an edit, even if its old configuration generation matches. Movement
invalidates the structural generation. Known resources stay in the independent
transfer registry under their original owner; no deployment replacement adopts or
replays them. Cross-file crash atomicity/restart recovery remains separate evidence.

## Alternatives and next integration

Chunk-only authority could replay old settings after an independent save or grant
change. Persisting operator status or treating a stored principal as an access
grant would bypass current policy. A second preset-owned inventory/transfer ledger
would split accounting. These alternatives are rejected.

The next layer must attach bounded active scheduling, observation/filtering,
priority routes, live stock/inbound accounting and original-owner staged progress
through shared services, then expose player controls and real client evidence.
Saving IMPORT/EXPORT/STOCK intent currently performs no automatic transfer; this
prerequisite does not complete A04 or P3.

Four core, three persistence and four native authority cases cover bounds,
identity/invalidation, fixed codec bytes/compressed disk data, strict migration/
rejection, old chunks, rotation, revocation/regrant/replacement and new roots. Actual
preset execution, client controls, independent saves/restart, multiplayer and
performance remain required release work.


Runtime extension: [ADR 0021](0021-machine-preset-dispatch.md) now attaches bounded
dispatch and schema2 configuration fences. Statements above about absent dispatch
describe this original prerequisite stage; player preset controls remain open.
