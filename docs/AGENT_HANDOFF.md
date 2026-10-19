# Full-release implementation handoff

Copy the following task into an agent session rooted at this repository. This is
a continuation toward the full first playable release; milestone demonstrations
are checkpoints. No new goal skill is installed by this file.

```text
Goal: Complete and verify Factory Core's full first playable release as defined
by SPEC.md, including all A01–A17 criteria and finished presentation/onboarding.

Use the environment's goal workflow if available. Create/track this objective
without inventing a token budget. If that workflow is unavailable, perform the
same implementation task and track progress in docs/IMPLEMENTATION.md. Do not
claim a missing skill was invoked. Do not mark the goal complete while required
features, assets or acceptance evidence remain unfinished.

Read AGENTS.md, SPEC.md, docs/IMPLEMENTATION.md, docs/ENGINEERING.md,
docs/TESTING.md and docs/ASSETS.md, then relevant module docs/ADRs and existing
code/tests. Inspect current git changes and preserve unrelated work. Do not
delegate unless I explicitly request it.

Current platform: Minecraft 1.21.1 / NeoForge 21.1.252 / Java 21. Read the
current acceptance table and last dated checkpoints in docs/IMPLEMENTATION.md;
older dated checkpoints describe introduction history, not today's missing work.
The world writer is schema 13; check nested codecs for their own versions.

The interrupted 2026-10-19 session has been inventoried in docs/CONTINUATION.md.
Main was clean at 0d92ed2; the last fluid-tank graphical commit had landed. Four
new WIP branches have committed code and individual handoffs. The fifth,
presentation, was uncommitted and is preserved in 9a387bf, then integrated for
verification. Keep all retained worktrees; do not re-merge the four older slices
already recovered in bfe3a4a. Check current git status/log before continuing.

P0 is locally verified on macOS arm64. P1–P7 are in progress. Storage, canonical
ownership/recovery, wired topology, shared transfers/presets, terminal search and
saved preferences, crafting/processing/publication/keep-stock, friendly/Rust-Wasm
programs and authoring, fluid/energy actions, shared wireless resource caps,
optional JEI callbacks, original art and onboarding all have partial local evidence.
Orderly restart, independent-save storage adoption, real chunk/root/wireless
unload and two-client terminal permissions have dedicated fixtures. Production
Wasm and crafting are implemented; do not restart them from older bootstrap notes.

Finish the interrupted slices with docs and verification: presentation, paid
throughput upgrades, processing/program interruption recovery, program host/log
limits and jobs/contention controls, access/ownership/recovery screens. Then finish
broader gateway/staged lifecycle, actual JEI gestures, multiplayer crafting/program
scenarios, repeated controlled performance/client/packet/crafting/Wasm workloads,
supported OS execution and release/asset reconciliation. A17 requires the user's
human survival playtest and working endgame factory; automated recipe reachability
is not that evidence. Every A01–A17 row remains partial until its full contract
and evidence are satisfied. Confirm this state against the checkout before work.

Resume from the earliest incomplete prerequisite; do not rebuild completed
bootstrap work. Resolve decision gates through evidence and ADRs. Close P1/P2
gaps, including physical storage/energy/connector integration and persistence,
then deliver the hopper -> storage -> searchable terminal -> withdrawal loop.
Use the plan's dependency rules for small slices across stage boundaries.
Continue through presets, crafting, programming, complete powered/wireless
progression, and P7 release verification. The useful-storage demo is not the
end goal. Keep features explicitly marked Later out of this release.

Implement finished models/textures/icons, device feedback, screens, translatable
messages and discoverable help alongside their features. Maintain docs/ASSETS.md
with real paths and separate implementation/client-verification status. Use
simple Minecraft-native assets where suitable; custom animations are optional.

Preserve shared authorization, scheduling, exact resource accounting, ownership
and bounded-work contracts. Add meaningful behavior/failure/migration tests,
including actual platform/client/world evidence where required. Update player
docs, module contracts, acceptance rows and milestone evidence with each slice.
Validate recipe dependencies, upgrade usefulness and combined power demand with
a survival walkthrough ending in a functioning factory, not just an Infinite Cell.

Run python3 scripts/verify.py and applicable additional checks from TESTING.md.
Report exact commands, failures, cached tasks and unavailable checks honestly.
Do not weaken tests, limits, CI or release scope to obtain a pass. A headless
build cannot substitute for client, multiplayer, restart or performance evidence.

Make routine technical/art/balance choices within the spec autonomously. Ask
only for genuine external constraints or conflicts that block a compatible
decision. Continue authorized implementation across milestones. If a check is
blocked, record the precise blocker and continue independent work where possible.
Leave a concrete checkpoint identifying changed behavior, evidence, limitations
and the next incomplete prerequisite whenever the session must end.

Finish only when all release acceptance and asset evidence is satisfied and the
supported artifact/configuration, player/admin instructions, recovery limits
and performance results are documented. Report any remaining limitation plainly.
```
