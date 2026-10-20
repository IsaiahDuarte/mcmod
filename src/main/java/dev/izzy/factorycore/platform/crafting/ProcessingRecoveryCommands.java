package dev.izzy.factorycore.platform.crafting;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.izzy.factorycore.core.crafting.CraftingJob;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.Loaded;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.storage.DeviceRecoveryAudit;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Operator inspection and resolution of processing dispatch journals (ADR 0057). Resolution writes
 * before-state evidence, then either makes the saved job record authoritative for the machine or
 * removes a journal no saved job dispatches to. It never touches machine contents, job staging,
 * reservations or storage, never loads a chunk and allows one resolution per second per server.
 */
public final class ProcessingRecoveryCommands {
  private static final Map<MinecraftServer, Integer> LAST = new WeakHashMap<>();

  private ProcessingRecoveryCommands() {}

  public static LiteralArgumentBuilder<CommandSourceStack> commands() {
    return Commands.literal("processing")
        .requires(source -> source.hasPermission(Actors.OPERATOR_LEVEL))
        .then(
            Commands.literal("show")
                .then(
                    Commands.argument("machine", BlockPosArgument.blockPos())
                        .executes(
                            c ->
                                run(
                                    c.getSource(),
                                    BlockPosArgument.getBlockPos(c, "machine"),
                                    false))))
        .then(
            Commands.literal("resolve")
                .then(
                    Commands.argument("machine", BlockPosArgument.blockPos())
                        .executes(
                            c ->
                                run(
                                    c.getSource(),
                                    BlockPosArgument.getBlockPos(c, "machine"),
                                    true))));
  }

  private static void fail(CommandSourceStack source, String reason) {
    source.sendFailure(Component.translatable("command.factorycore.recovery.processing." + reason));
  }

  private static int run(CommandSourceStack source, BlockPos at, boolean resolve) {
    var server = source.getServer();
    var level = source.getLevel();
    var saved = DeviceSavedData.get(server);
    if (!saved.diagnostic().isEmpty()) {
      source.sendFailure(Component.translatable("command.factorycore.recovery.unavailable"));
      return 0;
    }
    var chunk = Loaded.chunk(level, at);
    if (chunk == null) {
      fail(source, "unloaded");
      return 0;
    }
    var machine = chunk.getBlockEntity(at, LevelChunk.EntityCreationType.CHECK);
    var target = Positions.of(level, at);
    CraftingJob owner = null;
    for (var job : saved.craftingJobs().all())
      if (job.dispatch() != null && target.equals(job.dispatch().target())) owner = job;
    var raw = machine == null ? null : ProcessingJournal.raw(machine);
    if (machine == null || raw == null && owner == null) {
      fail(source, "none");
      return 0;
    }
    if (raw == null) {
      // A chunk saved after the dispatch finished (or before it began) carries no journal while
      // the saved job still claims progress there; record that absence as the evidence.
      var absent = new CompoundTag();
      absent.putBoolean("Absent", true);
      raw = absent;
    }
    var world = saved.registry().world();
    ProcessingJournal.Entry entry = null;
    try {
      entry = ProcessingJournal.read(machine, world);
    } catch (IllegalArgumentException unreadable) {
      source.sendFailure(Component.literal(unreadable.getMessage()));
    }
    if (entry != null) {
      boolean matches =
          owner != null && ProcessingJournal.check(entry, owner.id(), owner.dispatch()) == null;
      var row = entry;
      source.sendSuccess(
          () ->
              Component.translatable(
                  "command.factorycore.recovery.processing.row",
                  row.job().toString(),
                  row.pending(),
                  row.inserted(),
                  row.collected(),
                  matches),
          false);
    }
    if (owner == null)
      source.sendSuccess(
          () -> Component.translatable("command.factorycore.recovery.processing.orphan"), false);
    else {
      var job = owner;
      source.sendSuccess(
          () ->
              Component.translatable(
                  "command.factorycore.recovery.processing.job",
                  job.id().toString(),
                  job.state().name(),
                  job.reason(),
                  job.dispatch().inserted().toString(),
                  job.dispatch().collected().toString(),
                  job.staged().toString()),
          false);
    }
    source.sendSuccess(
        () -> Component.translatable("command.factorycore.recovery.processing.help"), false);
    if (!resolve) return 1;
    int now = server.getTickCount();
    Integer last = LAST.get(server);
    if (last != null && now - last < 20) {
      source.sendFailure(Component.translatable("command.factorycore.recovery.rate"));
      return 0;
    }
    var named = owner != null ? owner.id() : entry != null ? entry.job() : new UUID(0L, 0L);
    Path evidence;
    try {
      evidence =
          DeviceRecoveryAudit.preserveProcessingJournal(
              server.getWorldPath(LevelResource.ROOT),
              world,
              named,
              raw,
              owner == null ? null : owner.snapshot(),
              target,
              NbtUtils.writeBlockState(machine.getBlockState()),
              machine.saveWithFullMetadata(level.registryAccess()));
    } catch (IOException | RuntimeException failure) {
      fail(source, "refused");
      return 0;
    }
    LAST.put(server, now);
    if (owner != null) ProcessingJournal.write(machine, world, owner.id(), owner.dispatch(), false);
    else ProcessingJournal.clear(machine);
    var file = evidence.getFileName().toString();
    source.sendSuccess(
        () ->
            Component.translatable(
                "command.factorycore.recovery.processing.resolved",
                at.toShortString(),
                named.toString(),
                file),
        true);
    return 1;
  }
}
