# Q136 — Modded containers (2026-11-01)

From playthrough note 21: an Importer would not connect to a Sophisticated Storage chest.

## Cause

- **Connectors:** `WorldConnectors.probe` returned UNSUPPORTED for any provider block entity that
  was neither in `net.minecraft.world.level.block.entity` (a vanilla `Container`) nor an
  `ExternalBackingProvider` (ADR 0013).
- **Processing:** `ProviderIdentity.processingIdentity` applied the same rule, so modded machines
  could not take processing jobs either (`machine_identity`).

## Change

- **`WorldConnectors.probe`:** the vanilla-or-described gate is removed. Canonical storage is
  still refused by `providerPositions`, and the OVERLAP checks, quarantine and windows are
  unchanged.
- **`ProviderIdentity.processingIdentity`:** any block entity except canonical storage gets an
  identity; an undescribed one is one block.
- **`InterfaceBlock.detect`:** Storage mode now also applies to blocks tagged `c:chests`,
  `c:barrels` or `minecraft:shulker_boxes`.
- **Docs:** ADR 0013 amendment (with the accepted double-count risk for undetectable aliases),
  ADR 0032 and ADR 0059 notes.

## Tests

- **New:** `ModdedProviderGameTests.importersAndExportersWorkOnAnUndescribedModdedInventory`. On
  a 100-slot test inventory, an Importer moves 40 iron and 64 dirt exactly into storage, and a
  keep-8 Exporter puts exactly 8 back; this holds for 40 ticks.
- **Replaced, because the requirement changed** (both used to require refusal):
  - `ConnectorGameTests.sidedAvailabilityMalformedKeysAndUndescribedProvidersBind`: the rest of
    the test is unchanged, and the last step now expects OK for the opaque provider.
  - `ProcessingJobGameTests.anUndescribedModdedMachineReceivesProcessingInput`: the job
    dispatches, and the machine holds exactly 1 raw iron taken from storage.
- **Full GameTest run:** 523 of 523 pass.

## Not done

- **Over 64 slots:** slots past a port's 64-slot window are out of reach (plan Q145).
- **Real mod:** not yet tried with Sophisticated Storage in the user's pack.
