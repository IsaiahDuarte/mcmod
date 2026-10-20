package dev.izzy.factorycore.platform.storage;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.Loaded;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Operator recovery for one family of node-registry leases ({@code /factorycore recovery <word>}):
 * bounded {@code list}, {@code show}, exact original-holder {@code repair} of a conflicted lease,
 * and {@code adopt} of a stale holder after an independent-save mismatch. Every family shares the
 * same checks and refusal ordering; a family supplies only its kinds, its row and detail lines,
 * what blocks repair, and the holder's audited repair/adopt operations. Never edits presets,
 * permissions or policy.
 */
final class NodeRecovery<E> {
  /** An audited holder operation; returns the written audit file. */
  @FunctionalInterface
  interface Operation<E> {
    Path apply(E holder, NodeRegistry.Handle expected) throws IOException;
  }

  /** Extra {@code show} lines for one canonical node. */
  @FunctionalInterface
  interface Details {
    void show(CommandSourceStack source, DeviceSavedData saved, NodeRegistry.Stored owner);
  }

  @FunctionalInterface
  interface Row {
    Component describe(DeviceSavedData saved, NodeRegistry.Stored owner);
  }

  static final NodeRecovery<BufferedInterfaceEntity> BUFFER =
      new NodeRecovery<>(
          "buffer",
          Set.of(NodeRegistry.Kind.BUFFERED_INTERFACE),
          BufferedInterfaceEntity.class,
          (saved, owner) ->
              Component.translatable(
                  "command.factorycore.recovery.buffer.row",
                  owner.id().toString(),
                  owner.generation(),
                  owner.conflict(),
                  saved.buffers().evidence(owner.id()).slots().stream()
                      .mapToInt(slot -> slot.count())
                      .sum(),
                  place(owner)),
          (source, saved, owner) -> {
            var deployment = saved.buffers().deployment(owner.id());
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
          },
          (saved, id) -> saved.transfers().unresolved(id) != null,
          BufferedInterfaceEntity::repairConflict,
          BufferedInterfaceEntity::adoptBuffer);

  static final NodeRecovery<ConnectorEntity> PORT =
      new NodeRecovery<>(
          "port",
          Set.of(NodeRegistry.Kind.CONNECTOR, NodeRegistry.Kind.MACHINE_INTERFACE),
          ConnectorEntity.class,
          (saved, owner) -> kindRow("port", owner),
          (source, saved, owner) -> {
            if (owner.kind() != NodeRegistry.Kind.MACHINE_INTERFACE) return;
            var deployment = saved.machinePresets().get(owner.id());
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
                          saved.machinePresets().configuration(owner.id())),
                  false);
          },
          NodeRecovery::anyUnresolved,
          ConnectorEntity::repairConflict,
          ConnectorEntity::adoptPort);

  static final NodeRecovery<NetworkNodeEntity> NODE =
      new NodeRecovery<>(
          "node",
          Set.of(NodeRegistry.Kind.CONTROLLER, NodeRegistry.Kind.GATEWAY),
          NetworkNodeEntity.class,
          (saved, owner) -> kindRow("node", owner),
          (source, saved, owner) -> {
            if (owner.kind() == NodeRegistry.Kind.CONTROLLER) {
              var permissions = saved.network(owner.id()).snapshot();
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
          },
          NodeRecovery::anyUnresolved,
          NetworkNodeEntity::repairConflict,
          NetworkNodeEntity::adoptNode);

  private final String word;
  private final Set<NodeRegistry.Kind> kinds;
  private final Class<E> holder;
  private final Row row;
  private final Details details;
  private final BiPredicate<DeviceSavedData, UUID> repairBlocked;
  private final Operation<E> repair, adopt;

  private NodeRecovery(
      String word,
      Set<NodeRegistry.Kind> kinds,
      Class<E> holder,
      Row row,
      Details details,
      BiPredicate<DeviceSavedData, UUID> repairBlocked,
      Operation<E> repair,
      Operation<E> adopt) {
    this.word = word;
    this.kinds = kinds;
    this.holder = holder;
    this.row = row;
    this.details = details;
    this.repairBlocked = repairBlocked;
    this.repair = repair;
    this.adopt = adopt;
  }

  /** The row for families that span several node kinds: id, kind, generation, conflict, place. */
  private static Component kindRow(String word, NodeRegistry.Stored owner) {
    return Component.translatable(
        "command.factorycore.recovery." + word + ".row",
        owner.id().toString(),
        owner.kind().name(),
        owner.generation(),
        owner.conflict(),
        place(owner));
  }

  private static String place(NodeRegistry.Stored owner) {
    var pos = owner.position();
    return pos == null
        ? "portable"
        : pos.dimension() + " " + BlockPos.of(pos.block()).toShortString();
  }

  /** In-flight transfers on the lease, either as a backing or as a device, block repair. */
  private static boolean anyUnresolved(DeviceSavedData saved, UUID id) {
    return saved.transfers().unresolved(id) != null
        || saved.transfers().unresolvedDevice(id) != null;
  }

  LiteralArgumentBuilder<CommandSourceStack> commands() {
    return Commands.literal(word)
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
                                    Commands.argument(word, BlockPosArgument.blockPos())
                                        .executes(
                                            c ->
                                                adopt(
                                                    c.getSource(),
                                                    StringArgumentType.getString(c, "backing"),
                                                    LongArgumentType.getLong(c, "generation"),
                                                    BlockPosArgument.getBlockPos(c, word)))))));
  }

  private String key(String suffix) {
    return "command.factorycore.recovery." + word + "." + suffix;
  }

  private static UUID parse(String text) {
    if (text.length() != 36) throw new IllegalArgumentException("UUID length");
    return UUID.fromString(text);
  }

  private NodeRegistry.Stored owner(DeviceSavedData saved, UUID id) {
    var owner = saved.nodes().evidence(id);
    return owner != null && kinds.contains(owner.kind()) ? owner : null;
  }

  private int list(CommandSourceStack source, String after) {
    try {
      var saved = DeviceSavedData.get(source.getServer());
      var page = saved.nodes().handles(after == null ? null : parse(after), 16);
      int count = 0;
      for (var handle : page.handles()) {
        if (!kinds.contains(handle.kind())) continue;
        source.sendSuccess(() -> row.describe(saved, saved.nodes().evidence(handle.id())), false);
        count++;
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
      source.sendFailure(Component.translatable(key("refused")));
      return 0;
    }
  }

  /** {@code show} when {@code generation} is 0; otherwise repair at the original holder. */
  private int inspect(CommandSourceStack source, String backing, long generation) {
    try {
      var server = source.getServer();
      var saved = DeviceSavedData.get(server);
      var id = parse(backing);
      var owner = owner(saved, id);
      if (owner == null) throw new IllegalArgumentException("Unknown " + word);
      source.sendSuccess(() -> row.describe(saved, owner), false);
      if (generation == 0) {
        details.show(source, saved, owner);
        source.sendSuccess(() -> Component.translatable(key("help")), false);
        return 1;
      }
      if (!owner.conflict() || generation != owner.generation() || repairBlocked.test(saved, id))
        throw new IllegalStateException("Expected conflict unavailable");
      var position = owner.position();
      var level = server.getLevel(Loaded.dimension(position.dimension()));
      var pos = BlockPos.of(position.block());
      if (level == null
          || Loaded.chunk(level, pos) == null
          || !holder.isInstance(level.getBlockEntity(pos)))
        throw new IllegalStateException("Original canonical holder not loaded");
      if (!DeviceRecoveryCommands.admitRepair(source)) return 0;
      var path =
          repair.apply(
              holder.cast(level.getBlockEntity(pos)),
              new NodeRegistry.Handle(saved.registry().world(), id, generation, owner.kind()));
      source.sendSuccess(
          () ->
              Component.translatable(
                  key("repaired"), backing, generation + 1, path.getFileName().toString()),
          true);
      return 1;
    } catch (IOException auditFailure) {
      source.sendFailure(Component.translatable("command.factorycore.recovery.audit"));
      return 0;
    } catch (IllegalArgumentException | IllegalStateException | ArithmeticException rejected) {
      source.sendFailure(Component.translatable(key("refused")));
      return 0;
    }
  }

  /** Operator reconciliation of an independent-save mismatch at a loaded holder. */
  private int adopt(CommandSourceStack source, String backing, long generation, BlockPos at) {
    try {
      var saved = DeviceSavedData.get(source.getServer());
      var id = parse(backing);
      var owner = owner(saved, id);
      if (owner == null || owner.generation() != generation)
        throw new IllegalStateException("Canonical " + word + " changed");
      var level = source.getLevel();
      if (Loaded.chunk(level, at) == null || !holder.isInstance(level.getBlockEntity(at)))
        throw new IllegalStateException("Adopting " + word + " unavailable");
      if (!DeviceRecoveryCommands.admitRepair(source)) return 0;
      var path =
          adopt.apply(
              holder.cast(level.getBlockEntity(at)),
              new NodeRegistry.Handle(saved.registry().world(), id, generation, owner.kind()));
      source.sendSuccess(
          () ->
              Component.translatable(
                  key("adopted"),
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
      source.sendFailure(Component.translatable(key("adopt_refused")));
      return 0;
    }
  }
}
