package dev.izzy.factorycore.core.crafting;

import dev.izzy.factorycore.core.EnumKeys;
import dev.izzy.factorycore.core.resource.OperationResult;

/**
 * Why a crafting job waits (ADR 0031). Persisted with the job and shown in the Jobs view as {@code
 * gui.factorycore.terminal.job.reason.<key>}, so constant names are stable keys ({@link EnumKeys}).
 * The first group comes from crafting itself; the second mirrors {@link OperationResult.Reason} for
 * a storage or movement refusal during a craft.
 */
public enum JobReason {
  BUSY,
  DENIED,
  FULL,
  LEGACY_MACHINE,
  LEGACY_PROVIDER,
  MACHINE_CHANGED,
  MACHINE_FULL,
  MACHINE_IDENTITY,
  MACHINE_JOURNAL,
  MACHINE_MISSING,
  MACHINE_REPLACED,
  MACHINE_UNLOADED,
  OFFLINE,
  OUTPUT_NOT_CLEAN,
  PATTERN_MISSING,
  PROCESSING,
  RECIPE_CHANGED,
  RESERVED,
  SAVE_MISMATCH,
  SUBSTITUTES_SHORT,
  UNCERTAIN,
  TECHNICAL_LIMIT,
  CATALOG_LIMIT,
  RESERVATION_LIMIT,
  SHORTAGE,
  WRONG_RESOURCE,
  HANDLER_FAULT,
  RATE_LIMIT,
  OVERLAP,
  UNSUPPORTED,
  REDSTONE;

  public String key() {
    return EnumKeys.key(this);
  }

  /** The job reason for a refused storage or movement operation. */
  public static JobReason of(OperationResult.Reason refusal) {
    return switch (refusal) {
      case OK -> throw new IllegalArgumentException("OK is not a refusal");
      case FULL -> FULL;
      case TECHNICAL_LIMIT -> TECHNICAL_LIMIT;
      case CATALOG_LIMIT -> CATALOG_LIMIT;
      case RESERVATION_LIMIT -> RESERVATION_LIMIT;
      case SHORTAGE -> SHORTAGE;
      case RESERVED -> RESERVED;
      case OFFLINE -> OFFLINE;
      case WRONG_RESOURCE -> WRONG_RESOURCE;
      case DENIED -> DENIED;
      case HANDLER_FAULT -> HANDLER_FAULT;
      case UNCERTAIN -> UNCERTAIN;
      case RATE_LIMIT -> RATE_LIMIT;
      case OVERLAP -> OVERLAP;
      case UNSUPPORTED -> UNSUPPORTED;
      case BUSY -> BUSY;
      case REDSTONE -> REDSTONE;
    };
  }
}
