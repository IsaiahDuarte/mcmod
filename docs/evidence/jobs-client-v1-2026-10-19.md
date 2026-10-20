# Terminal Jobs mode in a real client — 2026-10-19

`caffeinate -d -i -u ./gradlew runPresentationClient` (GUI lock held) passed in 1m17s, on a
1708×960 framebuffer.

The tour does the following:
1. Places a Crafting Unit with a 1 oak log → 4 planks pattern beside the screens network.
2. Waits until the rebuilt network has the cell online, then stores 8 logs.
3. Opens the terminal by real use and presses **Jobs**, then **×8**.
   - Checks that the plan is ready: 2 crafts, ingredients available.
   - [preview capture](jobs-client-v1-2026-10-19/jobs-preview-scale-2.png)
4. Presses **Start**. The job row shows 2/2 delivering, with Cancel enabled.
   - [started capture](jobs-client-v1-2026-10-19/jobs-started-scale-2.png)
5. Requires the server to finish with exactly 8 planks in storage and no job left.
6. Presses **×64**. The preview reports the 10-log shortage and disables Start.
   - The panel passes fit, overlap and label checks at scale 4 in the 427×240 window.
   - [missing capture](jobs-client-v1-2026-10-19/jobs-missing-scale-4.png), [fit](jobs-client-v1-2026-10-19/fit.txt)

Bugs found and fixed:
- **Preview wording.** It read "1 steps" and "(1 missing items)". It now reads
  "Steps: 1 · crafts: 2" and "item types short: 1".
- **Fixture timing.** The first attempt stored the logs while the network was rebuilding (the
  cell was offline), so the fixture now waits for the cell to be online.

Not covered here: clicking Cancel in the client, processing-machine `busy` and cleanup states in
the client, and published-branch jobs. Native tests cover these (TerminalJobGameTests,
ProcessingJobGameTests, BranchCraftingGameTests).

## Keep-stock waiting state (2026-10-20)

The tour presses **Keep** on the 64-plank shortage. Once the rule evaluates, the row shows an
amber `!≥64` badge on a dark backing; a satisfied rule's badge stays green. The craftable tooltip
explains the reason ("Keep-stock waiting: Not enough ingredients").
[Capture](jobs-client-v1-2026-10-19/jobs-keep-waiting-scale-4.png).

`TerminalJobGameTests` starves a kept rule and requires the row's note to be `missing`.
`TerminalJobPacketsTest` covers the note's round trip and its 64-character bound.
