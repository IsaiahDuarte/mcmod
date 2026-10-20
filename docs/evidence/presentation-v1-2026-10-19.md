# Device presentation and GUI scales — 2026-10-19

Status: graphical fixture and canonical gate passed. This is partial
A05/A11/A13/A14 evidence, not full release or human survival acceptance.

## Source and environment

Integrated `9a387bf` (interrupted presentation worktree) onto `0d92ed2`, with
integration fixes described in [ADR 0058](../decisions/0058-device-presentation.md).
macOS 15.3.1 arm64, Homebrew OpenJDK 21.0.12, Minecraft 1.21.1,
NeoForge 21.1.252; only Factory Core and the excluded verification fixture added.
Framebuffer 1708×960; GUI scales 1–4 (427×240 GUI pixels at scale 4).

## Commands and observations

`caffeinate -d -i ./gradlew spotlessApply runPresentationClient` passed in one
minute with the exclusive `/tmp/factorycore-gui.lock` held. The isolated generated
world was saved and the client exited normally. [PASS report](presentation-v1-2026-10-19/result.txt)
records every checked scale, control count and real tooltip text. Gradle required
32 nonempty captures. [Hashes](presentation-v1-2026-10-19/SHA256SUMS) cover retained artifacts.

`python3 scripts/verify.py` passed in 2m24s: 29 tooling tests, 349 JVM tests and
306 GameTests, zero failures/errors/skips, plus the required dedicated staging,
independent-save, gateway-depth and wireless lifecycle probes. Formatting, PMD,
architecture and packaging gates passed. Unchanged Rust guest verification reused
validated up-to-date outputs. Final document/source inventory and
`git diff --check` passed after the documentation updates.

- 44 placed directional blocks, viewed from opposite corners, cover six facings
  for directional devices and four for terminal/generator.
- Actual controller/gateway topology drives active/fault states; physical drive
  slots drive occupancy. Program gallery states are deliberately set on
  undeployed controllers, so they verify rendering, not program execution.
- [Day](presentation-v1-2026-10-19/states-day-close.png) and
  [night](presentation-v1-2026-10-19/states-night-close.png) captures show distinct
  glyphs for active, paused and fault states and visible cable arms. Link lamps
  describe local pairing/power, not a valid routing grant.
- Representative main/offhand and third-person items were captured. This does
  not establish every item's held transform in every camera mode.
- Each tooltip corner is captured separately at scales 2 and 4. Real item
  tooltips include translated descriptions, capacities and lamp legends.
- Terminal, buffer settings, machine settings, program editor, gateway policy and
  guide panels passed bounds/control overlap/button-label checks at scales 1–4.
  Readable scale-4 captures include the
  [terminal](presentation-v1-2026-10-19/terminal-scale-4.png),
  [machine](presentation-v1-2026-10-19/machine-scale-4.png) and
  [program](presentation-v1-2026-10-19/program-scale-4.png).

Visual inspection covered both orientation views, state views, representative
held items, the four scale-4 tooltip corners and the scale-4 screens. This checks
appearance; the fixture's native physical uses and assertions establish opening
and layout. Existing interaction fixtures own editing and resource conservation.

## Failures found and corrections

The original WIP failed the art tooling test because generation now includes
JSON, then PMD borrowed-resource checks. Those are corrected while retaining
PNG assertions and static gates. The original JVM asset-reference test exposed
overlay templates with no default texture; generated defaults now resolve and
that unchanged focused test passes.

Two graphical attempts timed out at stage 11. Added diagnostics showed the
isolated access point permanently REBUILDING while every other expected lamp
matched. `WorldNetworks` seeded fewer device kinds than cable eligibility;
using the existing cable helper fixes initial standalone discovery. A native
regression now requires NO_ROOT and its fault lamp without adding a root/cable.

The first completed 26-capture run was not accepted as complete visual evidence:
the tooltip board drew four tooltips over each other and tutorial popups covered
controls. The revised fixture disables tutorials and captures each corner alone;
no layout, state or texture assertion was removed.

One later canonical attempt failed the existing
`stockStopsAndReplenishesWithoutOverWithdrawing` expectation, also reported by
the interrupted access/recovery agent. Added state/transfer/scheduler diagnostics;
the focused full native suite then passed 306/306 and the canonical rerun passed.
No stock count, deadline or production behavior was changed to obtain the pass.
This intermittent failure is retained as unexplained, not claimed fixed.

## Limits

New access/recovery screens from unmerged slices are outside this fixture.
Full keyboard/focus behavior, populated/blocked states for all flows, physical
JEI gestures, every held item, performance, other OS execution and survival
progression remain open. Native program state transitions are tested elsewhere;
manually set gallery states are not lifecycle evidence.
