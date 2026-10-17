package dev.izzy.factorycore.platform.storage;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import java.io.IOException;
import java.util.*;
import net.minecraft.commands.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;

/** Explicit operator selection of the existing canonical cell holder; no chunk loading. */
final class CellRecoveryCommands {
  private static final Map<MinecraftServer, Integer> REPAIRS = new WeakHashMap<>();

  private CellRecoveryCommands() {}

  static void stopped(MinecraftServer server) {
    REPAIRS.remove(server);
  }

  static LiteralArgumentBuilder<CommandSourceStack> commands() {
    return Commands.literal("cell")
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

  private static UUID parse(String value) {
    if (value.length() != 36) throw new IllegalArgumentException("UUID length");
    return UUID.fromString(value);
  }

  private static Component describe(DeviceRegistry.Ownership owner) {
    var location = owner.definition().location();
    return Component.translatable(
        "command.factorycore.recovery.cell.row",
        owner.handle().backing().toString(),
        owner.handle().generation(),
        owner.conflict(),
        owner.definition().kind().name(),
        owner.total(),
        location == null
            ? "portable"
            : location.dimension()
                + " "
                + BlockPos.of(location.position()).toShortString()
                + " slot="
                + location.slot());
  }

  private static int list(CommandSourceStack source, String after) {
    try {
      var registry = DeviceSavedData.get(source.getServer()).registry();
      var page = registry.handles(after == null ? null : parse(after), 16);
      for (var handle : page.handles())
        source.sendSuccess(() -> describe(registry.ownership(handle.backing())), false);
      source.sendSuccess(
          () ->
              Component.translatable(
                  "command.factorycore.recovery.page",
                  page.handles().size(),
                  page.next() == null ? "—" : page.next().toString()),
          false);
      return page.handles().size();
    } catch (IllegalArgumentException | IllegalStateException rejected) {
      source.sendFailure(Component.translatable("command.factorycore.recovery.cell.refused"));
      return 0;
    }
  }

  @SuppressWarnings("PMD.CloseResource") // Minecraft owns the borrowed server and level.
  private static int inspect(CommandSourceStack source, String backing, long generation) {
    try {
      var server = source.getServer();
      var saved = DeviceSavedData.get(server);
      var owner = saved.registry().ownership(parse(backing));
      if (owner == null) throw new IllegalArgumentException("Unknown backing");
      source.sendSuccess(() -> describe(owner), false);
      if (generation == 0) {
        source.sendSuccess(
            () -> Component.translatable("command.factorycore.recovery.cell.help"), false);
        return 1;
      }
      if (owner.handle().generation() != generation
          || !owner.conflict()
          || saved.transfers().unresolved(owner.handle().backing()) != null)
        throw new IllegalStateException("Conflict changed or transfer unresolved");
      var location = owner.definition().location();
      var level =
          server.getLevel(
              ResourceKey.create(
                  Registries.DIMENSION, ResourceLocation.parse(location.dimension())));
      var pos = BlockPos.of(location.position());
      if (level == null
          || level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null
          || !(level.getBlockEntity(pos) instanceof StorageDriveEntity drive))
        throw new IllegalStateException("Canonical holder unavailable");
      var previous = REPAIRS.get(server);
      if (previous != null && server.getTickCount() - previous < 20) {
        source.sendFailure(Component.translatable("command.factorycore.recovery.rate"));
        return 0;
      }
      REPAIRS.put(server, server.getTickCount());
      var path = drive.repairCellConflict(owner.handle());
      source.sendSuccess(
          () ->
              Component.translatable(
                  "command.factorycore.recovery.cell.repaired",
                  backing,
                  generation + 1,
                  path.getFileName().toString()),
          true);
      return 1;
    } catch (IOException auditFailure) {
      source.sendFailure(Component.translatable("command.factorycore.recovery.audit"));
      return 0;
    } catch (IllegalArgumentException | IllegalStateException rejected) {
      source.sendFailure(Component.translatable("command.factorycore.recovery.cell.refused"));
      return 0;
    }
  }
}
