package dev.izzy.factorycore.clienttesting;

import static dev.izzy.factorycore.clienttesting.RestartCheckpoint.require;

import dev.izzy.factorycore.core.network.*;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.storage.*;
import dev.izzy.factorycore.platform.network.*;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.*;
import java.util.List;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;

/** Keep a real child chunk loaded administratively while its upstream root unloads naturally. */
final class SplitRootVerification {
  private static final BlockPos ROOT = new BlockPos(1791, 65, 1),
      GATEWAY = new BlockPos(2064, 65, 1),
      DRIVE = GATEWAY.east(),
      MACHINE = DRIVE.east(),
      CHEST = MACHINE.south();
  private ServerLevel level;
  private NetworkNodeEntity root, gateway;
  private StorageDriveEntity drive;
  private MachineInterfaceEntity machine;
  private DeviceRegistry.Handle cell;
  private ResourceKey key;
  private ResourcePort oldPort;
  private CompoundTag canonical;
  private int phase, started, offlineSince, unloads;

  void register() {
    NeoForge.EVENT_BUS.addListener(this::unloaded);
  }

  private void unloaded(ChunkEvent.Unload event) {
    if (event.getLevel() == level
        && event.getChunk().getPos().x == 111
        && event.getChunk().getPos().z == 0) unloads++;
  }

  @SuppressWarnings("PMD.CloseResource") // Minecraft owns this fixture's world.
  boolean tick(ServerPlayer player) {
    var saved = DeviceSavedData.get(player.server);
    var runtime = WorldNetworks.get(player.server);
    var actor = new NetworkPermissions.Actor(player.getUUID(), false);
    int now = player.server.getTickCount();
    if (phase == 0) {
      level = player.serverLevel();
      started = now;
      // Explicit fixture setup loading is separate from nonloading production discovery.
      level.getChunk(ROOT);
      level.getChunk(GATEWAY);
      level.setChunkForced(129, 0, true);
      level.setChunkForced(111, 0, true);
      player.teleportTo(1920.5, 65, 4.5);
      level.setBlock(ROOT, NetworkContent.CONTROLLER.get().defaultBlockState(), 3);
      root = (NetworkNodeEntity) level.getBlockEntity(ROOT);
      root.commission(player.getUUID());
      // Forced chunks also retain generation dependencies; separate the root by eighteen chunks.
      for (int x = 1792; x < 2064; x++)
        level.setBlock(new BlockPos(x, 65, 1), NetworkContent.CABLE.get().defaultBlockState(), 3);
      level.setBlock(
          GATEWAY,
          NetworkContent.GATEWAY
              .get()
              .defaultBlockState()
              .setValue(BlockStateProperties.FACING, Direction.EAST),
          3);
      gateway = (NetworkNodeEntity) level.getBlockEntity(GATEWAY);
      gateway.commission(null);
      level.setBlock(DRIVE, StorageContent.DRIVE.get().defaultBlockState(), 3);
      drive = (StorageDriveEntity) level.getBlockEntity(DRIVE);
      drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
      cell = CellHandleData.read(drive.cell(0));
      level.setBlock(
          MACHINE,
          MachineInterfaceContent.BLOCK
              .get()
              .defaultBlockState()
              .setValue(BlockStateProperties.FACING, Direction.SOUTH),
          3);
      machine = (MachineInterfaceEntity) level.getBlockEntity(MACHINE);
      machine.place(new ItemStack(MachineInterfaceContent.ITEM.get()));
      level.setBlock(CHEST, Blocks.CHEST.defaultBlockState(), 3);
      key =
          new PlatformResourceCodec(player.registryAccess())
              .itemKey(new ItemStack(Items.GOLD_INGOT));
      phase = 1;
      return false;
    }
    if ((phase == 1 || phase == 2) && (now - started) % 100 == 0)
      System.out.println(
          "FACTORYCORE_SPLIT_WAIT phase="
              + phase
              + " membership="
              + runtime.membership(machine.position()).status()
              + " stock="
              + saved.registry().stock(cell, key)
              + " preset="
              + runtime.machinePresets().status(machine)
              + " diagnostic="
              + runtime.diagnostic());
    require(
        now - started < 1000,
        "Split-root lifecycle deadline phase="
            + phase
            + " membership="
            + runtime.membership(machine.position()).status()
            + " preset="
            + runtime.machinePresets().status(machine)
            + " root="
            + runtime.membership(root.position()).status()
            + " gateway="
            + runtime.membership(gateway.position()).status()
            + " diagnostic="
            + runtime.diagnostic());
    if (phase == 1) {
      if (runtime.membership(machine.position()).status() != NetworkTopology.Status.VALID)
        return false;
      require(
          !runtime.membership(machine.position()).scope().segment().equals(root.handle().id()),
          "Fixture machine is in a child segment");
      require(
          saved.registry().insert(cell, key, 24).amount() == 24
              && saved.registry().reserve(cell, player.getUUID(), key, 4).amount() == 4,
          "Own24 gold with a four-unit original-principal hold");
      machine.deployPreset(
          actor,
          MachinePresets.Mode.STOCK,
          new ResourceFilter(
              ResourceKind.ITEM,
              ResourceFilter.Mode.INCLUDE,
              List.of(new ResourceFilter.Exact(key))),
          8);
      phase = 2;
      return false;
    }
    if (phase == 2) {
      var chest = (ChestBlockEntity) level.getBlockEntity(CHEST);
      if (chest.getItem(0).getCount() != 8) return false;
      require(
          saved.registry().stock(cell, key).total() == 16,
          "Child-local Stock transfers eight actual gold");
      oldPort = runtime.connectors().port(runtime.connectors().bind(machine).handle());
      canonical = saved.save(new CompoundTag(), player.registryAccess());
      level.setChunkForced(111, 0, false);
      player.teleportTo(4096.5, 65, 4.5);
      phase = 3;
      System.out.println("FACTORYCORE_SPLIT_ROOT_LEAVE tick=" + now);
      return false;
    }
    if (phase == 3) {
      if (level.getChunkSource().getChunkNow(111, 0) != null || unloads == 0 || !root.isRemoved())
        return false;
      require(
          level.getChunkSource().getChunkNow(129, 0) != null
              && !gateway.isRemoved()
              && !drive.isRemoved()
              && !machine.isRemoved(),
          "Child devices stay physically loaded while root is actually removed");
      offlineSince = now;
      var chest =
          (ChestBlockEntity) level.getChunkSource().getChunkNow(129, 0).getBlockEntity(CHEST);
      require(player.getInventory().getItem(11).isEmpty(), "Empty slot receives three actual gold");
      var removed = chest.removeItem(0, 3);
      require(
          removed.getCount() == 3
              && new PlatformResourceCodec(player.registryAccess()).itemKey(removed).equals(key),
          "Remove real matching gold while root is offline");
      player.getInventory().setItem(11, removed);
      chest.setChanged();
      phase = 4;
      System.out.println("FACTORYCORE_SPLIT_ROOT_UNLOADED tick=" + now);
    }
    if (phase == 4) {
      require(
          level.getChunkSource().getChunkNow(111, 0) == null
              && level.getChunkSource().getChunkNow(129, 0) != null,
          "Root stays absent while child stays loaded");
      var stock = saved.registry().stock(cell, key);
      require(
          !stock.loaded() && stock.total() == 16 && stock.reserved() == 4 && stock.available() == 0,
          "Root unload suspends loaded child storage without losing stock or holds");
      require(
          saved.registry().extract(cell, key, 1, player.getUUID()).reason()
                  == OperationResult.Reason.OFFLINE
              && oldPort.extract(key, 1) == 0,
          "Claims and cached machine ports cannot bypass an unloaded root");
      require(
          runtime.membership(machine.position()).status() != NetworkTopology.Status.VALID
              && !machine.presetAuthorized(saved.machinePresets().get(machine.handle().id())),
          "Root loss invalidates child scope and saved execution authority");
      var chest =
          (ChestBlockEntity) level.getChunkSource().getChunkNow(129, 0).getBlockEntity(CHEST);
      require(
          chest.getItem(0).getCount() == 5
              && canonical.equals(saved.save(new CompoundTag(), player.registryAccess())),
          "Loaded child never replenishes under root loss; canonical ownership stays unchanged");
      require(
          level.getChunkSource().getChunkNow(111, 0) == null, "Child probes never reload the root");
      if (now - offlineSince < 40) return false;
      player.teleportTo(1920.5, 65, 4.5);
      phase = 5;
      return false;
    }
    if (phase == 5) {
      var chunk = level.getChunkSource().getChunkNow(111, 0);
      if (chunk == null
          || runtime.membership(machine.position()).status() != NetworkTopology.Status.VALID)
        return false;
      var reloaded = (NetworkNodeEntity) chunk.getBlockEntity(ROOT);
      require(
          reloaded != root
              && reloaded.handle().equals(root.handle())
              && !gateway.isRemoved()
              && !drive.isRemoved()
              && !machine.isRemoved(),
          "Original root identity rejoins continuously loaded child holders");
      var chest =
          (ChestBlockEntity) level.getChunkSource().getChunkNow(129, 0).getBlockEntity(CHEST);
      if (chest.getItem(0).getCount() != 8) return false;
      require(
          saved.registry().stock(cell, key).total() == 13
              && saved.registry().stock(cell, key).reserved() == 4
              && player.getInventory().getItem(11).getCount() == 3
              && machine.presetAuthorized(saved.machinePresets().get(machine.handle().id())),
          "Revalidated original child Stock resumes: cell13 + machine8 + player3 =24, hold4 retained");
      level.setChunkForced(129, 0, false);
      player.teleportTo(4.5, 65, 4.5);
      phase = 6;
      System.out.println(
          "FACTORYCORE_SPLIT_ROOT_PASS ticks=" + (now - started) + " unloads=" + unloads);
      return false;
    }
    return level.getChunkSource().getChunkNow(0, 0) != null;
  }
}
