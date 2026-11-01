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
- **EMI (Q16).** A separate `emi` plugin mirrors JEI's features through EMI's API. See the Q16
  entry in the ergonomics plan for its scope and evidence.
- **Evidence runs.** Each integration has its own source set that alone puts the mod on the
  runtime classpath:
  - `curiosTest`, with `runCuriosGameTestServer` in `releaseCheck`;
  - EMI's run, per Q16.

  The ordinary GameTest and client runs keep running without the mods.

## Alternatives

- **Hard dependencies.** Rejected: players without these mods would have to install them.
- **Reflection inside mandatory code.** Calling the Curios API by reflection from
  `WirelessTerminalItem` was rejected. It is fragile, and ArchUnit cannot check it.
- **Our own accessory slot.** Rejected: it would duplicate Curios and clash with it.
