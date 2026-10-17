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

Current recorded baseline: Minecraft 1.21.1 / NeoForge 21.1.252 / Java 21.
P0 is locally verified; P1/P2/P3 are partial; P4–P7 are unstarted. Cells/drives,
controller/gateway/cable ownership, automatic wired availability, core resource
accounting and scheduling exist. Physical energy banks, capabilities and portable
upgrades now have local headless evidence; real chunks/restart and client evidence
remain pending. Canonical transfer staging and accepted operation context now persist with schema-five migration
and conservative pending-intent recovery; scheduled digital cell/bank endpoints now exist; sided connector primitives now exist; bounded advisory external stock pages now share the scheduler; portable foreign claim metadata/revalidation introduced by schema six now persists under canonical schema ten; scheduled principal-owned reserve/release/claimed moves and background hold enforcement now exist; crafting-job claim ownership/reconciliation remains missing. Bounded live exact-key digital/foreign catalog discovery now exists; bounded explicit global priority routes now have portable/core and headless world evidence; persisted rotation now exists under schema-seven canonical preferences; bounded all-key discovery/registry-text search now exists; general automatic recovery dispatch remains
missing. Explicit scheduled delivery/owned cleanup now rebinds validated saved endpoints without re-extraction. Accepted source/destination/origin context now persists alongside staging. World-scoped provider component markers now retain
backing identity across supported block-entity NBT save/load.
The nine-slot owned ItemBuffer and NeoForge hopper handler prerequisite now exist; canonical buffer ownership/persistence now exists under schema eight; physical Buffered Interface blocks/capabilities now have real hopper, backpressure, movement/clone and recipe evidence; explicit authorized network buffer endpoints and staging recovery now exist under schema nine/context two; canonical authenticated preset deployment authority now exists in nested buffer schema two; bounded automatic Import/Paused controls, real hopper-to-cell/full-storage backpressure and original-owner staged cleanup now have headless evidence; bounded all-key discovery/search now has server-side component/world evidence; physical item terminal/menu controls now exist with headless evidence; partial graphical search/Take1/paging/deposit-payload evidence at scales2–4 now exists; manual/additional client checks and export/stock/filter controls remain pending. Bounded immutable selector/stock-deficit models now have portable and actual native tag/thread evidence; canonical guarded filtered Import and shared network rates now exist under nested buffers-v3/policy-v1, with native sample/name-tag/clock/paper controls and core/world evidence; automatic buffered Export/Stock now has scheduled priority discovery, canonical buffers-v4/policy-v2 goals, native compass/chest controls and conservation/backpressure/contention evidence; native settings/inventory menu and server-owned draft controls now exist with headless evidence; actual buffer and machine settings client verification now has recorded native interaction/scales2–4/conservation evidence (docs/evidence/settings-client-v1-2026-10-17.md); prior no-display failure is historical; explicit moved/requested feedback now has native partial/full/conservation evidence; machine-interface local labels, selected item windows and whole sided fluid/FE transfers now have component/headless evidence under world schema11 and reference schema2; a native authenticated machine settings editor now exposes labels/type/access/windows/priority/rates with atomic Apply/Cancel; machine presets, selected tanks and remaining release integration are next independent work.
Rust/Wasm is a test-only feasibility probe. Explicit authenticated player inventory endpoints and known staged cleanup now exist
under world schema ten/context three; physical terminal item menus/client controls have partial graphical evidence; production
scripting, crafting and wireless remain missing. Legacy device visuals are placeholders; native bank models
are implemented with client inspection pending.
Confirm this baseline against the current checkout before choosing work.

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
