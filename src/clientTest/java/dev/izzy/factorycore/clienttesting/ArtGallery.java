package dev.izzy.factorycore.clienttesting;

import dev.izzy.factorycore.core.storage.EnergyBankTier;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.power.FuelGeneratorBlock;
import dev.izzy.factorycore.platform.power.FuelGeneratorContent;
import dev.izzy.factorycore.platform.power.FuelGeneratorEntity;
import dev.izzy.factorycore.platform.program.ProgramContent;
import dev.izzy.factorycore.platform.storage.EnergyBankContent;
import dev.izzy.factorycore.platform.storage.InterfaceContent;
import dev.izzy.factorycore.platform.storage.InterfaceKind;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.TerminalContent;
import dev.izzy.factorycore.platform.wireless.WirelessContent;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * Art-inspection scene for the graphical client fixture: every Factory Core block in two rows, a
 * cable run with straight/corner/vertical/junction pieces and a sideways gateway, and a double
 * chest holding every Factory Core item. Placed far from the functional fixture so it cannot join
 * it.
 */
final class ArtGallery {
  record View(double x, double y, double z, float yaw, float pitch) {}

  static final View FRONT = new View(48.5, 67.5, 33.5, 0, 20);
  static final View TOP = new View(48.5, 73.5, 37.5, 0, 60);
  static final View CABLES = new View(47.5, 69.5, 53.5, 180, 40);
  static final View CHEST_VIEW = new View(38.5, 65, 28.5, 0, 30);
  static final BlockPos CHEST = new BlockPos(38, 65, 30);

  private ArtGallery() {}

  private static BlockState facing(BlockState state, Direction direction) {
    return state.setValue(BlockStateProperties.FACING, direction);
  }

  /** Front row (z=40) and back row (z=43); every device faces the viewer (north). */
  static int build(ServerLevel level) {
    for (int x = 36; x <= 62; x++)
      for (int z = 28; z <= 52; z++)
        level.setBlock(new BlockPos(x, 64, z), Blocks.SMOOTH_STONE.defaultBlockState(), 3);
    List<BlockState> front =
        List.of(
            NetworkContent.CONTROLLER.get().defaultBlockState(),
            StorageContent.DRIVE.get().defaultBlockState(),
            TerminalContent.BLOCK.get().defaultBlockState(),
            InterfaceContent.state(InterfaceKind.BUFFER, Direction.NORTH),
            InterfaceContent.state(InterfaceKind.STORAGE, Direction.NORTH),
            InterfaceContent.state(InterfaceKind.MACHINE, Direction.NORTH),
            facing(NetworkContent.GATEWAY.get().defaultBlockState(), Direction.NORTH),
            EnergyBankContent.block(EnergyBankTier.STARTER).defaultBlockState(),
            EnergyBankContent.block(EnergyBankTier.EXPANDED).defaultBlockState());
    List<BlockState> back =
        List.of(
            EnergyBankContent.block(EnergyBankTier.ADVANCED).defaultBlockState(),
            FuelGeneratorContent.BLOCK.get().defaultBlockState(),
            FuelGeneratorContent.BLOCK
                .get()
                .defaultBlockState()
                .setValue(FuelGeneratorBlock.LIT, true),
            CraftingContent.CRAFTER.get().defaultBlockState(),
            CraftingContent.CRAFTER.get().defaultBlockState(),
            ProgramContent.BLOCK.get().defaultBlockState(),
            WirelessContent.ACCESS_POINT.get().defaultBlockState(),
            facing(WirelessContent.LINK.get().defaultBlockState(), Direction.NORTH),
            NetworkContent.CABLE.get().defaultBlockState());
    int placed = 0;
    for (int i = 0; i < front.size(); i++) {
      level.setBlock(new BlockPos(40 + i * 2, 65, 40), front.get(i), 3);
      placed++;
    }
    for (int i = 0; i < back.size(); i++) {
      level.setBlock(new BlockPos(40 + i * 2, 65, 43), back.get(i), 3);
      placed++;
    }
    // Keep the lit generator burning for the capture.
    if (level.getBlockEntity(new BlockPos(44, 65, 43)) instanceof FuelGeneratorEntity generator)
      generator.slots().setStackInSlot(FuelGeneratorEntity.FUEL, new ItemStack(Items.COAL, 16));

    // Cable run: controller -> drive -> gateway (east-facing) -> terminal, plus a branch that
    // turns and climbs, and a cable touching the gateway's side (no arm: not a port).
    level.setBlock(
        new BlockPos(42, 65, 48), NetworkContent.CONTROLLER.get().defaultBlockState(), 3);
    level.setBlock(new BlockPos(47, 65, 48), StorageContent.DRIVE.get().defaultBlockState(), 3);
    level.setBlock(
        new BlockPos(50, 65, 48),
        facing(NetworkContent.GATEWAY.get().defaultBlockState(), Direction.EAST),
        3);
    level.setBlock(new BlockPos(52, 65, 48), TerminalContent.BLOCK.get().defaultBlockState(), 3);
    for (var cable :
        List.of(
            new BlockPos(43, 65, 48),
            new BlockPos(44, 65, 48),
            new BlockPos(45, 65, 48),
            new BlockPos(46, 65, 48),
            new BlockPos(48, 65, 48),
            new BlockPos(49, 65, 48),
            new BlockPos(51, 65, 48),
            new BlockPos(45, 65, 49),
            new BlockPos(45, 65, 50),
            new BlockPos(44, 65, 50),
            new BlockPos(44, 66, 50),
            new BlockPos(44, 67, 50),
            new BlockPos(50, 65, 49))) {
      var block = NetworkContent.CABLE.get();
      level.setBlock(cable, block.connected(level, cable), 3);
      placed++;
    }
    return placed;
  }

  /** A double chest holding one of every registered Factory Core item. */
  static int fillChest(ServerLevel level) {
    var chest = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
    level.setBlock(CHEST, chest.setValue(ChestBlock.TYPE, ChestType.LEFT), 3);
    level.setBlock(CHEST.east(), chest.setValue(ChestBlock.TYPE, ChestType.RIGHT), 3);
    var halves =
        List.of(
            (ChestBlockEntity) level.getBlockEntity(CHEST.east()),
            (ChestBlockEntity) level.getBlockEntity(CHEST));
    int index = 0;
    for (var id : BuiltInRegistries.ITEM.keySet().stream().sorted().toList()) {
      if (!id.getNamespace().equals(FactoryCoreMod.MOD_ID)) continue;
      halves.get(index / 27).setItem(index % 27, new ItemStack(BuiltInRegistries.ITEM.get(id)));
      index++;
    }
    return index;
  }

  static void teleport(ServerPlayer player, View view) {
    player.getAbilities().flying = true;
    player.onUpdateAbilities();
    player.teleportTo(player.serverLevel(), view.x(), view.y(), view.z(), view.yaw(), view.pitch());
  }
}
