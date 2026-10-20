package dev.izzy.factorycore.testing.power;

import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.storage.EnergyBankTier;
import dev.izzy.factorycore.core.storage.FuelGenerator;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.power.FuelGeneratorBlock;
import dev.izzy.factorycore.platform.power.FuelGeneratorContent;
import dev.izzy.factorycore.platform.power.FuelGeneratorEntity;
import dev.izzy.factorycore.platform.storage.EnergyBankContent;
import dev.izzy.factorycore.platform.storage.EnergyBankEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class FuelGeneratorGameTests {
  private static final BlockPos GENERATOR = new BlockPos(2, 2, 2);

  private FuelGeneratorGameTests() {}

  private static FuelGeneratorEntity place(GameTestHelper h) {
    h.setBlock(GENERATOR, FuelGeneratorContent.BLOCK.get());
    return h.getBlockEntity(GENERATOR);
  }

  private static void load(
      GameTestHelper h, FuelGeneratorEntity generator, long energy, long burn, long total) {
    var tag = generator.saveWithoutMetadata(h.getLevel().registryAccess());
    tag.putLong("Energy", energy);
    tag.putLong("Burn", burn);
    tag.putLong("BurnTotal", total);
    generator.loadWithComponents(tag, h.getLevel().registryAccess());
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void hopperFedStickBurnsToExactlyTwoThousandFe(GameTestHelper h) {
    var generator = place(h);
    var hopperPos = GENERATOR.above();
    h.setBlock(
        hopperPos, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
    HopperBlockEntity hopper = h.getBlockEntity(hopperPos);
    hopper.setItem(0, new ItemStack(Items.STICK));
    boolean[] lit = {false};
    h.startSequence()
        .thenWaitUntil(
            () -> {
              lit[0] |= h.getBlockState(GENERATOR).getValue(FuelGeneratorBlock.LIT);
              h.assertTrue(
                  generator.generator().energy() == 2_000
                      && generator.generator().status() == FuelGenerator.Status.NO_FUEL,
                  "One stick burns 100 ticks at 20 FE");
            })
        .thenExecute(
            () -> {
              h.assertTrue(lit[0], "Burning state is visible in the block state");
              h.assertTrue(
                  !h.getBlockState(GENERATOR).getValue(FuelGeneratorBlock.LIT),
                  "Idle generator is unlit");
              h.assertTrue(
                  hopper.isEmpty() && generator.slots().getStackInSlot(0).isEmpty(),
                  "Fuel consumed exactly once");
              h.assertTrue(
                  generator.blockedReason().equals("no_fuel"), "Idle reason asks for fuel");
              var refused =
                  generator.items(Direction.NORTH).insertItem(0, new ItemStack(Items.DIRT), false);
              h.assertTrue(refused.getCount() == 1, "Non-fuel is rejected by the fuel face");
              h.assertTrue(
                  generator
                          .items(Direction.DOWN)
                          .insertItem(0, new ItemStack(Items.STICK), false)
                          .getCount()
                      == 1,
                  "Bottom face never accepts fuel");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 100)
  public static void lavaBucketRemainderIsKeptAndExtractedBelow(GameTestHelper h) {
    var generator = place(h);
    var player = h.makeMockPlayer(GameType.SURVIVAL);
    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.LAVA_BUCKET));
    h.useBlock(GENERATOR, player);
    h.assertTrue(player.getMainHandItem().isEmpty(), "Player-inserted lava bucket left the hand");
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    generator.slots().getStackInSlot(FuelGeneratorEntity.REMAINDER).is(Items.BUCKET)
                        && generator.generator().burnTotal() == 20_000,
                    "Lava burns 20000 ticks and leaves its empty bucket"))
        .thenExecute(
            () -> {
              h.assertTrue(
                  generator.items(Direction.NORTH).extractItem(0, 1, false).isEmpty(),
                  "Side face cannot pull remainders or fuel");
              var bucket = generator.items(Direction.DOWN).extractItem(0, 1, false);
              h.assertTrue(
                  bucket.is(Items.BUCKET)
                      && generator.slots().getStackInSlot(FuelGeneratorEntity.REMAINDER).isEmpty(),
                  "Bottom face extracts exactly the remainder");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 100)
  public static void fullRemainderSlotKeepsFuelUntilEmptied(GameTestHelper h) {
    var generator = place(h);
    generator
        .slots()
        .setStackInSlot(FuelGeneratorEntity.REMAINDER, new ItemStack(Items.BUCKET, 16));
    generator.slots().setStackInSlot(FuelGeneratorEntity.FUEL, new ItemStack(Items.LAVA_BUCKET));
    h.startSequence()
        .thenIdle(5)
        .thenExecute(
            () -> {
              h.assertTrue(
                  generator.generator().burnRemaining() == 0
                      && generator
                          .slots()
                          .getStackInSlot(FuelGeneratorEntity.FUEL)
                          .is(Items.LAVA_BUCKET)
                      && generator.blockedReason().equals("remainder_full"),
                  "No fuel is consumed while its remainder has nowhere to go");
              generator.items(Direction.DOWN).extractItem(0, 1, false);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    generator.generator().burnRemaining() > 0
                        && generator
                                .slots()
                                .getStackInSlot(FuelGeneratorEntity.REMAINDER)
                                .getCount()
                            == 16
                        && generator.slots().getStackInSlot(FuelGeneratorEntity.FUEL).isEmpty(),
                    "Fuel ignites once space exists and the remainder fills it"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 100)
  public static void fullBufferRetainsFuelAndCapsCapabilityOutputPerTick(GameTestHelper h) {
    var generator = place(h);
    load(h, generator, 99_990, 0, 0);
    generator.slots().setStackInSlot(FuelGeneratorEntity.FUEL, new ItemStack(Items.COAL, 2));
    h.startSequence()
        .thenIdle(3)
        .thenExecute(
            () -> {
              h.assertTrue(
                  generator.slots().getStackInSlot(FuelGeneratorEntity.FUEL).getCount() == 2
                      && generator.generator().energy() == 99_990
                      && generator.blockedReason().equals("full"),
                  "Full buffer keeps all fuel");
              var energy =
                  h.getLevel()
                      .getCapability(
                          Capabilities.EnergyStorage.BLOCK,
                          h.absolutePos(GENERATOR),
                          Direction.EAST);
              h.assertTrue(
                  energy != null && !energy.canReceive() && energy.receiveEnergy(100, false) == 0,
                  "Generator never accepts FE");
              h.assertTrue(energy.extractEnergy(50, true) == 50, "Simulation reports");
              h.assertTrue(energy.extractEnergy(50, false) == 50, "First caller");
              h.assertTrue(energy.extractEnergy(50, false) == 30, "Shared 80 FE/tick cap");
              h.assertTrue(energy.extractEnergy(50, false) == 0, "Budget spent this tick");
              h.assertTrue(generator.generator().energy() == 99_910, "Exact FE removed");
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    generator.slots().getStackInSlot(FuelGeneratorEntity.FUEL).getCount() == 1
                        && generator.generator().burnTotal() == 1_600,
                    "Space for a burn tick consumes exactly one coal"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void pushIntoAdjacentBankConservesFeAtEightyPerTick(GameTestHelper h) {
    var generator = place(h);
    var bankPos = GENERATOR.east();
    h.setBlock(bankPos.east(), NetworkContent.CABLE.get());
    h.setBlock(bankPos.east(2), NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = h.getBlockEntity(bankPos.east(2));
    root.commission(UUID.randomUUID());
    h.setBlock(bankPos, EnergyBankContent.block(EnergyBankTier.STARTER));
    EnergyBankEntity bank = h.getBlockEntity(bankPos);
    bank.place(new ItemStack(EnergyBankContent.item(EnergyBankTier.STARTER)));
    load(h, generator, 1_000, 0, 0);
    long[] start = {0};
    h.startSequence()
        .thenIdle(1)
        .thenExecute(
            () ->
                h.assertTrue(
                    generator.generator().energy() == 1_000 && bank.getEnergyStored() == 0,
                    "An offline bank receives nothing and the generator keeps its FE"))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    WorldNetworks.get(h.getLevel().getServer())
                            .membership(Positions.of(h.getLevel(), h.absolutePos(bankPos)))
                            .status()
                        == NetworkTopology.Status.VALID,
                    "Bank network ready"))
        .thenExecute(() -> start[0] = h.getLevel().getGameTime())
        .thenWaitUntil(
            () -> {
              long elapsed = h.getLevel().getGameTime() - start[0] + 1;
              long received = bank.getEnergyStored();
              h.assertTrue(
                  received + generator.generator().energy() == 1_000,
                  "Every FE is in exactly one store");
              h.assertTrue(received <= 80 * elapsed, "At most 80 FE per tick");
              h.assertTrue(received == 1_000, "Bank eventually receives all FE");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 100)
  public static void breakingKeepsEnergyBurnFuelAndRemainder(GameTestHelper h) {
    var generator = place(h);
    h.startSequence()
        .thenExecute(
            () -> {
              load(h, generator, 1_234, 50, 100);
              generator
                  .slots()
                  .setStackInSlot(FuelGeneratorEntity.FUEL, new ItemStack(Items.COAL, 3));
              generator
                  .slots()
                  .setStackInSlot(FuelGeneratorEntity.REMAINDER, new ItemStack(Items.BUCKET));
              var expected = generator.generator().snapshot();
              h.getLevel().destroyBlock(h.absolutePos(GENERATOR), true);
              var drops =
                  h.getLevel()
                      .getEntitiesOfClass(
                          ItemEntity.class, new AABB(h.absolutePos(GENERATOR)).inflate(2));
              ItemStack device = ItemStack.EMPTY;
              var counts = new HashMap<String, Integer>();
              for (var drop : drops) {
                var stack = drop.getItem();
                if (stack.is(FuelGeneratorContent.ITEM.get())) device = stack.copy();
                counts.merge(
                    BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath(),
                    stack.getCount(),
                    Integer::sum);
                drop.discard();
              }
              h.assertTrue(
                  counts.equals(Map.of("fuel_generator", 1, "coal", 3, "bucket", 1)),
                  "Exactly the device, fuel and remainder drop: " + counts);
              h.assertTrue(
                  expected.equals(device.get(FuelGeneratorContent.STATE.get())),
                  "Drop carries stored FE and the partially burned fuel");
              h.setBlock(GENERATOR, FuelGeneratorContent.BLOCK.get());
              FuelGeneratorEntity restored = h.getBlockEntity(GENERATOR);
              restored.applyComponentsFromItemStack(device);
              h.assertTrue(
                  restored.generator().snapshot().equals(expected),
                  "Placement restores the exact state");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 100)
  public static void futureSchemaIsRetainedInertAcrossBreakAndPlace(GameTestHelper h) {
    var generator = place(h);
    var future = new CompoundTag();
    future.putInt("Schema", 2);
    future.putLong("Energy", 5_000);
    future.putString("Future", "kept");
    generator.loadWithComponents(future, h.getLevel().registryAccess());
    h.startSequence()
        .thenIdle(2)
        .thenExecute(
            () -> {
              h.assertTrue(!generator.diagnostic().isEmpty(), "Future data is diagnosed");
              h.assertTrue(
                  generator.energy().extractEnergy(80, false) == 0
                      && generator.energy().getEnergyStored() == 0,
                  "Unsupported data is never spent");
              h.assertTrue(
                  generator
                          .items(Direction.UP)
                          .insertItem(0, new ItemStack(Items.COAL), false)
                          .getCount()
                      == 1,
                  "Unsupported data accepts no fuel");
              var saved = generator.saveWithoutMetadata(h.getLevel().registryAccess());
              h.assertTrue(saved.equals(future), "Saved bytes are unchanged: " + saved);
              h.getLevel().destroyBlock(h.absolutePos(GENERATOR), true);
              var drops =
                  h.getLevel()
                      .getEntitiesOfClass(
                          ItemEntity.class, new AABB(h.absolutePos(GENERATOR)).inflate(2));
              h.assertTrue(drops.size() == 1, "Only the device drops");
              var device = drops.get(0).getItem().copy();
              drops.get(0).discard();
              h.assertTrue(
                  device.get(FuelGeneratorContent.RETAINED.get()) != null
                      && device.get(FuelGeneratorContent.STATE.get()) == null,
                  "Drop carries retained data and no fabricated state");
              h.setBlock(GENERATOR, FuelGeneratorContent.BLOCK.get());
              FuelGeneratorEntity placed = h.getBlockEntity(GENERATOR);
              placed.applyComponentsFromItemStack(device);
              h.assertTrue(
                  !placed.diagnostic().isEmpty()
                      && placed.saveWithoutMetadata(h.getLevel().registryAccess()).equals(future),
                  "Placement retains the future data again");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty")
  public static void recipeUsesVanillaMaterialsAndUnlocksFromFurnace(GameTestHelper h) {
    var id = ResourceLocation.fromNamespaceAndPath("factorycore", "fuel_generator");
    var recipe = h.getLevel().getRecipeManager().byKey(id).orElseThrow().value();
    var costs = new HashMap<String, Integer>();
    for (var ingredient : recipe.getIngredients())
      for (var stack : ingredient.getItems()) {
        var item = BuiltInRegistries.ITEM.getKey(stack.getItem());
        h.assertTrue(item.getNamespace().equals("minecraft"), "Vanilla ingredients only");
        costs.merge(item.getPath(), 1, Integer::sum);
      }
    h.assertTrue(
        costs.equals(Map.of("iron_ingot", 4, "copper_ingot", 3, "furnace", 1, "redstone", 1)),
        "Exact generator recipe: " + costs);
    var result = recipe.getResultItem(h.getLevel().registryAccess());
    h.assertTrue(
        result.is(FuelGeneratorContent.ITEM.get())
            && result.getCount() == 1
            && result.get(FuelGeneratorContent.STATE.get()) == null,
        "One empty generator");
    h.assertTrue(
        h.getLevel()
                .getServer()
                .getAdvancements()
                .get(
                    ResourceLocation.fromNamespaceAndPath(
                        "factorycore", "recipes/misc/fuel_generator"))
            != null,
        "Recipe book unlock loaded");
    h.succeed();
  }
}
