# 0065 — Machines on cables through cable ports

Status: accepted 2026-10-26 (playtest plan H4/H5, gate HD1). Revisits PD3 (the parity plan
chose option a, a Label Tool for Interfaces). Extends ADR 0066's per-face backing to cable
ports.

## Context

HD1 asked for SFM-style machines: a machine that touches a network cable is reachable, the
label goes on the machine, and programs may use any side of it. Until now a machine needed its
own Machine-mode Interface whose socket faced it, and the label lived on that Interface.

Machine access is built on a port device (`MachineInterfaceEntity`, a `ConnectorEntity`) that owns
the canonical lease (`NodeRegistry`, kind `MACHINE_INTERFACE`). The transfer endpoints, staging
and its recovery, rate budgets, processing admission, topology labels, presets, the recovery
commands and their audits all take that block entity as the owner.

## Decision

A machine is reached through a **cable port**: the network cable touching it, turned into a
`cable_port` block aimed at it.

- **Making one.** Use the Label Tool, holding a label, on a machine:
  - If a cable port is already aimed at it, that port is relabelled.
  - Otherwise the network cable touching the machine becomes a port aimed at it. The cable on the
    clicked face is tried first, then the others in direction order. The player needs EDIT where
    that cable is, and the machine must offer items, fluids or energy on that side.
  - After the network rebuilds, the port binds what the machine offers (items, else fluids, else
    energy) and takes the label under the same player's current EDIT.

  Messages say when there is no cable, nothing reachable on that side, or no permission.
- **What it is.** The block is a cable with a port plate. It connects the network on its other five
  faces, so it never splits a cable run (devices relay connectivity like cables in wire
  discovery). Its block entity is the Machine-port device, so it brings, unchanged:
  - the lease, rates and staging;
  - recovery, labels, redstone input and topology publication;
  - programs (`machines("label")`, with `side` on any face, since its binding is the whole
    container).

  Like ADR 0066's ports, its backing is per touched face, so it coexists with Importers and
  Exporters on the same machine. Using it opens a small kit screen with the label, resource and
  status.
- **Breaking one.** With nothing in transit, no fault and no upgrade, it is retired exactly like an
  Interface mode switch (ADR 0059): presets are forgotten, the lease is detached and retired, and
  a plain cable drops. Otherwise the `cable_port` item drops carrying the lease, like a broken
  Interface, so Transfer Recovery can still finish the work after it is placed back against the
  machine. That item has no recipe and is hidden from the creative tab.

## Alternatives

- **Ports with no block (any machine next to any cable, implicit).** This is the closest match to
  SFM. Every layer above currently takes a `ConnectorEntity` owner, so it would mean:
  - a new owner abstraction across `WorldConnectors`, `WorldPrograms`, `WorldMachinePresets`,
    the recovery commands and the audits;
  - a world schema change for saved-data-only leases;
  - new adoption, skew and recovery rules.

  That is a large change to the conservation-critical layer for little gain over a cable that
  visibly becomes a port. Rejected for now. It can follow later behind the same Label Tool flow.
- **Keep Machine-mode Interfaces.** The playtest showed that an oriented, separately crafted
  block per machine is the friction HD1 removes. H7 deletes Machine mode.
- **Label the machine block itself (block data or a saved map) and resolve ports at run time.**
  This still needs a port owner for every transfer, which is the first alternative again.

## Consequences

- SPEC R02 lists the cable port and the Label Tool's machine labelling. R04 is unchanged, because
  labels are still published by port devices within one segment.
- A machine can carry one cable port per touching cable. The Label Tool relabels the first one
  aimed at it.
- Players see a cable change shape when they label a machine. Breaking it gives the cable back.
- `CablePortGameTests` covers:
  - the Label Tool making a labelled port that still carries the network, then relabelling it in
    place;
  - a program pulling a furnace's output through the port with `side down` (fuel untouched);
  - breaking an idle port leaving one plain cable and retiring the lease.

## Amendment — row labelling (2026-10-26, ergonomics plan Q9)

Holding the **Label row** key (default Left Alt, the mod's first key mapping) while using the
Label Tool on a machine sends one bounded packet instead of a normal use. The server checks the
hand, the item and reach, then walks the cable run from the machine: network cables and cable
ports only, through wired faces, at most 2,048 loaded blocks, never loading a chunk. It collects
machines of the clicked machine's block type that touch the run, nearest first, at most 64.
Every machine is planned with the single-machine rules and the player's EDIT while the network is
still valid; relabels of existing ports then commit as one batch, and new ports come last.

A new label republishes the topology, so it starts a network rebuild, and EDIT checks fail until
the rebuild ends. Labels are therefore applied in batches: `MachineInterfaceEntity.applyLabels`
checks EDIT for every port of a batch before committing any, and pending labels of new ports are
queued when their network is valid and drained in one batch each tick (at high priority, before
`WorldNetworks.postTick`). A row of 64 new ports costs one rebuild for the ports and one for the
labels, instead of one per label; checked one at a time, relabelling a row of eight existing
ports labelled one and refused seven. A pending label waits up to 1,200 ticks for a valid network
instead of 20 retries. The wait is runtime-only, as before.

SFM's contiguous fill also reaches diagonal neighbours (M129). Here the row follows the cable
run, and a machine needs its own touching cable to get a port, so diagonal neighbours are not a
separate case.

## Amendment — picking a label (2026-10-26, ergonomics plan Q10)

A second key mapping, **Pick a label** (default middle mouse), sends the looked-at position while
the Label Tool is in hand. The server checks hand, item and reach, then reads the label of a cable
port or Interface, a Signal Interface, or (for a machine) a cable port aimed at it, under the
player's VIEW where that device is, and sets it on the tool. While the pick key shares vanilla
Pick Block's key, Pick Block is skipped with the tool in hand so the hotbar does not change.

## Amendment — label overlay (2026-10-26, ergonomics plan Q11)

While a player holds the Label Tool, the server builds a snapshot every 10 ticks from the block
entities of loaded chunks within 32 blocks: the label of each cable port, placed over the machine
it is aimed at; each Signal Interface's label; and an empty entry for each unlabelled machine
that touches a cable or cable port. Every entry needs the player's VIEW where the label or cable
is. The nearest 256 are kept, and the snapshot is sent only when it changed (an empty one when the
tool is put away). The payload is optional and its decoder refuses more than 256 entries. The
client draws labels as see-through tags and markers as faint dots, and a key (unbound by default)
hides them. The work per holding player is bounded by the block entities in about 25 loaded
chunks every half second.

## Amendment — network view and real use on machines (2026-10-26, ergonomics plan Q12)

The overlay key cycles labels, network view and off; the client tells the server (`SetMode`).
In the network view the server ray-casts 16 blocks from the player (keeping the last block seen
while they look at air) and walks from there along wired faces through loaded chunks, at most
1,024 entries, VIEW-checked per block. Each entry is a cable, port, device or gateway with a
branch number (segments in the order met) for colour, plus the machines cable ports are aimed at.
Using the tool on a machine in this view sends `MachineInspection.View`: per side, the item slots,
tanks or energy the machine offers there, and `move everything` / `fluids` / `energy` lines in
both directions, at most 48 lines of 160 characters, under VIEW where its cable is.

The tool now handles machines in `onItemUseFirst`, before the block's own use. Through the
server's real interaction path, a furnace or chest used to open its screen first, so the
machine-labelling flow added in H5 never reached `useOn` in play; the GameTests called `useOn`
directly. `LabelToolUseGameTests` goes through `ServerPlayerGameMode.useItemOn` and failed before
the change. Factory Core's own blocks already pass while an item is in hand.

## Amendment — copying settings to every device (2026-10-26, ergonomics plan Q13)

The Label Tool's copy and paste cover Buffers, Signal Interfaces, Drives, Gateways, Program
Controllers and Crafters (`DeviceCopy`), next to the existing port and Storage Interface copies.
A device copy is a compound with `Schema` 1 and `Device` (`buffer`, `signal`, `drive`, `gateway`,
`program`, `crafter`); port copies, which have no `Device`, are unchanged except that they also
record the port's `Tier`. Copying needs VIEW; pasting goes through the device's own authority
(EDIT, plus DEPLOY for a Buffer, or MANAGE for a Gateway) and refuses a different kind. The
confirm-once and 30-second batch rule applies to every kind, with a hash of the target's
settings as its change check. Device copies and pastes are handled in `onItemUseFirst`, before
a Drive or Crafter takes the held item.

As AE2's Memory Card and RS2's Configuration Card do, a paste first installs the copied
throughput tier from the player's inventory, one step's item at a time, and reports any step it
could not install. A Crafter paste copies the source's patterns exactly (one pattern may be held
by several Crafters) into empty slots, using one Blank Pattern each, and reports how many it
could not add. A Crafter's own upgrade is not copied, and a Program Controller receives the draft
only.

