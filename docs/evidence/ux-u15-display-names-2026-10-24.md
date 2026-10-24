# U15 — Display names

Scope: U15 (UD4 names table). Lang and docs only; registry IDs are unchanged.

Already applied by earlier slices: Interface (U11), Crafter (U12), Reserved/Available
(U1), preset Apply (U6). This slice finishes the table:

- Gateway text says "subnetwork" instead of "branch": the gateway tooltip, the three
  gateway presets, the Access button tooltip and guide page 11 (now "Subnetworks").
- Preset help text says "Apply" instead of "Deploy". Program Deploy stays, because it
  is a real program action and the name of a permission.
- The machine screen's "First index" / "Window" fields are "First slot" / "Slot
  count", and its help and binding message match. A visual slot picker is not part
  of this lang-only slice.
- Remaining "link ends" text says "linked gateways" (U13).
- `PLAYER_GUIDE.md` uses "subnetwork" and the new slot names.
- `LangKeysTest.playerTextAvoidsInternalWords` now also rejects "branch" and
  "endpoint" in player-facing keys.

Checks: `python3 scripts/verify.py` PASS; all 355 required GameTests pass. Under the
GUI lock and `caffeinate`: `runPresentationClient` PASS and `runTerminalClient` PASS.
Inspected `machine` at scales 1 and 4 (the new labels fit) and guide page 16 at
scale 4.

Kept: the display name "Crafter" from the UD4 table, although vanilla 1.21 also has a
Crafter block. Renaming it is a one-line lang change if wanted.
