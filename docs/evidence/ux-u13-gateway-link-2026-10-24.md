# U13 — Wireless link as a Gateway upgrade

Scope: U13 ([ADR 0061](../decisions/0061-gateway-link-upgrade.md)). Minecraft 1.21.1,
NeoForge 21.1.252, Java 21, macOS arm64.

Implemented:

- `factorycore:wireless_link` is an upgrade item. Used on a gateway's upstream or
  downstream face, it installs a `LinkModule` in the gateway's block entity, under
  Manage or orphan ownership. Other faces, a second link and non-gateway blocks are
  refused without consuming the item.
- The link keeps the endpoint's rules: 20,000 FE buffer at 1,000 FE/tick input,
  accepted on any face of a linked gateway; 32 FE per paired tick; range 256 in one
  dimension; one-to-one pairing by empty-hand sneak-use; never loading chunks;
  Overdrive pair caps; strict `Throughput` reading with an inert recovery item.
- Discovery adds one port from the linked side's vertex to the partner gateway
  while the pair is active. Throughput metering uses the same reciprocal pair.
- Breaking a linked gateway drops the casing, the link and any Overdrive.
- The gateway screen's status line shows the link state: Link paired, unpaired, out
  of range, needs FE or needs recovery.
- The `wireless_link_endpoint` block, item, block entity, recipe, loot table,
  blockstate, models, textures and lamp legend are removed with no aliases (UD7). The
  link item takes the endpoint's recipe. It has its own generated icon (an amber
  emitter dish on an upgrade card).

Tests (the requirement changed; coverage moved with it):

- `WirelessLinkGameTests`, `WirelessThroughputGameTests`, `WirelessCraftingGameTests`
  and `TerminalWirelessContainerGameTests` install links in the gateways the
  endpoints used to touch, then pair the gateways.
- `WirelessUpgradeRecoveryGameTests` runs the paid Overdrive, reload, break-drop and
  unknown-tier recovery checks on a linked gateway, through the real item. The new
  `linkItemInstallsOnlyOnAGatewaySideOnce` covers wrong-face and second-link refusals
  and the screen's link state.
- The presentation lamp row now checks link diagnostics at the linked gateways
  (paired/paired/no power/unpaired) instead of endpoint lamps. The asset bound is
  ≥13 blocks; the program lamp legend is the only device legend left.
- `runWirelessLifecycleServer` uses linked gateways: one stays loaded while the
  partner gateway's chunk really unloads for 40 ticks.

Checks:

- `python3 scripts/verify.py` PASS.
- Under the GUI lock and `caffeinate`: `python3 scripts/verify.py --full` PASS (all
  355 required GameTests, the wireless lifecycle server with `unloads=1`, and the
  gateway-depth server); `runPresentationClient` PASS; `runJeiClient` PASS.
- Screenshots inspected: `gateway-policy` at scales 1 and 4 shows "Online · Link
  unpaired" within the title line; the Wireless Link tooltip; the states rows; the
  regenerated reference sheet.

Open: the gateway has no lamp for link state, only its screen. Placed endpoints in old
worlds are not converted (UD7).
