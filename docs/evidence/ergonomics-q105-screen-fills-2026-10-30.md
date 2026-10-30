# Q105 — Remaining screen fills on the kit

Scope: [ergonomics plan](../ERGONOMICS_PLAN.md) Q105 (M143), after the
[Q60 re-check](ergonomics-q60-code-health-2026-10-26.md).

## Change

| Site | Now |
| --- | --- |
| Drive bay capacity bar | `Panels.track` and `Panels.meter(…, nearlyFull)` |
| `FilterSlots` selection, inactive wash, `=`/`#` badge | `Panels.selectedEdge`, `Panels.disabled`, `Panels.badge` |
| `SlotPicker` field and cells | `Panels.picker`, `Panels.pickerCell` |
| Status strip lamp | `Panels.lamp` (the Terminal's framed lamp) |
| Drive bay keyboard focus outline | `Panels.lightFocusEdge` |
| Port keep-amount field text | `Panels.FIELD_TEXT`, `Panels.FIELD_ERROR` |
| Job toast text, label overlay text | `Panels.TOAST_*`, `Panels.OVERLAY_*` |

`StatusStrip.color` and the dark-screen `StatusStrip.draw` had no callers and were removed with
their colours. Each new `Panels` colour is the literal it replaces, except two deliberate
changes: the status strip lamp is now the kit lamp (dark frame, green or amber centre) instead of
a flat square in the text colour, and the Drive bar's blue is the kit meter's `#2f8fb5` instead
of `#2f8fbf`.

| Search on `src/main/java/.../platform/client`, outside `ui/` | Before (Q60) | After |
| --- | --- | --- |
| Raw `fill` | 10 | 3, all in `ProgramTextEditor` (the code field, ADR 0064 H3c) |
| Colour literals | 20 lines | `ProgramTextEditor` only |

## Checks

- `python3 scripts/verify.py`: PASS, all 432 GameTests.
- `runPresentationClient`, `runTerminalRestartClient` and `runJeiClient` pass.
- Screenshots compared pixel by pixel with the previous commit's run. Where only the status strip
  changed, exactly the lamp differs: 25 px at scale 1 and 400 px at scale 4 (`cable-port`,
  `drive-partition`, `exporter-filter`). Buffer and Drive captures at scale 4 were also checked by
  eye: filter badges, the selected-slot outline, bay tracks, the blue meter and the near-full red
  meter look the same. Their other differing pixels are the live world behind the panel, hover
  tooltips caught at a different moment and fixture stock that differs between runs.
