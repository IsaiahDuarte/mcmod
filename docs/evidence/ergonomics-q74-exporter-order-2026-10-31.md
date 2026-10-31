# Q74 — Exporter scheduling (2026-10-31)

**Order.** `ExportOrder` (core) has In order, Round robin and Random. `after(sent, slots, random)`
gives the next pass's start slot, and `distance` is the cyclic distance a pass minimizes.
`WorldMachinePresets.orderedRows` scans the export pages for an "Only these" filter with two or
more slots. It keeps the stocked key nearest after the start and stops early at the start slot.
`finish` moves the start on according to the order after a delivered move. When the destination
refuses the move (full, wrong resource, unsupported), the next pass starts after that slot. A
transient refusal (rate, busy, rebuild) retries the same slot. An earlier draft also advanced on
those, and a full run showed `PPSSPS`.

**Block and screen.** `MachineInterfaceEntity` saves `ExportOrder` (In order is omitted, so
existing blocks keep their bytes). `MachineSettingsMenu` syncs it in a data slot and sets it with
button `320 + ordinal` under Edit, reporting `ORDER_SET`/`ORDER_DENIED` (preset protocol 4). The
Exporter screen's Keep row has an order button at its right.

## Checks

- `ExportOrderTest`: start slots for each order (round robin wraps), random within range, cyclic
  distance.
- `PortGameTests.roundRobinExporterAlternatesTwoItems`: an Exporter on a chest with an ender pearl
  slot and a snowball slot, 48 of each in storage. Both stack to 16, so each move is one full stack
  in the chest's next empty slot. The slots read back the move order, which is exactly `PSPSPS`.
  (Polling the chest each tick missed moves that finished in the same tick.) The block's saved tag
  holds `ExportOrder: ROUND_ROBIN`.
- `PortGameTests.inOrderExporterSendsTheFirstSlotFirst`: the same setup reads `PPPSSS`. Nothing is
  saved for the default.
- 479 GameTests passed in five consecutive full runs (with Q114's fixes).
- Presentation client `exporter-scale-1.png` / `-scale-4.png`: the Keep row with **In order** at
  its right, fitting at scales 1–4.
- `python3 scripts/verify.py`: PASS.
