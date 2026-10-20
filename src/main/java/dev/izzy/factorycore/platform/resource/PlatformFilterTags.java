package dev.izzy.factorycore.platform.resource;

import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.platform.ServerThread;
import java.util.Objects;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;

/** Current vanilla/loader tag membership, with no inventories, world scans or cached quantities. */
public final class PlatformFilterTags implements ResourceFilter.Tags {
  private final MinecraftServer server;

  public PlatformFilterTags(MinecraftServer server) {
    this.server = Objects.requireNonNull(server);
  }

  @Override
  public boolean contains(ResourceKind kind, String tag, ResourceKey candidate) {
    ServerThread.require(server, "Tag evaluation");
    if (candidate.kind() != kind) throw new IllegalArgumentException("Tag candidate kind mismatch");
    var id = ResourceLocation.parse(candidate.registryId());
    var group = ResourceLocation.parse(tag);
    return switch (kind) {
      case ITEM ->
          BuiltInRegistries.ITEM
              .getHolder(id)
              .map(holder -> holder.is(TagKey.create(Registries.ITEM, group)))
              .orElse(false);
      case FLUID ->
          BuiltInRegistries.FLUID
              .getHolder(id)
              .map(holder -> holder.is(TagKey.create(Registries.FLUID, group)))
              .orElse(false);
      case ENERGY -> throw new IllegalArgumentException("Energy has no tag model");
    };
  }
}
