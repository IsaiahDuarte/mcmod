# ADR 0038 — Terminal fluid containers and energy items

Status: selected; native headless evidence. Capacity/space columns, measured rates and
graphical checks remain open.

## Decision

The terminal's kind toggle (Items, Fluids, Energy) switches the same bounded server
search to that `ResourceKind`; rows show mB or FE with exact totals. Item withdraw/
deposit apply only in Items.

`TerminalContainers` performs explicit actions on the player's 36 main slots, one per
command, on the server thread:

- **Fill** (a fluid row): the first container whose `FluidHandler.ITEM` accepts the fluid.
  A disposable copy proves the filled container and its placement before any fluid
  leaves storage; the amount moved is exactly what the container accepted (≤ 16,000 mB),
  taken unreserved from Withdraw-allowed cells. A container that disagrees with its own
  simulation is reverted (`handler_fault`).
- **Empty** (a slot): drains the container on a copy, proves room in Deposit-allowed cells,
  inserts exactly the drained amount and only then replaces the container.
- **Charge / Discharge** (a single FE item): at most 1,000 FE per action through
  `EnergyBankAccess`, so bank tier rates apply; leftovers are returned.

No fluid is handed out as an item; unsupported containers and stacked energy items are
refused with a reason.

## Alternatives

Routing fluids through scheduled transfers would need a player-container endpoint with
partial staging; these actions are bounded and atomic within one tick on the player's
own inventory, which makes staging unnecessary. Treating energy as an inventory stack is
forbidden by R08.

## Consequences

Energy rows show the aggregate FE of authorized banks; per-provider lists, capacities,
measured rates and unknown external values are still to be shown. The mod's own
rechargeable handheld terminal arrives with wireless access.
