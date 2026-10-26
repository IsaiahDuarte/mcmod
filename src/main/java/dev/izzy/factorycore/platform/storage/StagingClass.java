package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.EnumKeys;

/**
 * The safe next step for a staged transfer record (ADR 0022, ADR 0047): an uncertain outcome needs
 * an operator; a complete one can be retired; a legacy one (no saved context) can be settled; a
 * known one can be returned by its owner. Shown as {@code gui.factorycore.recovery.class.<key>}
 * with its {@code .next.<key>} step.
 */
public enum StagingClass {
  UNCERTAIN,
  COMPLETE,
  LEGACY,
  KNOWN;

  public String key() {
    return EnumKeys.key(this);
  }
}
