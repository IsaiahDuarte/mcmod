package dev.izzy.factorycore.core.resource;

/** Actual amount changed, and the reason any remainder could not be processed. */
public record OperationResult(long amount, Reason reason) {
  public enum Reason {
    OK,
    FULL,
    TECHNICAL_LIMIT,
    CATALOG_LIMIT,
    RESERVATION_LIMIT,
    SHORTAGE,
    RESERVED,
    OFFLINE,
    WRONG_RESOURCE,
    DENIED,
    HANDLER_FAULT,
    UNCERTAIN
  }

  public OperationResult {
    if (amount < 0 || reason == null) {
      throw new IllegalArgumentException("Operation result needs a nonnegative amount and reason");
    }
  }

  public static void requireQuantity(long amount) {
    if (amount < 0) {
      throw new IllegalArgumentException("Quantity must be nonnegative");
    }
  }
}
