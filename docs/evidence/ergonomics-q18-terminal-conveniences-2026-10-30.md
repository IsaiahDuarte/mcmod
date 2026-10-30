# Q18 — Terminal conveniences

Scope: [ergonomics plan](../ERGONOMICS_PLAN.md) Q18 (M18). Design:
[STORAGE_TERMINAL](../modules/STORAGE_TERMINAL.md#deposit-all-and-search-focus-q18),
[ADR 0052](../decisions/0052-terminal-search-preferences.md) (FOCUS bit),
[ADR 0039 amendment](../decisions/0039-wireless-access.md#amendment--pick-block-from-the-network-2026-10-30-ergonomics-plan-q18);
player guide under the terminal and Wireless access.

## Check first

None of the four parts existed: no pinning of crafted items, no double-click deposit, no
search focus option, and Pick Block was only used by the Label Tool.

## Done

- (b) Shift-double-click deposits every matching stack: `TerminalGameTests.depositAllTakesEveryMatchingStack`
  deposits stacks of 64, 20 and 7 cobblestone (91 stored, none left) through one DEPOSIT_ALL
  and leaves dirt alone.
- (c) Search auto-focus: the FOCUS preference bit is saved and restored with the sort
  (`TerminalGameTests` persistence test) and accepted in packets (`TerminalSearchSupportTest`).
  Applying focus on open is client code and was not exercised in a graphical run.
- (d) Pick Block from the network: `WirelessGameTests.pickBlockPullsFromTheNetwork`, survival,
  handheld in inventory slot 20 (not held). A player without Withdraw is refused (`denied`); the
  owner's pick puts all 10 stored stone into the selected empty hotbar slot 3, selects it and
  costs 64 FE from the handheld; a second pick while holding stone moves nothing.
- (a) Recently crafted items first: not done here, moved to Q109 (the paged sorted grid needs a
  design for pinned rows).

## Found while testing

- SORT commands were bounded like slot indices (below 36); with the new FOCUS bit a saved sort
  can reach 63, which crashed the server tick in the persistence test. SORT selections now allow
  up to 63 (protocol 11).
- Vanilla's mock player always reports `isCreative()`; the pick checks
  `getAbilities().instabuild`, which follows the game mode, for real and test players alike.

## Checks

- Full GameTest run: all 452 passed.
- `TerminalPacketsTest`: the "unknown action" bound moved from REQUEST + 1 to DEPOSIT_ALL + 1.
- No graphical run: the screen's layout is unchanged (no new widgets); the double-click and
  focus handlers are client input code checked only by compiling and the server-side tests.
- `python3 scripts/verify.py`: PASS (all 452 GameTests) on the second run; the first failed on
  the packet-test bound above and a PMD empty-catch, both fixed.
