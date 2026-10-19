# Interrupted-session recovery — 2026-10-19

This durable checkpoint supersedes the temporary
`/private/tmp/factorycore-ai-handoff-2026-10-19.md` for locating interrupted work.
[IMPLEMENTATION.md](IMPLEMENTATION.md) remains the acceptance/evidence authority.
The task is the full first playable release; all A01–A17 remain partial.

## Recovered state

Main was clean at `0d92ed2`: the fluid-tank graphical-tour commit did land.
The five new slice worktrees are under `.claude/worktrees/`; branch names are
`worktree-` followed by the directory name below.

| Directory | Saved revision | Work and known gaps |
| --- | --- | --- |
| agent-abba5d46d69d99360 | `9a387bf` | Presentation work was uncommitted; preserved in this WIP commit, then integrated for verification. ADR 0058 and integrated fixes/docs are on main. |
| agent-af290ca1c3c2d6e51 | `5c01abd` | Paid throughput upgrades; only production compilation was checked. Packet/stock tests need new constructor/ceiling coverage; new behavior tests and ADR 0054/docs are missing. |
| agent-a9f012e11da71bc82 | `857a52d` | Processing journal and persistent program/craft link; focused JVM/native checks recorded in its handoff. Real process interruption/save-order fixture and ADR 0057/docs are missing. |
| agent-a614c8de168bafe44 | `22c2614` | Host/log limits, throttling, maintenance admission; final scheduler changes unverified. Jobs UI/contention work largely unstarted; ADR 0056/docs missing. |
| agent-ac17c57b160a6c9d0 | `f592daf`, code `8a905ad` | Network Access and Transfer Recovery screens; compilation and three native cases recorded. Full gate, graphical/multiplayer coverage and ADR 0055/docs missing. |

Each committed slice except presentation has a detailed file under its own
`docs/handoff/`. Inspect that file and actual changes before merging. Preserve
all appended implementation evidence, language keys and build tasks on conflicts.

Older retained worktrees `agent-a13a09ca37c89ccb4`, `agent-a47e25fddbd841c78`,
`agent-a7499058a1acdf1a5` and `agent-a9c1ab6d8ab7dc14c` contain the prior integration's
source state, including uncommitted changes. They were not reset, deleted or
merged again during recovery. Their work was previously recovered in `bfe3a4a`;
compare with main before using any of it.

## Work beyond the interrupted slices

Physical JEI gestures; broader gateway/staged lifecycle; published-job and program
multiplayer coverage; repeated controlled performance, client/packet/crafting/Wasm
workloads and supported OS execution; acceptance/asset reconciliation; A17 human
survival progression and working endgame factory. Passing the recovered slices
does not close these requirements.

Run one Gradle invocation per checkout at a time. Graphical fixtures use
`/tmp/factorycore-gui.lock` and `caffeinate -d -i`; inspect captures after a pass.
Keep performance runs isolated from builds and clients. Record actual outcomes
and remaining gaps in IMPLEMENTATION.md, without marking incomplete rows done.
