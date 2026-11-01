package dev.izzy.factorycore.testing.crafting;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.crafting.CrafterEntity;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.crafting.PatternContents;
import dev.izzy.factorycore.platform.crafting.WorldCraftingJobs;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
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

/** Q6 (QD3): stonecutter and smithing patterns run through jobs inside a Crafter. */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class TablePatternGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(2, 1, 1),
      UNIT = new BlockPos(1, 1, 2);

  private TablePatternGameTests() {}

  private record Fixture(
      UUID owner,
      NodeRegistry.Position origin,
      DeviceSavedData saved,
      DeviceRegistry.Handle cell,
      PlatformResourceCodec codec) {
    ResourceKey key(Item item) {
      return codec.itemKey(new ItemStack(item));
    }

    long stock(Item item) {
      return saved.registry().stock(cell, key(item)).total();
    }
  }

  private static ItemStack pattern(PatternContents.Table table, List<Item> inputs, Item output) {
    var cells = new ArrayList<ItemStack>(Collections.nCopies(9, ItemStack.EMPTY));
    for (int index = 0; index < inputs.size(); index++)
      cells.set(index, new ItemStack(inputs.get(index)));
    var stack = new ItemStack(CraftingContent.CRAFTING_PATTERN.get());
    stack.set(
        CraftingContent.PATTERN.get(),
        new PatternContents(
            UUID.randomUUID(),
            1,
            0,
            cells,
            new ItemStack(output),
            List.of(),
            Collections.nCopies(9, List.of()),
            table));
    return stack;
  }

  private static Fixture setup(GameTestHelper h, ItemStack pattern) {
    var owner = UUID.randomUUID();
    Net.in(h, owner).controller(ROOT);
    StorageDriveEntity drive = Net.in(h).drive(DRIVE, CellTier.STARTER_ITEM);
    h.setBlock(UNIT, CraftingContent.CRAFTER.get());
    CrafterEntity unit = h.getBlockEntity(UNIT);
    unit.patterns().insertItem(0, pattern, false);
    return new Fixture(
        owner,
        Positions.of(h.getLevel(), h.absolutePos(UNIT)),
        DeviceSavedData.get(h.getLevel().getServer()),
        CellHandleData.read(drive.cell(0)),
        new PlatformResourceCodec(h.getLevel().registryAccess()));
  }

  private static void ready(GameTestHelper h, Fixture f) {
    h.assertTrue(
        WorldNetworks.get(h.getLevel().getServer()).membership(f.origin).status()
                == NetworkTopology.Status.VALID
            && f.saved.registry().stock(f.cell, f.key(Items.STONE)).loaded(),
        "Network and cell ready");
  }

  /** Stocks {@code inputs}, requests {@code amount} of {@code output} and waits for the job. */
  private static void craft(
      GameTestHelper h, Fixture f, List<Item> inputs, int each, Item output, int amount) {
    UUID[] id = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              for (var input : inputs) f.saved.registry().insert(f.cell, f.key(input), each);
              var submission =
                  WorldCraftingJobs.get(h.getLevel().getServer())
                      .submit(
                          new NetworkPermissions.Actor(f.owner, false),
                          f.origin,
                          f.key(output),
                          amount);
              h.assertTrue(submission.job() != null, "Submitted: " + submission.reason());
              id[0] = submission.job();
            })
        .thenWaitUntil(
            () -> h.assertTrue(f.saved.craftingJobs().get(id[0]) == null, "Job finishes"))
        .thenExecute(
            () -> {
              h.assertTrue(
                  f.stock(output) == amount,
                  "Delivered " + f.stock(output) + " of " + amount + " " + output);
              for (var input : inputs)
                h.assertTrue(
                    f.stock(input) == each - amount, "Used exactly one " + input + " per craft");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void aStonecutterPatternCutsStoneBricksInACrafter(GameTestHelper h) {
    var inputs = List.of(Items.STONE);
    var f = setup(h, pattern(PatternContents.Table.STONECUTTING, inputs, Items.STONE_BRICKS));
    craft(h, f, inputs, 6, Items.STONE_BRICKS, 4);
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void aSmithingPatternUpgradesASwordToNetheriteInACrafter(GameTestHelper h) {
    var inputs =
        List.of(
            Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, Items.DIAMOND_SWORD, Items.NETHERITE_INGOT);
    var f = setup(h, pattern(PatternContents.Table.SMITHING, inputs, Items.NETHERITE_SWORD));
    craft(h, f, inputs, 2, Items.NETHERITE_SWORD, 1);
  }

  private static net.minecraft.resources.RegistryOps<net.minecraft.nbt.Tag> ops(GameTestHelper h) {
    return h.getLevel()
        .registryAccess()
        .createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
  }

  @GameTest(template = "empty")
  public static void tablePatternsRefuseCellsThatDoNotFitTheirWorkstation(GameTestHelper h) {
    boolean refused;
    try {
      pattern(
          PatternContents.Table.STONECUTTING,
          List.of(Items.STONE, Items.STONE),
          Items.STONE_BRICKS);
      refused = false;
    } catch (IllegalArgumentException expected) {
      refused = true;
    }
    h.assertTrue(refused, "A stonecutter pattern holds one input in the first cell");
    var saved =
        pattern(
                PatternContents.Table.SMITHING,
                List.of(
                    Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE,
                    Items.DIAMOND_SWORD,
                    Items.NETHERITE_INGOT),
                Items.NETHERITE_SWORD)
            .get(CraftingContent.PATTERN.get());
    var encoded = PatternContents.CODEC.encodeStart(ops(h), saved).getOrThrow();
    var decoded = PatternContents.CODEC.parse(ops(h), encoded).getOrThrow();
    h.assertTrue(
        decoded.equals(saved) && decoded.table() == PatternContents.Table.SMITHING,
        "The workstation survives saving");
    h.succeed();
  }
}
