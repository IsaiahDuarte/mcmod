# Wireless access

Implemented: `platform/wireless` — `AccessPointBlock`/`AccessPointEntity` (terminal role,
20,000 FE buffer, 1,000 FE/tick input, 64-block range, 16 FE per open-session tick),
`WirelessTerminalItem` (20,000 FE, 64 FE per mutating action, bound via use-on), and
`WirelessAccess`, the `TerminalAccess` implementation re-checked every tick.
See [ADR0039](../decisions/0039-wireless-access.md). The Access Point, the Wireless Link and
the Program Controller take FE through one `platform.power.RateLimitedEnergyInput`
(consume-only, capacity and per-tick rate, server side only, an owner gate, dirty callback); a
Link with unsupported saved data also reports `canReceive` false, and a Program Controller
item's stored FE is clamped to the buffer when placed.

Thread ownership: server thread only. Binding data is dimension + block position; no
grants, stock or session credentials are stored on the item or block.

Refusal reasons: `unbound`, `missing` (access point not loaded), `no_power`,
`out_of_range` (also wrong dimension or no View).

## Paired links

A link is a gateway upgrade ([ADR0040](../decisions/0040-paired-wireless-links.md),
[ADR0061](../decisions/0061-gateway-link-upgrade.md)). `LinkItem` installs a `LinkModule`
on the gateway's upstream or downstream face under Manage (or orphan ownership); the
gateway entity persists it under `FactoryCoreLink`. One-to-one pairing within 256 blocks
(`LinkPairing`, sneak-use with an empty hand), 20,000 FE buffer accepted on any face of
a linked gateway, 32 FE per paired tick. `WorldWireReader` adds a port from the linked
side's vertex to the partner gateway only while `active()`. Diagnostics, shown as the
gateway screen's status reason: `unpaired`, `out_of_range`, `no_power`, `unknown_data`.
Pairing results: `paired`, `out_of_range`, `missing`, `denied`.

Shared per-pair limits are 256 items, 4,000 mB and 8,000 FE per server tick,
aggregated across both directions and all callers. A route crossing several pairs
uses every pair's remaining allowance. Wired/sibling routes do not consume an
unrelated upstream pair. Partial FE actions can use remaining capacity; an
indivisible container or table craft waits for its whole transaction to fit.
Link operating FE is separate. See [ADR0050](../decisions/0050-shared-wireless-throughput.md)
for staging legs, uncertain calls, bounds and the legacy-restoration boundary.
Controlled throughput comparison remains unverified.

With Overdrive installed on both linked gateways, shared pair caps become 1,024 items,
16,000 mB and 32,000 FE per server tick. One upgraded end retains base pair caps.
Pair identity/spending stays unchanged during same-tick upgrades; nested paths
still use the tightest pair. Device/bank rates and operating FE remain separate.
Installation uses current Manage or the existing orphan-owner pairing rule.
Known upgrades drop separately on break for reinstallation.

The optional version-one `Throughput` NBT field accepts ENDGAME; absence means
legacy Base. Wrong types, unknown names or unsupported link tiers preserve the
whole before-state and keep the link inert. They accept no operating power
and cannot pair. Breaking the gateway emits exactly one link item carrying a recovery
envelope, with no guessed paid-item refund; installing it again retains the
unsupported data and remains inert. Use a compatible build or investigate the preserved item/backup. See
[ADR 0054](../decisions/0054-throughput-upgrades.md) and
`WirelessUpgradeRecoveryGameTests` for actual break/place verification.

## Link lifecycle verification

`./gradlew runWirelessLifecycleServer` runs a dedicated server with a root and one
linked gateway kept loaded, and a remote linked gateway and drive in another chunk.
It releases the remote chunk's fixture ticket, requires an actual chunk-unload event
and removed block entities, then checks 40 ticks without implicitly loading it.
The remote cell's exact stock and principal-owned reservation must remain unchanged
and unavailable. Reload must restore the reciprocal pair and original cell lease
before the reserved extraction can resume. The task fails on missing observations,
timeouts, or a missing fresh `build/reports/wireless-lifecycle/result.txt` report.
This is a required `check` dependency. It covers orderly linked-gateway eviction and
reload. Separate `WirelessThroughputGameTests`, `WirelessCraftingGameTests` and
`TerminalWirelessContainerGameTests` exercise resource limits; interrupted foreign
writes and controlled performance acceptance remain separate.
