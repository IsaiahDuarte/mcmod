package dev.izzy.factorycore.platform.storage;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import java.io.IOException;
import java.util.UUID;
import net.minecraft.commands.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/**
 * Bounded operator inspection and exact original-holder repair, never permission/policy editing.
 */
final class NetworkNodeRecoveryCommands {
  private NetworkNodeRecoveryCommands() {}

  static LiteralArgumentBuilder<CommandSourceStack> commands() {
    return Commands.literal("node")
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
                                            "node",
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
                                                        c, "node")))))));
  }

  /** Operator reconciliation of an independent-save mismatch at a loaded controller/gateway. */
  @SuppressWarnings("PMD.CloseResource") // Minecraft owns the borrowed server and level.
  private static int adopt(
      CommandSourceStack source, String backing, long generation, BlockPos at) {
    try {
      var saved = DeviceSavedData.get(source.getServer());
      var id = parse(backing);
      var owner = saved.nodes().evidence(id);
      if (owner == null
          || owner.kind() != NodeRegistry.Kind.CONTROLLER
              && owner.kind() != NodeRegistry.Kind.GATEWAY
          || owner.generation() != generation)
        throw new IllegalStateException("Canonical node changed");
      var level = source.getLevel();
      if (level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4) == null
          || !(level.getBlockEntity(at) instanceof NetworkNodeEntity node))
        throw new IllegalStateException("Adopting node unavailable");
      if (!DeviceRecoveryCommands.admitRepair(source)) return 0;
      var path =
          node.adoptNode(
              new NodeRegistry.Handle(saved.registry().world(), id, generation, owner.kind()));
      source.sendSuccess(
          () ->
              Component.translatable(
                  "command.factorycore.recovery.node.adopted",
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
      source.sendFailure(Component.translatable("command.factorycore.recovery.node.adopt_refused"));
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
        "command.factorycore.recovery.node.row",
        owner.id().toString(),
        owner.kind().name(),
        owner.generation(),
        owner.conflict(),
        pos == null
            ? "portable"
            : pos.dimension() + " " + BlockPos.of(pos.block()).toShortString());
  }

  private static void fail(CommandSourceStack source) {
    source.sendFailure(Component.translatable("command.factorycore.recovery.node.refused"));
  }

  private static int list(CommandSourceStack source, String after) {
    try {
      var saved = DeviceSavedData.get(source.getServer());
      var page = saved.nodes().handles(after == null ? null : parse(after), 16);
      int count = 0;
      for (var handle : page.handles()) {
        if (handle.kind() == NodeRegistry.Kind.CONTROLLER
            || handle.kind() == NodeRegistry.Kind.GATEWAY) {
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

  @SuppressWarnings("PMD.CloseResource") // Minecraft owns the borrowed server and level.
  private static int inspect(CommandSourceStack source, String backing, long generation) {
    try {
      var server = source.getServer();
      var saved = DeviceSavedData.get(server);
      var id = parse(backing);
      var owner = saved.nodes().evidence(id);
      if (owner == null
          || owner.kind() != NodeRegistry.Kind.CONTROLLER
              && owner.kind() != NodeRegistry.Kind.GATEWAY)
        throw new IllegalArgumentException("Unknown network node");
      source.sendSuccess(() -> describe(owner), false);
      if (generation == 0) {
        if (owner.kind() == NodeRegistry.Kind.CONTROLLER) {
          var permissions = saved.network(id).snapshot();
          source.sendSuccess(
              () ->
                  Component.translatable(
                      "command.factorycore.recovery.node.owner",
                      permissions.owner().toString(),
                      permissions.generation(),
                      permissions.grants().size()),
              false);
        } else {
          var policy = owner.policy();
          source.sendSuccess(
              () ->
                  Component.translatable(
                      "command.factorycore.recovery.node.policy",
                      policy.branchPermissions().toString(),
                      policy.inheritedPermissions().toString(),
                      policy.inheritedKinds().toString()),
              false);
        }
        source.sendSuccess(
            () -> Component.translatable("command.factorycore.recovery.node.help"), false);
        return 1;
      }
      if (!owner.conflict()
          || generation != owner.generation()
          || saved.transfers().unresolved(id) != null
          || saved.transfers().unresolvedDevice(id) != null)
        throw new IllegalStateException("Expected conflict unavailable");
      var position = owner.position();
      var level =
          server.getLevel(
              ResourceKey.create(
                  Registries.DIMENSION, ResourceLocation.parse(position.dimension())));
      var pos = BlockPos.of(position.block());
      if (level == null
          || level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null
          || !(level.getBlockEntity(pos) instanceof NetworkNodeEntity node))
        throw new IllegalStateException("Original canonical node not loaded");
      if (!DeviceRecoveryCommands.admitRepair(source)) return 0;
      var path =
          node.repairConflict(
              new NodeRegistry.Handle(saved.registry().world(), id, generation, owner.kind()));
      source.sendSuccess(
          () ->
              Component.translatable(
                  "command.factorycore.recovery.node.repaired",
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
