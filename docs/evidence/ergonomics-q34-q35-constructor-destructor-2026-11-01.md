# Q34/Q35 — Constructor and Destructor (2026-11-01)

QD9 resolved yes, as described. ADR 0070 added; SPEC R02 updated.

## Change

- **Blocks:** `PortBlock` gains `WorldAction` (NONE, CONSTRUCT, DESTRUCT) and a ticker for world
  actions. `PortContent.CONSTRUCTOR` is an EXPORTER port, and `PortContent.DESTRUCTOR` an
  IMPORTER port, both on the machine-port entity.
- **Buffer:** `MachineInterfaceEntity` holds a world buffer (Constructor 1 slot of 1, Destructor
  9 slots).
  - It reaches the buffer through `capability()` and `providerPosition()`.
  - It is saved under `FactoryCoreWorldBuffer` (schema 1) and dropped on break.
- **Engine changes, for a self-provider only:**
  - `ConnectorEntity.providerPosition()` is new.
  - `WorldConnectors.probe` accepts a connector that is its own provider.
  - `ProviderIdentity.providerPositions(owner)` returns that one block.
- **`WorldActions`:** the fake player, place, launch and drop, break with a fit check, pickup,
  and the Destructor's empty-buffer rule.
- **Recipes:** starter stage. The Constructor is an Exporter, a dispenser, iron and redstone; the
  Destructor is an Importer, an iron pickaxe, iron and redstone. Dispensers and iron pickaxes are
  now classified as starter inputs in `RecipeProgressionTest`. Art comes from `generate_art.py`.

## Tests (`WorldInteractionGameTests`)

- **`theConstructorPlacesWaitsWhenBlockedAndRespectsProtection`:**
  - With 3 stone and an EXPORT stone preset, it places one, holds one, and storage keeps one.
  - Blocked for 30 ticks: nothing more leaves storage.
  - At a protected position (`EntityPlaceEvent` cancelled) it stays air and the stone stays held.
  - Unprotected, it places again.
- **`theConstructorDropsItemsAndLaunchesFireworks`:** both diamonds are dropped in front and none
  lost, and a firework rocket is launched.
- **`theDestructorBreaksAndPicksUpAndStopsWhenStorageIsFull`:**
  - Placed facing stone, it breaks it, and the cobblestone reaches storage.
  - It picks up 5 floating oak logs.
  - **Full storage:**
    - With storage filled, the next stone's drop waits in the buffer.
    - The following stone stays for 40 ticks.
    - Freed space stores the held drop and breaks the waiting stone.
  - A cancelled `BreakEvent` leaves the stone and takes nothing.
- **Full GameTest run:** 520 of 520 pass.

## Not done (Q131)

- **Fluids:** placing and picking up fluid sources.
- **Upgrades:** Fortune and Silk Touch.
- **Block entities:** blocks with block entities are refused, so their contents never spill.
- **Speed:** speed upgrades speed up only the delivery into the buffer, not the 4-tick action
  period.
