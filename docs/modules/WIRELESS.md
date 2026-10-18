# Wireless access

Implemented: `platform/wireless` — `AccessPointBlock`/`AccessPointEntity` (terminal role,
20,000 FE buffer, 1,000 FE/tick input, 64-block range, 16 FE per open-session tick),
`WirelessTerminalItem` (20,000 FE, 64 FE per mutating action, bound via use-on), and
`WirelessAccess`, the `TerminalAccess` implementation re-checked every tick.
See [ADR0039](../decisions/0039-wireless-access.md).

Thread ownership: server thread only. Binding data is dimension + block position; no
grants, stock or session credentials are stored on the item or block.

Refusal reasons: `unbound`, `missing` (access point not loaded), `no_power`,
`out_of_range` (also wrong dimension or no View).

## Paired links

`LinkEndpointBlock`/`LinkEndpointEntity` ([ADR0040](../decisions/0040-paired-wireless-links.md)):
back face on a gateway, one-to-one pairing within 256 blocks, 20,000 FE buffer, 32 FE per
paired tick. `WorldWireReader` emits the partner port only while `active()`. Diagnostics:
`unpaired`, `needs_gateway`, `out_of_range`, `no_power`. Pairing results: `paired`,
`out_of_range`, `missing`, `denied`.

Not implemented: per-pair resource rate caps, measured throughput comparison beyond the
shared scheduled path.
