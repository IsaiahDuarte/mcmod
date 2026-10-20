# ADR 0058 — Device state presentation and client coverage

Status: accepted; canonical and graphical verification passed locally on macOS arm64.

## Context

R08/A11 require recognizable orientations, installed-cell feedback, translated
inspection and readable supported GUI scales. The interrupted presentation
worktree contained implementation and fixtures without a completed handoff.

## Decision

Use ordinary block states and generated model overlays for controller, gateway,
program and wireless lamps. Distinct glyphs accompany color. Refresh lamps on
the server every 20 ticks, staggered by position, using authoritative membership,
program or power state. Rebuilding retains the preceding lamp; inspection remains
the source of detailed routing diagnostics. Lamps grant no authority and never
participate in routing. Cosmetic updates suppress neighbor/shape updates and
topology invalidation. Drive occupancy mirrors its four physical slots immediately
on change and after chunk load, including worlds saved before occupancy states.
No canonical storage schema changes.

The original standard-library art generator now emits overlay model/blockstate
JSON as well as deterministic PNGs. Native coverage checks validate registered
models and facing/state variants. Item tooltips explain device purpose and lamps;
inspection and recovery names use translation keys.

Overlay templates have concrete default textures so they resolve independently;
each device child overrides that binding. The existing JVM asset-reference gate
caught missing defaults during integration and remains unchanged.

Graphical integration also reproduced an isolated access point stuck in
REBUILDING: region seeding used an older endpoint list that omitted access points,
programs, buffers and crafting devices. Seeding now uses the existing cable-port
eligibility helper, matching physical cable presentation and discovery adapters.
It still validates normal topology/authority before any resource access. The
native presentation regression requires an isolated access point to settle to
NO_ROOT and display its fault, rather than waiting forever without discovery.

`runPresentationClient` uses a disposable generated world and a 1708×960
framebuffer, yielding the minimum supported 427×240 GUI at scale 4. It records
opposite orientation views, day/night states, representative held items and
tooltip corners, and checks existing terminal/settings/program/policy/book
screens at scales 1–4. Additional screens introduced later need their own coverage.

## Alternatives and consequences

Inspection alone meets some status requirements but obscures bay occupancy and
nearby faults. Custom renderers/animations add lifecycle and rendering work;
ordinary generated models suffice. Lamps add bounded periodic device work and
state-change packets; controlled performance validation remains A15 work.

## Verification

`PresentationStateGameTests` exercises actual lamp refresh, unchanged valid
membership after cosmetic updates, and exact bay changes. `AssetCoverageGameTests`
checks state/facing models. The existing PNG-shape tooling test now recognizes
generated JSON and parses it, retaining the original PNG assertions for textures.
The canonical gate passes 349 JVM / 306 native tests; the graphical fixture passes
32 captures. [Evidence and limits](../evidence/presentation-v1-2026-10-19.md) retain
the failed attempts and an unexplained intermittent stock-test failure. Other OS
execution, full held/state/accessibility coverage and performance remain open.
