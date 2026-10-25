package dev.izzy.factorycore.core.storage;

import java.util.Objects;

/**
 * C2 redstone control for a Machine or Buffer preset. The owner feeds every change of its input
 * with {@link #observe}; the preset loop asks {@link #admit} before each step and {@link #chain}
 * before continuing straight into another step. Single-threaded: the server thread owns it.
 */
public final class RedstoneGate {
  public enum Control {
    /** Runs regardless of redstone (the default, and the behavior before C2). */
    IGNORE,
    /** Runs only while the Interface is powered. */
    POWERED,
    /** Runs only while it is unpowered. */
    UNPOWERED,
    /**
     * One step per rising edge: a pulse received while idle or busy is remembered (at most one),
     * and holding the signal high does not repeat it.
     */
    PULSE
  }

  private Control control = Control.IGNORE;
  private boolean powered, pulse;

  public Control control() {
    return control;
  }

  public boolean powered() {
    return powered;
  }

  /** True while a received pulse waits for its step. */
  public boolean pulsePending() {
    return pulse;
  }

  /** Changing the control discards a remembered pulse, so a new setting never starts a run. */
  public void control(Control next) {
    Objects.requireNonNull(next);
    if (next != control) pulse = false;
    control = next;
  }

  /** Records the input; returns true when this may let a held preset run (a wake is due). */
  public boolean observe(boolean now) {
    boolean rising = now && !powered;
    powered = now;
    if (rising && control == Control.PULSE) pulse = true;
    return control != Control.IGNORE;
  }

  /** May a step start now? PULSE consumes the remembered pulse. */
  public boolean admit() {
    return switch (control) {
      case IGNORE -> true;
      case POWERED -> powered;
      case UNPOWERED -> !powered;
      case PULSE -> {
        boolean ready = pulse;
        pulse = false;
        yield ready;
      }
    };
  }

  /** May a productive step continue straight into another without a new {@link #admit}? */
  public boolean chain() {
    return switch (control) {
      case IGNORE -> true;
      case POWERED -> powered;
      case UNPOWERED -> !powered;
      case PULSE -> false;
    };
  }

  /** Restores saved state; {@code pulse} only survives under PULSE. */
  public void restore(Control control, boolean powered, boolean pulse) {
    this.control = Objects.requireNonNull(control);
    this.powered = powered;
    this.pulse = pulse && control == Control.PULSE;
  }
}
