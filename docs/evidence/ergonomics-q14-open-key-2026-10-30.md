# Q14 — Open the Wireless Terminal with a key

Scope: [ergonomics plan](../ERGONOMICS_PLAN.md) Q14 (M14), inventory part. The Curios part is
Q110 (needs QD1). Design:
[ADR 0039 amendment](../decisions/0039-wireless-access.md#amendment--open-key-2026-10-30-ergonomics-plan-q14);
player guide under Wireless access.

## Checks

- `WirelessGameTests.openKeyFindsTheOneChargedHandheld` (survival): with nothing carried, or
  only an uncharged bound handheld, the key is refused (`no_handheld`); with two charged ones it
  is refused (`several_handhelds`); with one charged handheld in inventory slot 20, not in hand,
  the terminal opens and stays open; moving the handheld to slot 22 closes it.
- `runTerminalClient` PASS: after the existing hand-use stage, the handheld is moved to slot 20,
  the key is bound to O and pressed through `KeyMapping.click`. The client sends the payload and
  the terminal opens; pressing O in the screen closes it. `result.txt`: "wireless: the open key
  opened the terminal from inventory slot 20 (hand empty) and closed it again".
- No run with Curios: that part is Q110.
- Full GameTest run: all 454 passed.
- `python3 scripts/verify.py`: PASS (all 454 GameTests).
