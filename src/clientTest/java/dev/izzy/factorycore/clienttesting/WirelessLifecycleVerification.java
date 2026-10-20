package dev.izzy.factorycore.clienttesting;

import static dev.izzy.factorycore.clienttesting.RestartCheckpoint.require;

import dev.izzy.factorycore.core.network.*;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.storage.*;
import dev.izzy.factorycore.platform.FactoryCoreConfig;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.*;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.*;
import dev.izzy.factorycore.platform.wireless.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Actual endpoint chunk eviction: cached wireless geometry must never keep storage usable. */
final class WirelessLifecycleVerification {
  private static final BlockPos ROOT = new BlockPos(1792, 65, 1),
      FIRST_GATE = ROOT.east(),
      FIRST = ROOT.east(2),
      SECOND = FIRST.east(240),
      SECOND_GATE = SECOND.east(),
      DRIVE = SECOND.east(2);
  private static final UUID OWNER = UUID.fromString("0841e8bf-e600-4d1e-bc63-9262b015f10c");
  private final Path report = Path.of(System.getProperty("factorycore.wireless.report"));
  private ServerLevel level;
  private LinkEndpointEntity first, second;
  private StorageDriveEntity drive;
  private DeviceRegistry.Handle cell;
  private ResourceKey key;
  private int started, phase, offlineSince, unloads;
  private CompoundTag canonical;

  void register() {
    NeoForge.EVENT_BUS.addListener(this::started);
    NeoForge.EVENT_BUS.addListener(this::unloaded);
    NeoForge.EVENT_BUS.addListener(this::tick);
  }

  private void unloaded(ChunkEvent.Unload event) {
    if (event.getLevel() == level
        && event.getChunk().getPos().x == SECOND.getX() >> 4
        && event.getChunk().getPos().z == 0) unloads++;
  }

  private void started(ServerStartedEvent event) {
    level = event.getServer().overworld();
    started = event.getServer().getTickCount();
    level.setChunkForced(ROOT.getX() >> 4, 0, true);
    level.setChunkForced(SECOND.getX() >> 4, 0, true);
    level.getChunk(ROOT);
    level.getChunk(SECOND);
    level.setBlock(ROOT, NetworkContent.CONTROLLER.get().defaultBlockState(), 3);
    ((NetworkNodeEntity) level.getBlockEntity(ROOT)).commission(OWNER);
    for (var gate : new BlockPos[] {FIRST_GATE, SECOND_GATE}) {
      level.setBlock(
          gate,
          NetworkContent.GATEWAY
              .get()
              .defaultBlockState()
              .setValue(BlockStateProperties.FACING, Direction.EAST),
          3);
      ((NetworkNodeEntity) level.getBlockEntity(gate)).commission(null);
    }
    for (var end : new BlockPos[] {FIRST, SECOND}) {
      level.setBlock(
          end,
          WirelessContent.LINK
              .get()
              .defaultBlockState()
              .setValue(
                  BlockStateProperties.FACING, end.equals(FIRST) ? Direction.EAST : Direction.WEST),
          3);
    }
    first = (LinkEndpointEntity) level.getBlockEntity(FIRST);
    second = (LinkEndpointEntity) level.getBlockEntity(SECOND);
    // Restore normal saved pairing rather than using a synthetic player session.
    for (var end : new LinkEndpointEntity[] {first, second}) {
      var tag = end.saveWithoutMetadata(level.registryAccess());
      tag.putUUID("Owner", OWNER);
      tag.putLong("Partner", (end == first ? SECOND : FIRST).asLong());
      end.loadWithComponents(tag, level.registryAccess());
      end.setChanged();
    }
    level.setBlock(DRIVE, StorageContent.DRIVE.get().defaultBlockState(), 3);
    drive = (StorageDriveEntity) level.getBlockEntity(DRIVE);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    cell = CellHandleData.read(drive.cell(0));
    key =
        new PlatformResourceCodec(level.registryAccess()).itemKey(new ItemStack(Items.IRON_INGOT));
  }

  private NodeRegistry.Position position(BlockPos block) {
    return Positions.of(level, block);
  }

  private void tick(ServerTickEvent.Post event) {
    if (level == null || phase == 4) return;
    try {
      int now = event.getServer().getTickCount();
      var runtime = WorldNetworks.get(event.getServer());
      var saved = DeviceSavedData.get(event.getServer());
      require(
          now - started < 1000,
          "Wireless chunk lifecycle deadline phase="
              + phase
              + " child="
              + runtime.membership(position(DRIVE)).status()
              + " unloads="
              + unloads);
      if (first.current()) first.energyStorage().receiveEnergy(1_000, false);
      if (second.current()) second.energyStorage().receiveEnergy(1_000, false);
      if (phase == 0) {
        if (Boolean.getBoolean("factorycore.wireless.depthLimited")) {
          if (runtime.membership(position(DRIVE)).status() != NetworkTopology.Status.DEPTH_LIMIT)
            return;
          require(
              FactoryCoreConfig.gatewayDepth() == 1,
              "The real world server config loaded its nondefault gateway depth");
          require(
              !saved.registry().stock(cell, key).loaded(),
              "The second nested gateway keeps its cell unavailable under configured depth one");
          Files.createDirectories(report.getParent());
          Files.writeString(
              report,
              "PASS\nConfigured gateway depth 1 rejects the two-gateway wireless branch as DEPTH_LIMIT.\n");
          phase = 4;
          event.getServer().halt(false);
          return;
        }
        if (runtime.membership(position(DRIVE)).status() != NetworkTopology.Status.VALID) return;
        require(
            saved.registry().insert(cell, key, 24).amount() == 24
                && saved.registry().reserve(cell, OWNER, key, 4).amount() == 4,
            "Wireless child owns exact stock and hold before unload");
        canonical = saved.save(new CompoundTag(), level.registryAccess());
        level.setChunkForced(SECOND.getX() >> 4, 0, false);
        phase = 1;
        System.out.println("FACTORYCORE_WIRELESS_RELEASE tick=" + now);
        return;
      }
      if (phase == 1) {
        if (level.getChunkSource().getChunkNow(SECOND.getX() >> 4, 0) != null
            || unloads == 0
            || !second.isRemoved()) return;
        offlineSince = now;
        phase = 2;
      }
      if (phase == 2) {
        require(
            first.current() && !second.current() && drive.isRemoved(),
            "One wireless endpoint remains physically loaded while its partner is removed");
        require(
            level.getChunkSource().getChunkNow(SECOND.getX() >> 4, 0) == null,
            "Production discovery never loads the remote endpoint chunk");
        var stock = saved.registry().stock(cell, key);
        require(
            !stock.loaded()
                && stock.total() == 24
                && stock.reserved() == 4
                && stock.available() == 0
                && saved.registry().extract(cell, key, 1, OWNER).reason()
                    == OperationResult.Reason.OFFLINE,
            "Cached remote stock and reservations cannot serve an unloaded wireless branch");
        require(
            canonical.equals(saved.save(new CompoundTag(), level.registryAccess())),
            "Wireless unload changes no canonical ownership or quantities");
        if (now - offlineSince < 40) return;
        level.setChunkForced(SECOND.getX() >> 4, 0, true);
        level.getChunk(SECOND);
        second = (LinkEndpointEntity) level.getBlockEntity(SECOND);
        phase = 3;
        return;
      }
      if (phase == 3) {
        if (runtime.membership(position(DRIVE)).status() != NetworkTopology.Status.VALID) return;
        var reloaded = (StorageDriveEntity) level.getBlockEntity(DRIVE);
        require(
            reloaded != drive
                && CellHandleData.read(reloaded.cell(0)).equals(cell)
                && SECOND.equals(first.partner())
                && FIRST.equals(second.partner()),
            "Reload restores the original cell lease and reciprocal pair");
        require(
            saved.registry().extract(cell, key, 4, OWNER).amount() == 4
                && saved.registry().stock(cell, key).total() == 20
                && saved.registry().stock(cell, key).reserved() == 0,
            "Revalidated wireless branch resumes exact reserved extraction");
        Files.createDirectories(report.getParent());
        Files.writeString(
            report,
            "PASS\nunloads="
                + unloads
                + " ticks="
                + (now - started)
                + "\nOne endpoint actually unloaded for 40 ticks; its partner stayed loaded.\n"
                + "No implicit chunk load; stock 24/hold 4 preserved; original pair/lease restored; extract 4 leaves 20.\n");
        phase = 4;
        event.getServer().halt(false);
      }
    } catch (Exception failure) {
      throw new IllegalStateException("Wireless endpoint lifecycle verification failed", failure);
    }
  }
}
