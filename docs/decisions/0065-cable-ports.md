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
still valid; relabels apply first and new ports last, because each new port starts a rebuild.

A port's pending label now waits up to 1,200 ticks for a valid network instead of 20 retries:
each applied label rebuilds the network, so a row of 64 new ports applies one label per rebuild
and the last waits about 320 ticks. The wait is runtime-only, as before.

SFM's contiguous fill also reaches diagonal neighbours (M129). Here the row follows the cable
run, and a machine needs its own touching cable to get a port, so diagonal neighbours are not a
separate case.

