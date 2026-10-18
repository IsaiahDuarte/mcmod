# JEI graphical verification — 2026-10-18

Scope: ADR 0043 client behavior (A11 JEI grid fill, R07 JEI drop). Minecraft 1.21.1,
NeoForge 21.1.252, Java 21.0.12, macOS 15.3.1 arm64, Apple M1 with one active display.
Mods: Minecraft, NeoForge, Factory Core, JEI 19.57.0.451 and the isolated
`factorycore_jei_tests` fixture.

## Reproduction

`caffeinate -d -i ./gradlew runJeiClient --console=plain` passed. JEI is a `jeiTestRuntimeOnly`
dependency, so no other run, GameTest or dedicated-server probe loads it. The fixture
mod's `@JeiPlugin` only captures JEI's live runtime. Every check below goes through that
runtime.

## Checks

1. **Terminal with JEI open.** The terminal holds 16 oak planks; JEI's ingredient list is
   displayed beside it ([capture](jei-client-v1-2026-10-18/jei-terminal-overlay.png)).
2. **Recipe view.** JEI's recipe view for `minecraft:crafting_table` opens with the
   terminal as its parent, and its **+** transfer button is enabled
   ([capture](jei-client-v1-2026-10-18/jei-recipe-over-terminal.png)).
3. **Grid fill.** JEI's `IRecipeTransferManager` resolves Factory Core's
   `TerminalTransferHandler` for the open `TerminalMenu`. It is fed JEI's own recipe-layout
   slots: the preview reports no error, and the transfer sends the fill. The server grid
   then holds four `minecraft:oak_planks` cells and previews a crafting table
   ([capture](jei-client-v1-2026-10-18/jei-terminal-filled.png)). JEI's options list every
   plank type, so the transfer chose the one the terminal actually stocks.
4. **Program drops.** JEI's `IScreenHelper` returns the program ghost handler. Dropping an
   iron ingot and then water yields exactly `item("minecraft:iron_ingot") fluid("minecraft:water")`
   ([capture](jei-client-v1-2026-10-18/jei-program-ghost.png)).

## Fixed during this run

Two consecutive drops originally produced
`item("minecraft:iron_ingot")fluid("minecraft:water")`. A drop now types a separating
space when the draft does not end in whitespace.

**Not covered:** a physical mouse drag from JEI's list, a click on the **+** button
itself, shift (max) transfer and JEI bookmarks. `result.txt` and `SHA256SUMS` are alongside.
