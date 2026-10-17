package dev.izzy.factorycore.platform.storage;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.izzy.factorycore.core.network.NodeRegistry;
import java.io.IOException;
import java.util.UUID;
import net.minecraft.commands.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/** Bounded operator inspection and exact original-holder repair, never preset deployment. */
final class BufferRecoveryCommands {
  private BufferRecoveryCommands() {}

  static LiteralArgumentBuilder<CommandSourceStack> commands() {
    return Commands.literal("buffer")
        .requires(source -> source.hasPermission(2))
        .then(
            Commands.literal("list")
                .executes(c -> list(c.getSource(), null))
                .then(
                    Commands.argument("after", StringArgumentType.word())
                        .executes(
                            c -> list(c.getSource(), StringArgumentType.getString(c, "after")))))
        .then(
            Commands.literal("show")
                .then(
                    Commands.argument("backing", StringArgumentType.word())
                        .executes(
                            c ->
                                inspect(
                                    c.getSource(), StringArgumentType.getString(c, "backing"), 0))))
        .then(
            Commands.literal("repair")
                .then(
                    Commands.argument("backing", StringArgumentType.word())
                        .then(
                            Commands.argument("generation", LongArgumentType.longArg(1))
                                .executes(
                                    c ->
                                        inspect(
                                            c.getSource(),
                                            StringArgumentType.getString(c, "backing"),
                                            LongArgumentType.getLong(c, "generation"))))));
  }

  private static UUID parse(String text) {
    if (text.length() != 36) throw new IllegalArgumentException("UUID length");
    return UUID.fromString(text);
  }

  private static Component describe(DeviceSavedData saved, NodeRegistry.Stored owner) {
    var inventory = saved.buffers().evidence(owner.id());
    int total = inventory.slots().stream().mapToInt(slot -> slot.count()).sum();
    var pos = owner.position();
    return Component.translatable(
        "command.factorycore.recovery.buffer.row",
        owner.id().toString(),
        owner.generation(),
        owner.conflict(),
        total,
        pos == null
            ? "portable"
            : pos.dimension() + " " + BlockPos.of(pos.block()).toShortString());
  }

  private static void fail(CommandSourceStack source) {
    source.sendFailure(Component.translatable("command.factorycore.recovery.buffer.refused"));
  }

  private static int list(CommandSourceStack source, String after) {
    try {
      var saved = DeviceSavedData.get(source.getServer());
      var page = saved.nodes().handles(after == null ? null : parse(after), 16);
      int count = 0;
      for (var handle : page.handles()) {
        if (handle.kind() == NodeRegistry.Kind.BUFFERED_INTERFACE) {
          source.sendSuccess(() -> describe(saved, saved.nodes().evidence(handle.id())), false);
          count++;
        }
      }
      int shown = count;
      source.sendSuccess(
          () ->
              Component.translatable(
                  "command.factorycore.recovery.page",
                  shown,
                  page.next() == null ? "—" : page.next().toString()),
          false);
      return count;
    } catch (IllegalArgumentException | IllegalStateException rejected) {
      fail(source);
      return 0;
    }
  }

  @SuppressWarnings("PMD.CloseResource") // Minecraft owns the borrowed server and level.
  private static int inspect(CommandSourceStack source, String backing, long generation) {
    try {
      var server = source.getServer();
      var saved = DeviceSavedData.get(server);
      var id = parse(backing);
      var owner = saved.nodes().evidence(id);
      if (owner == null || owner.kind() != NodeRegistry.Kind.BUFFERED_INTERFACE)
        throw new IllegalArgumentException("Unknown buffer");
      source.sendSuccess(() -> describe(saved, owner), false);
      if (generation == 0) {
        var deployment = saved.buffers().deployment(id);
        if (deployment != null)
          source.sendSuccess(
              () ->
                  Component.translatable(
                      "command.factorycore.recovery.buffer.deployment",
                      deployment.principal().toString(),
                      deployment.mode().name(),
                      deployment.revision(),
                      deployment.leaseGeneration(),
                      deployment.policy().rate(),
                      deployment.policy().target()),
              false);
        source.sendSuccess(
            () -> Component.translatable("command.factorycore.recovery.buffer.help"), false);
        return 1;
      }
      if (!owner.conflict()
          || generation != owner.generation()
          || saved.transfers().unresolved(id) != null)
        throw new IllegalStateException("Expected conflict unavailable");
      var position = owner.position();
      var level =
          server.getLevel(
              ResourceKey.create(
                  Registries.DIMENSION, ResourceLocation.parse(position.dimension())));
      var pos = BlockPos.of(position.block());
      if (level == null
          || level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null
          || !(level.getBlockEntity(pos) instanceof BufferedInterfaceEntity buffer))
        throw new IllegalStateException("Original canonical buffer not loaded");
      if (!DeviceRecoveryCommands.admitRepair(source)) return 0;
      var path =
          buffer.repairConflict(
              new NodeRegistry.Handle(saved.registry().world(), id, generation, owner.kind()));
      source.sendSuccess(
          () ->
              Component.translatable(
                  "command.factorycore.recovery.buffer.repaired",
                  backing,
                  generation + 1,
                  path.getFileName().toString()),
          true);
      return 1;
    } catch (IOException auditFailure) {
      source.sendFailure(Component.translatable("command.factorycore.recovery.audit"));
      return 0;
    } catch (IllegalArgumentException | IllegalStateException rejected) {
      fail(source);
      return 0;
    }
  }
}
