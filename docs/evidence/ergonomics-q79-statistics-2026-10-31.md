# Q79 — Statistics (2026-10-31)

`StorageStats` registers two custom statistics, `factorycore:items_stored` and
`factorycore:items_taken`, with lang "Items stored" and "Items taken". They show on the vanilla
Statistics screen's General tab. `WorldPlayerInventories.PlayerPort` awards them where items really
leave and enter the player's inventory or cursor:
- `extract` awards stored;
- `insert` awards taken.

So they count every network-screen move (Terminal, Buffer Deposit/Take), whatever the other end.
A move that could not finish returns its items through `insert`, so it counts as both stored and
taken (documented in the guide).

## Checks

- `TerminalGameTests.nativeMenuSearchWithdrawAndExactShiftDeposit`: after withdrawing 17 and
  depositing 17, both statistics rose by exactly 17.
- 482 GameTests passed twice.
- `python3 scripts/verify.py`: PASS.
