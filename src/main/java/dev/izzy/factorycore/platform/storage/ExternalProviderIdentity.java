package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.resource.ResourceKind;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Small reference-only marker; foreign inventory contents never enter this schema. */
final class ExternalProviderIdentity {
  static final String FIELD = "factorycore:external_identity";

  private ExternalProviderIdentity() {}

  static UUID read(Tag raw, UUID world) {
    Objects.requireNonNull(world);
    if (raw == null) return null;
    if (!(raw instanceof CompoundTag tag)
        || !Set.of("Schema", "World", "Id").equals(tag.getAllKeys())
        || !tag.contains("Schema", Tag.TAG_INT)
        || tag.getInt("Schema") != 1
        || !tag.hasUUID("World")
        || !tag.hasUUID("Id"))
      throw new IllegalArgumentException(
          "Unsupported provider identity; preserve its data for recovery");
    if (!world.equals(tag.getUUID("World")))
      throw new IllegalArgumentException(
          "Provider identity belongs to another world; preserve for recovery");
    return tag.getUUID("Id");
  }

  static CompoundTag write(UUID world, UUID id) {
    var tag = new CompoundTag();
    tag.putInt("Schema", 1);
    tag.putUUID("World", Objects.requireNonNull(world));
    tag.putUUID("Id", Objects.requireNonNull(id));
    return tag;
  }

  static UUID group(UUID world, ResourceKind kind, List<UUID> members) {
    Objects.requireNonNull(world);
    Objects.requireNonNull(kind);
    if (members.isEmpty() || members.size() > 64 || new HashSet<>(members).size() != members.size())
      throw new IllegalArgumentException("Invalid or duplicated provider component identity");
    var text =
        new StringBuilder("factorycore:external:1:").append(world).append(':').append(kind.name());
    members.stream().sorted().forEach(id -> text.append(':').append(Objects.requireNonNull(id)));
    return UUID.nameUUIDFromBytes(text.toString().getBytes(StandardCharsets.UTF_8));
  }
}
