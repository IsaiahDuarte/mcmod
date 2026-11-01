# Q37 — Point-to-point Tunnels (2026-11-01)

QD10 approved P2P tunnels. ADR 0071 added; SPEC and the player guide updated.

## Change

- **New package `platform/tunnel`:**
  - **`TunnelBlock`:** FACING and ROLE (UNSET, INPUT, OUTPUT). The front face is not wired.
    Outputs give weak and strong redstone toward their front. Sneak-use pairs; a plain use shows
    status.
  - **`TunnelEntity`:** the frequency, owner and signal.
    - `outputs()` and `input()` return only loaded tunnels on the same frequency and VALID network.
    - The item, fluid and energy handlers work only on the input's front and take outputs in turn.
      Simulated moves leave the rotation alone, and a move that re-enters a tunnel is refused.
    - Redstone is re-checked on neighbour changes and every 10 ticks.
    - Role changes update clients only.
  - **`TunnelPairing`** and **`WorldTunnels`:** the loaded-tunnel registry, which never loads
    chunks.
  - **`TunnelContent`:** the registrations, including the capabilities.
- **Assets and recipe:** art from `generate_art.py` (ring, filled, dot). The recipe makes 2:
  iron, quartz, cable and an ender pearl, at the expanded stage.

## Tests

- **`TunnelGameTests.anInputForwardsItemsFluidsEnergyAndRedstoneToItsOutputs`:**
  - A stranger's sneak-use leaves the tunnel unset; the owner sets up an input and two outputs.
  - Only the input's front has a handler, and a simulated insert moves nothing.
  - **Items:** two inserts of 10 iron go 10 to each output's target. A dirt overflow returns
    exactly what did not fit.
  - **Fluid and FE:** 1000 mB of water and 500 FE are forwarded.
  - **Redstone:** a redstone block at the input gives 15 at both outputs, and removing it gives 0.
  - **Off the network:** cutting the outputs off unlinks them, their signal drops to 0, and the
    input takes nothing.
- **Full GameTest run:** 522 of 522 pass.
