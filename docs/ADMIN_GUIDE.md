# Factory Core admin guide

For server owners and operators. Players start with the
[player guide](PLAYER_GUIDE.md). This guide covers permissions commands, server
configuration, saved data and the recovery tools. Recovery is for rare cases:
- a server crash between chunk saves and Factory Core's own save;
- duplicated or copied blocks;
- transfers whose outcome could not be confirmed.

## Access commands

Networks start private. At a loaded block in a valid network,
`/factorycore access list <x> <y> <z>` shows the owner and grants. The owner, a player
with Manage, or an operator can change grants:

```text
/factorycore access grant <x> <y> <z> <online-name-or-UUID> view,deposit,withdraw
/factorycore access revoke <x> <y> <z> <online-name-or-UUID> withdraw
/factorycore access revoke <x> <y> <z> <online-name-or-UUID>
```

- **Rights:** `view`, `deposit`, `withdraw`, `craft`, `edit`, `deploy` and `manage`;
  `all` names every right.
- **Grant and revoke:** grant adds rights. Revoke without rights removes every
  explicit grant. Offline players need their UUID.
- **Manage** allows administration, so grant it deliberately.
- **Gateways:** gateway policies still apply at the chosen block.
- **Operators** have management access, but must be given resource rights to use
  someone else's stock.
- **Refresh:** a grant change invalidates old observations, and open terminal
  views refresh.

The in-game **Network Access** screen offers the same rights through roles (Guest,
Member, Builder, Admin). Only the owner or an operator can transfer ownership.

## Server configuration

`serverconfig/factorycore-server.toml` in the world folder:

| Setting | Section | Default | Range | Effect |
| --- | --- | --- | --- | --- |
| `gatewayDepth` | `[network]` | 8 | 1–32 | Maximum nested gateways on a root-to-subnetwork path, wireless-linked gateways included. |

Reopen the world or restart after editing. A network deeper than the setting reports
`DEPTH_LIMIT` and pauses access without deleting stored resources.

## Saved data and backups

- **Where it lives:** cell, bank and buffer contents, grants, gateway policies,
  presets, jobs and owned transfer staging are saved in
  `world/data/factorycore_devices.dat` (registry schema ten). Blocks and items carry
  references to that data, not copies, so copying a block or item cannot duplicate
  stock. A conflicting copy disables the shared backing until it is repaired.
- **Old worlds:** schema-one through nine registries migrate, keeping identities,
  contents and reservations. Worlds containing removed blocks are not supported (UX
  plan UD7): the Storage Connector, Machine Interface, Buffered Interface, Crafting
  Unit, Crafting Interface and Wireless Link Endpoint.
- **Unreadable data:** unknown or malformed data stays unavailable with its
  original bytes preserved. Drives, banks, buffers and links with unsupported saved
  data break into labelled *recovery* items that keep the evidence. Keep those
  items and the world backup.
- **Downgrades:** do not downgrade a save to an older mod build.
- **Unloaded chunks:** an unloaded controller or upstream gateway pauses its
  subnetworks until reloaded. Factory Core never keeps chunks loaded implicitly or
  through wireless links.
- **Server limits:** a rebuild blocked by a server limit leaves resources owned
  and unavailable, never discarded.

## Transfers and recovery

Every move between holders stages its resources under the player or automation that
started it.
- **Known staging:** it is delivered or returned only through its accepted owner and
  endpoints. Players return their own staging with **Return** on the terminal,
  buffer or machine screen.
- **Uncertain outcomes:** a foreign call that may or may not have happened is
  quarantined. Its recorded amount is evidence, not a refund, and it is never
  replayed after a restart.

### Recovery

`/factorycore recovery screen` opens the **Transfer Recovery** screen. Players see
their own interrupted transfers; operators see everyone's. Pick a record to see its
safe next step, then **Return**, **Settle** or **Retire** under the rules below.
Uncertain transfers offer no action.

**Listing and inspecting** (operators and the console):
- `/factorycore recovery list [next-UUID]` pages 16 records at a time.
- `/factorycore recovery show <transfer-UUID>` reports the owner, exact counts,
  root and subnetwork, origin and device IDs, and a safe next action. It reads
  metadata only.

**Returning known staging:** the original logged-in owner runs
`/factorycore recovery return <UUID>`.
- **Who:** only the owner's own command source works. Console or `execute as`
  cannot impersonate them.
- **What:** it returns only existing staging to its original source. It grants no
  new access after revocation.
- **What blocks it:** unloaded holders, moved holders, a full source, rate or access
  limits, or changed bindings. Retry once the cause is fixed.

**Old records without context** ("No accepted endpoint context"): the original owner
runs `/factorycore recovery settle <UUID>` instead.
- **Factory Core source:** staging from a cell, bank or Buffer-mode Interface returns
  there.
- **Factory Core destination:** staging bound for a cell or bank is delivered there
  only with current Deposit.
- **Foreign or unknown targets** stay preserved for investigation.

The holder must be loaded and on a valid network. A known source always takes
precedence. Each step moves at most 128 items or 1,000 mB/FE.

**Retiring:** an operator runs `/factorycore recovery retire <UUID>` once a record is
known complete and empty. This archives it and frees quota. Uncertain or live records
cannot be retired.

**Evidence:** before acting, every command writes before-state evidence under the
world's `factorycore-recovery` directory and prints its file name. Nothing happens
if the evidence cannot be written.
- **Quota:** the directory allows 512 files of up to 512 KiB, 32 MiB in total.
- **When it fills:** archive files elsewhere; no command deletes audits.
- **Rate limit:** requests are throttled to about one per second.

### Device repair

These commands fix duplicated blocks. Every one:
- reads no providers and loads no chunks;
- needs the **loaded original holder** with its exact reference and no unresolved
  transfer;
- archives the full before-state under `factorycore-device-recovery`, then rotates
  the lease so old copies become inert;
- never changes quantities, replays calls or picks another survivor.

| Device | Commands (`/factorycore recovery …`) | Notes |
| --- | --- | --- |
| Cells | `cell list [after]`, `cell show <backing>`, `cell repair <backing> <generation>` | The drive slot shown is 0–3. Stock and reservations stay. |
| Energy banks | `bank list [after]`, `bank show <backing>`, `bank repair <backing> <generation>` | Stored FE, claims and same-tick rate spending stay. |
| Buffer-mode Interfaces | `buffer list [after]`, `buffer show <backing>`, `buffer repair <backing> <generation>` | Slots and preset evidence stay; the preset stays inactive until a player with rights presses Apply. |
| Controllers and gateways | `node list [after]`, `node show <id>`, `node repair <id> <generation>` | Owner, grants, policy and network identities stay. |
| Storage/Machine Interfaces | `port list [after]`, `port show <id>`, `port repair <id> <generation>` | Settings, label and face stay; the container is not touched. Machine presets wait for a player with Deploy to apply them again. |

- **Shared limits:** repairs share a 20-tick throttle and device-audit quotas of 512
  files, 9 MiB per file and 32 MiB in total.
- **Paging:** list pages examine at most 16 handles and may show zero matches with
  a cursor to continue.
- **Repair refuses:**
  - wrong, stale or missing references;
  - unresolved transfers;
  - unsupported data;
  - mismatched saves.

  Investigate those with backups.

### Adopting after a crash

If the server stops between saving chunks and saving Factory Core's data, a holder
may report a *stale placed* reference and its contents become unavailable. Nothing
is lost or duplicated. After checking with `show`, an operator names the block that
physically holds the reference:

```text
/factorycore recovery cell adopt <backing> <generation> <x y z> <slot>
/factorycore recovery bank adopt <backing> <generation> <x y z>
/factorycore recovery node adopt <id> <generation> <x y z>
/factorycore recovery buffer adopt <id> <generation> <x y z>
/factorycore recovery port adopt <id> <generation> <x y z>
```

- **Location check:** the original location must be loaded and no longer hold the
  reference.
- **Evidence:** it is recorded first.
- **Kept:** ownership, grants, policies, items and provider contents.
- **Afterwards:** re-enable buffer modes and re-apply machine presets.

## Processing jobs and machines

A processing job leases its exact machine and remembers its persistent identity.
- **Busy ports:** they retry after the job's cleanup.
- **Replaced machines:** a new machine at the same position cannot continue the
  job.
- **Rotated Crafters:** a rotated Crafter must be turned back before the job
  continues.
- **Unproven outcomes:** an old dispatch without proof of its machine asks the
  player to cancel. Inputs already inserted, and unproven outputs, stay in the
  machine for manual inspection; there is no guessed refund.

## Display support

The supported minimum framebuffer is 1708×960 at GUI scale 4 (427×240 GUI pixels).
GUI scales 1–4 are checked by the presentation fixture.
