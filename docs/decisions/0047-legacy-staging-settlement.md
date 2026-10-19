# ADR 0047 — Legacy staged-transfer settlement

Status: selected and implemented; native evidence for cell return and cell delivery. Buffer
return is covered by JVM classification only.

## Context

World schema 5 added accepted endpoint context to staged transfers. Records from older
worlds, or records created without context, have none. Before this decision:

- Known staging in such a record could never progress. `return` refused it and `progress`
  rejected it `UNSUPPORTED`, so the items stayed in the record forever.
- Connector and Machine Interface conflict repair ([ADR 0026](0026-foreign-port-repair.md))
  refused while **any** unresolved legacy record existed and no provider registration was
  remembered. Registrations are not saved, so after any restart one old record blocked
  port recovery across the whole world.

The safety rules still apply: never extract again, never replay or refund an uncertain
call, never lose resources, never widen visibility, and audit operator-visible outcomes.

## Decision

### Settlement

The original owner (the actual logged-in player, as for `return`) may run
`/factorycore recovery settle <id>` on a legacy record with known staging.
`WorldStorageTransfers.settleLegacy` chooses the target from canonical identity only, using
`LegacySettlement.of`:

1. **Return:** if the source backing is a Factory Core cell or bank ledger, or a Buffered
   Interface, staging returns there. This uses the existing owned-cleanup exemption, so it
   needs no grant.
2. **Deliver:** otherwise, if the destination backing is a Factory Core cell or bank,
   staging is delivered there, only while the owner currently has DEPOSIT access to that
   holder on its current network.
3. **Otherwise:** `UNSUPPORTED`. Foreign or unknown backings are never guessed and the
   record is preserved for manual investigation.

A canonical source that is currently unloaded, moved into an item, or conflicted gives
`OFFLINE`. Settlement never falls back to delivery in that case, so the outcome depends on
identity, not on what happens to be loaded.

**Guards:**

- Pending or quarantined records reject `UNCERTAIN`.
- Records with context reject `UNSUPPORTED`; they use `return`.
- Only the recorded principal may settle; the operator flag adopts nothing.
- Console and `execute as` cannot settle for the owner.

**Execution:**

- The before-state record is written to the existing `factorycore-recovery` audit (schema 1)
  before queueing.
- Each step goes through the shared scheduler on the target holder's current network and
  offers at most the recovery quantum (128 items or 1,000 mB/FE).
- Nothing is extracted: the port to the other end is inert.
- A full target leaves the remainder staged for another step.
- Execution rechecks owner, legacy status, the chosen target, its admitted network and
  segment and, for delivery, DEPOSIT. A move into another network or segment refuses the
  stale ticket without mutation; resubmit to charge the current network.

A completed record can then be retired by an operator as before.

### Port-recovery preflight

Port repair (ADR 0026) without a remembered registration now calls
`TransferRegistry.hasUnscopedUncertain(DeviceSavedData::canonicalIdentity)` instead of
`hasUnscopedUnresolved()`. A legacy record blocks only when **both** of these hold:

- Its outcome is uncertain (a pending call or quarantine).
- It names a backing that is not a Factory Core identity, so it could be any port's
  provider.

Known legacy staging no longer blocks. Its only resolution is settlement into canonical
holders, which never goes through a port. Rotating a port lease also changes no provider
contents or identity.

The other port checks are unchanged:

- unresolved records naming the node as backing or endpoint device;
- unresolved records for a remembered provider backing;
- persisted provider faults;
- machine fences.

## Alternatives

- **Keep the global block:** this is the most cautious about unknown associations. But it
  protects no resource, because port rotation cannot strand a context-less record, and one
  forgotten record would disable port recovery forever.
- **Operator write-off of legacy staging:** it would delete evidence of resources that
  may exist, which would be silent loss in all but name. Rejected.
- **Probe providers or read container markers to match backings:** this invokes unknown
  provider code before recovery admission (ADR 0026). Rejected.
- **Deliver whenever the source is offline:** the outcome would depend on load state and
  could bypass a returnable source. Rejected.

## Consequences

Old known staging can finish without replay, and only genuinely uncertain foreign legacy
evidence still blocks port recovery. Uncertain records remain frozen for investigation
with backups.

No world, transfer-entry, context or audit schema changes. The command adds
`settle`; `return` behavior is unchanged.

The old global check `hasUnscopedUnresolved()` remains as a read-only inspection query.
