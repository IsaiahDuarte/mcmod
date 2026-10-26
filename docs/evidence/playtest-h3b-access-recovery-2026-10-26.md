# H3b — Access and Recovery screens on the panel kit

Scope: playtest plan H3 group (b), findings K1 and K3. Decisions are in the
[ADR 0064 H3b amendment](../decisions/0064-panel-kit.md).

Implemented:

- **Network Access** (`NetworkAccessScreen`), 230 px wide instead of 300:
  - The info lines: owner, your access, and gateway limits.
  - A list of players, one line each with their role. Click a player to select them; hover
    shows the full name and UUID.
  - The selected player's role choices, or with Advanced the seven permission toggles (selected
    when granted, `*` when a gateway blocks the grant).
  - Revoke, Owner (with the existing two-step confirmation), and Roles/Advanced.
  - Footer: paging and Recovery….
- **Transfer Recovery** (`RecoveryScreen`):
  - The scope line, a list of records (selected and hovered rows are highlighted, and the
    tooltip has the full record).
  - The safe next step, in amber for uncertain records.
  - Return, Settle and Retire (operators only).
  - Footer: Home and Next.
- The server menus and packets are unchanged: the screens send the same commands.

Checks: `python3 scripts/verify.py` passed (414 GameTests; the first run hit the known BufferImport rebuild-race flake and a rerun passed); `runPresentationClient` and `runMultiplayerCheck` passed. The presentation tour captures `access`, `access-advanced` and
`recovery` at scales 1 and 4. The multiplayer check reads the real second player's name from
the access view.
