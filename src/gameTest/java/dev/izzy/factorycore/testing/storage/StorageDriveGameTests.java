package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.CellItem;
import dev.izzy.factorycore.platform.storage.CellUpgradeItem;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class StorageDriveGameTests {
  private static final BlockPos FIRST = new BlockPos(1, 1, 1);
  private static final BlockPos SECOND = new BlockPos(3, 1, 1);
  private static final ResourceKey IRON =
      new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[0]);

  private StorageDriveGameTests() {}

  @GameTest(template = "empty")
  public static void playerInsertRemoveAndFailedUpgradeKeepSingleOwner(GameTestHelper helper) {
    var drive = drive(helper, FIRST);
    var player = helper.makeMockPlayer(GameType.CREATIVE);
    var input = new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM));
    player.setItemInHand(InteractionHand.MAIN_HAND, input);
    helper.useBlock(FIRST, player);
    helper.assertTrue(
        input.isEmpty() && !drive.cell(0).isEmpty(), "Creative insertion consumed physical cell");
    var placed = drive.cell(0);
    var handle = CellHandleData.read(placed);
    player.setItemInHand(InteractionHand.OFF_HAND, placed);
    var ingredient = new ItemStack(StorageContent.EXPANSION.get());
    player.setItemInHand(InteractionHand.MAIN_HAND, ingredient);
    helper.assertTrue(
        StorageContent.EXPANSION
                    .get()
                    .use(helper.getLevel(), player, InteractionHand.MAIN_HAND)
                    .getResult()
                == InteractionResult.FAIL
            && ingredient.getCount() == 1,
        "Placed copy cannot upgrade its backing");
    player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    player.setShiftKeyDown(true);
    helper.useBlock(FIRST, player);
    helper.assertTrue(
        drive.cell(0).isEmpty() && player.getMainHandItem().getItem() instanceof CellItem,
        "Sneak removal delivers one cell to empty hand");
    helper.assertTrue(
        registry(helper).validate(handle) == DeviceRegistry.Failure.STALE,
        "Removed player handle consumed");
    helper.succeed();
  }

  @GameTest(template = "empty")
  public static void cellSchemaFixtureRejectsForeignAndUnknownWithoutReset(GameTestHelper helper)
      throws IOException {
    CompoundTag fixture;
    try (var source =
        StorageDriveGameTests.class.getResourceAsStream("/persistence/cell-lease-v1.snbt")) {
      if (source == null) throw new IllegalStateException("Missing lease fixture");
      fixture =
          net.minecraft.nbt.TagParser.parseTag(
              new String(source.readAllBytes(), StandardCharsets.UTF_8));
    } catch (com.mojang.brigadier.exceptions.CommandSyntaxException invalid) {
      throw new IllegalStateException("Invalid lease fixture", invalid);
    }
    var drive = drive(helper, FIRST);
    var stack = new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM));
    CustomData.update(
        DataComponents.CUSTOM_DATA, stack, tag -> tag.put("FactoryCoreCell", fixture.copy()));
    helper.assertTrue(
        CellHandleData.read(stack)
            .equals(new DeviceRegistry.Handle(new UUID(0, 11), new UUID(0, 1), 2)),
        "Version-one exact lease fixture");
    int count = registry(helper).snapshot().devices().size();
    var original = stack.copy();
    rejects(helper, () -> drive.installCell(stack));
    helper.assertTrue(
        ItemStack.matches(original, stack) && registry(helper).snapshot().devices().size() == count,
        "Foreign cell preserved without new backing");
    CustomData.update(
        DataComponents.CUSTOM_DATA,
        stack,
        tag -> tag.getCompound("FactoryCoreCell").putInt("Schema", 2));
    rejects(helper, () -> drive.installCell(stack));
    helper.assertTrue(
        stack
                    .get(DataComponents.CUSTOM_DATA)
                    .copyTag()
                    .getCompound("FactoryCoreCell")
                    .getInt("Schema")
                == 2
            && registry(helper).snapshot().devices().size() == count,
        "Unknown schema retained without empty replacement");
    helper.succeed();
  }

  @GameTest(template = "empty")
  public static void placementRemovalAndCopiedLease(GameTestHelper helper) {
    var drive = drive(helper, FIRST);
    var registry = registry(helper);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    var placed = CellHandleData.read(drive.cell(0));
    helper.assertTrue(!registry.stock(placed, IRON).loaded(), "New placement stays offline");
    registry.availability(placed, drive.location(0), true);
    helper.assertTrue(
        registry.insert(placed, IRON, 9000).amount() == 8192, "Exact starter capacity");
    var job = UUID.randomUUID();
    registry.reserve(placed, job, IRON, 200);
    var portable = drive.removeCell(0);
    var handle = CellHandleData.read(portable);
    helper.assertTrue(
        registry.validate(placed) == DeviceRegistry.Failure.STALE, "Consumed placed lease");
    helper.assertTrue(!registry.stock(handle, IRON).loaded(), "Portable backing unavailable");
    helper.assertTrue(
        registry.stock(handle, IRON).total() == 8192
            && registry.stock(handle, IRON).reserved() == 200,
        "Stock and claim survive removal");
    var other = drive(helper, SECOND);
    other.installCell(portable);
    var current = CellHandleData.read(other.cell(0));
    rejects(helper, () -> drive.installCell(portable.copy()));
    helper.assertTrue(drive.cell(0).isEmpty(), "Stale copy did not occupy holder");
    helper.assertTrue(registry.stock(current, IRON).total() == 8192, "No duplicated/lost stock");
    helper.succeed();
  }

  @GameTest(template = "empty")
  public static void fourSlotsAndMalformedCellPreserveInputs(GameTestHelper helper) {
    var drive = drive(helper, FIRST);
    var registry = registry(helper);
    var malformed = new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM));
    CustomData.update(
        DataComponents.CUSTOM_DATA, malformed, tag -> tag.putString("FactoryCoreCell", "bad"));
    int before = registry.snapshot().devices().size();
    rejects(helper, () -> drive.installCell(malformed));
    helper.assertTrue(
        registry.snapshot().devices().size() == before && malformed.getCount() == 1,
        "Invalid metadata did not allocate or consume");
    for (int i = 0; i < 4; i++)
      drive.installCell(
          new ItemStack(
              StorageContent.cell(i % 2 == 0 ? CellTier.STARTER_ITEM : CellTier.STARTER_FLUID)));
    int full = registry.snapshot().devices().size();
    var fifth = new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM));
    rejects(helper, () -> drive.installCell(fifth));
    helper.assertTrue(
        registry.snapshot().devices().size() == full && fifth.getCount() == 1,
        "Full drive did not allocate or consume");
    helper.succeed();
  }

  @GameTest(template = "empty")
  public static void upgradeUseKeepsBackingClaimsAndComponents(GameTestHelper helper) {
    var drive = drive(helper, FIRST);
    var registry = registry(helper);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    var placed = CellHandleData.read(drive.cell(0));
    registry.availability(placed, drive.location(0), true);
    registry.insert(placed, IRON, 8000);
    registry.reserve(placed, UUID.randomUUID(), IRON, 500);
    var portable = drive.removeCell(0);
    var handle = CellHandleData.read(portable);
    portable.set(
        DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Owned stock"));
    var player = helper.makeMockPlayer(GameType.SURVIVAL);
    player.setItemInHand(InteractionHand.OFF_HAND, portable);
    for (var module :
        new CellUpgradeItem[] {
          StorageContent.EXPANSION.get(),
          StorageContent.ADVANCED.get(),
          StorageContent.INFINITE.get()
        }) {
      var ingredient = new ItemStack(module, 2);
      player.setItemInHand(InteractionHand.MAIN_HAND, ingredient);
      helper.assertTrue(
          module.use(helper.getLevel(), player, InteractionHand.MAIN_HAND).getResult()
                  == InteractionResult.CONSUME
              && ingredient.getCount() == 1,
          "Upgrade consumed exactly one module");
      var upgraded = player.getOffhandItem();
      helper.assertTrue(
          CellHandleData.read(upgraded).equals(handle), "Identity and generation preserved");
      helper.assertTrue(
          upgraded.getHoverName().getString().equals("Owned stock"), "Custom components preserved");
      helper.assertTrue(
          registry.stock(handle, IRON).total() == 8000
              && registry.stock(handle, IRON).reserved() == 500,
          "Contents and claims preserved");
    }
    helper.assertTrue(registry.definition(handle).infinite(), "Earned infinite item tier");
    var bad = new ItemStack(StorageContent.INFINITE.get(), 2);
    player.setItemInHand(InteractionHand.MAIN_HAND, bad);
    helper.assertTrue(
        StorageContent.INFINITE
                    .get()
                    .use(helper.getLevel(), player, InteractionHand.MAIN_HAND)
                    .getResult()
                == InteractionResult.FAIL
            && bad.getCount() == 2,
        "Invalid upgrade preserved ingredient");
    helper.succeed();
  }

  @GameTest(template = "empty")
  public static void fluidTierAndPortableReloadCannotCreateOwners(GameTestHelper helper) {
    var drive = drive(helper, FIRST);
    var registry = registry(helper);
    var fluid = new ItemStack(StorageContent.cell(CellTier.STARTER_FLUID));
    var player = helper.makeMockPlayer(GameType.SURVIVAL);
    player.setItemInHand(InteractionHand.OFF_HAND, fluid);
    for (var module :
        new CellUpgradeItem[] {StorageContent.EXPANSION.get(), StorageContent.ADVANCED.get()}) {
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(module));
      helper.assertTrue(
          module.use(helper.getLevel(), player, InteractionHand.MAIN_HAND).getResult()
              == InteractionResult.CONSUME,
          "Finite fluid upgrade");
    }
    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(StorageContent.INFINITE.get()));
    helper.assertTrue(
        StorageContent.INFINITE
                    .get()
                    .use(helper.getLevel(), player, InteractionHand.MAIN_HAND)
                    .getResult()
                == InteractionResult.FAIL
            && player.getMainHandItem().getCount() == 1,
        "No infinite fluid tier; module preserved");
    fluid = player.getOffhandItem();
    drive.installCell(fluid);
    var placed = CellHandleData.read(drive.cell(0));
    var water = new ResourceKey(ResourceKind.FLUID, "minecraft:water", new byte[0]);
    registry.availability(placed, drive.location(0), true);
    helper.assertTrue(
        registry.insert(placed, water, Long.MAX_VALUE).amount() == 4096000,
        "Finite advanced fluid capacity in mB");
    registry.reserve(placed, UUID.randomUUID(), water, 2000);
    helper.assertTrue(
        registry.insert(placed, IRON, 1).amount() == 0, "Fluid cell cannot accept items");
    var portable = drive.removeCell(0);
    var handle = CellHandleData.read(portable);
    var invalid = new CompoundTag();
    invalid.putInt("CellSchema", 1);
    var slots = new ListTag();
    var slot = new CompoundTag();
    slot.putInt("Slot", 0);
    slot.put("Stack", portable.save(helper.getLevel().registryAccess()));
    slots.add(slot);
    invalid.put("CellSlots", slots);
    drive.loadWithComponents(invalid, helper.getLevel().registryAccess());
    drive.onLoad();
    helper.assertTrue(
        !drive.diagnostic().isEmpty(), "Portable lease rejected during placed reload");
    helper.assertTrue(
        registry.validate(handle) == DeviceRegistry.Failure.OK
            && registry.definition(handle).location() == null,
        "Bad placed reload did not consume portable generation");
    helper.assertTrue(
        registry.stock(handle, water).total() == 4096000
            && registry.stock(handle, water).reserved() == 2000
            && !registry.stock(handle, water).loaded(),
        "Finite fluid stock and claims preserved offline");
    helper.succeed();
  }

  @GameTest(template = "empty")
  public static void blockEntityReloadPreservesLeaseAndDisablesRouting(GameTestHelper helper) {
    var drive = drive(helper, FIRST);
    var registry = registry(helper);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    var handle = CellHandleData.read(drive.cell(0));
    registry.availability(handle, drive.location(0), true);
    registry.insert(handle, IRON, 100);
    registry.reserve(handle, UUID.randomUUID(), IRON, 60);
    var saved = drive.saveWithFullMetadata(helper.getLevel().registryAccess());
    var position = helper.absolutePos(FIRST);
    helper.getLevel().removeBlockEntity(position);
    helper.assertTrue(
        !registry.stock(handle, IRON).loaded(), "Removal disables routing immediately");
    var restored = new StorageDriveEntity(position, StorageContent.DRIVE.get().defaultBlockState());
    restored.loadWithComponents(saved, helper.getLevel().registryAccess());
    helper.getLevel().setBlockEntity(restored);
    restored.onLoad();
    helper.assertTrue(
        restored.diagnostic().isEmpty() && CellHandleData.read(restored.cell(0)).equals(handle),
        "Same holder reload preserved lease");
    helper.assertTrue(
        registry.stock(handle, IRON).total() == 100
            && registry.stock(handle, IRON).reserved() == 60
            && !registry.stock(handle, IRON).loaded(),
        "Reload retained offline stock and claims");
    var copied = drive(helper, SECOND);
    copied.loadWithComponents(saved, helper.getLevel().registryAccess());
    copied.onLoad();
    helper.assertTrue(
        !copied.diagnostic().isEmpty()
            && registry.validate(handle) == DeviceRegistry.Failure.CONFLICT,
        "Concurrent chunk copy quarantined owner");
    helper.assertTrue(
        registry.insert(handle, IRON, 1).reason() == OperationResult.Reason.DENIED,
        "Conflicted owner rejected mutation");
    helper.succeed();
  }

  @GameTest(template = "empty")
  public static void breakingDriveDropsOnePortableCellAndCasing(GameTestHelper helper) {
    var drive = drive(helper, FIRST);
    var registry = registry(helper);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    var old = CellHandleData.read(drive.cell(0));
    registry.availability(old, drive.location(0), true);
    registry.insert(old, IRON, 45);
    var absolute = helper.absolutePos(FIRST);
    helper.getLevel().destroyBlock(absolute, true);
    var drops =
        helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(absolute).inflate(2));
    var cells = drops.stream().filter(e -> e.getItem().getItem() instanceof CellItem).toList();
    helper.assertTrue(
        cells.size() == 1 && cells.getFirst().getItem().getCount() == 1, "Exactly one cell drop");
    var portable = CellHandleData.read(cells.getFirst().getItem());
    helper.assertTrue(
        registry.stock(portable, IRON).total() == 45 && !registry.stock(portable, IRON).loaded(),
        "Broken contents held offline");
    helper.assertTrue(
        registry.validate(old) == DeviceRegistry.Failure.STALE, "Broken holder consumed old lease");
    helper.assertTrue(
        drops.stream()
                .filter(e -> e.getItem().is(StorageContent.DRIVE_ITEM.get()))
                .mapToInt(e -> e.getItem().getCount())
                .sum()
            == 1,
        "Exactly one casing drop");
    helper.succeed();
  }

  @GameTest(template = "empty")
  public static void futureDriveDataSurvivesSaveBreakAndPlacement(GameTestHelper helper) {
    var drive = drive(helper, FIRST);
    var unknown = new CompoundTag();
    unknown.putInt("CellSchema", 2);
    unknown.putString("FutureOwnedRecord", "keep exactly");
    unknown.putInt("x", 9876);
    drive.loadWithComponents(unknown, helper.getLevel().registryAccess());
    helper.assertTrue(drive.needsDataRecovery(), "Unknown schema quarantined");
    var saved = drive.saveWithFullMetadata(helper.getLevel().registryAccess());
    helper.assertTrue(
        saved.getInt("x") == helper.absolutePos(FIRST).getX(),
        "Rejected metadata cannot overwrite actual position");
    helper.assertTrue(
        saved.getCompound("RejectedCellData").equals(unknown),
        "Original preserved inside recovery envelope");
    helper.getLevel().destroyBlock(helper.absolutePos(FIRST), true);
    var drops =
        helper
            .getLevel()
            .getEntitiesOfClass(ItemEntity.class, new AABB(helper.absolutePos(FIRST)).inflate(2));
    helper.assertTrue(
        drops.size() == 1 && drops.getFirst().getItem().is(StorageContent.DRIVE_ITEM.get()),
        "One recovery casing without extra empty casing");
    var recovery = drops.getFirst().getItem();
    var recovered = drive(helper, SECOND);
    recovered.restoreRecovery(recovery, helper.getLevel().registryAccess());
    helper.assertTrue(
        recovered.needsDataRecovery()
            && recovered
                .saveWithFullMetadata(helper.getLevel().registryAccess())
                .getCompound("RejectedCellData")
                .equals(unknown),
        "Replacement preserved unknown data without reset");
    rejects(
        helper,
        () -> recovered.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM))));
    var malformed = new ItemStack(StorageContent.DRIVE_ITEM.get());
    CustomData.update(
        DataComponents.CUSTOM_DATA,
        malformed,
        data -> data.putString(StorageDriveEntity.RECOVERY, "do not discard"));
    var third = drive(helper, new BlockPos(3, 1, 3));
    third.restoreRecovery(malformed, helper.getLevel().registryAccess());
    helper.assertTrue(
        third.needsDataRecovery()
            && third
                .saveWithFullMetadata(helper.getLevel().registryAccess())
                .getCompound("RejectedCellData")
                .getString(StorageDriveEntity.RECOVERY)
                .equals("do not discard"),
        "Wrong-typed recovery envelope retained");
    helper.succeed();
  }

  @GameTest(template = "empty")
  public static void vanillaRecipesPreservePopulatedCells(GameTestHelper helper) {
    var manager = helper.getLevel().getRecipeManager();
    var expected =
        Map.of(
            "starter_item_cell", Map.of("iron_ingot", 4, "redstone", 4, "chest", 1),
            "starter_fluid_cell", Map.of("glass", 7, "bucket", 1, "redstone", 1),
            "storage_drive", Map.of("iron_ingot", 4, "redstone", 4, "hopper", 1),
            "expansion_module", Map.of("gold_ingot", 4, "redstone", 2, "diamond", 1),
            "advanced_module", Map.of("diamond", 4, "redstone", 2, "netherite_ingot", 1),
            "infinite_module", Map.of("netherite_ingot", 8, "nether_star", 1));
    for (var id :
        new String[] {
          "starter_item_cell",
          "starter_fluid_cell",
          "storage_drive",
          "expansion_module",
          "advanced_module",
          "infinite_module"
        }) {
      var recipe =
          manager
              .byKey(
                  net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("factorycore", id))
              .orElseThrow();
      helper.assertTrue(
          recipe.value().getResultItem(helper.getLevel().registryAccess()).getCount() == 1,
          "One recipe output");
      var costs = new HashMap<String, Integer>();
      for (var ingredient : recipe.value().getIngredients()) {
        for (var item : ingredient.getItems()) {
          helper.assertTrue(
              !(item.getItem() instanceof CellItem)
                  && net.minecraft.core.registries.BuiltInRegistries.ITEM
                      .getKey(item.getItem())
                      .getNamespace()
                      .equals("minecraft"),
              "Only vanilla inputs; no populated cell consumed");
          costs.merge(
              net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem()).getPath(),
              1,
              Integer::sum);
        }
      }
      helper.assertTrue(
          costs.equals(expected.get(id)), "Recipe costs match balance version one: " + id);
      helper.assertTrue(
          net.minecraft.core.registries.BuiltInRegistries.ITEM
              .getKey(recipe.value().getResultItem(helper.getLevel().registryAccess()).getItem())
              .toString()
              .equals("factorycore:" + id),
          "Correct named recipe output");
      helper.assertTrue(
          helper
                  .getLevel()
                  .getServer()
                  .getAdvancements()
                  .get(
                      net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                          "factorycore", "recipes/misc/" + id))
              != null,
          "Survival recipe unlock advancement");
    }
    helper.assertTrue(
        manager
            .byKey(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    "factorycore", "infinite_item_cell"))
            .isEmpty(),
        "Infinite requires sequential modules");
    helper.succeed();
  }

  private static StorageDriveEntity drive(GameTestHelper helper, BlockPos position) {
    helper.setBlock(position, StorageContent.DRIVE.get());
    return helper.getBlockEntity(position);
  }

  private static DeviceRegistry registry(GameTestHelper helper) {
    return DeviceSavedData.get(helper.getLevel().getServer()).registry();
  }

  private static void rejects(GameTestHelper helper, Runnable action) {
    boolean rejected = false;
    try {
      action.run();
    } catch (IllegalArgumentException | IllegalStateException expected) {
      rejected = true;
    }
    helper.assertTrue(rejected, "Operation must reject without replacement");
  }
}
