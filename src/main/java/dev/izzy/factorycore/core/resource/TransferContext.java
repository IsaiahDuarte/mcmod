package dev.izzy.factorycore.core.resource;

import dev.izzy.factorycore.core.network.NodeRegistry;
import java.nio.charset.StandardCharsets;
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
    BUFFER,
    PLAYER
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
              : configuration <= 0
                  || type == Type.BUFFER && !device.equals(backing)
                  || type == Type.PLAYER && configuration > 37))
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
    if ((source.type() == Type.BUFFER
            || destination.type() == Type.BUFFER
            || source.type() == Type.PLAYER
            || destination.type() == Type.PLAYER)
        && move.key().kind() != ResourceKind.ITEM)
      throw new IllegalArgumentException("Buffer/player context requires item staging");
    if (source.type() == Type.PLAYER && source.configuration() == 37)
      throw new IllegalArgumentException("Player extraction requires one selected slot");
    for (var endpoint : new Endpoint[] {source, destination})
      if (endpoint.type() == Type.PLAYER
          && (!endpoint.device().equals(move.owner())
              || !endpoint.backing().equals(playerBacking(world, endpoint.device()))))
        throw new IllegalArgumentException("Player context has another principal/backing");
    if (!source.backing().equals(move.source())
        || !destination.backing().equals(move.destination()))
      throw new IllegalArgumentException("Transfer context does not match owned staging");
  }

  public static UUID playerBacking(UUID world, UUID principal) {
    Objects.requireNonNull(world);
    Objects.requireNonNull(principal);
    return UUID.nameUUIDFromBytes(
        ("factorycore:player-inventory:v1:" + world + ":" + principal)
            .getBytes(StandardCharsets.UTF_8));
  }
}
