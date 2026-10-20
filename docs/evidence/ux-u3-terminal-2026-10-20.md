# U3 — Terminal grid — 2026-10-20

Scope: P3/P4/P6, partial A11/A13 evidence. Minecraft 1.21.1, NeoForge
21.1.252, Java 21.0.12, macOS arm64, framebuffer 1708×960.

The item terminal now has an adaptive scrolling grid (20×4 at GUI 427×240),
wheel and draggable scrollbar, live search, Name/Qty sorting, visible resource/Jobs
tabs and an unfiltered item-capacity bar. Left-click takes a native stack to the
cursor, right-click takes half, Shift-click withdraws to inventory, and clicking
with carried items deposits them. Wired and wireless use the same screen.

The real menu cursor is a menu-session-bound variant of the existing authenticated
player endpoint, sharing its inventory backing and 128-unit per-direction tick
limits. Native requests still use shared authorization, scheduling, routes and
staging. Only cursor intents use nested context schema 4; ordinary player contexts
retain schema 3 and the world envelope remains 13. Known cursor staging returns to
the owner's main inventory, never a replacement menu's cursor. Uncertain calls
remain quarantined. ADR 0016/0017 and the module docs record these contracts.

Five native `TerminalGridGameTests` cover windows beyond the old eight identities,
exact components, stack/half/inventory pickup, cursor deposits, partial fit,
stale fingerprints, closed/revoked requests, independent capacity aggregation
(including empty/Infinite cells and excluded private/fluid storage), and known
cursor staging recovery after menu replacement. Codec tests cover 256-row payloads,
window bounds, exact large counters and strict schema-4 migration compatibility.

The user reported clipped count labels in the PNGs. Inspection reproduced it:
plain text at the base GUI depth was hidden behind 3D item models. Counts now use
vanilla item-decoration depth; long compact labels scale to stay within a cell.
The presentation fixture deliberately stocks Stone=1, Iron=23, Gold=64,
Diamond=1,234 and Emerald=999,000. Final [scale 1](ux-u3-terminal-2026-10-20/terminal-scale-1.png)
and [scale 4](ux-u3-terminal-2026-10-20/terminal-scale-4.png) captures were inspected:
all labels (1, 23, 64, 1k, 999k) render completely, the screen fits, and the redundant
idle “Empty grid” label is absent. [Keyboard focus](ux-u3-terminal-2026-10-20/terminal-keyboard-scale-4.png)
is visible; the fixture activates it with Enter, then deposits the actual cursor
stack by clicking. This is a graphical regression fixture for the rendering change;
a JVM assertion about draw-call structure would not catch the depth problem.

Verification:

- `./gradlew spotlessApply` passed.
- `caffeinate -d -i -u ./gradlew runPresentationClient` passed under the graphical
  lock: 291 stages, screens at scales 1–4, real keyboard pickup/deposit and Jobs
  execution. [Report](ux-u3-terminal-2026-10-20/presentation.txt).
- `caffeinate -d -i -u ./gradlew runTerminalRestartClient` passed under the
  graphical lock before the final count-overlay and idle-label changes: live-search
  typing, wheel/scrollbar drag, exact-component cursor pickup/deposit, manual crafting,
  Fluids/Energy, wireless handheld and fresh-JVM preferences/state restoration.
  [Earlier tour report](ux-u3-terminal-2026-10-20/terminal-tour.txt); its legacy
  Take1/Next/Home summary prose has since been corrected in the fixture. The final
  presentation captures above verify the later rendering changes.
- `python3 scripts/verify.py --full` passed: 34 tooling, 386 JVM and 337 native
  tests; formatting/static/architecture checks, locked Rust SDK, fixture compilation
  and every dedicated restart/save-skew/throughput/wireless/SIGKILL release check.
  Gradle completed in 4m44s.
- `git diff --check` passed. [Artifact checksums](ux-u3-terminal-2026-10-20/SHA256SUMS).

An interim presentation run timed out while waiting for an active item cell:
its stock setup had been placed after the keyboard step. Moving setup before the
terminal opens fixed the fixture; no timeout or assertion was weakened.

Limitations: full survival progression, offline cached stock, multiplayer physical
Shift gestures, representative large-catalog timings and independently interrupted
player/world saves remain separate release work. This slice does not establish
full A11/A13 acceptance. The crafting panel redesign remains U4.
