# U6 — Better defaults

Scope: F7. Minecraft 1.21.1, NeoForge 21.1.252, Java 21, macOS arm64.

## (a) Buffers start in Import (commit `3c320db`)

A buffer with no deployment, placed by a live player who holds EDIT, DEPLOY, VIEW,
WITHDRAW and DEPOSIT, deploys Import in that player's name once its network is
VALID. A scheduled block tick retries every 5 ticks, at most 20 times, and calls
the normal `configure` path. Anyone else, a placer who logs out, or an existing
deployment leaves it Paused. See ADR 0014 and ADR 0018.
`BufferDefaultImportGameTests` covers the default and the denied case (no DEPLOY).

## (b) Preset Apply saves and deploys in one step

On the Presets page, the "Set goal" button is gone and "Deploy" is now Apply. Apply
saves the whole draft, including the typed goal, and starts it. Enter in the goal
field also applies. A binding Apply by the preset's deployer keeps the preset
running. By anyone else it pauses the preset and tells the player how to resume.

## (c) Upgrades keep a deployer's preset running

ADR 0054's pause protects authority: someone else's change must not keep or move
automation in the deployer's name. It now applies only to non-deployers.

- An upgrade by the deployer redeploys the same mode, filter and goal once the
  rebuilt network is VALID, after re-checking all grants.
- An upgrade or edit by anyone else pauses the preset. The interrupted mode is
  remembered in memory only. The Presets page preloads it as "Paused by a settings
  change or upgrade. Apply to resume it.", and one Apply resumes it in the clicking
  player's name with that player's own grants.

Tests:

- `MachinePresetResumeGameTests.deployerEditAndUpgradeKeepPresetRunning`
- `MachinePresetResumeGameTests.otherEditorPausesAndResumeNeedsDeployGrants`: a
  resume without DEPLOY is refused; the menu shows "resumable" with Import
  preloaded; one Apply redeploys in the editor's name.
- Two existing invariant tests now use a non-deployer editor, so they still check
  that the pause holds: old-chunk replay cannot resume it
  (`MachinePresetAuthorityGameTests`), and queued work cannot extract after
  invalidation (`MachinePresetGameTests`). A deployer's own edit now legitimately
  resumes, which those tests did not model.

Checks:

- `python3 scripts/verify.py` PASS; all 344 required GameTests pass.
- `runTerminalRestartClient` (terminal and restart) PASS and `runPresentationClient`
  PASS, under the GUI lock and `caffeinate`. The terminal tour presses the renamed
  preset Apply and runs a real Stock preset. I inspected
  `machine-preset-scale-4-stock.png`: the full-width goal field, no Set button, and
  Apply in the bottom row.

Limitations: the remembered interrupted mode and a pending deployer resume are not
saved, so after a restart a paused preset has to be set up again.
