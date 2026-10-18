package dev.izzy.factorycore.clienttesting;

import static dev.izzy.factorycore.clienttesting.RestartCheckpoint.require;

import com.mojang.authlib.GameProfile;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Independent-save interruption across two dedicated JVMs. The seed saves a consistent world, keeps
 * copies of factorycore_devices.dat and the region file, moves a 20-item cell from drive A to drive
 * B and saves again. Between JVMs the build restores one old file: {@code data-old} restores the
 * data file (chunks newer than SavedData: cell physically in B, lease still at A; adopt B); {@code
 * chunks-old} restores the region file (SavedData newer than chunks: cell physically in A, lease
 * already at B; adopt A).
 */
final class SaveOrderVerification {
  private static final BlockPos ROOT = new BlockPos(9, 65, 9), A = ROOT.east(), B = ROOT.west();
  private static final ResourceKey IRON =
      new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[0]);
  private final boolean restoring = Boolean.getBoolean("factorycore.saveorder.restore");
  private final boolean chunksOld =
      "chunks-old".equals(System.getProperty("factorycore.saveorder.order", "data-old"));
  private final Path report = Path.of(System.getProperty("factorycore.saveorder.report"));
  private final Path observation = Path.of(System.getProperty("factorycore.saveorder.expected"));
  private ServerPlayer principal;
  private Properties expected = new Properties();
  private int started, phase;
  private DeviceRegistry.Handle cell;

  void register() {
    NeoForge.EVENT_BUS.addListener(this::started);
    NeoForge.EVENT_BUS.addListener(this::tick);
  }

  @SuppressWarnings("PMD.CloseResource") // Server owns the level and fake-player service.
  private void started(ServerStartedEvent event) {
    try {
      Files.createDirectories(report.getParent());
      var level = event.getServer().overworld();
      level.setChunkForced(0, 0, true);
      principal =
          FakePlayerFactory.get(
              level,
              new GameProfile(
                  UUID.fromString("6f0a7c55-2f6e-4d0a-9b8e-3b1d5e2c7a10"), "save_order_probe"));
      started = event.getServer().getTickCount();
      if (restoring) expected = RestartCheckpoint.read(observation);
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
  }

  private static Path data(MinecraftServer server) {
    return server.getWorldPath(LevelResource.ROOT).resolve("data/" + DeviceSavedData.NAME + ".dat");
  }

  private boolean valid(MinecraftServer server, BlockPos position) {
    return WorldNetworks.get(server)
            .membership(
                new NodeRegistry.Position(
                    principal.serverLevel().dimension().location().toString(), position.asLong()))
            .status()
        == NetworkTopology.Status.VALID;
  }

  @SuppressWarnings("PMD.CloseResource") // Borrowed Minecraft world.
  private void tick(ServerTickEvent.Post event) {
    if (principal == null || phase < 0) return;
    var server = event.getServer();
    try {
      require(server.getTickCount() - started < 600, "Save-order probe deadline");
      var level = principal.serverLevel();
      var registry = DeviceSavedData.get(server).registry();
      if (restoring) {
        restore(server, level, registry);
        return;
      }
      switch (phase) {
        case 0 -> {
          level.setBlock(ROOT, NetworkContent.CONTROLLER.get().defaultBlockState(), 3);
          ((NetworkNodeEntity) level.getBlockEntity(ROOT)).commission(principal.getUUID());
          level.setBlock(A, StorageContent.DRIVE.get().defaultBlockState(), 3);
          level.setBlock(B, StorageContent.DRIVE.get().defaultBlockState(), 3);
          var drive = (StorageDriveEntity) level.getBlockEntity(A);
          drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
          cell = CellHandleData.read(drive.cell(0));
          phase = 1;
        }
        case 1 -> {
          if (!valid(server, A) || !registry.stock(cell, IRON).loaded()) return;
          require(registry.insert(cell, IRON, 20).amount() == 20, "Seed 20 iron");
          require(server.saveEverything(true, true, true), "Consistent save");
          Files.copy(
              data(server),
              data(server).resolveSibling(DeviceSavedData.NAME + ".before"),
              StandardCopyOption.REPLACE_EXISTING);
          var region = server.getWorldPath(LevelResource.ROOT).resolve("region/r.0.0.mca");
          Files.copy(
              region, region.resolveSibling("r.0.0.before"), StandardCopyOption.REPLACE_EXISTING);
          var moved =
              ((StorageDriveEntity) level.getBlockEntity(B))
                  .installCell(((StorageDriveEntity) level.getBlockEntity(A)).removeCell(0));
          var after =
              CellHandleData.read(((StorageDriveEntity) level.getBlockEntity(B)).cell(moved));
          require(after.generation() == cell.generation() + 2, "Move rotates twice");
          require(server.saveEverything(true, true, true), "Second save");
          expected.setProperty("backing", cell.backing().toString());
          expected.setProperty("before", Long.toString(cell.generation()));
          expected.setProperty("after", Long.toString(after.generation()));
          try (var output = Files.newOutputStream(observation)) {
            expected.store(output, "Observations only; never installed into world authority");
          }
          finish(
              server,
              "seed",
              "20 iron in a cell at drive A saved; moved to drive B and saved again.");
        }
        default -> {}
      }
    } catch (Exception error) {
      throw new IllegalStateException("Save-order verification failed", error);
    }
  }

  @SuppressWarnings("PMD.CloseResource") // Borrowed Minecraft world.
  private void restore(
      MinecraftServer server, net.minecraft.server.level.ServerLevel level, DeviceRegistry registry)
      throws IOException, com.mojang.brigadier.exceptions.CommandSyntaxException {
    var backing = UUID.fromString(expected.getProperty("backing"));
    long before = Long.parseLong(expected.getProperty("before"));
    long after = Long.parseLong(expected.getProperty("after"));
    // The surviving drive holds the physical cell; the other is where the canonical lease points.
    var target = chunksOld ? A : B;
    var drive = (StorageDriveEntity) level.getBlockEntity(target);
    var origin = (StorageDriveEntity) level.getBlockEntity(chunksOld ? B : A);
    if (drive == null || origin == null || !valid(server, ROOT)) return;
    var owner = registry.ownership(backing);
    long canonical = chunksOld ? after : before;
    switch (phase) {
      case 0 -> {
        var physical = CellHandleData.read(drive.cell(0));
        require(
            physical != null && physical.generation() == (chunksOld ? before : after),
            "The surviving chunk holds the expected reference");
        require(origin.cell(0).isEmpty(), "The canonical holder's chunk holds no cell");
        require(
            owner.handle().generation() == canonical
                && owner.definition().location().equals(origin.location(0)),
            "Canonical data names the other drive");
        require(!drive.diagnostic().isEmpty(), "The surviving drive reports the stale reference");
        require(
            owner.total() == 20 && !registry.stock(owner.handle(), IRON).loaded(),
            "One ledger with exactly 20 iron, offline: nothing duplicated, nothing usable");
        var console = server.createCommandSourceStack();
        int adopted =
            server
                .getCommands()
                .getDispatcher()
                .execute(
                    "factorycore recovery cell adopt %s %d %d %d %d 0"
                        .formatted(backing, canonical, target.getX(), target.getY(), target.getZ()),
                    console);
        require(adopted == 1, "Operator adopts the surviving reference");
        phase = 1;
      }
      case 1 -> {
        var handle = CellHandleData.read(drive.cell(0));
        if (!valid(server, B) || !registry.stock(handle, IRON).loaded()) return;
        require(
            handle.generation() == canonical + 1
                && registry.definition(handle).location().equals(drive.location(0)),
            "Adopted lease lives at the surviving drive with one new generation");
        require(registry.extract(handle, IRON, 64, null).amount() == 20, "Exactly 20 iron, once");
        require(registry.stock(handle, IRON).total() == 0, "Nothing left over");
        require(
            Files.list(
                        server
                            .getWorldPath(LevelResource.ROOT)
                            .resolve("factorycore-device-recovery"))
                    .count()
                >= 1,
            "Adoption preserved before-state evidence");
        finish(
            server,
            "restore",
            (chunksOld
                    ? "New data/old chunk: lease at B, cell in A"
                    : "Old data/new chunk: lease at A, cell in B")
                + ", inert with exactly 20; adopt moved the lease to the surviving drive; 20"
                + " extracted once.");
      }
      default -> {}
    }
  }

  private void finish(MinecraftServer server, String mode, String detail) throws IOException {
    Files.writeString(
        report,
        "PASS\nmode=" + mode + " pid=" + ProcessHandle.current().pid() + "\n" + detail + "\n");
    System.out.println("FACTORYCORE_SAVE_ORDER_PASS mode=" + mode);
    phase = -1;
    server.halt(false);
  }
}
