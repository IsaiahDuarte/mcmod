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

/** Explicit operator selection of the existing canonical storage holder; no chunk loading. */
final class DeviceRecoveryCommands {
  private static final Map<MinecraftServer, Integer> REPAIRS = new WeakHashMap<>();

  private DeviceRecoveryCommands() {}

  static void stopped(MinecraftServer server) {
    REPAIRS.remove(server);
  }

  static LiteralArgumentBuilder<CommandSourceStack> commands(boolean bank) {
    var root = commands(bank, Commands.literal(bank ? "bank" : "cell"));
    if (bank)
      return root.then(
          Commands.literal("adopt")
              .then(
                  Commands.argument("backing", StringArgumentType.word())
                      .then(
                          Commands.argument("generation", LongArgumentType.longArg(1))
                              .then(
                                  Commands.argument(
                                          "bank",
                                          net.minecraft.commands.arguments.coordinates
                                              .BlockPosArgument.blockPos())
                                      .executes(
                                          c ->
                                              adopt(
                                                  c.getSource(),
                                                  StringArgumentType.getString(c, "backing"),
                                                  LongArgumentType.getLong(c, "generation"),
                                                  net.minecraft.commands.arguments.coordinates
                                                      .BlockPosArgument.getBlockPos(c, "bank"),
                                                  -1))))));
    return root.then(
        Commands.literal("adopt")
            .then(
                Commands.argument("backing", StringArgumentType.word())
                    .then(
                        Commands.argument("generation", LongArgumentType.longArg(1))
                            .then(
                                Commands.argument(
                                        "drive",
                                        net.minecraft.commands.arguments.coordinates
                                            .BlockPosArgument.blockPos())
                                    .then(
                                        Commands.argument(
                                                "slot",
                                                com.mojang.brigadier.arguments.IntegerArgumentType
                                                    .integer(0, 3))
                                            .executes(
                                                c ->
                                                    adopt(
                                                        c.getSource(),
                                                        StringArgumentType.getString(c, "backing"),
                                                        LongArgumentType.getLong(c, "generation"),
                                                        net.minecraft.commands.arguments.coordinates
                                                            .BlockPosArgument.getBlockPos(
                                                            c, "drive"),
                                                        com.mojang.brigadier.arguments
                                                            .IntegerArgumentType.getInteger(
                                                            c, "slot"))))))));
  }

  /**
   * Operator reconciliation of an independent-save mismatch: adopt the loaded drive slot's (or,
   * with {@code slot < 0}, energy bank's) reference in the source's dimension as the canonical
   * holder. All proof is server-side.
   */
  @SuppressWarnings("PMD.CloseResource") // Minecraft owns the borrowed server and level.
  private static int adopt(
      CommandSourceStack source, String backing, long generation, BlockPos drive, int slot) {
    try {
      var server = source.getServer();
      var owner = DeviceSavedData.get(server).registry().ownership(parse(backing));
      boolean bank = slot < 0;
      if (owner == null || !matches(owner, bank) || owner.handle().generation() != generation)
        throw new IllegalStateException("Canonical lease changed");
      var level = source.getLevel();
      if (level.getChunkSource().getChunkNow(drive.getX() >> 4, drive.getZ() >> 4) == null)
        throw new IllegalStateException("Adopting holder unavailable");
      var entity = level.getBlockEntity(drive);
      if (!(bank ? entity instanceof EnergyBankEntity : entity instanceof StorageDriveEntity))
        throw new IllegalStateException("Adopting holder has the wrong kind");
      if (!admitRepair(source)) return 0;
      var path =
          bank
              ? ((EnergyBankEntity) entity).adoptBank(owner.handle())
              : ((StorageDriveEntity) entity).adoptCell(owner.handle(), slot);
      source.sendSuccess(
          () ->
              Component.translatable(
                  "command.factorycore.recovery." + (bank ? "bank" : "cell") + ".adopted",
                  backing,
                  drive.toShortString(),
                  slot,
                  generation + 1,
                  path.getFileName().toString()),
          true);
      return 1;
    } catch (IOException auditFailure) {
      source.sendFailure(Component.translatable("command.factorycore.recovery.audit"));
      return 0;
    } catch (IllegalArgumentException | IllegalStateException rejected) {
      source.sendFailure(
          Component.translatable(
              "command.factorycore.recovery." + (slot < 0 ? "bank" : "cell") + ".adopt_refused"));
      return 0;
    }
  }

  private static LiteralArgumentBuilder<CommandSourceStack> commands(
      boolean bank, LiteralArgumentBuilder<CommandSourceStack> root) {
    return root.requires(source -> source.hasPermission(2))
        .then(
            Commands.literal("list")
                .executes(c -> list(c.getSource(), null, bank))
                .then(
                    Commands.argument("after", StringArgumentType.word())
                        .executes(
                            c ->
                                list(
                                    c.getSource(),
                                    StringArgumentType.getString(c, "after"),
                                    bank))))
        .then(
            Commands.literal("show")
                .then(
                    Commands.argument("backing", StringArgumentType.word())
                        .executes(
                            c ->
                                inspect(
                                    c.getSource(),
                                    StringArgumentType.getString(c, "backing"),
                                    0,
                                    bank))))
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
                                            LongArgumentType.getLong(c, "generation"),
                                            bank)))));
  }

  @SuppressWarnings("PMD.CloseResource") // Minecraft owns the borrowed server.
  static boolean admitRepair(CommandSourceStack source) {
    var server = source.getServer();
    var previous = REPAIRS.get(server);
    if (previous != null && server.getTickCount() - previous < 20) {
      source.sendFailure(Component.translatable("command.factorycore.recovery.rate"));
      return false;
    }
    REPAIRS.put(server, server.getTickCount());
    return true;
  }

  private static boolean matches(DeviceRegistry.Ownership owner, boolean bank) {
    return (owner.definition().kind() == dev.izzy.factorycore.core.resource.ResourceKind.ENERGY)
        == bank;
  }

  private static UUID parse(String value) {
    if (value.length() != 36) throw new IllegalArgumentException("UUID length");
    return UUID.fromString(value);
  }

  private static Component describe(DeviceRegistry.Ownership owner) {
    var location = owner.definition().location();
    return Component.translatable(
        "command.factorycore.recovery.device.row",
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

  private static int list(CommandSourceStack source, String after, boolean bank) {
    try {
      var registry = DeviceSavedData.get(source.getServer()).registry();
      var page = registry.handles(after == null ? null : parse(after), 16);
      int shown = 0;
      for (var handle : page.handles()) {
        var owner = registry.ownership(handle.backing());
        if (matches(owner, bank)) {
          source.sendSuccess(() -> describe(owner), false);
          shown++;
        }
      }
      int count = shown;
      source.sendSuccess(
          () ->
              Component.translatable(
                  "command.factorycore.recovery.page",
                  count,
                  page.next() == null ? "—" : page.next().toString()),
          false);
      return count;
    } catch (IllegalArgumentException | IllegalStateException rejected) {
      source.sendFailure(Component.translatable("command.factorycore.recovery.device.refused"));
      return 0;
    }
  }

  @SuppressWarnings("PMD.CloseResource") // Minecraft owns the borrowed server and level.
  private static int inspect(
      CommandSourceStack source, String backing, long generation, boolean bank) {
    try {
      var server = source.getServer();
      var saved = DeviceSavedData.get(server);
      var owner = saved.registry().ownership(parse(backing));
      if (owner == null || !matches(owner, bank))
        throw new IllegalArgumentException("Unknown storage kind");
      source.sendSuccess(() -> describe(owner), false);
      if (generation == 0) {
        source.sendSuccess(
            () ->
                Component.translatable(
                    "command.factorycore.recovery." + (bank ? "bank" : "cell") + ".help"),
            false);
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
          || level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null)
        throw new IllegalStateException("Canonical holder unavailable");
      if (!admitRepair(source)) return 0;
      var holder = level.getBlockEntity(pos);
      var path =
          bank && holder instanceof EnergyBankEntity energyBank
              ? energyBank.repairConflict(owner.handle())
              : !bank && holder instanceof StorageDriveEntity drive
                  ? drive.repairCellConflict(owner.handle())
                  : null;
      if (path == null) throw new IllegalStateException("Wrong canonical holder kind");
      source.sendSuccess(
          () ->
              Component.translatable(
                  "command.factorycore.recovery." + (bank ? "bank" : "cell") + ".repaired",
                  backing,
                  generation + 1,
                  path.getFileName().toString()),
          true);
      return 1;
    } catch (IOException auditFailure) {
      source.sendFailure(Component.translatable("command.factorycore.recovery.audit"));
      return 0;
    } catch (IllegalArgumentException | IllegalStateException rejected) {
      source.sendFailure(Component.translatable("command.factorycore.recovery.device.refused"));
      return 0;
    }
  }
}
