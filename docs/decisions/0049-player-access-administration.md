# ADR 0049 — Player access administration and live terminal observations

Status: implemented; integrated compilation and initial JVM checks pass. Real
two-client multiplayer verification and the combined canonical gate passed.

## Context

R04/R08/A12 require usable multiplayer grant administration and server validation.
The core authority already persists separate grants, but players had no command to
administer them. A completed terminal observation also remained unchanged while
another terminal or automation moved stock.

## Decision

Provide `/factorycore access list|grant|revoke <x> <y> <z> ...` against a loaded,
valid network member. Resolve the actor from the command source and require current
Manage at that position, including ancestor branch policies. Owners implicitly have
Manage; permission-level-2 operators can administer. An operator gains no implicit
resource access. Grant names are the seven existing permission tokens, comma- or
space-separated, or `all`. Principals are online names or UUIDs; offline names are
refused without a remote lookup. Grant adds named permissions; revoke removes them,
or removes all when its permission argument is omitted. Listing returns only the
owner and the bounded canonical table (maximum 256 explicit principals), to a
current administrator. Commands use the existing saved authority and schema.

Terminal menus periodically replace completed stock observations after 20 ticks,
retaining the last rows during the bounded scheduled rescan. A stale observation
caused by a changed permission generation is discarded and reissued only while the
menu remains authorized. Mutations still revalidate server-owned selections and
current authority. The rescan is observational and grants no stale mutation rights.

A manual `runMultiplayerCheck` fixture starts one dedicated server and two separate
graphical clients over loopback TCP. It uses an isolated offline-mode test server,
not a production authentication configuration. Each process must report PASS and
required captures must exist; the harness is not part of the headless `check` gate.

## Alternatives and consequences

Direct client edits to saved grants would bypass the shared authority and are
rejected. A dedicated permissions screen can build on this administration path;
these commands alone do not complete every R08 screen requirement. Refreshing rows
locally after one's own actions would miss other sessions and automation, so the
existing bounded observation service is reused instead.

`AccessCommandGameTests` exercises private defaults, owner and operator management,
additive grants, partial/full revocation, delegated Manage, invalid names/positions,
implicit owner grants and permission-generation changes. `TerminalGameTests` adds
an idle-viewer refresh regression. Actual multiplayer outcomes must be recorded
after running the combined fixture; compilation is not a multiplayer pass.

## Graphical fixture correction

The first integrated multiplayer run reached revocation with exact stock 61, owner
2 and guest 1, but waited for the owner's status to become `ready`. A successful
transfer deliberately retains `ok` while background observations refresh the rows
(`TerminalMenu.finish` and `broadcastChanges`). The fixture now requires a nonbusy
menu with exact current stock and either valid status (`ready` or `ok`), rather than
requiring a status transition the product does not promise. The native idle-viewer
regression now also checks preservation of the acting session's `ok` result. No
production permission or conservation condition was changed.

The corrected `./gradlew runMultiplayerCheck` passed (53 seconds), with three PASS
reports, all 19 captures and exact conservation through concurrent withdrawal and
revocation. [Recorded evidence](../evidence/multiplayer-v1-2026-10-18.md).
