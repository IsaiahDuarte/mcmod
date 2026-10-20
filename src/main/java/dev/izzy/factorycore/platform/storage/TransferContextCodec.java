package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.TransferContext;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Context schemas one–four for legacy/buffer/player/cursor references; absent data invents no
 * endpoint.
 */
final class TransferContextCodec {
  private TransferContextCodec() {}

  static CompoundTag encode(TransferContext context) {
    var tag = new CompoundTag();
    tag.putInt(
        "Schema",
        context.source().type() == TransferContext.Type.PLAYER
                || context.destination().type() == TransferContext.Type.PLAYER
            ? (cursor(context) ? 4 : 3)
            : context.source().type() == TransferContext.Type.BUFFER
                    || context.destination().type() == TransferContext.Type.BUFFER
                ? 2
                : 1);
    tag.putUUID("World", context.world());
    tag.putUUID("Network", context.network());
    tag.putUUID("Segment", context.segment());
    tag.putString("Dimension", context.origin().dimension());
    tag.putLong("Origin", context.origin().block());
    tag.put("Source", endpoint(context.source()));
    tag.put("Destination", endpoint(context.destination()));
    return tag;
  }

  private static CompoundTag endpoint(TransferContext.Endpoint endpoint) {
    var tag = new CompoundTag();
    tag.putString("Type", endpoint.type().name());
    tag.putUUID("Device", endpoint.device());
    tag.putUUID("Backing", endpoint.backing());
    tag.putLong("Generation", endpoint.generation());
    tag.putLong("Configuration", endpoint.configuration());
    return tag;
  }

  static TransferContext decode(CompoundTag tag) {
    if (!Set.of(
                "Schema",
                "World",
                "Network",
                "Segment",
                "Dimension",
                "Origin",
                "Source",
                "Destination")
            .equals(tag.getAllKeys())
        || !tag.contains("Schema", Tag.TAG_INT)
        || tag.getInt("Schema") < 1
        || tag.getInt("Schema") > 4)
      throw new IllegalArgumentException("Unsupported transfer context");
    for (var field : new String[] {"World", "Network", "Segment"})
      if (!tag.hasUUID(field))
        throw new IllegalArgumentException("Malformed transfer scope identity");
    if (!tag.contains("Dimension", Tag.TAG_STRING)
        || !tag.contains("Origin", Tag.TAG_LONG)
        || !tag.contains("Source", Tag.TAG_COMPOUND)
        || !tag.contains("Destination", Tag.TAG_COMPOUND))
      throw new IllegalArgumentException("Malformed transfer context fields");
    var context =
        new TransferContext(
            tag.getUUID("World"),
            tag.getUUID("Network"),
            tag.getUUID("Segment"),
            new NodeRegistry.Position(tag.getString("Dimension"), tag.getLong("Origin")),
            endpoint(tag.getCompound("Source")),
            endpoint(tag.getCompound("Destination")));
    boolean buffered =
        context.source().type() == TransferContext.Type.BUFFER
            || context.destination().type() == TransferContext.Type.BUFFER;
    boolean player =
        context.source().type() == TransferContext.Type.PLAYER
            || context.destination().type() == TransferContext.Type.PLAYER;
    if (tag.getInt("Schema") != (player ? (cursor(context) ? 4 : 3) : buffered ? 2 : 1))
      throw new IllegalArgumentException("Endpoint union does not match its context schema");
    return context;
  }

  private static boolean cursor(TransferContext context) {
    return context.source().type() == TransferContext.Type.PLAYER
            && context.source().configuration() == 38
        || context.destination().type() == TransferContext.Type.PLAYER
            && context.destination().configuration() == 38;
  }

  private static TransferContext.Endpoint endpoint(CompoundTag tag) {
    if (!Set.of("Type", "Device", "Backing", "Generation", "Configuration").equals(tag.getAllKeys())
        || !tag.contains("Type", Tag.TAG_STRING)
        || !tag.hasUUID("Device")
        || !tag.hasUUID("Backing")
        || !tag.contains("Generation", Tag.TAG_LONG)
        || !tag.contains("Configuration", Tag.TAG_LONG))
      throw new IllegalArgumentException("Malformed saved endpoint");
    return new TransferContext.Endpoint(
        TransferContext.Type.valueOf(tag.getString("Type")),
        tag.getUUID("Device"),
        tag.getUUID("Backing"),
        tag.getLong("Generation"),
        tag.getLong("Configuration"));
  }
}
