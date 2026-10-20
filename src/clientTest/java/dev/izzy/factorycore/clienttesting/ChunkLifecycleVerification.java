package dev.izzy.factorycore.clienttesting;

import static dev.izzy.factorycore.clienttesting.RestartCheckpoint.*;

import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Observe native distance-driven chunk lifecycle; never post synthetic chunk events or load probes.
 */
final class ChunkLifecycleVerification {
  private ServerLevel level;
  private StorageDriveEntity oldDrive;
  private EnergyBankEntity oldBank;
  private BufferedInterfaceEntity oldBuffer;
  private MachineInterfaceEntity oldMachine;
  private NetworkNodeEntity oldRoot;
  private DeviceRegistry.Handle item, fluid, energy;
  private IItemHandler oldItems;
  private ResourcePort oldPort;
  private WorldConnectors.Handle external;
  private ResourceKey key, water;
  private CompoundTag canonical;
  private int phase, started, offlineSince, unloads, loads;

  void register() {
    NeoForge.EVENT_BUS.addListener(this::unloaded);
    NeoForge.EVENT_BUS.addListener(this::loaded);
  }

  private void unloaded(ChunkEvent.Unload event) {
    if (event.getLevel() == level
        && event.getChunk().getPos().x == 0
        && event.getChunk().getPos().z == 0) unloads++;
  }

  private void loaded(ChunkEvent.Load event) {
    if (event.getLevel() == level
        && event.getChunk().getPos().x == 0
        && event.getChunk().getPos().z == 0) loads++;
  }

  boolean tick(ServerPlayer player) {
    var saved = DeviceSavedData.get(player.server);
    var runtime = WorldNetworks.get(player.server);
    int now = player.server.getTickCount();
    if (phase == 0) {
      level = player.serverLevel();
      oldDrive = (StorageDriveEntity) level.getBlockEntity(DRIVE);
      oldBank = (EnergyBankEntity) level.getBlockEntity(BANK);
      oldBuffer = (BufferedInterfaceEntity) level.getBlockEntity(BUFFER);
      oldMachine = (MachineInterfaceEntity) level.getBlockEntity(MACHINE);
      oldRoot = (NetworkNodeEntity) level.getBlockEntity(ROOT);
      item = CellHandleData.read(oldDrive.cell(0));
      fluid = CellHandleData.read(oldDrive.cell(1));
      energy = oldBank.handle();
      oldItems = oldBuffer.capability();
      external = runtime.connectors().bind(oldMachine).handle();
      require(
          oldItems != null && external != null, "Capture loaded buffer and machine capabilities");
      oldPort = runtime.connectors().port(external);
      var codec = new PlatformResourceCodec(player.registryAccess());
      key = codec.itemKey(player.getInventory().getItem(9));
      water = codec.fluidKey(new FluidStack(Fluids.WATER, 1));
      canonical = saved.save(new CompoundTag(), player.registryAccess());
      level.getGameRules().getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0, player.server);
      started = now;
      player.teleportTo(1024.5, 65, 1024.5);
      phase = 1;
      System.out.println("FACTORYCORE_CHUNK_LEAVE tick=" + now);
      return false;
    }
    require(now - started < 1000, "Native chunk lifecycle completes within1000 server ticks");
    if (phase == 1) {
      if (level.getChunkSource().getChunkNow(0, 0) != null
          || unloads == 0
          || !oldDrive.isRemoved()
          || !oldBank.isRemoved()
          || !oldBuffer.isRemoved()
          || !oldMachine.isRemoved()
          || !oldRoot.isRemoved()) return false;
      offlineSince = now;
      phase = 2;
      System.out.println("FACTORYCORE_CHUNK_UNLOADED tick=" + now + " events=" + unloads);
    }
    if (phase == 2) {
      require(
          level.getChunkSource().getChunkNow(0, 0) == null,
          "Device chunk remains genuinely unloaded");
      var stock = saved.registry().stock(item, key);
      require(
          !stock.loaded() && stock.total() == 3 && stock.available() == 0,
          "Offline item ownership retains3 but offers no availability");
      require(
          !saved.registry().stock(energy, ResourceKey.ENERGY).loaded(),
          "Unloaded bank offers no FE");
      var fluidStock = saved.registry().stock(fluid, water);
      require(
          !fluidStock.loaded()
              && fluidStock.total() == 1200
              && fluidStock.reserved() == 400
              && fluidStock.available() == 0,
          "Unloaded fluid cell retains stock/hold and offers no availability");
      require(
          saved.registry().extract(item, key, 1, null).reason() == OperationResult.Reason.OFFLINE,
          "Offline digital extraction rejects without mutation");
      require(oldBank.extractEnergy(1, false) == 0, "Cached removed bank cannot extract");
      require(oldItems.extractItem(0, 1, false).isEmpty(), "Cached removed buffer cannot extract");
      ItemStack sample = player.getInventory().getItem(9).copyWithCount(1);
      require(
          oldItems.insertItem(0, sample, false).getCount() == 1,
          "Cached removed buffer returns all insertion");
      require(
          oldPort.extract(key, 1) == 0
              && runtime.connectors().stock(external, key).reason()
                  == OperationResult.Reason.OFFLINE,
          "Cached machine port cannot mutate or observe offline provider");
      require(
          level.getChunkSource().getChunkNow(0, 0) == null,
          "Resource probes never reload the device chunk");
      require(
          canonical.equals(saved.save(new CompoundTag(), player.registryAccess())),
          "Offline probes preserve complete canonical ownership/accounting");
      if (now - offlineSince < 40) return false;
      player.teleportTo(4.5, 65, 4.5);
      phase = 3;
      System.out.println("FACTORYCORE_CHUNK_RETURN tick=" + now);
      return false;
    }
    if (phase == 3) {
      var chunk = level.getChunkSource().getChunkNow(0, 0);
      if (chunk == null || loads == 0) return false;
      var machine = (MachineInterfaceEntity) chunk.getBlockEntity(MACHINE);
      if (machine == null || !machine.canUse(player)) return false;
      var drive = (StorageDriveEntity) chunk.getBlockEntity(DRIVE);
      var bank = (EnergyBankEntity) chunk.getBlockEntity(BANK);
      var buffer = (BufferedInterfaceEntity) chunk.getBlockEntity(BUFFER);
      var root = (NetworkNodeEntity) chunk.getBlockEntity(ROOT);
      if (!saved.registry().stock(fluid, water).loaded()
          || !saved.registry().stock(energy, ResourceKey.ENERGY).loaded()) return false;
      require(
          drive != oldDrive
              && bank != oldBank
              && buffer != oldBuffer
              && machine != oldMachine
              && root != oldRoot,
          "Native reload constructs fresh physical holders");
      require(
          item.equals(CellHandleData.read(drive.cell(0)))
              && fluid.equals(CellHandleData.read(drive.cell(1)))
              && energy.equals(bank.handle())
              && oldBuffer.handle().equals(buffer.handle())
              && oldRoot.handle().equals(root.handle())
              && oldMachine.handle().equals(machine.handle()),
          "Reload preserves every canonical ownership lease");
      require(
          canonical.equals(saved.save(new CompoundTag(), player.registryAccess())),
          "Reload preserves complete canonical state");
      require(
          oldPort.extract(key, 1) == 0
              && oldBank.extractEnergy(1, false) == 0
              && oldItems.extractItem(0, 1, false).isEmpty(),
          "Old capability objects remain inert after new holders bind");
      var rebound = runtime.connectors().bind(machine).handle();
      require(
          rebound != null
              && rebound.backing().equals(external.backing())
              && machine.presetAuthorized(saved.machinePresets().get(machine.handle().id())),
          "Reload revalidates persistent provider backing and original preset authority");
      var chest = (ChestBlockEntity) chunk.getBlockEntity(MACHINE.south());
      require(
          chest.getItem(2).getCount() == 8
              && buffer.inventory().total(key) == 9
              && saved.registry().stock(item, key).total() == 3
              && bank.getEnergyStored() == 500,
          "Physical resources survive native chunk serialization and reconstruction");
      var removed = chest.removeItem(2, 1);
      require(
          removed.getCount() == 1
              && player.getInventory().getItem(10).getCount() == 3
              && new PlatformResourceCodec(player.registryAccess()).itemKey(removed).equals(key)
              && ItemStack.isSameItemSameComponents(removed, player.getInventory().getItem(10)),
          "Remove one real item after reload");
      chest.setChanged();
      player.getInventory().getItem(10).grow(1);
      player.getInventory().setChanged();
      player.inventoryMenu.broadcastChanges();
      phase = 4;
      System.out.println("FACTORYCORE_CHUNK_RELOADED tick=" + now + " events=" + loads);
      return false;
    }
    var chunk = level.getChunkSource().getChunkNow(0, 0);
    require(chunk != null, "Player keeps returned device chunk loaded");
    var chest = (ChestBlockEntity) chunk.getBlockEntity(MACHINE.south());
    if (chest.getItem(2).getCount() != 8) return false;
    var buffer = (BufferedInterfaceEntity) chunk.getBlockEntity(BUFFER);
    require(
        saved.registry().stock(item, key).total() == 2
            && buffer.inventory().total(key) == 9
            && player.getInventory().getItem(9).getCount() == 2
            && player.getInventory().getItem(10).getCount() == 4,
        "After chunk reload saved Stock resumes and conserves cell2 + machine8 + buffer9 + player6 =25");
    System.out.println(
        "FACTORYCORE_CHUNK_PASS ticks="
            + (now - started)
            + " unloads="
            + unloads
            + " loads="
            + loads);
    return true;
  }
}
