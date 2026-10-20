package dev.izzy.factorycore.platform.storage;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.platform.Loaded;
import java.io.IOException;
import java.util.UUID;
import net.minecraft.commands.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Bounded operator inspection and exact original-holder repair, never permission/policy editing.
 */
final class PortRecoveryCommands {
  private PortRecoveryCommands() {}

  static LiteralArgumentBuilder<CommandSourceStack> commands() {
    return Commands.literal("port")
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
                                            LongArgumentType.getLong(c, "generation"))))))
        .then(
            Commands.literal("adopt")
                .then(
                    Commands.argument("backing", StringArgumentType.word())
                        .then(
                            Commands.argument("generation", LongArgumentType.longArg(1))
                                .then(
                                    Commands.argument(
                                            "port",
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
                                                        c, "port")))))));
  }

  private static int adopt(
      CommandSourceStack source, String backing, long generation, BlockPos at) {
    try {
      var saved = DeviceSavedData.get(source.getServer());
      var id = parse(backing);
      var owner = saved.nodes().evidence(id);
      var level = source.getLevel();
      if (owner == null
          || owner.generation() != generation
          || owner.kind() != NodeRegistry.Kind.CONNECTOR
              && owner.kind() != NodeRegistry.Kind.MACHINE_INTERFACE
          || Loaded.chunk(level, at) == null
          || !(level.getBlockEntity(at) instanceof ConnectorEntity port))
        throw new IllegalStateException("Adopting port unavailable");
      if (!DeviceRecoveryCommands.admitRepair(source)) return 0;
      var path =
          port.adoptPort(
              new NodeRegistry.Handle(saved.registry().world(), id, generation, owner.kind()));
      source.sendSuccess(
          () ->
              Component.translatable(
                  "command.factorycore.recovery.port.adopted",
                  backing,
                  at.toShortString(),
                  generation + 1,
                  path.getFileName().toString()),
          true);
      return 1;
    } catch (IOException auditFailure) {
      source.sendFailure(Component.translatable("command.factorycore.recovery.audit"));
      return 0;
    } catch (IllegalArgumentException | IllegalStateException | ArithmeticException rejected) {
      source.sendFailure(Component.translatable("command.factorycore.recovery.port.adopt_refused"));
      return 0;
    }
  }

  private static UUID parse(String text) {
    if (text.length() != 36) throw new IllegalArgumentException("UUID length");
    return UUID.fromString(text);
  }

  private static Component describe(NodeRegistry.Stored owner) {
    var pos = owner.position();
    return Component.translatable(
        "command.factorycore.recovery.port.row",
        owner.id().toString(),
        owner.kind().name(),
        owner.generation(),
        owner.conflict(),
        pos == null
            ? "portable"
            : pos.dimension() + " " + BlockPos.of(pos.block()).toShortString());
  }

  private static void fail(CommandSourceStack source) {
    source.sendFailure(Component.translatable("command.factorycore.recovery.port.refused"));
  }

  private static int list(CommandSourceStack source, String after) {
    try {
      var saved = DeviceSavedData.get(source.getServer());
      var page = saved.nodes().handles(after == null ? null : parse(after), 16);
      int count = 0;
      for (var handle : page.handles()) {
        if (handle.kind() == NodeRegistry.Kind.CONNECTOR
            || handle.kind() == NodeRegistry.Kind.MACHINE_INTERFACE) {
          source.sendSuccess(() -> describe(saved.nodes().evidence(handle.id())), false);
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
      if (owner == null
          || owner.kind() != NodeRegistry.Kind.CONNECTOR
              && owner.kind() != NodeRegistry.Kind.MACHINE_INTERFACE)
        throw new IllegalArgumentException("Unknown foreign port");
      source.sendSuccess(() -> describe(owner), false);
      if (generation == 0) {
        if (owner.kind() == NodeRegistry.Kind.MACHINE_INTERFACE) {
          var deployment = saved.machinePresets().get(id);
          if (deployment != null)
            source.sendSuccess(
                () ->
                    Component.translatable(
                        "command.factorycore.recovery.port.deployment",
                        deployment.authority().principal().toString(),
                        deployment.mode().name(),
                        deployment.revision(),
                        deployment.leaseGeneration(),
                        deployment.configuration(),
                        saved.machinePresets().configuration(id)),
                false);
        }
        source.sendSuccess(
            () -> Component.translatable("command.factorycore.recovery.port.help"), false);
        return 1;
      }
      if (!owner.conflict()
          || generation != owner.generation()
          || saved.transfers().unresolved(id) != null
          || saved.transfers().unresolvedDevice(id) != null)
        throw new IllegalStateException("Expected conflict unavailable");
      var position = owner.position();
      var level = server.getLevel(Loaded.dimension(position.dimension()));
      var pos = BlockPos.of(position.block());
      if (level == null
          || Loaded.chunk(level, pos) == null
          || !(level.getBlockEntity(pos) instanceof ConnectorEntity port))
        throw new IllegalStateException("Original canonical port not loaded");
      if (!DeviceRecoveryCommands.admitRepair(source)) return 0;
      var path =
          port.repairConflict(
              new NodeRegistry.Handle(saved.registry().world(), id, generation, owner.kind()));
      source.sendSuccess(
          () ->
              Component.translatable(
                  "command.factorycore.recovery.port.repaired",
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
