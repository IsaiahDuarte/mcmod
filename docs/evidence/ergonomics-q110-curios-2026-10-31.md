# Q110 — Open key finds Curios slots (2026-10-31)

QD1 resolved yes. [ADR 0069](../decisions/0069-curios-and-emi.md).

## Change

- **Hook:** `HandheldSlots` is a hook in mandatory code. `WirelessTerminalItem.openFromInventory`
  counts the handhelds it reports together with the inventory and offhand.
- **Integration:** `integration/curios/CuriosHandhelds` reads every Curios slot and keeps reading
  the same slot during the session.
- **Loading:** `FactoryCoreMod` calls `Integrations.install` by class name only when Curios is
  loaded. ArchUnit now also forbids `top.theillusivec4.curios..` and `dev.emi..` outside
  integration packages.
- **Data:** a `charm` slot for players, and the Wireless Terminal tagged `curios:charm`.
- **Build:** Curios 9.5.1 is `compileOnly`. The `curiosTest` source set and
  `runCuriosGameTestServer`, which is part of `releaseCheck`, are the only places it runs.

## Tests

- **`CuriosOpenKeyGameTests.theOpenKeyFindsAHandheldWornInACharmSlot`**
  (`runCuriosGameTestServer`, Curios loaded):
  - the terminal is valid in a charm slot;
  - one worn plus one carried is refused as several;
  - the worn one alone opens the terminal;
  - taking it off closes the terminal.

  The mock player is built with its connection configured before it joins, because Curios syncs
  slots on join.
- **Without Curios:** `WirelessGameTests.openKeyFindsTheOneChargedHandheld` still passes in the
  normal run.
- **Not run:** the plan asked for client runs. These are server GameTests, because the key only
  sends a packet and the search happens on the server.
