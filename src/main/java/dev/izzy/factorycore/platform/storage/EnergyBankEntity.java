package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.core.storage.EnergyBankOwnership;
import dev.izzy.factorycore.core.storage.EnergyBankTier;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * A physical reference only; canonical stock, claims and rate spending belong to world services.
 */
public final class EnergyBankEntity extends BlockEntity implements IEnergyStorage {
  private Tag data;
  private DeviceRegistry.Handle handle;
  private String diagnostic = "";

  public EnergyBankEntity(BlockPos position, BlockState state) {
    super(EnergyBankContent.ENTITY.get(), position, state);
  }

  public EnergyBankTier tier() {
    return ((EnergyBankBlock) getBlockState().getBlock()).tier();
  }

  public DeviceRegistry.Handle handle() {
    return handle;
  }

  public String diagnostic() {
    return diagnostic;
  }

  @SuppressWarnings("PMD.CloseResource")
  public DeviceRegistry.Location location() {
    if (!(level instanceof ServerLevel server))
      throw new IllegalStateException("Bank requires server level");
    return new DeviceRegistry.Location(
        server.dimension().location().toString(), worldPosition.asLong(), 0);
  }

  @SuppressWarnings("PMD.CloseResource")
  private DeviceSavedData authority() {
    if (!(level instanceof ServerLevel server))
      throw new IllegalStateException("Bank requires server level");
    return DeviceSavedData.get(server.getServer());
  }

  public void place(ItemStack stack) {
    var custom = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    if (custom.contains(EnergyBankHandleData.FIELD))
      data = custom.get(EnergyBankHandleData.FIELD).copy();
    parse();
    if (!diagnostic.isEmpty()) {
      setChanged();
      changedNetwork();
      return;
    }
    try {
      handle = new EnergyBankOwnership(authority().registry()).place(handle, tier(), location());
      data = EnergyBankHandleData.write(handle);
    } catch (IllegalArgumentException | IllegalStateException failure) {
      diagnostic = failure.getMessage();
    }
    setChanged();
    changedNetwork();
  }

  public void validatePlaced() {
    if (isRemoved() || !diagnostic.isEmpty() || handle == null)
      throw new IllegalStateException("Bank unavailable");
    var definition = new EnergyBankOwnership(authority().registry()).validate(handle, tier());
    if (!location().equals(definition.location()))
      throw new IllegalStateException("Bank location mismatch");
  }

  private void parse() {
    handle = null;
    diagnostic = "";
    try {
      handle = EnergyBankHandleData.read(data);
    } catch (IllegalArgumentException failure) {
      diagnostic = failure.getMessage();
    }
  }

  public ItemStack portableForBreak() {
    var stack = new ItemStack(getBlockState().getBlock().asItem());
    if (handle != null && diagnostic.isEmpty()) {
      try {
        handle = new EnergyBankOwnership(authority().registry()).remove(handle, tier(), location());
        data = EnergyBankHandleData.write(handle);
      } catch (IllegalArgumentException | IllegalStateException failure) {
        diagnostic = failure.getMessage();
      }
    }
    if (data != null)
      CustomData.update(
          DataComponents.CUSTOM_DATA,
          stack,
          custom -> custom.put(EnergyBankHandleData.FIELD, data.copy()));
    if (!diagnostic.isEmpty())
      stack.set(
          DataComponents.CUSTOM_NAME, Component.translatable("block.factorycore.bank_recovery"));
    return stack;
  }

  @Override
  public void onLoad() {
    super.onLoad();
    if (level instanceof ServerLevel && handle != null && diagnostic.isEmpty()) {
      try {
        new EnergyBankOwnership(authority().registry()).reload(handle, tier(), location());
      } catch (IllegalArgumentException | IllegalStateException failure) {
        diagnostic = failure.getMessage();
      }
    }
    changedNetwork();
  }

  @Override
  public void setRemoved() {
    changedNetwork();
    if (level instanceof ServerLevel && handle != null) {
      try {
        authority().registry().availability(handle, location(), false);
      } catch (IllegalArgumentException | IllegalStateException failure) {
        diagnostic = failure.getMessage();
      }
    }
    super.setRemoved();
  }

  private void changedNetwork() {
    WorldNetworks.changed(level, worldPosition);
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    if (data != null) tag.put(EnergyBankHandleData.FIELD, data.copy());
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.loadAdditional(tag, registries);
    data =
        tag.contains(EnergyBankHandleData.FIELD)
            ? tag.get(EnergyBankHandleData.FIELD).copy()
            : null;
    parse();
  }

  private int transfer(int amount, boolean simulate, boolean input) {
    if (amount < 0) throw new IllegalArgumentException("Negative FE request");
    try {
      validatePlaced();
      var saved = authority();
      return Math.toIntExact(
          (input
                  ? saved.energyBanks().receive(handle, tier(), amount, simulate)
                  : saved.energyBanks().extract(handle, tier(), amount, simulate))
              .amount());
    } catch (IllegalArgumentException | IllegalStateException unavailable) {
      return 0;
    }
  }

  @Override
  public int receiveEnergy(int amount, boolean simulate) {
    return transfer(amount, simulate, true);
  }

  @Override
  public int extractEnergy(int amount, boolean simulate) {
    return transfer(amount, simulate, false);
  }

  @Override
  public int getEnergyStored() {
    try {
      validatePlaced();
      var stock = authority().registry().stock(handle, ResourceKey.ENERGY);
      return Math.toIntExact(stock.total());
    } catch (IllegalArgumentException | IllegalStateException unavailable) {
      return 0;
    }
  }

  @Override
  public int getMaxEnergyStored() {
    return Math.toIntExact(tier().capacity());
  }

  @Override
  public boolean canExtract() {
    return true;
  }

  @Override
  public boolean canReceive() {
    return true;
  }
}
