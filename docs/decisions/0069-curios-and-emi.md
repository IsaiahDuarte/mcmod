# 0069 — Curios and EMI as optional integrations

Status: accepted 2026-10-31 (ergonomics plan gates QD1 and QD2). Extends ADR 0043 (JEI).

## Context

AE2 and Refined Storage let a wireless terminal be worn in a Curios slot, and support EMI as well
as JEI. Factory Core had JEI only (ADR 0043). Its open key (Q14) found a Wireless Terminal in the
inventory or offhand, but not in an accessory slot.

## Decision

- **Optional, compile-only.** Curios (`curios-neoforge` API) and EMI are `compileOnly`
  dependencies. Neither mod is required or bundled, and the mod loads and passes every check
  without them.
- **Isolation.** Integration code lives under `..integration..` packages. ArchUnit
  (`ArchitectureTest`) forbids every other class from depending on those packages or on the
  `top.theillusivec4.curios..`, `dev.emi..`, `mezz.jei..` and `mekanism..` APIs.
- **Loading.** JEI and EMI find their plugins through their own annotations. Curios has no plugin
  scan, so `FactoryCoreMod` checks `ModList.isLoaded("curios")` and then calls
  `Integrations.install` with the integration's class name. Mandatory bytecode never names it.
- **Hooks in mandatory code.** An integration installs a hook defined in mandatory code:
  - `HandheldSlots.Source` for Curios: where else a handheld may be;
  - `RecipeViewerSearch` (ADR 0052 amendment) for search sync.

  Without an integration, each hook is empty.
- **Curios (Q110).** Data gives players a `charm` slot (`curios/entities/wireless_terminal.json`)
  and tags the Wireless Terminal for it (`curios:charm`). Without Curios these files are ignored.
  - **Opening:** the open key counts a charged, bound terminal in any Curios slot together with
    the inventory and offhand. One opens it; two or more are refused as before.
  - **Session:** it reads the same Curios slot each tick, so taking the terminal off closes the
    terminal.
- **EMI (Q16).** A separate `integration/emi` plugin (`@EmiEntrypoint`) mirrors the JEI one
  through EMI's API:
  - **Fill:** one `EmiRecipeHandler` for the terminal menu fills the Table grid (crafting,
    stonecutter, smithing) or the processing grid (any other recipe), and moves nothing. With
    Ctrl it opens the output as a request instead.
  - **Available stock:** the handler reports the player's inventory plus the terminal's stock as
    EMI's inventory, so EMI marks recipes the network can supply.
  - **Drops:** drag-drop handlers cover ghost filters and the program editor. A generic exclusion
    area keeps EMI's lists off the upgrade panel.
  - **Search:** the terminal's search sync uses `EmiApi` search text.
  - **Shared code:** what the player holds (`RecipeViewerHoldings`) and search sync
    (`RecipeViewerSearch`) live in mandatory client code, so both plugins use them.
  - **Tag name:** the `curios:charm` item tag has a translation, as EMI asks of every listed tag.
- **Evidence runs.** Each integration has its own source set that alone puts the mod on the
  runtime classpath:
  - `curiosTest`, with `runCuriosGameTestServer` in `releaseCheck`;
  - `emiTest`, with `runEmiClient`, a graphical run (needs a display) like `runJeiClient`. The
    fixture drives EMI's own `EmiRecipeFiller` and `EmiDragDropHandlers`, so it compiles against
    the full EMI jar rather than the API.

  The ordinary GameTest and client runs keep running without the mods.

## Alternatives

- **Hard dependencies.** Rejected: players without these mods would have to install them.
- **Reflection inside mandatory code.** Calling the Curios API by reflection from
  `WirelessTerminalItem` was rejected. It is fragile, and ArchUnit cannot check it.
- **Our own accessory slot.** Rejected: it would duplicate Curios and clash with it.
