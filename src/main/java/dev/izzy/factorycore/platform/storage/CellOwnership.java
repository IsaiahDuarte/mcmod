package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.resource.LedgerStateCodec;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import net.minecraft.world.item.ItemStack;

final class CellOwnership {
  private CellOwnership() {}

  static ItemStack install(
      ItemStack input, DeviceRegistry registry, DeviceRegistry.Location location) {
    if (!(input.getItem() instanceof CellItem item) || input.getCount() != 1)
      throw new IllegalArgumentException("Expected one cell");
    var handle = CellHandleData.read(input);
    if (!registry.vacant(location))
      throw new IllegalStateException("Drive holder slot already owns a cell");
    if (handle == null) {
      var created =
          registry.create(
              item.tier().kind(),
              item.tier().capacity(),
              item.tier().infinite(),
              LedgerStateCodec.MAX_CATALOG,
              LedgerStateCodec.MAX_RESERVATIONS);
      if (created.failure() != DeviceRegistry.Failure.OK)
        throw new IllegalStateException("Cell creation: " + created.failure());
      handle = created.handle();
    } else {
      validateTier(item.tier(), registry.definition(handle));
      if (registry.definition(handle).location() != null)
        throw new IllegalStateException("Cell is already placed");
    }
    var attached = registry.attach(handle, location);
    if (attached.failure() != DeviceRegistry.Failure.OK)
      throw new IllegalStateException("Cell placement: " + attached.failure());
    ItemStack installed = input.copy();
    CellHandleData.write(installed, attached.handle());
    return installed;
  }

  static void rebind(ItemStack stack, DeviceRegistry registry, DeviceRegistry.Location location) {
    var handle = CellHandleData.read(stack);
    if (!(stack.getItem() instanceof CellItem item) || handle == null)
      throw new IllegalArgumentException("Placed cell lacks a valid lease");
    var definition = registry.definition(handle);
    validateTier(item.tier(), definition);
    if (definition.location() == null)
      throw new IllegalStateException("Placed cell references a portable lease");
    var attached = registry.attach(handle, location);
    if (attached.failure() != DeviceRegistry.Failure.OK || !attached.handle().equals(handle)) {
      throw new IllegalStateException("Stale placed cell: " + attached.failure());
    }
  }

  static ItemStack upgrade(ItemStack stack, int targetLevel, DeviceRegistry registry) {
    if (!(stack.getItem() instanceof CellItem item) || stack.getCount() != 1)
      throw new IllegalArgumentException("Hold one cell in the offhand");
    CellTier tier = item.tier().upgrade(targetLevel);
    var handle = CellHandleData.read(stack);
    if (handle != null) {
      var definition = registry.definition(handle);
      validateTier(item.tier(), definition);
      if (definition.location() != null)
        throw new IllegalStateException("Remove cell from its drive before upgrading");
      var result = registry.resize(handle, tier.capacity(), tier.infinite());
      if (result.reason() != OperationResult.Reason.OK)
        throw new IllegalStateException("Cell upgrade: " + result.reason());
    }
    var replacement = new ItemStack(StorageContent.cell(tier));
    replacement.applyComponents(stack.getComponentsPatch());
    return replacement;
  }

  private static void validateTier(CellTier tier, DeviceRegistry.Definition definition) {
    if (tier.kind() != definition.kind()
        || tier.capacity() != definition.capacity()
        || tier.infinite() != definition.infinite()) {
      throw new IllegalStateException("Cell tier does not match its authoritative backing");
    }
  }
}
