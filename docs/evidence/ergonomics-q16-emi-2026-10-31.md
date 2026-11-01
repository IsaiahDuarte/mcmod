# Q16 — EMI support, and Q127 — quick clicks are not dropped (2026-10-31)

QD2 resolved: EMI yes, REI no. [ADR 0069](../decisions/0069-curios-and-emi.md).

## Q16 change

- **Plugin:** `platform/client/integration/emi`, found by EMI's `@EmiEntrypoint` scan only.
  - `TerminalRecipeHandler` fills the terminal grid for every recipe EMI shows, or requests the
    output with Ctrl (`EmiRequests`). It reports the inventory plus the terminal's stock to EMI.
  - `FilterDrop` and `ProgramDrop` handle drags; a generic exclusion area covers the upgrade
    panel.
  - Search sync goes through `RecipeViewerSearch`.
- **Shared code:** `RecipeViewerHoldings` (formerly the JEI plugin's `Holdings`) is now shared
  client code.
- **Build:** EMI 1.1.24 is `compileOnly` (API). The `emiTest` source set and `runEmiClient` are
  the only places it is loaded; `check` compiles the fixture.
- **Isolation:** ArchUnit already forbids `dev.emi..` outside integration packages (Q110).

## Q16 evidence: `runEmiClient` (EMI loaded, JEI absent), PASS

Fills go through EMI's `EmiRecipeFiller.performFill` with the recipes EMI lists, and drops go
through `EmiDragDropHandlers.dropStack`. EMI therefore resolves our handlers itself. The physical
click and drag are not simulated.

| Step | Result | Capture |
|---|---|---|
| Handlers | EMI resolves exactly one handler for the terminal | `emi-terminal.png` |
| Crafting fill | The crafting table recipe fills the Table grid with oak planks | `emi-crafting-fill.png` |
| Ctrl request | Jobs opens with the crafting table selected | `emi-request-jobs.png` |
| Processing fill | Smelting glass chooses red sand (8 stocked) over sand and fills Inputs/Outputs | `emi-processing-fill.png` |
| Stonecutter fill | Stone, with stone bricks chosen | `emi-stonecutter-fill.png` |
| Smithing fill | Template, diamond sword and netherite ingot | `emi-smithing-fill.png` |
| Search sync | `cobble` is copied to EMI, and `stone` from EMI back to the terminal; J turns sync off | `emi-search-sync.png` |
| Program editor drop | An item and a fluid type `iron ingot fluid water`; a drop outside the editor is left to EMI | `emi-program-drop.png` |
| Buffer filter drop | Iron is added as an ID rule | `emi-buffer-drop.png` |
| Exclusion area | One EMI exclusion area per visible upgrade slot (26×26) | — |

The first run warned "Untranslated tag #curios:charm" (from Q110's data). The tag now has a
name, and the warning is gone.

`runJeiClient` still passes after the move of `Holdings`.

## Q127 change and evidence

- **Found by:** the EMI run's sync-off click on J, sent a tick or two after the search, did
  nothing. A trace showed the view at revision 7 (`LOADING`), then revision 8 with the sort
  unchanged: `MenuSession.admitRate` had dropped the command.
  - Every menu (terminal, buffer, machine settings, drive, access, program) used a `TickGate`
    that admitted one command per 2 ticks and silently dropped closer ones.
  - The Q22 evidence had already noted GameTests working around this.
- **Fix:** `TickGate` is a generic cell rate limit with a burst. The command gate admits up to
  `COMMAND_BURST = 4` back to back; credit returns at one per `COMMAND_TICKS = 2`. With a burst of
  1 (polls, program authoring) the behaviour is unchanged.
- **`TickGateTest`:**
  - a burst of one refuses closer events;
  - four clicks in one tick pass and a fifth is refused;
  - credit returns at one per period;
  - over 1,000 ticks of spam, 503 events are admitted (500 plus the initial burst).
- **Client check:** the EMI fixture no longer retries the J press. Three runs before the fix
  needed the retry; after the fix it passes without one.
- **GameTests:** `NetworkAccessGameTests` and `RecoveryScreenGameTests` asserted that a second
  command in the same tick was dropped. They now send a full burst and check that the next
  command is dropped (Access shows `RATE`; Recovery leaves Return unapplied). Return is then
  checked after the gate recovers.
- **Full GameTest run:** 501 of 501 pass.
- **Docs:** MACHINE_INTERFACES, BUFFERED_INTERFACES and ADR 0055 state the new rate.
