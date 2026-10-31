package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.MachinePresets;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.FilterEdits;
import dev.izzy.factorycore.platform.storage.MachineInterfaceEntity;
import dev.izzy.factorycore.platform.storage.PortBlock;
import dev.izzy.factorycore.platform.storage.PortContent;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * H6 (ADR 0066): Importer and Exporter placed the way a player places them. An Importer starts
 * importing everything under its placer; an Exporter waits for a filter, then exports or keeps a
 * stock; each refuses the other's presets; three of them make a furnace an auto smelter.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class PortGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 2, 1),
      DRIVE = new BlockPos(1, 2, 2),
      PORT = new BlockPos(2, 2, 1),
      TARGET = new BlockPos(3, 2, 1);

  private PortGameTests() {}

  private static ServerPlayer player(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    return player;
  }

  private static void end(ServerPlayer... players) {
    for (var player : players)
      player.connection.disconnect(Component.literal("Port fixture complete"));
  }

  /** Places a port facing {@code facing} through the block's placement hook, as a player does. */
  private static MachineInterfaceEntity place(
      GameTestHelper h, BlockPos at, PortBlock.Role role, Direction facing, ServerPlayer placer) {
    var block = role == PortBlock.Role.IMPORTER ? PortContent.IMPORTER : PortContent.EXPORTER;
    h.setBlock(at, block.get().defaultBlockState().setValue(BlockStateProperties.FACING, facing));
    var position = h.absolutePos(at);
    var state = h.getLevel().getBlockState(position);
    state.getBlock().setPlacedBy(h.getLevel(), position, state, placer, new ItemStack(block.get()));
    return h.getBlockEntity(at);
  }

  private static MachinePresets.Deployment deployment(Net net, MachineInterfaceEntity port) {
    return net.saved().machinePresets().get(port.handle().id());
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void importerOnAChestMovesTheWholeInventoryIntoStorage(GameTestHelper h) {
    var owner = player(h);
    var net = Net.in(h, owner.getUUID());
    net.controller(ROOT);
    net.drive(DRIVE, CellTier.STARTER_ITEM);
    h.setBlock(TARGET, Blocks.CHEST);
    ChestBlockEntity chest = h.getBlockEntity(TARGET);
    chest.setItem(0, new ItemStack(Items.IRON_INGOT, 40));
    chest.setItem(5, new ItemStack(Items.DIRT, 64));
    chest.setItem(26, new ItemStack(Items.GOLD_NUGGET, 3));
    var importer = place(h, PORT, PortBlock.Role.IMPORTER, Direction.EAST, owner);
    var cell = net.cell(DRIVE, 0);
    h.succeedWhen(
        () -> {
          var deployment = deployment(net, importer);
          h.assertTrue(
              deployment != null
                  && deployment.mode() == MachinePresets.Mode.IMPORT
                  && deployment.filter().mode() == ResourceFilter.Mode.ALL
                  && deployment.authority().principal().equals(owner.getUUID()),
              "A placed Importer starts importing everything under its placer");
          h.assertTrue(chest.isEmpty(), "The chest is emptied");
          var registry = net.saved().registry();
          h.assertTrue(
              registry.stock(cell, net.key(Items.IRON_INGOT)).total() == 40
                  && registry.stock(cell, net.key(Items.DIRT)).total() == 64
                  && registry.stock(cell, net.key(Items.GOLD_NUGGET)).total() == 3,
              "Every stack arrives exactly in storage");
          end(owner);
        });
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void exporterWaitsForAFilterThenKeepsTheChestStocked(GameTestHelper h) {
    var owner = player(h);
    var net = Net.in(h, owner.getUUID());
    net.controller(ROOT);
    net.drive(DRIVE, CellTier.STARTER_ITEM);
    var cell = net.cell(DRIVE, 0);
    h.setBlock(TARGET, Blocks.CHEST);
    ChestBlockEntity chest = h.getBlockEntity(TARGET);
    var exporter = place(h, PORT, PortBlock.Role.EXPORTER, Direction.EAST, owner);
    var actor = Actors.of(owner);
    var iron =
        new ResourceFilter(
            ResourceKind.ITEM,
            ResourceFilter.Mode.INCLUDE,
            List.of(new ResourceFilter.Exact(net.key(Items.IRON_INGOT))));
    h.startSequence()
        .thenWaitUntil(() -> net.assertValid(PORT))
        .thenIdle(
            (MachineInterfaceEntity.RESUME_ATTEMPTS + 2) * MachineInterfaceEntity.RESUME_TICKS)
        .thenExecute(
            () -> {
              var deployment = deployment(net, exporter);
              h.assertTrue(
                  deployment == null || deployment.mode() == MachinePresets.Mode.PAUSED,
                  "A new Exporter sends nothing until told what to send");
              h.assertTrue(chest.isEmpty(), "Nothing left storage");
              boolean refused = false;
              try {
                exporter.deployPreset(actor, MachinePresets.Mode.IMPORT, iron, 0);
              } catch (IllegalArgumentException expected) {
                refused = true;
              }
              h.assertTrue(refused, "An Exporter refuses to import");
              net.saved().registry().insert(cell, net.key(Items.IRON_INGOT), 64);
              net.saved().registry().insert(cell, net.key(Items.DIRT), 64);
              exporter.deployPreset(actor, MachinePresets.Mode.STOCK, iron, 8);
            })
        .thenWaitUntil(
            () -> {
              int iron8 = 0;
              for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                var stack = chest.getItem(slot);
                h.assertTrue(
                    stack.isEmpty() || stack.is(Items.IRON_INGOT), "Only the filtered item moves");
                iron8 += stack.getCount();
              }
              h.assertTrue(
                  iron8 == 8,
                  "Keep 8 stocks exactly eight: "
                      + iron8
                      + " "
                      + net.runtime().machinePresets().status(exporter));
              h.assertTrue(
                  net.saved().registry().stock(cell, net.key(Items.IRON_INGOT)).total() == 56
                      && net.saved().registry().stock(cell, net.key(Items.DIRT)).total() == 64,
                  "Storage loses exactly what the chest gained");
            })
        .thenIdle(40)
        .thenExecute(
            () -> {
              int count = 0;
              for (int slot = 0; slot < chest.getContainerSize(); slot++)
                count += chest.getItem(slot).getCount();
              h.assertTrue(count == 8, "The stock holds at eight: " + count);
              end(owner);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void importerRefusesExportAndAGuestWithoutDeployStaysPaused(GameTestHelper h) {
    var owner = player(h);
    var guest = player(h);
    var net = Net.in(h, owner.getUUID());
    var root = net.controller(ROOT);
    net.drive(DRIVE, CellTier.STARTER_ITEM);
    net.saved()
        .network(root.handle().id())
        .setGrants(
            Actors.of(owner),
            guest.getUUID(),
            Set.of(
                NetworkPermissions.Permission.VIEW,
                NetworkPermissions.Permission.EDIT,
                NetworkPermissions.Permission.WITHDRAW,
                NetworkPermissions.Permission.DEPOSIT));
    h.setBlock(TARGET, Blocks.CHEST);
    ChestBlockEntity chest = h.getBlockEntity(TARGET);
    chest.setItem(0, new ItemStack(Items.COBBLESTONE, 16));
    var importer = place(h, PORT, PortBlock.Role.IMPORTER, Direction.EAST, guest);
    h.startSequence()
        .thenWaitUntil(() -> net.assertValid(PORT))
        .thenIdle(
            (MachineInterfaceEntity.RESUME_ATTEMPTS + 2) * MachineInterfaceEntity.RESUME_TICKS)
        .thenExecute(
            () -> {
              var deployment = deployment(net, importer);
              h.assertTrue(
                  deployment == null || deployment.mode() == MachinePresets.Mode.PAUSED,
                  "Without Deploy the placement default is denied and the Importer stays paused");
              h.assertTrue(chest.getItem(0).getCount() == 16, "Nothing moved");
              boolean refused = false;
              try {
                importer.deployPreset(
                    Actors.of(owner),
                    MachinePresets.Mode.EXPORT,
                    ResourceFilter.all(ResourceKind.ITEM),
                    0);
              } catch (IllegalArgumentException expected) {
                refused = true;
              }
              h.assertTrue(refused, "An Importer refuses to export");
              end(owner, guest);
            })
        .thenSucceed();
  }

  /**
   * Ports on different faces of one machine coexist (the smelter test), but a container that is
   * network storage stays exclusive: an Importer on it is refused rather than double-counting it.
   */
  @GameTest(template = "empty", timeoutTicks = 400)
  public static void importerOnAStorageChestIsRefusedAsAnOverlap(GameTestHelper h) {
    var owner = player(h);
    var net = Net.in(h, owner.getUUID());
    net.controller(ROOT);
    h.setBlock(TARGET, Blocks.CHEST);
    ChestBlockEntity chest = h.getBlockEntity(TARGET);
    chest.setItem(0, new ItemStack(Items.IRON_INGOT, 5));
    var storageAt = new BlockPos(3, 3, 1);
    net.cable(new BlockPos(1, 3, 1), new BlockPos(2, 3, 1));
    h.setBlock(
        storageAt,
        dev.izzy.factorycore.platform.storage.InterfaceContent.state(
            dev.izzy.factorycore.platform.storage.InterfaceKind.STORAGE, Direction.DOWN));
    dev.izzy.factorycore.platform.storage.ConnectorEntity storage = h.getBlockEntity(storageAt);
    storage.place(new ItemStack(dev.izzy.factorycore.platform.storage.InterfaceContent.ITEM.get()));
    var importer = place(h, PORT, PortBlock.Role.IMPORTER, Direction.EAST, owner);
    h.startSequence()
        .thenWaitUntil(() -> net.assertValid(PORT, storageAt))
        .thenWaitUntil(
            () -> {
              h.assertTrue(
                  net.runtime().connectors().bind(storage).handle() != null,
                  "The Storage Interface owns the chest");
              h.assertTrue(
                  net.runtime().connectors().bind(importer).reason()
                      == dev.izzy.factorycore.core.resource.OperationResult.Reason.OVERLAP,
                  "The Importer on the same chest is refused as an overlap");
            })
        .thenIdle(20)
        .thenExecute(
            () -> {
              h.assertTrue(chest.getItem(0).getCount() == 5, "Nothing was moved twice");
              end(owner);
            })
        .thenSucceed();
  }

  /**
   * The auto smelter from the guide: an Exporter on the furnace's top sends raw iron, one on its
   * side keeps eight coal, and an Importer underneath brings the ingots back into storage.
   */
  @GameTest(template = "empty", timeoutTicks = 1200)
  public static void threePortsMakeAFurnaceAnAutoSmelter(GameTestHelper h) {
    var owner = player(h);
    var net = Net.in(h, owner.getUUID());
    net.controller(ROOT);
    net.drive(DRIVE, CellTier.STARTER_ITEM);
    var furnace = new BlockPos(3, 2, 1);
    var top = new BlockPos(3, 3, 1);
    var bottom = new BlockPos(3, 1, 1);
    net.cable(
        new BlockPos(1, 3, 1), new BlockPos(2, 3, 1), new BlockPos(1, 1, 1), new BlockPos(2, 1, 1));
    var cell = net.cell(DRIVE, 0);
    h.setBlock(furnace, Blocks.FURNACE);
    var actor = Actors.of(owner);
    var ore = place(h, top, PortBlock.Role.EXPORTER, Direction.DOWN, owner);
    var fuel = place(h, PORT, PortBlock.Role.EXPORTER, Direction.EAST, owner);
    place(h, bottom, PortBlock.Role.IMPORTER, Direction.UP, owner);
    h.startSequence()
        .thenWaitUntil(() -> net.assertValid(top, PORT, bottom))
        .thenExecute(
            () -> {
              net.saved().registry().insert(cell, net.key(Items.RAW_IRON), 2);
              net.saved().registry().insert(cell, net.key(Items.COAL), 16);
              ore.deployPreset(
                  actor,
                  MachinePresets.Mode.EXPORT,
                  new ResourceFilter(
                      ResourceKind.ITEM,
                      ResourceFilter.Mode.INCLUDE,
                      List.of(new ResourceFilter.Exact(net.key(Items.RAW_IRON)))),
                  0);
              fuel.deployPreset(
                  actor,
                  MachinePresets.Mode.STOCK,
                  new ResourceFilter(
                      ResourceKind.ITEM,
                      ResourceFilter.Mode.INCLUDE,
                      List.of(new ResourceFilter.Exact(net.key(Items.COAL)))),
                  8);
            })
        .thenWaitUntil(
            () -> {
              var registry = net.saved().registry();
              h.assertTrue(
                  registry.stock(cell, net.key(Items.IRON_INGOT)).total() == 2,
                  "Both raw iron came back as ingots: ore "
                      + net.runtime().machinePresets().status(ore)
                      + " fuel "
                      + net.runtime().machinePresets().status(fuel)
                      + " furnace "
                      + ((FurnaceBlockEntity) h.getBlockEntity(furnace)).getItem(0)
                      + "/"
                      + ((FurnaceBlockEntity) h.getBlockEntity(furnace)).getItem(1)
                      + "/"
                      + ((FurnaceBlockEntity) h.getBlockEntity(furnace)).getItem(2));
              FurnaceBlockEntity entity = h.getBlockEntity(furnace);
              h.assertTrue(
                  entity.getItem(0).isEmpty() && entity.getItem(2).isEmpty(),
                  "The furnace's input and output are empty");
              h.assertTrue(
                  registry.stock(cell, net.key(Items.RAW_IRON)).total() == 0,
                  "No raw iron left in storage");
              long coal = registry.stock(cell, net.key(Items.COAL)).total();
              int burning = entity.getItem(1).getCount();
              h.assertTrue(
                  coal + burning <= 16 && coal + burning >= 15,
                  "Coal is only burned or stocked, never lost: " + coal + " + " + burning);
              end(owner);
            })
        .thenSucceed();
  }

  /**
   * Q100: the Exporter screen marks each exact filter rule: iron in storage, sticks craftable by a
   * Crafter's pattern, diamonds absent; a tag rule has no status.
   */
  @GameTest(template = "empty", timeoutTicks = 400)
  public static void exporterFilterRulesShowWhatStorageHolds(GameTestHelper h) {
    var owner = player(h);
    var net = Net.in(h, owner.getUUID());
    net.controller(ROOT);
    net.drive(DRIVE, CellTier.STARTER_ITEM);
    var cell = net.cell(DRIVE, 0);
    var crafterAt = new BlockPos(0, 2, 1);
    h.setBlock(crafterAt, dev.izzy.factorycore.platform.crafting.CraftingContent.CRAFTER.get());
    dev.izzy.factorycore.platform.crafting.CrafterEntity crafter = h.getBlockEntity(crafterAt);
    var cells =
        new java.util.ArrayList<ItemStack>(java.util.Collections.nCopies(9, ItemStack.EMPTY));
    cells.set(0, new ItemStack(Items.OAK_PLANKS));
    cells.set(3, new ItemStack(Items.OAK_PLANKS));
    var pattern =
        new ItemStack(
            dev.izzy.factorycore.platform.crafting.CraftingContent.CRAFTING_PATTERN.get());
    pattern.set(
        dev.izzy.factorycore.platform.crafting.CraftingContent.PATTERN.get(),
        new dev.izzy.factorycore.platform.crafting.PatternContents(
            java.util.UUID.randomUUID(), 1, 0, cells, new ItemStack(Items.STICK, 4), List.of()));
    crafter.patterns().insertItem(0, pattern, false);
    var exporter = place(h, PORT, PortBlock.Role.EXPORTER, Direction.EAST, owner);
    var filter =
        new ResourceFilter(
            ResourceKind.ITEM,
            ResourceFilter.Mode.INCLUDE,
            List.of(
                new ResourceFilter.Exact(net.key(Items.IRON_INGOT)),
                new ResourceFilter.Registry("minecraft:stick"),
                new ResourceFilter.Exact(net.key(Items.DIAMOND)),
                new ResourceFilter.Tag("minecraft:logs")));
    h.startSequence()
        .thenWaitUntil(() -> net.assertValid(PORT))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    net.saved().registry().stock(cell, net.key(Items.IRON_INGOT)).loaded(),
                    "Cell ready"))
        .thenExecute(
            () -> {
              net.saved().registry().insert(cell, net.key(Items.IRON_INGOT), 5);
              exporter.deployPreset(Actors.of(owner), MachinePresets.Mode.PAUSED, filter, 0);
              h.useBlock(PORT, owner);
            })
        .thenWaitUntil(
            () -> {
              h.assertTrue(
                  owner.containerMenu
                      instanceof dev.izzy.factorycore.platform.storage.MachineSettingsMenu,
                  "The Exporter screen is open");
              var menu =
                  (dev.izzy.factorycore.platform.storage.MachineSettingsMenu) owner.containerMenu;
              menu.broadcastChanges();
              var statuses = menu.presetView().statuses();
              h.assertTrue(
                  statuses.equals(
                      List.of(
                          dev.izzy.factorycore.platform.storage.MachinePresetPackets.RuleStatus
                              .STOCKED,
                          dev.izzy.factorycore.platform.storage.MachinePresetPackets.RuleStatus
                              .CRAFTABLE,
                          dev.izzy.factorycore.platform.storage.MachinePresetPackets.RuleStatus
                              .EMPTY,
                          dev.izzy.factorycore.platform.storage.MachinePresetPackets.RuleStatus
                              .UNKNOWN)),
                  "Iron stocked, sticks craftable, diamonds absent, the tag unknown: " + statuses);
            })
        .thenExecute(() -> end(owner))
        .thenSucceed();
  }

  /**
   * Q74: an Exporter with an ender pearl slot and a snowball slot, the cell holding 48 of each.
   * Both stack to 16, so each move sends one full stack into the chest's next empty slot, and the
   * chest's slots read back the order of the six moves ("P" pearls, "S" snowballs).
   */
  private static void exportTwo(
      GameTestHelper h, dev.izzy.factorycore.core.storage.ExportOrder order, String expected) {
    var owner = player(h);
    var net = Net.in(h, owner.getUUID());
    net.controller(ROOT);
    net.drive(DRIVE, CellTier.STARTER_ITEM);
    var cell = net.cell(DRIVE, 0);
    h.setBlock(TARGET, Blocks.CHEST);
    ChestBlockEntity chest = h.getBlockEntity(TARGET);
    var exporter = place(h, PORT, PortBlock.Role.EXPORTER, Direction.EAST, owner);
    var filter =
        new ResourceFilter(
            ResourceKind.ITEM,
            ResourceFilter.Mode.INCLUDE,
            List.of(
                new ResourceFilter.Exact(net.key(Items.ENDER_PEARL)),
                new ResourceFilter.Exact(net.key(Items.SNOWBALL))));
    h.startSequence()
        .thenWaitUntil(() -> net.assertValid(PORT))
        .thenExecute(
            () -> {
              net.saved().registry().insert(cell, net.key(Items.ENDER_PEARL), 48);
              net.saved().registry().insert(cell, net.key(Items.SNOWBALL), 48);
              exporter.exportOrder(order);
              var saved = exporter.saveWithoutMetadata(h.getLevel().registryAccess());
              h.assertTrue(
                  order == dev.izzy.factorycore.core.storage.ExportOrder.IN_ORDER
                      ? !saved.contains("ExportOrder")
                      : saved.getString("ExportOrder").equals(order.name()),
                  "The order is saved with the block: " + saved.getString("ExportOrder"));
              exporter.deployPreset(Actors.of(owner), MachinePresets.Mode.EXPORT, filter, 0);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    net.saved().registry().stock(cell, net.key(Items.ENDER_PEARL)).total() == 0
                        && net.saved().registry().stock(cell, net.key(Items.SNOWBALL)).total() == 0,
                    "Everything sent"))
        .thenExecute(
            () -> {
              var moves = new StringBuilder();
              for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                var stack = chest.getItem(slot);
                if (stack.isEmpty()) continue;
                h.assertTrue(stack.getCount() == 16, "Each move is one full stack");
                moves.append(stack.is(Items.ENDER_PEARL) ? 'P' : 'S');
              }
              h.assertTrue(moves.toString().equals(expected), order + " sends " + moves);
              end(owner);
            })
        .thenSucceed();
  }

  /** Q74: round robin takes turns between the two slots, one move each. */
  @GameTest(template = "empty", timeoutTicks = 600)
  public static void roundRobinExporterAlternatesTwoItems(GameTestHelper h) {
    exportTwo(h, dev.izzy.factorycore.core.storage.ExportOrder.ROUND_ROBIN, "PSPSPS");
  }

  /** Q74: In order (the default) sends the first slot until storage has no more of it. */
  @GameTest(template = "empty", timeoutTicks = 600)
  public static void inOrderExporterSendsTheFirstSlotFirst(GameTestHelper h) {
    exportTwo(h, dev.izzy.factorycore.core.storage.ExportOrder.IN_ORDER, "PPPSSS");
  }

  /**
   * Q76: an Exporter set to iron pickaxes below 50% durability sends only the worn one, leaving a
   * fresh and a lightly used pickaxe in storage. The slot cycle reaches the worn steps only for
   * damageable items.
   */
  @GameTest(template = "empty", timeoutTicks = 400)
  public static void wornFilterExportsOnlyWornTools(GameTestHelper h) {
    var owner = player(h);
    var net = Net.in(h, owner.getUUID());
    net.controller(ROOT);
    net.drive(DRIVE, CellTier.STARTER_ITEM);
    var cell = net.cell(DRIVE, 0);
    h.setBlock(TARGET, Blocks.CHEST);
    ChestBlockEntity chest = h.getBlockEntity(TARGET);
    var exporter = place(h, PORT, PortBlock.Role.EXPORTER, Direction.EAST, owner);
    int max = new ItemStack(Items.IRON_PICKAXE).getMaxDamage();
    ItemStack worn = new ItemStack(Items.IRON_PICKAXE), used = worn.copy(), fresh = worn.copy();
    worn.setDamageValue(max * 7 / 10); // 30% left
    used.setDamageValue(max / 5); // 80% left
    var codec = new PlatformResourceCodec(h.getLevel().registryAccess());
    var id = "minecraft:iron_pickaxe";
    // The slot cycle: Exact → worn 99/75/50/25 → the item's tags; stone has no worn steps.
    ResourceFilter.Term step = new ResourceFilter.Exact(codec.itemKey(fresh));
    var seen = new java.util.ArrayList<Integer>();
    for (int i = 0; i < 4; i++) {
      step = FilterEdits.cycle(ResourceKind.ITEM, step, id, codec);
      h.assertTrue(step instanceof ResourceFilter.Worn, "Worn step " + i + ": " + step);
      seen.add(((ResourceFilter.Worn) step).below());
    }
    h.assertTrue(seen.equals(List.of(99, 75, 50, 25)), "Steps " + seen);
    step = FilterEdits.cycle(ResourceKind.ITEM, step, id, codec);
    h.assertTrue(
        step instanceof ResourceFilter.Tag || step instanceof ResourceFilter.Registry,
        "After 25% come the tags: " + step);
    var stone =
        FilterEdits.cycle(
            ResourceKind.ITEM,
            new ResourceFilter.Exact(codec.itemKey(new ItemStack(Items.STONE))),
            "minecraft:stone",
            codec);
    h.assertTrue(!(stone instanceof ResourceFilter.Worn), "Stone is never worn: " + stone);
    var filter =
        new ResourceFilter(
            ResourceKind.ITEM,
            ResourceFilter.Mode.INCLUDE,
            List.of(new ResourceFilter.Worn(id, 50)));
    h.startSequence()
        .thenWaitUntil(() -> net.assertValid(PORT))
        .thenExecute(
            () -> {
              for (var stack : List.of(worn, used, fresh))
                net.saved().registry().insert(cell, codec.itemKey(stack), 1);
              exporter.deployPreset(Actors.of(owner), MachinePresets.Mode.EXPORT, filter, 0);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    net.saved().registry().stock(cell, codec.itemKey(worn)).total() == 0,
                    "The worn pickaxe leaves storage"))
        .thenIdle(40)
        .thenExecute(
            () -> {
              int sent = 0;
              for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                var stack = chest.getItem(slot);
                if (stack.isEmpty()) continue;
                h.assertTrue(
                    stack.getDamageValue() == worn.getDamageValue(), "Only the worn one moves");
                sent += stack.getCount();
              }
              h.assertTrue(sent == 1, "Exactly one pickaxe sent: " + sent);
              h.assertTrue(
                  net.saved().registry().stock(cell, codec.itemKey(used)).total() == 1
                      && net.saved().registry().stock(cell, codec.itemKey(fresh)).total() == 1,
                  "The fresh and lightly used pickaxes stay in storage");
              end(owner);
            })
        .thenSucceed();
  }
}
