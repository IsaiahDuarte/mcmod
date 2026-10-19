# Native multiplayer verification — 2026-10-18

Command: `./gradlew runMultiplayerCheck` — PASS, 53 seconds. macOS 15.3.1 arm64,
Apple M1, Java 21.0.12, Minecraft 1.21.1, NeoForge 21.1.252. Main `c7cc16c` plus
the recovered/integrated worktree changes; optional JEI/Mekanism absent. One
dedicated server and two separate real graphical clients connected over loopback
TCP, using isolated offline-mode test identities FactoryOwner and FactoryGuest.
Both principals were nonoperators. Captured framebuffer 2048×1536 at GUI scale 2.

All three independent reports passed: [server](multiplayer-v1-2026-10-18/server.txt),
[owner client](multiplayer-v1-2026-10-18/owner.txt),
[guest client](multiplayer-v1-2026-10-18/stranger.txt). The task also required all
19 nonempty screenshots under `build/reports/multiplayer`. Selected captures:
[concurrent terminal](multiplayer-v1-2026-10-18/owner-terminal-concurrent.png),
[owner after revoke](multiplayer-v1-2026-10-18/owner-after-revoke.png),
[guest after revoke](multiplayer-v1-2026-10-18/stranger-revoked-closed.png).

## Observed behavior

- The private network refused the guest's terminal use and self-grant command.
- The owner edited gateway policy; a guest received read-only controls and its
  forged button packet changed nothing.
- View-only access listed 64 iron but refused Take 1 with no item mutation.
- Owner commands added Deposit/Withdraw, then both terminals opened simultaneously.
- Guest Take 1 changed stock 64→63; the idle owner's screen updated after 14 client
  ticks. Owner Take 1 changed stock to 62; the guest's screen updated after 9 ticks.
- Guest deposited one through the real C2S payload, restoring stock to 63.
- Both clients then issued Take 1; both landed one server tick after the shared step
  began. Final stock 61 + owner 2 + guest 1 = exactly 64. No dropped items existed.
- Revocation closed the guest menu and refused reopening, while the owner's menu
  remained usable with exact stock 61. A console grant restored guest View only.
- Both clients disconnected only after the final barrier.

## Initial fixture failure and limits

The first integrated run failed at revocation step 12: owner acknowledgement stayed
at 11 while guest reached 12. The owner fixture required status `ready`, although
its successful transfer retains `ok` during background observations. The corrected
fixture accepts either valid status only with a nonbusy menu and exact current
stock. A native regression also checks retained `ok`; no permission or conservation
check was weakened.

This is partial A03/A11/A12/A14 evidence. It does not verify remote internet
authentication, arbitrary latency, every GUI scale, full recipe/job/program
multiplayer behavior, or the full release.
