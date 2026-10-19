# ADR 0055 — Network Access and Transfer Recovery screens

Status: implemented. The canonical gate and the graphical presentation fit check passed on
2026-10-19. Ownership transfer and grant edits through the UI in a real two-client session are
not yet exercised.

## Context

A12/A13 and the ASSETS permissions row need player-facing grant editing, explicit ownership
transfer, revocation feedback and recovery actions. Before this ADR they existed only as
commands (ADR 0049 access commands, transfer recovery commands).

## Decision

- **Network Access screen.** It opens when a player sneak-uses a controller, or from the gateway
  policy screen's Access button.
  - **Grant rows.** One row per principal, with seven permission toggles (V/D/W/C/E/P/M), Revoke
    and Owner. Toggles mark grants that an ancestor gateway blocks with `*`.
  - **Gateway limits.** The header shows branch, inherited and kind limits for that point.
  - **Shared server paths.** Every edit goes through `WorldNetworks.setGrants`, and ownership
    goes through the new `WorldNetworks.transferOwnership`; `AccessCommands` and the screen
    share `AccessAdministration`.
  - **Packet checks.** Commands must match the session nonce, container id and sender. They are
    rate-limited to one per 2 ticks.
- **Ownership transfer.**
  - Only the current owner or an operator can transfer, and only while holding Manage at the
    origin. Delegated Manage cannot transfer, and the nil UUID is refused.
  - The UI needs a request and then a Confirm. The request expires after 200 ticks, and is
    withdrawn when the network generation changes or a different principal is confirmed.
- **Live revocation.** A viewer who loses Manage while the screen is open sees `manage_revoked`
  and a read-only view. Viewers without Manage see only their own access.
- **Transfer Recovery screen.**
  - **Opening.** It opens with `/factorycore recovery screen` or from the Access screen.
  - **Visibility.** Operators page through all records. Other players see only their own, with a
    bounded scan of 4 pages × 16.
  - **Actions.** Return, Settle and Retire use the same `RecoveryActions` rules and rate limits
    as the commands, and act only on IDs from the current page. Uncertain records offer no
    action and are never replayed.

## Alternatives

- **Commands only.** Rejected: it fails the player-facing UI requirement.
- **Per-network permissions on the terminal.** Rejected: the controller is the network's
  identity, and the gateway screen already hosts policy.
- **Allow delegated managers to transfer ownership.** Rejected: transfer is explicit
  owner/operator authority under SPEC.

## Consequences

- Grant administration is available in-game, without commands.
- Device repair and adoption are still command-only; ASSETS records that gap.
- Players the server has never seen show as a truncated UUID, because no name is available.

## Evidence

- GameTests: `NetworkAccessGameTests` (2) and `RecoveryScreenGameTests` (1). They cover forged
  packets, wrong sessions, stale transfer confirmations, delegated managers and operator
  retirement.
- Graphical: `runPresentationClient` opens both screens through real use and the real command,
  with a granted second principal, and checks fit, no overlap and label fit at GUI scales 1–4
  in the smallest window. That check caught the overflowing Revoke label, which is now fixed.
  [Evidence](../evidence/access-recovery-ui-v1-2026-10-19.md).
