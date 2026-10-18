# ADR 0033 — Published branch recipes

Status: selected; native headless evidence. A player gateway-policy editor remains P6.

## Context

R04/R06 require central coordination through published crafting jobs: a parent may
request a child branch's recipe without receiving private machine handles or child
storage, reservations pass to child execution instead of being made twice, and the
gateway policy is validated before dispatch. A07 is the acceptance criterion.

## Decision

A pattern held in a descendant branch is **published** to an ancestor segment when
every gateway on the path from the holder up to that ancestor allows `CRAFT` in its
`branchPermissions`. Publication is derived from existing policy, so no new persisted
field exists; the default open gateway publishes, and a gateway that forbids its
branch from crafting also withholds its recipes. Publication is upward only.

`WorldCrafting.index(scope)` includes published offers (marked `published`) alongside
local ones and rebuilds per query, so the policy is re-checked before every dispatch.
A requester's job stays owned by the requester's segment: it plans and reserves only
from the requester's CRAFT storage view, executes at the branch's holder through job
staging, and delivers outputs to the requester's storage. Branch storage never enters
the parent's view; the parent never receives a handle to the branch's machines.

`NodeRegistry.setPolicy(handle, policy)` is a trusted owner-thread setter for a valid
gateway lease (identity, generation and position unchanged); callers authenticate the
editor and trigger topology revalidation.

## Alternatives

A dedicated persisted "publish" flag would change node and audit encodings for a
choice the existing branch-permission policy already expresses. Executing published
jobs as separate child jobs with their own reservations would reserve twice and need a
cross-segment claim transfer; one parent-owned job with staging gives the same
isolation with one reservation.

## Consequences

Branch machines are used by parent jobs under the same exclusive-use rules as local
ones. Nested branches publish transitively only when every intervening gateway allows
CRAFT. A gateway policy UI is still missing, so publication changes need trusted
server code (or the default).
