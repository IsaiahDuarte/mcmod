# ADR 0018 — Canonical buffered item selection and network rates

Status: accepted; filtered Import and basic physical controls implemented, client/release evidence pending.

## Decision

Extend the existing canonical buffer deployment with immutable BufferPolicy: an
item ResourceFilter and1–64 items/tick network I/O limit, independently per direction.
Default ALL/64 retains the existing nested buffers-v2 encoding and behavior.
Nondefault policies require buffers-v3, with a strict nested policy-v1 record.
World schema ten and physical reference schema one remain unchanged. Reading
buffers-v1/v2 supplies the explicit default; unknown fields/versions/types reject
with original canonical evidence preserved. Pause/resume retains policy. Editing
atomically replaces one deployment and advances its reference revision after
shared metadata admission; no filter/stock/grant is copied into portable item NBT.

Nondefault policy charge is128 bytes plus the shared selector's conservative charge.
It counts together with owned slot metadata under the existing16MiB/256-buffer
budget. Default values fit the existing fixed owner overhead. Restore and new
configuration must pass the combined budget before binding/publishing authority.
A failed configuration does not replace provenance, advance revision or dirty data.

Automatic Import scans only nine owned slots and selects exact keys through the
configured registry/exact/tag include/exclude rules. Shared priority routes, grants,
scope, scheduling and canonical staging remain mandatory. Queued requests and known
progress recheck the same deployment and current tag membership. A rule change never
extracts/refunds a second time: old or uncertain staging remains owned and blocks
fresh work, with explicit original-owner cleanup. Tag rebinding wakes at most256
loaded metadata entries; ordinary tick preparation still visits at most eight.

Network buffer ports use one budget per backing and actual server tick, not per
slot, caller, query or configuration revision. Lowering below an already-used budget
clamps remaining admission to zero; changing configuration does not reset counters.
Known return shares the input budget and keeps any remainder staged. Native foreign
IItemHandler/hopper access remains ordinary nine-slot access, outside network rate
and script grants; this is not a block-claim system. Filters constrain automatic
selection, not physical hopper admission or independently authorized explicit moves.

Provide native, authenticated controls before the full settings screen: offhand
sample selects registry identity, sneak sample exact components; named name tags
select an ID or #tag, with sneak choosing exclusion. Sneak-clock cycles64/32/16/8/4/1;
sneak-paper clears selection; existing sneak-redstone cleanup retains precedence.
Materials are samples/tools and are not consumed. Policy edits preserve the current
Import/Paused mode. EDIT/DEPLOY and current resource grants remain required for active
Import. Translatable feedback/tooltip help accompany these controls.

## Alternatives and consequences

Chunk/item policy copies could replay stale provenance and lose canonical budget
ownership. Per-operation rates could multiply through multiple calls; per-config
budgets could be reset by editing. Filtering by rewriting keys would merge components
and corrupt conservation. Treating tag misses/faults as ALL would broaden access.
Those alternatives are rejected. A dedicated full editor remains required for
multiple rules/advanced settings and progression; physical controls are partial
presentation, not complete P3/P6 acceptance. Export/stock modes and other resource
interfaces remain later dependent implementation within the same release goal.

Tests must cover strict old/new bytes, malformed/future/raw preservation, combined
admission, actual matching/backpressure, queued reconfiguration/revocation, same-tick
rate sharing, tag-reload wake, native controls and staged cleanup. Actual client,
chunks/restart/independent saves, multiplayer and performance remain release checks.

## Export/Stock extension

The same coordinator now discovers bounded authorized item pages and dispatches
priority withdrawals into its owned buffer. Transient cursors share2MiB/256-owner
and32768-page limits; source discovery never replaces exact route reservation/
permission admission. Stock maintains0–576 total matching physical units, using
live insertion caps to avoid overshoot and unresolved staging to block fresh
extraction. Physical pipe access stays ordinary inventory; owned returns bypass
only the goal cap, preserving rate/lease/ownership validation. New modes/nondefault
goals require buffers-v4/policy-v2; older Import/Paused encodings stay byte-stable.
Basic compass/chest controls retain other policy settings; full editor/client and
release verification remain pending. No wider persistence or authority override
is introduced by these modes.
