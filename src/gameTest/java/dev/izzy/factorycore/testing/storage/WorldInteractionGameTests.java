package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.MachinePresets;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.storage.MachineInterfaceEntity;
import dev.izzy.factorycore.platform.storage.PortContent;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Q34/Q35 (QD9, ADR 0070): the Constructor places, drops and launches from storage, and the
 * Destructor breaks and picks up into storage, both as their deployer through the game's events: a
 * protected position refuses, a blocked or full target keeps everything, nothing is voided.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class WorldInteractionGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 2, 1),
      DRIVE = new BlockPos(1, 2, 2),
      PORT = new BlockPos(2, 2, 1),
      TARGET = new BlockPos(3, 2, 1);

  /** Absolute positions where place and break events are cancelled, as a protection mod would. */
  private static final Set<BlockPos> PROTECTED = Collections.synchronizedSet(new HashSet<>());

  static {
    NeoForge.EVENT_BUS.addListener(
        (BlockEvent.EntityPlaceEvent event) -> {
          if (PROTECTED.contains(event.getPos())) event.setCanceled(true);
        });
    NeoForge.EVENT_BUS.addListener(
        (BlockEvent.BreakEvent event) -> {
          if (PROTECTED.contains(event.getPos())) event.setCanceled(true);
        });
  }

  private WorldInteractionGameTests() {}

  private static ServerPlayer player(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    return player;
  }

  private static MachineInterfaceEntity place(
      GameTestHelper h,
      net.neoforged.neoforge.registries.DeferredBlock<?> block,
      ServerPlayer placer) {
    h.setBlock(
        PORT,
        block.get().defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST));
    var position = h.absolutePos(PORT);
    var state = h.getLevel().getBlockState(position);
    state.getBlock().setPlacedBy(h.getLevel(), position, state, placer, new ItemStack(block.get()));
    return h.getBlockEntity(PORT);
  }

  private static ResourceFilter only(Net net, Item item) {
    return new ResourceFilter(
        ResourceKind.ITEM,
        ResourceFilter.Mode.INCLUDE,
        List.of(new ResourceFilter.Exact(net.key(item))));
  }

  private static int buffered(MachineInterfaceEntity port) {
    int count = 0;
    for (int slot = 0; slot < port.worldBuffer().getSlots(); slot++)
      count += port.worldBuffer().getStackInSlot(slot).getCount();
    return count;
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void theConstructorPlacesWaitsWhenBlockedAndRespectsProtection(GameTestHelper h) {
    var owner = player(h);
    var net = Net.in(h, owner.getUUID());
    net.controller(ROOT);
    net.drive(DRIVE, CellTier.STARTER_ITEM);
    var cell = net.cell(DRIVE, 0);
    var constructor = place(h, PortContent.CONSTRUCTOR, owner);
    var stone = net.key(Items.STONE);
    var target = h.absolutePos(TARGET);
    h.startSequence()
        .thenWaitUntil(() -> net.assertValid(PORT))
        .thenExecute(
            () -> {
              net.saved().registry().insert(cell, stone, 3);
              constructor.deployPreset(
                  Actors.of(owner), MachinePresets.Mode.EXPORT, only(net, Items.STONE), 0);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    h.getBlockState(TARGET).is(Blocks.STONE)
                        && buffered(constructor) == 1
                        && net.saved().registry().stock(cell, stone).total() == 1,
                    "It places one stone and holds the next: storage keeps the third: "
                        + net.runtime().machinePresets().status(constructor)
                        + " "
                        + constructor.diagnostic()))
        .thenIdle(30)
        .thenExecute(
            () -> {
              h.assertTrue(
                  buffered(constructor) == 1
                      && net.saved().registry().stock(cell, stone).total() == 1,
                  "A blocked target keeps the held stone; nothing more leaves storage");
              PROTECTED.add(target);
              h.setBlock(TARGET, Blocks.AIR);
            })
        .thenIdle(30)
        .thenExecute(
            () -> {
              h.assertTrue(
                  h.getBlockState(TARGET).isAir()
                      && buffered(constructor) == 1
                      && net.saved().registry().stock(cell, stone).total() == 1,
                  "A protected position refuses the place event; the stone stays held");
              PROTECTED.remove(target);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    h.getBlockState(TARGET).is(Blocks.STONE)
                        && buffered(constructor) == 1
                        && net.saved().registry().stock(cell, stone).total() == 0,
                    "Unprotected, it places again"))
        .thenExecute(() -> owner.connection.disconnect(Component.literal("done")))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void theConstructorDropsItemsAndLaunchesFireworks(GameTestHelper h) {
    var owner = player(h);
    var net = Net.in(h, owner.getUUID());
    net.controller(ROOT);
    net.drive(DRIVE, CellTier.STARTER_ITEM);
    var cell = net.cell(DRIVE, 0);
    var constructor = place(h, PortContent.CONSTRUCTOR, owner);
    var area = new AABB(h.absolutePos(TARGET)).inflate(1, 3, 1);
    h.startSequence()
        .thenWaitUntil(() -> net.assertValid(PORT))
        .thenExecute(
            () -> {
              net.saved().registry().insert(cell, net.key(Items.DIAMOND), 2);
              constructor.deployPreset(
                  Actors.of(owner), MachinePresets.Mode.EXPORT, only(net, Items.DIAMOND), 0);
            })
        .thenWaitUntil(
            () -> {
              int dropped = 0;
              for (var item : h.getLevel().getEntitiesOfClass(ItemEntity.class, area))
                if (item.getItem().is(Items.DIAMOND)) dropped += item.getItem().getCount();
              h.assertTrue(
                  dropped == 2
                      && net.saved().registry().stock(cell, net.key(Items.DIAMOND)).total() == 0,
                  "Both diamonds are dropped in front, none lost: " + dropped);
            })
        .thenExecute(
            () -> {
              net.saved().registry().insert(cell, net.key(Items.FIREWORK_ROCKET), 1);
              constructor.deployPreset(
                  Actors.of(owner),
                  MachinePresets.Mode.EXPORT,
                  only(net, Items.FIREWORK_ROCKET),
                  0);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    !h.getLevel()
                            .getEntitiesOfClass(FireworkRocketEntity.class, area.inflate(0, 8, 0))
                            .isEmpty()
                        && net.saved()
                                .registry()
                                .stock(cell, net.key(Items.FIREWORK_ROCKET))
                                .total()
                            == 0,
                    "A firework is launched"))
        .thenExecute(() -> owner.connection.disconnect(Component.literal("done")))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 800)
  public static void theDestructorBreaksAndPicksUpAndStopsWhenStorageIsFull(GameTestHelper h) {
    var owner = player(h);
    var net = Net.in(h, owner.getUUID());
    net.controller(ROOT);
    net.drive(DRIVE, CellTier.STARTER_ITEM);
    var cell = net.cell(DRIVE, 0);
    var target = h.absolutePos(TARGET);
    h.setBlock(TARGET, Blocks.STONE);
    // A placed Destructor imports (breaks) everything under its placer, like an Importer.
    var destructor = place(h, PortContent.DESTRUCTOR, owner);
    var cobble = net.key(Items.COBBLESTONE);
    var filler = net.key(Items.DIRT);
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    h.getBlockState(TARGET).isAir()
                        && net.saved().registry().stock(cell, cobble).total() == 1,
                    "It breaks the stone; the cobblestone it drops goes to storage"))
        .thenExecute(
            () -> {
              var item =
                  new ItemEntity(
                      h.getLevel(),
                      target.getX() + 0.5,
                      target.getY() + 0.2,
                      target.getZ() + 0.5,
                      new ItemStack(Items.OAK_LOG, 5));
              item.setNoGravity(true);
              h.getLevel().addFreshEntity(item);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    net.saved().registry().stock(cell, net.key(Items.OAK_LOG)).total() == 5,
                    "It picks up the five logs in front into storage"))
        .thenExecute(
            () -> {
              // Fill storage, then give it a block: its drop is held, not voided.
              long room =
                  CellTier.STARTER_ITEM.capacity()
                      - net.saved().registry().stock(cell, cobble).total()
                      - 5;
              net.saved().registry().insert(cell, filler, room);
              h.setBlock(TARGET, Blocks.STONE);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    h.getBlockState(TARGET).isAir() && buffered(destructor) == 1,
                    "With storage full the drop waits in its buffer"))
        .thenExecute(() -> h.setBlock(TARGET, Blocks.STONE))
        .thenIdle(40)
        .thenExecute(
            () -> {
              h.assertTrue(
                  h.getBlockState(TARGET).is(Blocks.STONE) && buffered(destructor) == 1,
                  "While its drop cannot be stored it leaves the next block in place");
              net.saved().registry().extract(cell, filler, 64, null);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    h.getBlockState(TARGET).isAir()
                        && net.saved().registry().stock(cell, cobble).total() == 3,
                    "With room again it stores the held drop and breaks the next block"))
        .thenExecute(
            () -> {
              PROTECTED.add(target);
              h.setBlock(TARGET, Blocks.STONE);
            })
        .thenIdle(30)
        .thenExecute(
            () -> {
              h.assertTrue(
                  h.getBlockState(TARGET).is(Blocks.STONE) && buffered(destructor) == 0,
                  "A cancelled break event leaves the block and takes nothing");
              PROTECTED.remove(target);
              owner.connection.disconnect(Component.literal("done"));
            })
        .thenSucceed();
  }
}
