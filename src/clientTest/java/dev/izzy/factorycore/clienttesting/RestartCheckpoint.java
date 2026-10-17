package dev.izzy.factorycore.clienttesting;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.storage.*;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.*;
import java.io.*;
import java.nio.file.*;
import java.util.Properties;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

/** Test-only expected state captured before normal save, never used to restore world authority. */
final class RestartCheckpoint {
  static final BlockPos ROOT = new BlockPos(1, 65, 1),
      DRIVE = new BlockPos(2, 65, 1),
      BUFFER = new BlockPos(3, 65, 1),
      MACHINE = new BlockPos(2, 65, 2),
      BANK = new BlockPos(3, 65, 2);
  private boolean installed;

  @SuppressWarnings("PMD.CloseResource") // Minecraft owns the player's live level.
  boolean prepare(ServerPlayer player, Path path) throws IOException {
    var level = player.serverLevel();
    var saved = DeviceSavedData.get(player.server);
    var drive = (StorageDriveEntity) level.getBlockEntity(DRIVE);
    if (!installed) {
      require(
          drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_FLUID))) == 1,
          "Fresh fluid fixture occupies drive slot1");
      level.setBlock(BANK, EnergyBankContent.block(EnergyBankTier.STARTER).defaultBlockState(), 3);
      var bank = (EnergyBankEntity) level.getBlockEntity(BANK);
      bank.place(new ItemStack(EnergyBankContent.item(EnergyBankTier.STARTER)));
      installed = true;
      return false;
    }
    var codec = new PlatformResourceCodec(player.registryAccess());
    var water = codec.fluidKey(new FluidStack(Fluids.WATER, 1));
    var lava = codec.fluidKey(new FluidStack(Fluids.LAVA, 1));
    var fluid = CellHandleData.read(drive.cell(1));
    var machine = (MachineInterfaceEntity) level.getBlockEntity(MACHINE);
    var bank = (EnergyBankEntity) level.getBlockEntity(BANK);
    if (!machine.canUse(player)
        || !saved.registry().stock(fluid, water).loaded()
        || !saved.registry().stock(bank.handle(), ResourceKey.ENERGY).loaded()) return false;
    require(bank.receiveEnergy(500, false) == 500, "Finite bank owns500 real FE");
    require(saved.registry().insert(fluid, water, 1200).amount() == 1200, "Own1200mB water");
    require(saved.registry().insert(fluid, lava, 300).amount() == 300, "Own300mB lava");
    require(
        saved.registry().reserve(fluid, player.getUUID(), water, 400).amount() == 400,
        "Persist a principal-owned400mB hold");
    var root = (NetworkNodeEntity) level.getBlockEntity(ROOT);
    var actor = new NetworkPermissions.Actor(player.getUUID(), false);
    var bound = WorldNetworks.get(player.server).connectors().bind(machine);
    require(bound.handle() != null, "Live provider backing before save");
    var expected = new Properties();
    expected.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
    expected.setProperty("principal", player.getUUID().toString());
    expected.setProperty("world", saved.registry().world().toString());
    expected.setProperty("root", root.handle().id().toString());
    expected.setProperty("machine", machine.handle().id().toString());
    expected.setProperty("backing", bound.handle().backing().toString());
    expected.setProperty("configuration", Long.toString(machine.generation()));
    require(
        saved.network(root.handle().id()).snapshot().owner().equals(actor.id()),
        "Owner before save");
    expected.setProperty(
        "canonical", saved.save(new CompoundTag(), player.registryAccess()).toString());
    try (var output = Files.newOutputStream(path)) {
      expected.store(output, "Expected observation only; reopening never installs these values");
    }
    System.out.println("FACTORYCORE_RESTART_CHECKPOINT seedPid=" + ProcessHandle.current().pid());
    return true;
  }

  static Properties read(Path path) throws IOException {
    var properties = new Properties();
    try (var input = Files.newInputStream(path)) {
      properties.load(input);
    }
    return properties;
  }

  static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }
}
