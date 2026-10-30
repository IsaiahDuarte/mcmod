package dev.izzy.factorycore.core.crafting;

import dev.izzy.factorycore.core.EnumKeys;

/**
 * The outcome of a crafting request or keep-stock rule (ADR 0031, ADR 0034): why a preview,
 * submission or rule was refused, or what a Jobs-view command did. Shown as {@code
 * gui.factorycore.terminal.job.status.<key>}; constant names are stable keys ({@link EnumKeys}).
 */
public enum JobStatus {
  /** The Jobs view before any command. */
  READY,
  PREVIEWED,
  STARTED,
  KEPT,
  UNKEPT,
  REFUSED,
  CANCELLING,
  /** The terminal had no energy for the action. */
  NO_POWER,
  /** Stock changed between the plan and its reservation. */
  CHANGED,
  CYCLE,
  DENIED,
  JOB_LIMIT,
  LIMIT,
  MISSING,
  OFFLINE,
  PRINCIPAL_LIMIT,
  PRINCIPAL_RULE_LIMIT,
  RULE_LIMIT,
  UNSUPPORTED,
  /** A rule's or program request's job is still running. */
  IN_FLIGHT,
  /** A rule found enough stock. */
  SATISFIED,
  /** Q7: Max previewed the largest amount that can be made. */
  MAXIMUM,
  /** Q7: Max stopped at the 4,096 request limit; more could be made. */
  CAPPED;

  public String key() {
    return EnumKeys.key(this);
  }

  /** A rule in this state needs nothing from the player: stocked, or its job is under way. */
  public boolean healthy() {
    return this == SATISFIED || this == STARTED || this == IN_FLIGHT;
  }
}
