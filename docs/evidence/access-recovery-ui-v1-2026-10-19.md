# Network Access and Transfer Recovery screens — 2026-10-19

macOS arm64 (Apple M1), Java 21.0.12, Minecraft 1.21.1, NeoForge 21.1.252, framebuffer 1708×960.

- `python3 scripts/verify.py` passed after merging branch `worktree-agent-ac17c57b160a6c9d0`:
  315 GameTests with zero failures.
- `caffeinate -d -i ./gradlew runPresentationClient` (GUI lock held) passed in 1m15s.
  - **Access screen.** The owner first grants a synthetic second principal View+Deposit through
    `WorldNetworks.setGrants`, then sneak-uses the controller.
  - **Recovery screen.** It opens through the real `/factorycore recovery screen` command, as an
    operator in singleplayer.
  - **Fit checks.** Both screens pass panel, control-overlap and label-fit checks at scales
    1–4 ([fit.txt](access-recovery-ui-v1-2026-10-19/fit.txt)).
- **Bug found and fixed.** The first run failed with `Label does not fit its button: Revoke`
  (a 36 px button). Revoke is now 40 px and Owner is 34 px, inside the 292 px inner edge.
- **Captures.** [access, scale 4](access-recovery-ui-v1-2026-10-19/access-scale-4.png),
  [access, scale 1](access-recovery-ui-v1-2026-10-19/access-scale-1.png),
  [recovery, scale 4](access-recovery-ui-v1-2026-10-19/recovery-scale-4.png),
  [recovery, scale 1](access-recovery-ui-v1-2026-10-19/recovery-scale-1.png), plus SHA256SUMS.

Not covered:
- Grant, revoke and transfer clicks between two real clients.
- Recovery actions on populated records in the client; these are covered natively.
- Device repair and adoption screens.
