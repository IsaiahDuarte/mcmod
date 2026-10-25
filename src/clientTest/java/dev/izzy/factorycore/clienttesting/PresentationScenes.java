package dev.izzy.factorycore.clienttesting;

import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.platform.crafting.CrafterEntity;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.crafting.PatternContents;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.power.FuelGeneratorBlock;
import dev.izzy.factorycore.platform.power.FuelGeneratorContent;
import dev.izzy.factorycore.platform.power.FuelGeneratorEntity;
import dev.izzy.factorycore.platform.program.ProgramContent;
import dev.izzy.factorycore.platform.program.ProgramControllerEntity;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.BufferedInterfaceEntity;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.ConnectorEntity;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.InterfaceContent;
import dev.izzy.factorycore.platform.storage.InterfaceKind;
import dev.izzy.factorycore.platform.storage.MachineInterfaceEntity;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.platform.storage.TerminalContent;
import dev.izzy.factorycore.platform.wireless.AccessPointEntity;
import dev.izzy.factorycore.platform.wireless.LinkModule;
import dev.izzy.factorycore.platform.wireless.LinkPairing;
import dev.izzy.factorycore.platform.wireless.WirelessContent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Presentation scenes for {@link PresentationClientVerification}: every directional device in every
 * facing, real device states that drive status lamps and drive bays, and cable arms at sockets.
 * Program Controller lamp states use the trusted state transition on undeployed controllers; the
 * runtime skips undeployed controllers, so no program runs.
 */
final class PresentationScenes {
  record View(double x, double y, double z, float yaw, float pitch) {
    /** A camera at ({@code x},{@code y},{@code z}) looking at the target point. */
    static View looking(double x, double y, double z, double tx, double ty, double tz) {
      double dx = tx - x;
      double dy = ty - y;
      double dz = tz - z;
      float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
      float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
      return new View(x, y, z, yaw, pitch);
    }
  }

  static final int ORIENT_X = 100;
  static final int ORIENT_Y = 72;
  static final int ORIENT_Z = 100;
  static final int SPACING = 3;
  static final List<Direction> FACINGS =
      List.of(
          Direction.NORTH,
          Direction.EAST,
          Direction.SOUTH,
          Direction.WEST,
          Direction.UP,
          Direction.DOWN);

  /** From north-east above: north, east and up faces are visible. */
  static final View ORIENT_NORTH_EAST_UP =
      View.looking(
          ORIENT_X + 20, ORIENT_Y + 9, ORIENT_Z - 9, ORIENT_X + 7.5, ORIENT_Y, ORIENT_Z + 10.5);

  /** From south-west below: south, west and down faces are visible. */
  static final View ORIENT_SOUTH_WEST_DOWN =
      View.looking(
          ORIENT_X - 5, ORIENT_Y - 8, ORIENT_Z + 30, ORIENT_X + 7.5, ORIENT_Y, ORIENT_Z + 10.5);

  static final int STATE_Z = 40;
  static final View STATES = new View(50.5, 68.5, 33.5, 0, 25);
  static final View STATES_CLOSE = new View(46.5, 67.0, 38.0, 0, 30);
  static final View HELD = new View(50.5, 65, 36.5, 0, 15);
  static final BlockPos SCREENS = new BlockPos(0, 65, 0);

  private PresentationScenes() {}

  private static BlockState facing(Block block, Direction direction) {
    return facing(block.defaultBlockState(), direction);
  }

  private static BlockState facing(BlockState state, Direction direction) {
    return state.hasProperty(BlockStateProperties.FACING)
        ? state.setValue(BlockStateProperties.FACING, direction)
        : state.setValue(HorizontalDirectionalBlock.FACING, direction);
  }

  static void floor(ServerLevel level, int x0, int z0, int x1, int z1, int y) {
    for (int x = x0; x <= x1; x++)
      for (int z = z0; z <= z1; z++)
        level.setBlock(new BlockPos(x, y, z), Blocks.SMOOTH_STONE.defaultBlockState(), 3);
  }

  /**
   * Floating grid: one row per directional device, one column per facing. Rows: controller,
   * gateway, connector, machine interface, crafting interface, terminal, generator (the last two
   * are horizontal-only and skip up/down). Returns the number of blocks placed.
   */
  static int orientations(ServerLevel level) {
    List<BlockState> rows =
        List.of(
            NetworkContent.CONTROLLER.get().defaultBlockState(),
            NetworkContent.GATEWAY.get().defaultBlockState(),
            InterfaceContent.state(InterfaceKind.STORAGE, Direction.NORTH),
            InterfaceContent.state(InterfaceKind.MACHINE, Direction.NORTH),
            CraftingContent.CRAFTER.get().defaultBlockState(),
            TerminalContent.BLOCK.get().defaultBlockState(),
            FuelGeneratorContent.BLOCK.get().defaultBlockState());
    int placed = 0;
    for (int row = 0; row < rows.size(); row++)
      for (int column = 0; column < FACINGS.size(); column++) {
        var direction = FACINGS.get(column);
        var block = rows.get(row);
        if (direction.getAxis().isVertical() && !block.hasProperty(BlockStateProperties.FACING))
          continue;
        level.setBlock(
            new BlockPos(ORIENT_X + column * SPACING, ORIENT_Y, ORIENT_Z + row * SPACING),
            facing(block, direction),
            Block.UPDATE_CLIENTS);
        placed++;
      }
    return placed;
  }

  private static NetworkNodeEntity root(ServerLevel level, BlockPos position, ServerPlayer owner) {
    level.setBlock(position, NetworkContent.CONTROLLER.get().defaultBlockState(), 3);
    var node = (NetworkNodeEntity) level.getBlockEntity(position);
    node.commission(owner == null ? null : owner.getUUID());
    return node;
  }

  private static void drive(ServerLevel level, BlockPos position, int... bays) {
    level.setBlock(position, StorageContent.DRIVE.get().defaultBlockState(), 3);
    var drive = (StorageDriveEntity) level.getBlockEntity(position);
    for (int i = 0; i < 4; i++)
      drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    for (int slot = 0; slot < 4; slot++) {
      boolean keep = false;
      for (int bay : bays) keep |= bay == slot;
      if (!keep) drive.removeCell(slot);
    }
  }

  private static ProgramControllerEntity program(
      ServerLevel level, BlockPos position, ProgramControllerEntity.State state) {
    level.setBlock(position, ProgramContent.BLOCK.get().defaultBlockState(), 3);
    var controller = (ProgramControllerEntity) level.getBlockEntity(position);
    if (state != ProgramControllerEntity.State.STOPPED) controller.state(state, "gallery");
    return controller;
  }

  /** A gateway with a link on its north (downstream) face (ADR 0061). */
  private static LinkModule link(ServerLevel level, BlockPos position, boolean powered) {
    level.setBlock(
        position, facing(NetworkContent.GATEWAY.get(), Direction.NORTH), Block.UPDATE_ALL);
    var gateway = (NetworkNodeEntity) level.getBlockEntity(position);
    gateway.installLink(Direction.NORTH, null, null);
    var link = gateway.link();
    if (powered) link.energyStorage().receiveEnergy(1_000, false);
    return link;
  }

  /**
   * Status row (z=40) and lamp row (z=43), each device isolated by one empty block. Returns a
   * description of what each position should show.
   */
  static String states(ServerPlayer player) {
    var level = player.serverLevel();
    floor(level, 36, 30, 66, 50, 64);
    int z = STATE_Z;
    // Network nodes: lone valid root (active); two joined roots (fault); rootless gateway (fault);
    // gateway whose upstream face reaches a root (active).
    root(level, new BlockPos(40, 65, z), player);
    root(level, new BlockPos(42, 65, z), player);
    root(level, new BlockPos(43, 65, z), player);
    level.setBlock(
        new BlockPos(45, 65, z), facing(NetworkContent.GATEWAY.get(), Direction.NORTH), 3);
    ((NetworkNodeEntity) level.getBlockEntity(new BlockPos(45, 65, z))).commission(null);
    root(level, new BlockPos(47, 65, z + 1), player);
    level.setBlock(
        new BlockPos(47, 65, z), facing(NetworkContent.GATEWAY.get(), Direction.NORTH), 3);
    ((NetworkNodeEntity) level.getBlockEntity(new BlockPos(47, 65, z))).commission(null);
    // Drives: empty, bays 1+3, full.
    drive(level, new BlockPos(49, 65, z));
    drive(level, new BlockPos(51, 65, z), 0, 2);
    drive(level, new BlockPos(53, 65, z), 0, 1, 2, 3);
    // Generators: unlit and burning.
    level.setBlock(
        new BlockPos(55, 65, z), facing(FuelGeneratorContent.BLOCK.get(), Direction.NORTH), 3);
    level.setBlock(
        new BlockPos(57, 65, z),
        facing(FuelGeneratorContent.BLOCK.get(), Direction.NORTH)
            .setValue(FuelGeneratorBlock.LIT, true),
        3);
    if (level.getBlockEntity(new BlockPos(57, 65, z)) instanceof FuelGeneratorEntity generator)
      generator.slots().setStackInSlot(FuelGeneratorEntity.FUEL, new ItemStack(Items.COAL, 64));

    int lamps = z + 3;
    // Programs share one root behind them: stopped, running, paused, trapped.
    root(level, new BlockPos(40, 65, lamps + 1), player);
    for (int i = 0; i < 4; i++)
      level.setBlock(
          new BlockPos(41 + i, 65, lamps + 1), NetworkContent.CABLE.get().defaultBlockState(), 3);
    var states = ProgramControllerEntity.State.values();
    for (int i = 0; i < states.length; i++)
      program(level, new BlockPos(40 + i, 65, lamps), states[i]);
    // Access points: networked with FE (active) and rootless (fault).
    root(level, new BlockPos(46, 65, lamps + 1), player);
    level.setBlock(
        new BlockPos(46, 65, lamps), WirelessContent.ACCESS_POINT.get().defaultBlockState(), 3);
    ((AccessPointEntity) level.getBlockEntity(new BlockPos(46, 65, lamps)))
        .energyStorage()
        .receiveEnergy(1_000, false);
    level.setBlock(
        new BlockPos(48, 65, lamps), WirelessContent.ACCESS_POINT.get().defaultBlockState(), 3);
    // Links: powered pair (active), unpowered pair (fault), unpaired (off).
    link(level, new BlockPos(50, 65, lamps), true);
    link(level, new BlockPos(52, 65, lamps), true);
    link(level, new BlockPos(54, 65, lamps), false);
    link(level, new BlockPos(56, 65, lamps), false);
    link(level, new BlockPos(58, 65, lamps), false);
    String powered =
        LinkPairing.pair(player, new BlockPos(50, 65, lamps), new BlockPos(52, 65, lamps));
    String unpowered =
        LinkPairing.pair(player, new BlockPos(54, 65, lamps), new BlockPos(56, 65, lamps));
    if (!"paired".equals(powered) || !"paired".equals(unpowered))
      throw new IllegalStateException("Gallery link pairing: " + powered + "/" + unpowered);

    // Cable arms at device sockets: a connector facing down into a chest with a cable rising from
    // its back, and a machine interface facing a furnace with cable entering from behind.
    int arms = lamps + 4;
    level.setBlock(new BlockPos(41, 65, arms), Blocks.CHEST.defaultBlockState(), 3);
    level.setBlock(
        new BlockPos(41, 66, arms),
        InterfaceContent.state(InterfaceKind.STORAGE, Direction.DOWN),
        3);
    ((ConnectorEntity) level.getBlockEntity(new BlockPos(41, 66, arms)))
        .place(new ItemStack(InterfaceContent.ITEM.get()));
    level.setBlock(new BlockPos(44, 65, arms), Blocks.FURNACE.defaultBlockState(), 3);
    level.setBlock(
        new BlockPos(44, 65, arms - 1),
        InterfaceContent.state(InterfaceKind.MACHINE, Direction.SOUTH),
        3);
    ((MachineInterfaceEntity) level.getBlockEntity(new BlockPos(44, 65, arms - 1)))
        .place(new ItemStack(InterfaceContent.ITEM.get()));
    var cable = NetworkContent.CABLE.get();
    for (var position :
        List.of(
            new BlockPos(41, 67, arms),
            new BlockPos(42, 67, arms),
            new BlockPos(43, 67, arms),
            new BlockPos(44, 67, arms - 1),
            new BlockPos(43, 67, arms - 1),
            new BlockPos(44, 66, arms - 1),
            new BlockPos(45, 65, arms - 1),
            new BlockPos(46, 65, arms - 1))) level.setBlock(position, cable.defaultBlockState(), 3);
    for (var position :
        List.of(
            new BlockPos(41, 67, arms),
            new BlockPos(42, 67, arms),
            new BlockPos(43, 67, arms),
            new BlockPos(44, 67, arms - 1),
            new BlockPos(43, 67, arms - 1),
            new BlockPos(44, 66, arms - 1),
            new BlockPos(45, 65, arms - 1),
            new BlockPos(46, 65, arms - 1)))
      level.setBlock(position, cable.connected(level, position), 3);
    return "row z=40: root(active) roots(fault) gateway(fault) gateway(active) drives(0,1+3,4)"
        + " generators(unlit,lit); row z=43: programs(stopped,running,paused,trapped)"
        + " access points(active,fault) linked gateways(powered pair, unpowered pair, unpaired); arms z=47";
  }

  /**
   * Functional screen network along x at z=0: root, drive, terminal, buffer, machine interface
   * (socket north into a chest), program controller, cable (with a Storage Connector north into a
   * chest), gateway (downstream east) and branch.
   */
  /** Planks from one oak log on a Crafting Unit beside the screens drive; 8 logs stored. */
  static final BlockPos JOB_UNIT = SCREENS.east().north();

  static void jobs(ServerPlayer player) {
    var level = player.serverLevel();
    level.setBlock(JOB_UNIT, CraftingContent.CRAFTER.get().defaultBlockState(), 3);
    var cells = new ArrayList<ItemStack>(Collections.nCopies(9, ItemStack.EMPTY));
    cells.set(0, new ItemStack(Items.OAK_LOG));
    var pattern = new ItemStack(CraftingContent.CRAFTING_PATTERN.get());
    pattern.set(
        CraftingContent.PATTERN.get(),
        new PatternContents(
            UUID.randomUUID(), 1, 0, cells, new ItemStack(Items.OAK_PLANKS, 4), List.of()));
    ((CrafterEntity) level.getBlockEntity(JOB_UNIT)).patterns().insertItem(0, pattern, false);
  }

  /** Crafters added beside the Jobs scene so the craftable strip has to scroll (P4). */
  static final BlockPos[] EXTRA_UNITS = {SCREENS.north(), SCREENS.east(2).north()};

  /** Fills the Jobs Crafter and two more with 22 more outputs (23 craftables in all). */
  static void manyJobs(ServerPlayer player) {
    var level = player.serverLevel();
    Item[] outputs = {
      Items.STICK,
      Items.TORCH,
      Items.CHEST,
      Items.LADDER,
      Items.BOWL,
      Items.OAK_SLAB,
      Items.OAK_STAIRS,
      Items.OAK_FENCE,
      Items.OAK_DOOR,
      Items.OAK_BUTTON,
      Items.OAK_SIGN,
      Items.OAK_TRAPDOOR,
      Items.OAK_PRESSURE_PLATE,
      Items.OAK_FENCE_GATE,
      Items.OAK_BOAT,
      Items.CRAFTING_TABLE,
      Items.BARREL,
      Items.COMPOSTER,
      Items.BOOKSHELF,
      Items.LECTERN,
      Items.LOOM,
      Items.SMITHING_TABLE
    };
    for (var unit : EXTRA_UNITS)
      level.setBlock(unit, CraftingContent.CRAFTER.get().defaultBlockState(), 3);
    BlockPos[] units = {JOB_UNIT, EXTRA_UNITS[0], EXTRA_UNITS[1]};
    for (int index = 0; index < outputs.length; index++) {
      // Slot 0 of the Jobs Crafter keeps the planks pattern.
      int slot = (index + 1) % 9, unit = (index + 1) / 9;
      var cells = new ArrayList<ItemStack>(Collections.nCopies(9, ItemStack.EMPTY));
      cells.set(0, new ItemStack(Items.OAK_LOG));
      var pattern = new ItemStack(CraftingContent.CRAFTING_PATTERN.get());
      pattern.set(
          CraftingContent.PATTERN.get(),
          new PatternContents(
              UUID.randomUUID(), 1, 0, cells, new ItemStack(outputs[index]), List.of()));
      ((CrafterEntity) level.getBlockEntity(units[unit]))
          .patterns()
          .insertItem(slot, pattern, false);
    }
  }

  /** Removes the extra Crafters and patterns so later scenes see the original Jobs fixture. */
  static void fewJobs(ServerPlayer player) {
    var level = player.serverLevel();
    var unit = (CrafterEntity) level.getBlockEntity(JOB_UNIT);
    for (int slot = 1; slot < 9; slot++) unit.patterns().setStackInSlot(slot, ItemStack.EMPTY);
    for (var extra : EXTRA_UNITS) {
      for (int slot = 0; slot < 9; slot++)
        ((CrafterEntity) level.getBlockEntity(extra))
            .patterns()
            .setStackInSlot(slot, ItemStack.EMPTY);
      level.removeBlock(extra, false);
    }
  }

  /** Stores the 8 oak logs once the rebuilt network has the cell online again. */
  static void stockJobs(ServerPlayer player) {
    var level = player.serverLevel();
    var drive = (StorageDriveEntity) level.getBlockEntity(SCREENS.east());
    var cell = CellHandleData.read(drive.cell(0));
    var key =
        new PlatformResourceCodec(level.registryAccess()).itemKey(new ItemStack(Items.OAK_LOG));
    var stored = DeviceSavedData.get(player.server).registry().insert(cell, key, 8);
    if (stored.amount() != 8)
      throw new IllegalStateException("Jobs scene stores 8 oak logs: " + stored);
  }

  /** Takes the logs left after the typed job so the later Keep-64 rule still waits (A1). */
  static void drainLogs(ServerPlayer player) {
    var level = player.serverLevel();
    var drive = (StorageDriveEntity) level.getBlockEntity(SCREENS.east());
    var key =
        new PlatformResourceCodec(level.registryAccess()).itemKey(new ItemStack(Items.OAK_LOG));
    var registry = DeviceSavedData.get(player.server).registry();
    for (int slot = 0; slot < 4; slot++) {
      if (drive.cell(slot).isEmpty()) continue;
      var cell = CellHandleData.read(drive.cell(slot));
      var stock = registry.stock(cell, key);
      long free = stock.total() - stock.reserved();
      if (free <= 0) continue;
      var taken = registry.extract(cell, key, free, null);
      if (taken.amount() != free)
        throw new IllegalStateException("Jobs scene drains " + free + " logs: " + taken);
    }
  }

  static void screens(ServerPlayer player) {
    var level = player.serverLevel();
    floor(level, -2, -3, 12, 5, 64);
    var base = SCREENS;
    root(level, base, player);
    drive(level, base.east(), 0);
    level.setBlock(base.east(2), facing(TerminalContent.BLOCK.get(), Direction.SOUTH), 3);
    level.setBlock(base.east(3), InterfaceContent.state(InterfaceKind.BUFFER, Direction.NORTH), 3);
    ((BufferedInterfaceEntity) level.getBlockEntity(base.east(3)))
        .place(new ItemStack(InterfaceContent.ITEM.get()), player.getUUID());
    level.setBlock(base.east(4).north(), Blocks.CHEST.defaultBlockState(), 3);
    level.setBlock(base.east(4), InterfaceContent.state(InterfaceKind.MACHINE, Direction.NORTH), 3);
    ((MachineInterfaceEntity) level.getBlockEntity(base.east(4)))
        .place(new ItemStack(InterfaceContent.ITEM.get()));
    level.setBlock(base.east(5), ProgramContent.BLOCK.get().defaultBlockState(), 3);
    level.setBlock(base.east(6), NetworkContent.CABLE.get().defaultBlockState(), 3);
    level.setBlock(base.east(7), facing(NetworkContent.GATEWAY.get(), Direction.EAST), 3);
    ((NetworkNodeEntity) level.getBlockEntity(base.east(7))).commission(null);
    // A link on the downstream face puts its state on the gateway screen's status line.
    ((NetworkNodeEntity) level.getBlockEntity(base.east(7)))
        .installLink(Direction.EAST, player.getUUID(), null);
    level.setBlock(base.east(8), NetworkContent.CABLE.get().defaultBlockState(), 3);
    // Storage Connector off the cable, socket north into its own chest (U5 screen).
    level.setBlock(base.east(6).north(2), Blocks.CHEST.defaultBlockState(), 3);
    level.setBlock(
        base.east(6).north(), InterfaceContent.state(InterfaceKind.STORAGE, Direction.NORTH), 3);
    ((ConnectorEntity) level.getBlockEntity(base.east(6).north()))
        .place(new ItemStack(InterfaceContent.ITEM.get()));
  }

  static void teleport(ServerPlayer player, View view) {
    player.getAbilities().flying = true;
    player.onUpdateAbilities();
    player.teleportTo(player.serverLevel(), view.x(), view.y(), view.z(), view.yaw(), view.pitch());
  }
}
