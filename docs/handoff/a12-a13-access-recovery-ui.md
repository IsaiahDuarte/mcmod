# Handoff: A12/A13 access and recovery screens

Branch: `worktree-agent-ac17c57b160a6c9d0` (worktree `.claude/worktrees/agent-ac17c57b160a6c9d0`).
Status: work in progress, not verified. Commit `8a905ad` is marked WIP.

## Done (code)

- `WorldNetworks.transferOwnership`: only the current owner or an operator can transfer, and only with
  current Manage at the origin. Delegated Manage cannot transfer. The nil UUID is refused.
- `AccessAdministration`: grant, revoke, transfer and notification logic. `AccessCommands` now uses it.
- `NetworkAccessMenu`, `AccessPackets` and `NetworkAccessScreen`:
  - Sneak-use on a controller opens the screen. The gateway policy screen's "Access…" button
    (`GatewayPolicyMenu.ACCESS_BUTTON` = 64) also opens it.
  - Each principal row has seven permission toggles, Revoke and Owner. Ownership transfer is two
    steps: request, then Confirm. The request expires after 200 ticks and is withdrawn if the
    generation changes or a different principal is confirmed.
  - The screen shows ancestor gateway branch, inherited and kind limits. Toggles mark grants that
    a gateway blocks with `*`.
  - When a player loses Manage, the screen shows `manage_revoked` and switches to read-only.
    Viewers without Manage see only their own access.
  - Commands are checked against the session nonce, container ID and sender, and are rate-limited
    to one per 2 ticks.
- `RecoveryActions`: the return/settle/retire logic shared by the commands and the screen, with
  shared rate limits. `RecoveryCommands` now uses it and gains `/factorycore recovery screen`.
- `RecoveryMenu`, `RecoveryPackets`, `RecoveryScreen` and `RecoveryContent`:
  - Operators can page through all records. Other players see only their own records, with a
    bounded scan of 4 pages × 16.
  - Return, Settle and Retire act only on IDs from the current page.
- Added 81 `en_us.json` lang keys (appended).
- GameTests: `NetworkAccessGameTests` (2) and `RecoveryScreenGameTests` (1).

## Verified

- `./gradlew compileJava compileGameTestJava compileClientTestJava` passed.
- Last `./gradlew runGameTestServer`: my 3 new tests passed. The run failed overall on one
  unrelated test, `stockstopsandreplenisheswithoutoverwithdrawing` ("Stock replenishes only exact
  deficit"). It looks flaky or concurrency-related; rerun it to confirm. The run before that
  passed everything except my two tests, which are now fixed.
- `scripts/verify.py` has not been run.

## Next

1. Rerun `runGameTestServer` and `python3 scripts/verify.py`.
2. Write ADR 0055: permissions screen, transfer restricted to owner/operator, confirmation,
   and the recovery screen.
3. Update `docs/modules/NETWORK_AUTHORITY.md` and `PLAYER_GUIDE.md`, the ASSETS rows (Permissions,
   Device diagnostics), the A12/A13 rows, and add a dated section at the end of IMPLEMENTATION.md.
4. Add a new client fixture for screenshots at scales 2 and 4: access as owner, gateway limits,
   transfer confirmation, read-only view and the recovery screen. Route it via a system property
   in `VerificationClient`; do not edit `TerminalClientVerification`. Use the GUI lock.
5. Optionally extend `runMultiplayerCheck` with UI grant, revoke and transfer steps.
6. Not covered: device (cell/bank/buffer/node/port) repair and adoption status are still
   command-only.
