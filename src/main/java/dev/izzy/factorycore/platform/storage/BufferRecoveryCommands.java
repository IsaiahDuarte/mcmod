package dev.izzy.factorycore.platform.storage;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.Loaded;
import java.io.IOException;
import java.util.UUID;
import net.minecraft.commands.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Bounded operator inspection and exact original-holder repair, never preset deployment. */
final class BufferRecoveryCommands {
  private BufferRecoveryCommands() {}

  static LiteralArgumentBuilder<CommandSourceStack> commands() {
    return Commands.literal("buffer")
        .requires(source -> source.hasPermission(Actors.OPERATOR_LEVEL))
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
                                            LongArgumentType.getLong(c, "generation"))))))
        .then(
            Commands.literal("adopt")
                .then(
                    Commands.argument("backing", StringArgumentType.word())
                        .then(
                            Commands.argument("generation", LongArgumentType.longArg(1))
                                .then(
                                    Commands.argument(
                                            "buffer",
                                            net.minecraft.commands.arguments.coordinates
                                                .BlockPosArgument.blockPos())
                                        .executes(
                                            c ->
                                                adopt(
                                                    c.getSource(),
                                                    StringArgumentType.getString(c, "backing"),
                                                    LongArgumentType.getLong(c, "generation"),
                                                    net.minecraft.commands.arguments.coordinates
                                                        .BlockPosArgument.getBlockPos(
                                                        c, "buffer")))))));
  }

  /** Operator reconciliation of an independent-save mismatch at a loaded buffered interface. */
  private static int adopt(
      CommandSourceStack source, String backing, long generation, BlockPos at) {
    try {
      var saved = DeviceSavedData.get(source.getServer());
      var id = parse(backing);
      var owner = saved.nodes().evidence(id);
      if (owner == null
          || owner.kind() != NodeRegistry.Kind.BUFFERED_INTERFACE
          || owner.generation() != generation)
        throw new IllegalStateException("Canonical buffer changed");
      var level = source.getLevel();
      if (Loaded.chunk(level, at) == null
          || !(level.getBlockEntity(at) instanceof BufferedInterfaceEntity buffer))
        throw new IllegalStateException("Adopting buffer unavailable");
      if (!DeviceRecoveryCommands.admitRepair(source)) return 0;
      var path =
          buffer.adoptBuffer(
              new NodeRegistry.Handle(saved.registry().world(), id, generation, owner.kind()));
      source.sendSuccess(
          () ->
              Component.translatable(
                  "command.factorycore.recovery.buffer.adopted",
                  backing,
                  at.toShortString(),
                  generation + 1,
                  path.getFileName().toString()),
          true);
      return 1;
    } catch (IOException auditFailure) {
      source.sendFailure(Component.translatable("command.factorycore.recovery.audit"));
      return 0;
    } catch (IllegalArgumentException | IllegalStateException rejected) {
      source.sendFailure(
          Component.translatable("command.factorycore.recovery.buffer.adopt_refused"));
      return 0;
    }
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
      var level = server.getLevel(Loaded.dimension(position.dimension()));
      var pos = BlockPos.of(position.block());
      if (level == null
          || Loaded.chunk(level, pos) == null
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
