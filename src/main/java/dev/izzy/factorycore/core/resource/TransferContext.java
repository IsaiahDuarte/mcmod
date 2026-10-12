package dev.izzy.factorycore.core.resource;

import dev.izzy.factorycore.core.network.NodeRegistry;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable accepted world scope and endpoint references; no authority or live handler is saved.
 */
public record TransferContext(
    UUID world,
    UUID network,
    UUID segment,
    NodeRegistry.Position origin,
    Endpoint source,
    Endpoint destination) {
  public enum Type {
    DIGITAL,
    EXTERNAL,
    BUFFER
  }

  public record Endpoint(
      Type type, UUID device, UUID backing, long generation, long configuration) {
    public Endpoint {
      Objects.requireNonNull(type);
      Objects.requireNonNull(device);
      Objects.requireNonNull(backing);
      if (generation <= 0
          || (type == Type.DIGITAL
              ? configuration != 0 || !device.equals(backing)
              : configuration <= 0 || type == Type.BUFFER && !device.equals(backing)))
        throw new IllegalArgumentException("Invalid saved transfer endpoint");
    }
  }

  public TransferContext {
    Objects.requireNonNull(world);
    Objects.requireNonNull(network);
    Objects.requireNonNull(segment);
    Objects.requireNonNull(origin);
    Objects.requireNonNull(source);
    Objects.requireNonNull(destination);
    if (source.backing().equals(destination.backing()))
      throw new IllegalArgumentException("Saved transfer endpoints overlap");
  }

  public void validate(StagedTransfer.Snapshot move) {
    if ((source.type() == Type.BUFFER || destination.type() == Type.BUFFER)
        && move.key().kind() != ResourceKind.ITEM)
      throw new IllegalArgumentException("Buffer context requires item staging");
    if (!source.backing().equals(move.source())
        || !destination.backing().equals(move.destination()))
      throw new IllegalArgumentException("Transfer context does not match owned staging");
  }
}
