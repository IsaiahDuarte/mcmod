# Q31 — Relay (2026-11-01)

QD7 resolved yes, with at most one rebuild per second. SPEC R02 and ADR 0007 amended.

## Change

- **Block:** `RelayBlock` is a full `NetworkedBlock` with a `powered` state. Its faces are wired
  only while powered.
  - **Tick:** a neighbour change schedules a tick, unless one is already pending. The tick applies
    a changed signal and schedules the next read 20 ticks later.
  - **Wire reader:** it reads the Relay as a CABLE vertex whose ports follow `wiredFace`.
- **Recipe:** starter stage, 4 iron, 3 redstone, 2 Network Cables. It has art from
  `generate_art.py` (`relay`, `relay_on`) and is listed in `RecipeProgressionTest` and
  `RecipeGameTests` (30 recipes).

## Tests

- **`RelayGameTests.theBranchIsOnlineOnlyWhileTheRelayIsPowered`:**
  - Root, cable, Relay, cable.
  - Unpowered, the branch is not VALID.
  - A redstone block makes it VALID in the root's network.
  - Removed after the cooldown, the branch goes offline again.
- **`RelayGameTests.aFlickeringSignalChangesTheRelayAtMostOncePerSecond`:**
  - The signal turns on, then off, on and off again within 6 ticks.
  - The Relay stays powered inside the cooldown.
  - It turns off only once a full 20 ticks have passed since it turned on.
- **`WiringConsistencyGameTests`:** for every networked block, the reader's ports match the
  cable's arms. It now includes the Relay.
- **Full GameTest run:** 515 of 515 pass.
