# Q3 — Patterns view

Scope: [ergonomics plan](../ERGONOMICS_PLAN.md) Q3 (M3), after Q2. Design:
[ADR 0060 amendment](../decisions/0060-crafter.md#amendment--the-terminals-patterns-view-2026-10-30-ergonomics-plan-q3),
[ADR 0064 amendment](../decisions/0064-panel-kit.md#amendment--fitting-tabs-and-the-terminal-lamp-2026-10-30-ergonomics-plan-q3),
payloads in [STORAGE_TERMINAL](../modules/STORAGE_TERMINAL.md#patterns-view-q3).

## Behaviour

The terminal has a fifth tab, **Patterns**. It lists every Crafter the terminal's segment can use,
which is the same set autocrafting uses (`WorldCrafting.visible`, now shared by `index`): the
segment's own Crafters and those of branches whose gateways publish CRAFT. Each row names the
Crafter by position, marks a subnetwork Crafter, and shows its nine slots by output. A click
takes a pattern onto the cursor, inserts the cursor's pattern into an empty slot, or swaps. Each
needs EDIT where the Crafter is (`PatternHolderEntity.editableBy`, also used by the Q2 Crafter
screen); rows without it say "view only" and grey their slots. The terminal search keeps
Crafters with a matching output and greys the other slots. The list shows at most three Crafters
from a scroll offset (two at the 427×240 minimum, where fewer rows fit), with a scrollbar.

## Checks

- `TerminalPatternGameTests` (5 GameTests, all pass):
  - `craftersAndAPublishedBranchAreListed`: two Crafters and a branch Crafter behind a gateway
    that publishes CRAFT are listed (the branch one as `published`), each slot by output; the
    search "stick" keeps only the stick Crafter and marks its slot as matching.
  - `aBranchWithoutCraftIsHidden`: with the gateway withholding CRAFT, only the two network
    Crafters are listed while the branch itself is online.
  - `aPatternIsTakenAndInsertedRemotely`: from a Crafter holding three patterns, a click takes
    exactly the third onto the cursor; a click on slot 7 inserts it there; a diamond is refused.
  - `withoutEditPatternsStayPut`: a MEMBER (no EDIT) sees the row as not editable; a take and an
    insert are both DENIED and nothing moves.
  - `theCrafterListIsBoundedAndScrolls`: four Crafters show three at a time; one step scrolls by
    one; a scroll to `MAX_OFFSET` stops at the last Crafter; a click outside the three-row window
    cannot be constructed.
- `LangKeysTest.everyMenuStatusHasItsKey` now also covers the Patterns statuses and the Q2
  Crafter activity, machine-state and side keys.
- `runPresentationClient` passes. The tour opens the Patterns tab on the presentation network
  (three Crafters), clicks the first slot (TAKEN, cursor holds the pattern, slot empty), clicks
  again (INSERTED), and captures `terminal-patterns-scale-1.png` and
  `terminal-patterns-scale-4.png`. The first run showed five tabs running past the panel's
  right edge and the network lamp over the Patterns label; tabs now fit through
  `TabButton.widths` and the lamp ends the search row (`terminal-scale-1/4.png` show the new
  strip). `runJeiClient` and `runTerminalRestartClient` pass.
- `python3 scripts/verify.py`: PASS (all 440 GameTests) on the sixth run; the five runs before it
  failed only on the M145 tests below.

## GameTest flakiness seen while testing (M145)

Full GameTest runs were repeated while checking this slice:

| Tree | Runs | Failures |
| --- | --- | --- |
| `dc9f59d` (Q2), in a separate worktree | 3 | 1: `stockStopsAndReplenishesWithoutOverwithdrawing` ("Physical graph identity collision") |
| Q3 main code, Q3 tests set aside | 1 | 1: the same stock test |
| Q3 with its tests (two variants), GameTests alone and in `verify.py` | 16 | 12 with `oneInterfaceFeedsFurnaceTopAndSideAndEmptiesItsBottom` ("storage: denied"), three times together with the stock test |
| Q3 main code with the five Q3 tests replaced by empty ones that succeed at once | 2 | 1: the furnace test |

The new tests do not touch programs, buffers or furnaces. The first variant changed a gateway
policy mid-test, which rebuilds a network. The final version sets the policy before the network
forms, and the furnace test still failed with the gateway tests disabled, and with all five Q3
tests replaced by empty ones. So the failures follow the number and placement of tests, not what
Q3's tests do. Where both tests
failed in one run, their structures were neighbours, 11 blocks apart on one row and sharing a
chunk, and neither places blocks outside its template. The collision diagnostic means one
discovery met the same node UUID twice. The data is recorded under M145, which is behind the
PD7 gate; no test was changed or weakened for it.
