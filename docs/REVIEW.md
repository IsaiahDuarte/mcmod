# Specification and setup review

Reviewed on 2026-10-03. This records issues corrected during the design audit; it is not a second product specification.

## Findings and resolutions

| Finding | Resolution |
| --- | --- |
| Confirmed features also appeared as candidates/proposals; scope and open questions were duplicated. | Replaced the discovery draft with one authoritative numbered behavior contract and stable acceptance IDs. |
| “Scripting first” could postpone mandatory player UI. | Explicitly separated development order from release scope; terminal, pattern editor, presets, and friendly editor are required. |
| Storage terminal/manual crafting and fluid/energy storage were underspecified. | Defined terminal views, ghost crafting grid, remainder accounting, container actions, energy banks, and distinct resource capacities. |
| Infinite storage could mean generated supply, unlimited throughput, or literally unlimited memory. | Defined infinite gameplay item capacity with exact deposited quantities, explicit operational limits, and independent throughput budgets. |
| Segments were private but also allowed undefined direct central access. | First-release coordination uses published jobs; direct cross-branch machine access is deferred. |
| Root/branch unload, network splits, label ambiguity, and private storage visibility were unclear. | Defined affected automation pause, invalid-topology suspension, local label groups, root ownership, and inherited storage views. |
| Repeated keep-stock code could register unlimited persistent rules. | Distinguished per-invocation statements from keyed persistent rules; registration replaces the same key. |
| Pausing a program could leave autonomous rules running. | Defined deployment-owned rule shutdown, fresh-instance reload, explicit persistent state, and ongoing job cleanup. |
| Crafting reservations/leases seemed to lock foreign actors or guarantee output attribution. | Scoped leases to our actors and required deterministic patterns/exclusive external use, with explicit attribution limits. |
| Crash language could imply impossible universal transactions with third-party saves. | Defined normal conservation separately from a required persistence/durability decision and uncertain-operation recovery. |
| Wireless was only deferred; early/endgame tradeoffs were absent. | Added wired starter infrastructure, wireless terminal access, paired same-dimension links, distinct costs/caps, and deferred cross-dimensional support. |
| Runtime/platform/numeric/performance choices had no owner or deadline. | Added D01–D07 decision gates and P0–P7 stages with required evidence and acceptance tracking. |
| Verification could pass documentation while tests were empty/not run. | Made the verifier run tooling tests and reject empty collection; CI uses the same canonical command. |
| Unclosed Markdown fences hid trailing links from checks. | Added fence validation and failure regression coverage. |
| Remote CI readiness was easy to confuse with active enforcement. | Kept local verification, prepared CI, missing remote run, and absent branch protection explicit. |
| Powered wireless progression required energy without defining a source independent of optional mods. | Added a basic vanilla-fuel generator and rechargeable handheld terminal, with conversion/balance in D06. |
| Decision validation could require an experiment before permitting its implementation. | Distinguished selecting a design before dependent coding from validating it with experiments by stage exit. |

## Remaining decisions, explicitly gated

At the design audit, Minecraft/loader/toolchain, quantity/ABI representation, persistence durability, Wasm runtime, exact language grammar, numerical recipes/balance, and performance targets required evidence. The subsequent P0 platform selection and local build/startup evidence are in [ADR 0001](decisions/0001-platform.md); the other gates remain open. Their owner, deadline, and validation are recorded in [IMPLEMENTATION.md](IMPLEMENTATION.md). They are not silently chosen and are not a reason to invent incompatible behavior during coding.

The spec is ready for staged AI implementation beginning with decision/verification bootstrap. It is not a claim that every technical detail is already settled, that gameplay has been tested, or that one automated generation will produce a correct release.

## Review limits

This was a single-agent document/tooling review. No independent agent, playable mod test, or hosted CI run was performed. The LogisticsNetworks comparison uses selected source/doc evidence, not a full runtime audit. Actual release completion requires the spec's acceptance evidence.

## Local verification

The canonical command, python scripts/verify.py, passed on the Windows workspace during this review: documentation/link/fence checks, acceptance-ID correspondence, source-bootstrap guard, and all 15 tooling tests. There are 17 product acceptance criteria tracked in the implementation plan; none is marked implemented. This result covers repository tooling, not gameplay or performance.
