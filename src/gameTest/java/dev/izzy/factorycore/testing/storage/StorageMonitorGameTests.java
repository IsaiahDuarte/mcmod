package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.crafting.CrafterEntity;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.crafting.PatternContents;
import dev.izzy.factorycore.platform.monitor.MonitorContent;
import dev.izzy.factorycore.platform.monitor.MonitorEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Q28: the Storage Monitor shows a count and moves items through the shared player-transfer path:
 * take a stack, store the held stack, store every matching stack on a quick second use, refuse a
 * take without Withdraw, keep items when storage is full, and craft a stack when none is stored.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class StorageMonitorGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(2, 1, 1),
      MONITOR = new BlockPos(1, 1, 2),
      CRAFTER = new BlockPos(2, 1, 2);

  private record Fixture(
      ServerPlayer owner,
      MonitorEntity monitor,
      DeviceRegistry.Handle cell,
      ResourceKey cobble,
      DeviceRegistry registry) {}

  private StorageMonitorGameTests() {}

  private static Fixture setup(GameTestHelper h) {
    var owner = TestPlayers.mock(h);
    TestPlayers.connect(owner);
    owner.setGameMode(GameType.SURVIVAL);
    owner.getInventory().clearContent();
    owner.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    Net.in(h, owner.getUUID()).controller(ROOT);
    var cell = CellHandleData.read(Net.in(h).drive(DRIVE, CellTier.STARTER_ITEM).cell(0));
    h.setBlock(MONITOR, MonitorContent.BLOCK.get());
    MonitorEntity monitor = h.getBlockEntity(MONITOR);
    monitor.place(owner.getUUID());
    var cobble =
        new PlatformResourceCodec(h.getLevel().registryAccess())
            .itemKey(new ItemStack(Items.COBBLESTONE));
    return new Fixture(
        owner, monitor, cell, cobble, DeviceSavedData.get(h.getLevel().getServer()).registry());
  }

  private static void ready(GameTestHelper h, Fixture f) {
    h.assertTrue(
        WorldNetworks.get(h.getLevel().getServer())
                    .membership(Positions.of(h.getLevel(), h.absolutePos(MONITOR)))
                    .status()
                == NetworkTopology.Status.VALID
            && f.registry.stock(f.cell, f.cobble).loaded(),
        "Monitor on a valid network with its cell online");
  }

  private static long stored(Fixture f) {
    return f.registry.stock(f.cell, f.cobble).total();
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void showTakeStoreAndStoreAll(GameTestHelper h) {
    var f = setup(h);
    var inventory = f.owner.getInventory();
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.registry.insert(f.cell, f.cobble, 100);
              inventory.selected = 0;
              inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 5));
              h.assertTrue(f.monitor.use(f.owner, inventory.getItem(0)), "Choosing an item");
              h.assertTrue(f.cobble.equals(f.monitor.shown()), "It shows cobblestone");
              // Nothing is stored by choosing; the held stack stays.
              h.assertTrue(
                  inventory.getItem(0).getCount() == 5 && stored(f) == 100, "Choosing moves none");
            })
        .thenWaitUntil(
            () -> h.assertTrue(f.monitor.count() == 100, "It reads 100: " + f.monitor.count()))
        .thenExecute(() -> f.monitor.use(f.owner, inventory.getItem(0)))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    inventory.getItem(0).isEmpty() && stored(f) == 105,
                    "Using it with the shown item stores the held stack"))
        .thenIdle(MonitorEntity.DOUBLE_USE + 5)
        .thenExecute(
            () -> {
              inventory.setItem(0, ItemStack.EMPTY);
              f.monitor.use(f.owner, ItemStack.EMPTY);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    inventory.getItem(0).is(Items.COBBLESTONE)
                        && inventory.getItem(0).getCount() == 64
                        && stored(f) == 41,
                    "An empty hand takes a stack into the selected slot"))
        .thenWaitUntil(
            () -> h.assertTrue(f.monitor.count() == 41, "It reads 41: " + f.monitor.count()))
        .thenExecute(
            () -> {
              inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 10));
              inventory.setItem(5, new ItemStack(Items.COBBLESTONE, 7));
              inventory.setItem(20, new ItemStack(Items.COBBLESTONE, 3));
              inventory.setItem(21, new ItemStack(Items.DIRT, 3));
              // Two quick uses: the first stores the held 10, the second every other stack.
              f.monitor.use(f.owner, inventory.getItem(0));
              f.monitor.use(f.owner, new ItemStack(Items.COBBLESTONE));
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    inventory.countItem(Items.COBBLESTONE) == 0
                        && inventory.countItem(Items.DIRT) == 3
                        && stored(f) == 61,
                    "A quick second use stores every cobblestone stack, and nothing else"))
        .thenExecute(() -> f.owner.connection.disconnect(Component.literal("done")))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void refusalsKeepEverythingWhereItWas(GameTestHelper h) {
    var f = setup(h);
    var guest = TestPlayers.mock(h);
    TestPlayers.connect(guest);
    guest.setGameMode(GameType.SURVIVAL);
    guest.getInventory().clearContent();
    guest.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    var inventory = f.owner.getInventory();
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.registry.insert(f.cell, f.cobble, 10);
              f.monitor.use(f.owner, new ItemStack(Items.COBBLESTONE));
              // A player without grants can neither choose nor lock, nor take.
              f.monitor.use(guest, new ItemStack(Items.DIRT));
              h.assertTrue(f.cobble.equals(f.monitor.shown()), "A guest cannot change it");
              guest.setShiftKeyDown(true);
              f.monitor.use(guest, ItemStack.EMPTY);
              guest.setShiftKeyDown(false);
              h.assertTrue(!f.monitor.locked(), "A guest cannot lock it");
              f.monitor.use(guest, ItemStack.EMPTY);
            })
        .thenIdle(10)
        .thenExecute(
            () -> {
              h.assertTrue(
                  guest.getInventory().countItem(Items.COBBLESTONE) == 0 && stored(f) == 10,
                  "A take without Withdraw moves nothing");
              // The owner locks it; a different item then does not change what it shows.
              f.owner.setShiftKeyDown(true);
              f.monitor.use(f.owner, ItemStack.EMPTY);
              f.owner.setShiftKeyDown(false);
              h.assertTrue(f.monitor.locked(), "Sneak-use locks it");
              f.monitor.use(f.owner, new ItemStack(Items.DIRT));
              h.assertTrue(f.cobble.equals(f.monitor.shown()), "Locked: dirt does not replace it");
              // Fill the starter cell, then try to store more.
              f.registry.insert(f.cell, f.cobble, CellTier.STARTER_ITEM.capacity() - 10);
              inventory.selected = 0;
              inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 16));
              f.monitor.use(f.owner, inventory.getItem(0));
            })
        .thenIdle(10)
        .thenExecute(
            () ->
                h.assertTrue(
                    inventory.getItem(0).getCount() == 16
                        && stored(f) == CellTier.STARTER_ITEM.capacity(),
                    "Full storage keeps the held stack"))
        .thenExecute(
            () -> {
              f.owner.connection.disconnect(Component.literal("done"));
              guest.connection.disconnect(Component.literal("done"));
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void anEmptyHandAtZeroCraftsAStack(GameTestHelper h) {
    var f = setup(h);
    h.setBlock(CRAFTER, CraftingContent.CRAFTER.get());
    CrafterEntity crafter = h.getBlockEntity(CRAFTER);
    var stacks = new ArrayList<ItemStack>(Collections.nCopies(9, ItemStack.EMPTY));
    stacks.set(0, new ItemStack(Items.OAK_LOG));
    var pattern = new ItemStack(CraftingContent.CRAFTING_PATTERN.get());
    pattern.set(
        CraftingContent.PATTERN.get(),
        new PatternContents(
            UUID.randomUUID(), 1, 0, stacks, new ItemStack(Items.OAK_PLANKS, 4), List.of()));
    crafter.patterns().insertItem(0, pattern, false);
    var codec = new PlatformResourceCodec(h.getLevel().registryAccess());
    var log = codec.itemKey(new ItemStack(Items.OAK_LOG));
    var planks = codec.itemKey(new ItemStack(Items.OAK_PLANKS));
    var inventory = f.owner.getInventory();
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.registry.insert(f.cell, log, 16);
              f.monitor.use(f.owner, new ItemStack(Items.OAK_PLANKS));
            })
        .thenWaitUntil(() -> h.assertTrue(f.monitor.count() == 0, "It reads zero planks"))
        .thenExecute(() -> f.monitor.use(f.owner, ItemStack.EMPTY))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.registry.stock(f.cell, planks).total() == 64
                        && f.registry.stock(f.cell, log).total() == 0
                        && inventory.countItem(Items.OAK_PLANKS) == 0,
                    "The take found none, so a job crafted one stack into storage: "
                        + f.registry.stock(f.cell, planks).total()))
        .thenWaitUntil(
            () -> h.assertTrue(f.monitor.count() == 64, "It reads 64: " + f.monitor.count()))
        .thenExecute(() -> f.owner.connection.disconnect(Component.literal("done")))
        .thenSucceed();
  }
}
