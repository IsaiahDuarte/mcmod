# ADR 0021 — Bounded machine preset dispatch

Status: accepted P3 runtime slice. Player preset authoring, broader client evidence,
selected tanks, multiport coordination and full release verification remain open.

## Runtime and resource boundary

Attach loaded machine holders to one per-server coordinator, capped at256. Visit
at most eight entries per tick and admit sixteen-credit preparation through the
existing shared scheduler. A holder has one outstanding observation, route or
known-progress request. No new threads, resource ledger or permission path exists.
Import reads one16-position provider page, selects a current matching exact key,
and routes to authorized storage by existing priority. Export discovers one
bounded storage page and routes its matching exact key into the machine. All
kinds retain item/mB/FE units and existing shared per-backing/per-port rates.

Stock counts matching resident units across the selected item window or whole
fluid/energy capability. Its goal is aggregate matching physical stock, not a
reservation or per-key promise. Live reads are capped at64 positions (four16-row
pages); fluid handlers with more than64 tanks are explicitly unsupported for
Stock. Import/Export retain paginated capability discovery. Discovery stops after
32768 unsuccessful pages and charges transient storage cursors against a shared
2MiB bound. Source/row rotation,20-tick retry and tag-reload wakes prevent tight
empty/full polling. Paused holders leave the active queue; unloaded holders
release their cursor/entry. Canonical removal also unregisters cached holders.

Every scheduled phase rechecks the captured canonical deployment and current
permissions; exact-key/tag selection is rechecked before mutation. The existing
transfer staging owns actual extracted units. Unresolved work blocks fresh moves;
unknown outcomes freeze without replay/refund. Compatible original-owner/current
configuration staging is delivered, never extracted again. Old programs, manual
operations or replacement principals cannot be silently adopted merely because
UUIDs or a local revision match.

Stock additionally caps matching network simulation/insertion to the live deficit,
including explicit moves. A provider refill between discovery and delivery is
therefore re-evaluated. Live reads recheck endpoint, deployment and permissions
before insertion; malformed handlers quarantine. This remains best effort for a
foreign handler: no transaction or exclusion of arbitrary mutations inside that
handler is promised. Ordinary physical automation is not goal-capped. Known owned
returns bypass only the goal, preserving endpoint identity, access, rate/capacity
checks, and cannot extract through the return wrapper.

## Configuration provenance and compatibility

Nested MachinePresets schema2 adds canonical per-node configuration high-water
marks. Retain them after deployment removal, bounded by4096 structural owners
(at most512KiB conservative fixed charges), separately from256 deployment/16MiB
selector admission. Validation of a current native reference may raise, never
lower, its mark. Every machine binding edit and active redeployment advances beyond
both native and canonical counters. Active redeployment changes the physical port
configuration once, so existing EXTERNAL context configuration identifies which
program could have issued an operation. Admission is prepared before that write
and committed only as the expected successor, preventing partial configuration
changes on quota failure. Pause advances intent revision without changing the
endpoint configuration, preserving explicit original-owner cleanup.

This closes a counter-reuse hazard: chunk rollback plus metadata removal could
otherwise recreate an old revision/configuration and adopt its staging. High-water
marks persist independently. Schema1 migration derives them from deployments and
accepted machine transfer contexts; current native references add observed values.
Schema2 rejects unknown/duplicate/nonpositive marks, unknown machine owners and
accepted context configurations above their mark. The global writer stays world12;
old schema1 bytes remain readable/frozen and old readers preserve unsupported
schema2 raw data. Transfer context ABI3 and machine reference schema2 are unchanged.

## Evidence and remaining work

Nineteen MachinePresetGameTests exercise real automatic item/mB/FE Import/Export/
Stock, exclusions, full-storage vacancy/pause, live explicit insertion caps, known
inbound/no-refill re-extraction, owned return, queued edits/revocation, replacement,
unknown extraction freeze, quota admission and original-owner recovery after
removal/chunk rollback. Core/persistence regressions cover prepared successors,
retained marks, overflow, compressed schema2 data and strict schema1 migration.
Tests correct shared-world fixture accounting and clean up temporary quota rows;
no production limit or resource assertion is weakened.

No runtime timing/throughput guarantee is inferred from those bounds. Actual
chunk/restart/independent saves, multiplayer, full guest/job recovery, performance
and player authoring/client interaction remain required. Old incompatible staging
is retained RESERVED for explicit original-owner cleanup; an unsupported changed
foreign source is not guessed or rebound. This does not finish A04/P3/A01–A17.
