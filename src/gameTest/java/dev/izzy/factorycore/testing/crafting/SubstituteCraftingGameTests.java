package dev.izzy.factorycore.testing.crafting;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.crafting.CrafterEntity;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.crafting.PatternContents;
import dev.izzy.factorycore.platform.crafting.WorldCraftingJobs;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.testing.Net;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * E1 (PD4): a crafting pattern encoded with substitutes crafts chests from mixed oak and birch
 * planks, revalidating the real recipe on each mixed grid; an exact pattern still refuses birch.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class SubstituteCraftingGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(2, 1, 1),
      UNIT = new BlockPos(1, 1, 2);
  private static final int[] RING = {0, 1, 2, 3, 5, 6, 7, 8};

  private SubstituteCraftingGameTests() {}

  private static ItemStack chestPattern(boolean substitutes) {
    var cells = new ArrayList<ItemStack>(Collections.nCopies(9, ItemStack.EMPTY));
    var alternatives = new ArrayList<List<String>>(Collections.nCopies(9, List.<String>of()));
    for (int cell : RING) {
      cells.set(cell, new ItemStack(Items.OAK_PLANKS));
      if (substitutes) alternatives.set(cell, List.of("minecraft:birch_planks"));
    }
    var stack = new ItemStack(CraftingContent.CRAFTING_PATTERN.get());
    stack.set(
        CraftingContent.PATTERN.get(),
        new PatternContents(
            UUID.randomUUID(), 1, 0, cells, new ItemStack(Items.CHEST), List.of(), alternatives));
    return stack;
  }

  private record Fixture(
      UUID owner,
      DeviceSavedData saved,
      dev.izzy.factorycore.core.storage.DeviceRegistry.Handle cell,
      dev.izzy.factorycore.core.network.NodeRegistry.Position origin,
      PlatformResourceCodec codec) {
    long stock(Item item) {
      return saved.registry().stock(cell, codec.itemKey(new ItemStack(item))).total();
    }

    void store(Item item, long count) {
      saved.registry().insert(cell, codec.itemKey(new ItemStack(item)), count);
    }
  }

  private static Fixture setup(GameTestHelper h, boolean substitutes) {
    var owner = UUID.randomUUID();
    Net.in(h, owner).controller(ROOT);
    var drive = Net.in(h).drive(DRIVE, CellTier.STARTER_ITEM);
    h.setBlock(UNIT, CraftingContent.CRAFTER.get());
    CrafterEntity unit = h.getBlockEntity(UNIT);
    unit.patterns().insertItem(0, chestPattern(substitutes), false);
    return new Fixture(
        owner,
        DeviceSavedData.get(h.getLevel().getServer()),
        CellHandleData.read(drive.cell(0)),
        Positions.of(h.getLevel(), h.absolutePos(UNIT)),
        new PlatformResourceCodec(h.getLevel().registryAccess()));
  }

  private static void ready(GameTestHelper h, Fixture f) {
    h.assertTrue(
        WorldNetworks.get(h.getLevel().getServer()).membership(f.origin).status()
                == NetworkTopology.Status.VALID
            && f.saved
                .registry()
                .stock(f.cell, f.codec.itemKey(new ItemStack(Items.CHEST)))
                .loaded(),
        "Network and cell ready");
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void substitutingPatternCraftsChestsFromMixedPlanks(GameTestHelper h) {
    var f = setup(h, true);
    var jobs = WorldCraftingJobs.get(h.getLevel().getServer());
    UUID[] id = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.store(Items.OAK_PLANKS, 4);
              f.store(Items.BIRCH_PLANKS, 12);
              var submission =
                  jobs.submit(
                      new NetworkPermissions.Actor(f.owner, false),
                      f.origin,
                      f.codec.itemKey(new ItemStack(Items.CHEST)),
                      2);
              h.assertTrue(submission.job() != null, "Submitted: " + submission.reason());
              id[0] = submission.job();
            })
        .thenWaitUntil(
            () -> h.assertTrue(f.saved.craftingJobs().get(id[0]) == null, "The job finishes"))
        .thenExecute(
            () ->
                h.assertTrue(
                    f.stock(Items.CHEST) == 2
                        && f.stock(Items.OAK_PLANKS) == 0
                        && f.stock(Items.BIRCH_PLANKS) == 0,
                    "Two chests from 4 oak and 12 birch planks (the second craft mixes them): chests "
                        + f.stock(Items.CHEST)
                        + " oak "
                        + f.stock(Items.OAK_PLANKS)
                        + " birch "
                        + f.stock(Items.BIRCH_PLANKS)))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void exactPatternStillRefusesBirch(GameTestHelper h) {
    var f = setup(h, false);
    var jobs = WorldCraftingJobs.get(h.getLevel().getServer());
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.store(Items.BIRCH_PLANKS, 16);
              var submission =
                  jobs.submit(
                      new NetworkPermissions.Actor(f.owner, false),
                      f.origin,
                      f.codec.itemKey(new ItemStack(Items.CHEST)),
                      2);
              h.assertTrue(
                  submission.job() == null
                      && submission.reason().equals("missing")
                      && f.stock(Items.BIRCH_PLANKS) == 16,
                  "Without substitutes birch is not oak: " + submission.reason());
            })
        .thenSucceed();
  }

  private static void command(
      dev.izzy.factorycore.platform.storage.TerminalMenu menu,
      net.minecraft.server.level.ServerPlayer player,
      int action,
      int cell,
      int source,
      byte[] fingerprint) {
    menu.gridCommand(
        player,
        new dev.izzy.factorycore.platform.storage.TerminalCraftingPackets.Command(
            menu.containerId,
            menu.session(),
            menu.view().revision(),
            menu.gridView().gridRevision(),
            action,
            cell,
            source,
            1,
            fingerprint));
  }

  /** The terminal's Subst. toggle records each cell's recipe alternatives; off stays exact. */
  @GameTest(template = "empty", timeoutTicks = 300)
  public static void terminalEncodesSubstitutesFromTheRecipeOnlyWhenAsked(GameTestHelper h) {
    var player = dev.izzy.factorycore.testing.TestPlayers.mock(h);
    dev.izzy.factorycore.testing.TestPlayers.connect(player);
    player.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(h.absolutePos(ROOT)));
    player.getInventory().clearContent();
    Net.in(h, player.getUUID()).controller(ROOT);
    var terminalAt = new BlockPos(0, 1, 1);
    h.setBlock(terminalAt, dev.izzy.factorycore.platform.storage.TerminalContent.BLOCK.get());
    dev.izzy.factorycore.platform.storage.TerminalEntity terminal = h.getBlockEntity(terminalAt);
    var inventory = player.getInventory();
    inventory.setItem(0, new ItemStack(Items.OAK_PLANKS, 8));
    inventory.setItem(10, new ItemStack(CraftingContent.BLANK_PATTERN.get(), 2));
    var codec = new PlatformResourceCodec(player.registryAccess());
    var oak = codec.itemKey(new ItemStack(Items.OAK_PLANKS));
    var birch = codec.itemKey(new ItemStack(Items.BIRCH_PLANKS));
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(terminal.canUse(player), "Terminal ready"))
        .thenExecute(
            () -> {
              h.useBlock(terminalAt, player);
              var menu = (dev.izzy.factorycore.platform.storage.TerminalMenu) player.containerMenu;
              for (int cell : RING)
                command(
                    menu,
                    player,
                    dev.izzy.factorycore.platform.storage.TerminalCraftingPackets.SET_OWN,
                    cell,
                    0,
                    dev.izzy.factorycore.platform.storage.TerminalPackets.fingerprint(oak));
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              var menu = (dev.izzy.factorycore.platform.storage.TerminalMenu) player.containerMenu;
              command(
                  menu,
                  player,
                  dev.izzy.factorycore.platform.storage.TerminalCraftingPackets.ENCODE,
                  -1,
                  1,
                  new byte[32]);
              menu.broadcastChanges();
              h.assertTrue(menu.gridView().status().equals("encoded"), "Encoded with substitutes");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              var menu = (dev.izzy.factorycore.platform.storage.TerminalMenu) player.containerMenu;
              command(
                  menu,
                  player,
                  dev.izzy.factorycore.platform.storage.TerminalCraftingPackets.ENCODE,
                  -1,
                  0,
                  new byte[32]);
              menu.broadcastChanges();
              var patterns = new ArrayList<dev.izzy.factorycore.core.crafting.Pattern>();
              for (int slot = 0; slot < 36; slot++)
                if (inventory.getItem(slot).is(CraftingContent.CRAFTING_PATTERN.get()))
                  patterns.add(
                      inventory.getItem(slot).get(CraftingContent.PATTERN.get()).pattern(codec));
              h.assertTrue(patterns.size() == 2, "Two patterns encoded: " + patterns.size());
              var substituting = patterns.stream().filter(p -> !p.substitutes().isEmpty()).toList();
              var exact = patterns.stream().filter(p -> p.substitutes().isEmpty()).toList();
              h.assertTrue(
                  substituting.size() == 1
                      && exact.size() == 1
                      && substituting.getFirst().substitutes().get(oak).contains(birch)
                      && !substituting.getFirst().substitutes().get(oak).contains(oak),
                  "One pattern lists the recipe's other planks for oak, the other is exact: "
                      + substituting);
            })
        .thenSucceed();
  }
}
