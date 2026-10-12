package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.core.storage.EnergyBankTier;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.EnergyBankContent;
import dev.izzy.factorycore.platform.storage.EnergyBankEntity;
import dev.izzy.factorycore.platform.storage.EnergyBankHandleData;
import dev.izzy.factorycore.platform.storage.StorageContent;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class EnergyBankGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1);
  private static final BlockPos CABLE = new BlockPos(2, 1, 1);
  private static final BlockPos BANK = new BlockPos(3, 1, 1);

  private EnergyBankGameTests() {}

  private static EnergyBankEntity setup(GameTestHelper helper) {
    helper.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = helper.getBlockEntity(ROOT);
    root.commission(UUID.randomUUID());
    helper.setBlock(CABLE, NetworkContent.CABLE.get());
    helper.setBlock(BANK, EnergyBankContent.block(EnergyBankTier.STARTER));
    EnergyBankEntity bank = helper.getBlockEntity(BANK);
    bank.place(new ItemStack(EnergyBankContent.item(EnergyBankTier.STARTER)));
    return bank;
  }

  private static void ready(GameTestHelper helper) {
    ready(helper, BANK);
  }

  private static void ready(GameTestHelper helper, BlockPos bankPosition) {
    var position =
        new NodeRegistry.Position(
            helper.getLevel().dimension().location().toString(),
            helper.absolutePos(bankPosition).asLong());
    helper.assertTrue(
        WorldNetworks.get(helper.getLevel().getServer()).membership(position).status()
            == NetworkTopology.Status.VALID,
        "Bank network ready");
  }

  private static DeviceRegistry registry(GameTestHelper helper) {
    return DeviceSavedData.get(helper.getLevel().getServer()).registry();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void creativePlacementConsumesLeaseAndBreakDropsExactlyOnePopulatedBank(
      GameTestHelper helper) {
    helper.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = helper.getBlockEntity(ROOT);
    root.commission(UUID.randomUUID());
    helper.setBlock(CABLE, NetworkContent.CABLE.get());
    helper.setBlock(BANK.below(), Blocks.STONE);
    var player = helper.makeMockPlayer(GameType.CREATIVE);
    var stack = new ItemStack(EnergyBankContent.item(EnergyBankTier.STARTER));
    player.setItemInHand(InteractionHand.MAIN_HAND, stack);
    var hit =
        new net.minecraft.world.phys.BlockHitResult(
            net.minecraft.world.phys.Vec3.atCenterOf(helper.absolutePos(BANK.below())),
            Direction.UP,
            helper.absolutePos(BANK.below()),
            false);
    var context =
        new net.minecraft.world.item.context.BlockPlaceContext(
            helper.getLevel(), player, InteractionHand.MAIN_HAND, stack, hit);
    var result = ((net.minecraft.world.item.BlockItem) stack.getItem()).place(context);
    helper.assertTrue(
        result.consumesAction() && stack.isEmpty(),
        "Creative placement consumes physical reference");
    EnergyBankEntity bank = helper.getBlockEntity(BANK);
    var placed = bank.handle();
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper))
        .thenExecute(
            () -> {
              bank.receiveEnergy(81, false);
              helper.getLevel().destroyBlock(helper.absolutePos(BANK), true);
              var drops =
                  helper
                      .getLevel()
                      .getEntitiesOfClass(
                          net.minecraft.world.entity.item.ItemEntity.class,
                          new net.minecraft.world.phys.AABB(helper.absolutePos(BANK)).inflate(0.8),
                          e -> e.getItem().is(EnergyBankContent.item(EnergyBankTier.STARTER)));
              helper.assertTrue(
                  drops.size() == 1 && drops.getFirst().getItem().getCount() == 1,
                  "Break emits one portable bank, with no second loot copy");
              var lease =
                  EnergyBankHandleData.read(
                      drops
                          .getFirst()
                          .getItem()
                          .get(DataComponents.CUSTOM_DATA)
                          .copyTag()
                          .get(EnergyBankHandleData.FIELD));
              helper.assertTrue(
                  registry(helper).validate(placed) == DeviceRegistry.Failure.STALE
                      && lease.backing().equals(placed.backing())
                      && registry(helper).stock(lease, ResourceKey.ENERGY).total() == 81
                      && !registry(helper).stock(lease, ResourceKey.ENERGY).loaded(),
                  "Exact energy moves once into offline portable owner");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void allFacesShareRatesSimulationAndReservationsWithImmediateDisconnection(
      GameTestHelper helper) {
    var bank = setup(helper);
    helper.assertTrue(bank.receiveEnergy(500, false) == 0, "Offline before validation");
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper))
        .thenExecute(
            () -> {
              for (var side : Direction.values()) {
                var port =
                    helper
                        .getLevel()
                        .getCapability(
                            Capabilities.EnergyStorage.BLOCK, helper.absolutePos(BANK), side);
                helper.assertTrue(
                    port != null && port.receiveEnergy(1000, true) == 1000,
                    "All faces expose real advisory capability");
              }
              var north =
                  helper
                      .getLevel()
                      .getCapability(
                          Capabilities.EnergyStorage.BLOCK,
                          helper.absolutePos(BANK),
                          Direction.NORTH);
              var south =
                  helper
                      .getLevel()
                      .getCapability(
                          Capabilities.EnergyStorage.BLOCK,
                          helper.absolutePos(BANK),
                          Direction.SOUTH);
              helper.assertTrue(
                  north.receiveEnergy(700, false) == 700
                      && south.receiveEnergy(700, false) == 300
                      && bank.receiveEnergy(1, false) == 0,
                  "Combined face ceiling");
              var job = UUID.randomUUID();
              registry(helper).reserve(bank.handle(), job, ResourceKey.ENERGY, 600);
              helper.assertTrue(
                  south.extractEnergy(1000, true) == 400 && north.extractEnergy(1000, false) == 400,
                  "Claims take precedence over foreign pipes");
              helper.assertTrue(
                  bank.getEnergyStored() == 600 && bank.getMaxEnergyStored() == 100000,
                  "Real capacity and stored energy");
              helper.setBlock(CABLE, Blocks.AIR);
              helper.assertTrue(
                  north.receiveEnergy(1, false) == 0 && south.extractEnergy(1, false) == 0,
                  "Cached capabilities cannot bypass removed wire");
              helper.assertTrue(
                  registry(helper).stock(bank.handle(), ResourceKey.ENERGY).total() == 600
                      && registry(helper).stock(bank.handle(), ResourceKey.ENERGY).reserved()
                          == 600,
                  "Disconnection retains exact claims");
              helper.setBlock(CABLE, NetworkContent.CABLE.get());
            })
        .thenWaitUntil(() -> ready(helper))
        .thenExecute(
            () ->
                helper.assertTrue(
                    bank.getEnergyStored() == 600 && bank.receiveEnergy(1000, false) == 1000,
                    "Reconnect starts a new tick without loss"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void populatedPortableModuleUpgradesKeepClaimsComponentsAndRejectInfinite(
      GameTestHelper helper) {
    var bank = setup(helper);
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper))
        .thenExecute(
            () -> {
              bank.receiveEnergy(999, false);
              var job = UUID.randomUUID();
              registry(helper).reserve(bank.handle(), job, ResourceKey.ENERGY, 123);
              var old = bank.handle();
              var portable = bank.portableForBreak();
              var lease =
                  EnergyBankHandleData.read(
                      portable
                          .get(DataComponents.CUSTOM_DATA)
                          .copyTag()
                          .get(EnergyBankHandleData.FIELD));
              helper.assertTrue(
                  registry(helper).validate(old) == DeviceRegistry.Failure.STALE,
                  "Placed copy consumed on removal");
              portable.set(DataComponents.CUSTOM_NAME, Component.literal("Factory reserve"));
              var player = helper.makeMockPlayer(GameType.SURVIVAL);
              player.setItemInHand(InteractionHand.OFF_HAND, portable);
              for (var module :
                  new dev.izzy.factorycore.platform.storage.CellUpgradeItem[] {
                    StorageContent.EXPANSION.get(), StorageContent.ADVANCED.get()
                  }) {
                var ingredient = new ItemStack(module);
                player.setItemInHand(InteractionHand.MAIN_HAND, ingredient);
                helper.assertTrue(
                    module.use(helper.getLevel(), player, InteractionHand.MAIN_HAND).getResult()
                            == InteractionResult.CONSUME
                        && ingredient.isEmpty(),
                    "Sequential module consumed after successful upgrade");
                helper.assertTrue(
                    player.getOffhandItem().getHoverName().getString().equals("Factory reserve"),
                    "Custom component retained");
              }
              player.setItemInHand(
                  InteractionHand.MAIN_HAND, new ItemStack(StorageContent.INFINITE.get()));
              helper.assertTrue(
                  StorageContent.INFINITE
                              .get()
                              .use(helper.getLevel(), player, InteractionHand.MAIN_HAND)
                              .getResult()
                          == InteractionResult.FAIL
                      && player.getMainHandItem().getCount() == 1,
                  "Infinite energy rejected without consuming ingredient");
              helper.assertTrue(
                  registry(helper).definition(lease).capacity() == 10000000
                      && registry(helper).stock(lease, ResourceKey.ENERGY).total() == 999
                      && registry(helper).stock(lease, ResourceKey.ENERGY).reserved() == 123,
                  "Same canonical owner retains stock and claims");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void lifecycleReloadAndCopiedPlacedBlockQuarantineWithoutEnergyCopy(
      GameTestHelper helper) {
    var bank = setup(helper);
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper))
        .thenExecute(
            () -> {
              bank.receiveEnergy(53, false);
              var saved = bank.saveWithFullMetadata(helper.getLevel().registryAccess());
              bank.setRemoved();
              helper.assertTrue(
                  bank.extractEnergy(1, false) == 0, "Retained capability rejects removed entity");
              bank.clearRemoved();
              bank.loadWithComponents(saved, helper.getLevel().registryAccess());
              bank.onLoad();
              helper.assertTrue(
                  !registry(helper).stock(bank.handle(), ResourceKey.ENERGY).loaded(),
                  "Reload requires topology revalidation");
            })
        .thenWaitUntil(() -> ready(helper))
        .thenExecute(
            () -> {
              helper.assertTrue(bank.getEnergyStored() == 53, "Lifecycle reload exact stock");
              var copyPos = new BlockPos(3, 1, 3);
              helper.setBlock(copyPos, EnergyBankContent.block(EnergyBankTier.STARTER));
              EnergyBankEntity copy = helper.getBlockEntity(copyPos);
              copy.loadWithComponents(
                  bank.saveWithFullMetadata(helper.getLevel().registryAccess()),
                  helper.getLevel().registryAccess());
              copy.onLoad();
              helper.assertTrue(
                  !copy.diagnostic().isEmpty()
                      && copy.extractEnergy(10, false) == 0
                      && bank.extractEnergy(10, false) == 0,
                  "Both copied references quarantined");
              helper.assertTrue(
                  registry(helper).snapshot().devices().stream()
                      .anyMatch(
                          d ->
                              d.ledger().id().equals(bank.handle().backing())
                                  && d.ledger().contents().get(ResourceKey.ENERGY) == 53),
                  "Canonical energy retained exactly once");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void brokenPopulatedBankUpgradesAndReplacesItsPhysicalOwner(GameTestHelper helper) {
    var original = setup(helper);
    var oldLease = original.handle();
    var job = UUID.randomUUID();
    var moved = new BlockPos(2, 1, 2);
    EnergyBankEntity[] replacement = {null};
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper))
        .thenExecute(
            () -> {
              helper.assertTrue(
                  original.receiveEnergy(700, false) == 700, "Seed real bank capability");
              registry(helper).reserve(oldLease, job, ResourceKey.ENERGY, 200);
              helper.getLevel().destroyBlock(helper.absolutePos(BANK), true);
              var drops =
                  helper
                      .getLevel()
                      .getEntitiesOfClass(
                          net.minecraft.world.entity.item.ItemEntity.class,
                          new net.minecraft.world.phys.AABB(helper.absolutePos(BANK)).inflate(2),
                          e -> e.getItem().is(EnergyBankContent.item(EnergyBankTier.STARTER)));
              helper.assertTrue(drops.size() == 1, "One physical portable bank");
              var player = helper.makeMockPlayer(GameType.SURVIVAL);
              player.setItemInHand(InteractionHand.OFF_HAND, drops.getFirst().getItem().copy());
              drops.getFirst().discard();
              player.setItemInHand(
                  InteractionHand.MAIN_HAND, new ItemStack(StorageContent.EXPANSION.get()));
              helper.assertTrue(
                  StorageContent.EXPANSION
                          .get()
                          .use(helper.getLevel(), player, InteractionHand.MAIN_HAND)
                          .getResult()
                      == InteractionResult.CONSUME,
                  "Upgrade actual dropped bank");
              helper.setBlock(moved, EnergyBankContent.block(EnergyBankTier.EXPANDED));
              replacement[0] = helper.getBlockEntity(moved);
              replacement[0].place(player.getOffhandItem());
              player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
              helper.assertTrue(
                  original.receiveEnergy(1, false) == 0 && original.extractEnergy(1, false) == 0,
                  "Retained old entity capability cannot mutate replacement");
              helper.assertTrue(
                  replacement[0].receiveEnergy(1, false) == 0
                      && replacement[0].extractEnergy(1, false) == 0,
                  "Physical replacement requires wired revalidation");
              helper.assertTrue(
                  registry(helper).validate(oldLease) == DeviceRegistry.Failure.STALE,
                  "Consumed old placed lease");
            })
        .thenWaitUntil(() -> ready(helper, moved))
        .thenExecute(
            () -> {
              var bank = replacement[0];
              helper.assertTrue(
                  bank.handle().backing().equals(oldLease.backing())
                      && bank.getMaxEnergyStored() == 1000000
                      && bank.getEnergyStored() == 700
                      && registry(helper).stock(bank.handle(), ResourceKey.ENERGY).reserved()
                          == 200,
                  "Physical upgraded bank keeps canonical identity, energy and claims");
              helper.assertTrue(
                  bank.extractEnergy(1000, false) == 500,
                  "Foreign capability cannot take preserved claim");
              helper.assertTrue(
                  registry(helper).extract(bank.handle(), ResourceKey.ENERGY, 200, job).amount()
                      == 200,
                  "Original claim owner can consume retained reservation");
              helper.assertTrue(
                  bank.receiveEnergy(5000, false) == 4000,
                  "Expanded physical tier applies its new input rate");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty")
  public static void starterRecipeUsesVanillaMaterialsAndUnlocksWithoutConsumingBank(
      GameTestHelper helper) {
    var id =
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
            "factorycore", "starter_energy_bank");
    var recipe = helper.getLevel().getRecipeManager().byKey(id).orElseThrow().value();
    var costs = new java.util.HashMap<String, Integer>();
    for (var ingredient : recipe.getIngredients())
      for (var stack : ingredient.getItems()) {
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        helper.assertTrue(
            item.getNamespace().equals("minecraft"), "No bank reference consumed by crafting");
        costs.merge(item.getPath(), 1, Integer::sum);
      }
    helper.assertTrue(
        costs.equals(java.util.Map.of("iron_ingot", 4, "redstone", 4, "copper_ingot", 1)),
        "Exact starter balance recipe");
    var result = recipe.getResultItem(helper.getLevel().registryAccess());
    helper.assertTrue(
        result.is(EnergyBankContent.item(EnergyBankTier.STARTER))
            && result.getCount() == 1
            && result.get(DataComponents.CUSTOM_DATA) == null,
        "One empty unbound bank");
    helper.assertTrue(
        helper
                .getLevel()
                .getServer()
                .getAdvancements()
                .get(
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                        "factorycore", "recipes/misc/starter_energy_bank"))
            != null,
        "Recipe book unlock loaded");
    helper.assertTrue(
        helper
            .getLevel()
            .getRecipeManager()
            .byKey(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    "factorycore", "expanded_energy_bank"))
            .isEmpty(),
        "Higher tiers use safe modules, not a populated-bank recipe");
    helper.succeed();
  }

  @GameTest(template = "empty")
  public static void futureAndMalformedTagsSurviveBreakAndCannotAllocateEmptyStock(
      GameTestHelper helper) {
    helper.setBlock(BANK, EnergyBankContent.block(EnergyBankTier.STARTER));
    EnergyBankEntity bank = helper.getBlockEntity(BANK);
    var original = new CompoundTag();
    original.putInt("Schema", 77);
    original.putString("Future", "preserve me");
    var envelope = new CompoundTag();
    envelope.put(EnergyBankHandleData.FIELD, original.copy());
    bank.loadWithComponents(envelope, helper.getLevel().registryAccess());
    bank.onLoad();
    int before = registry(helper).snapshot().devices().size();
    var retained = bank.portableForBreak();
    helper.assertTrue(
        retained
            .get(DataComponents.CUSTOM_DATA)
            .copyTag()
            .get(EnergyBankHandleData.FIELD)
            .equals(original),
        "Future reference preserved on break");
    bank.place(retained);
    helper.assertTrue(
        !bank.diagnostic().isEmpty()
            && bank.receiveEnergy(10, false) == 0
            && registry(helper).snapshot().devices().size() == before,
        "Future recovery never replaced by empty ledger");
    var malformed = new ItemStack(EnergyBankContent.item(EnergyBankTier.STARTER));
    CustomData.update(
        DataComponents.CUSTOM_DATA,
        malformed,
        tag -> tag.putString(EnergyBankHandleData.FIELD, "invalid"));
    bank.place(malformed);
    helper.assertTrue(
        bank.portableForBreak()
                .get(DataComponents.CUSTOM_DATA)
                .copyTag()
                .getString(EnergyBankHandleData.FIELD)
                .equals("invalid")
            && registry(helper).snapshot().devices().size() == before,
        "Malformed raw type also retained");
    helper.succeed();
  }
}
