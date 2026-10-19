package dev.izzy.factorycore.platform.resource;

import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.ArrayList;
import java.util.List;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

/** Bounded advisory observations; no simulation, reservation or mutation of foreign stock. */
public record ExternalStockPage(List<Entry> entries, int next) {
  public static final int PAGE_SIZE = 16;

  /** Index is relative to the configured item window, or configured tank window. */
  public record Entry(int index, ResourceKey key, long stored, Long capacity) {
    public Entry {
      if (index < 0 || key == null || stored < 0 || capacity != null && capacity < stored)
        throw new IllegalArgumentException("Invalid observed stock");
    }
  }

  public ExternalStockPage {
    entries = List.copyOf(entries);
    if (entries.size() > PAGE_SIZE || next < -1)
      throw new IllegalArgumentException("Invalid stock page bounds");
  }

  public static ExternalStockPage read(
      ResourceKind kind,
      Object handler,
      PlatformResourceCodec codec,
      int firstSlot,
      int slotCount,
      int cursor) {
    if (cursor < 0) throw new IllegalArgumentException("Negative stock cursor");
    int total =
        switch (kind) {
          case ITEM -> {
            int slots = ((IItemHandler) handler).getSlots();
            if (slots < 0 || firstSlot < 0 || firstSlot > slots || slotCount <= 0 || slotCount > 64)
              throw new IllegalStateException("Invalid foreign item window");
            yield Math.min(slotCount, slots - firstSlot);
          }
          case FLUID -> {
            int tanks = ((IFluidHandler) handler).getTanks();
            if (tanks < 0 || firstSlot < 0 || firstSlot > tanks || slotCount < 1 || slotCount > 64)
              throw new IllegalStateException("Invalid foreign tank window");
            yield firstSlot == 0 && slotCount == 64
                ? tanks
                : Math.min(slotCount, tanks - firstSlot);
          }
          case ENERGY -> 1;
        };
    if (total < 0) throw new IllegalStateException("Negative foreign catalog size");
    if (cursor >= total) return new ExternalStockPage(List.of(), -1);
    int end = cursor + Math.min(PAGE_SIZE, total - cursor);
    var entries = new ArrayList<Entry>();
    for (int index = cursor; index < end; index++) {
      switch (kind) {
        case ITEM -> {
          var stack = ((IItemHandler) handler).getStackInSlot(firstSlot + index);
          if (!stack.isEmpty())
            entries.add(new Entry(index, codec.itemKey(stack), stack.getCount(), null));
        }
        case FLUID -> {
          var fluids = (IFluidHandler) handler;
          var stack = fluids.getFluidInTank(firstSlot + index);
          if (!stack.isEmpty())
            entries.add(
                new Entry(
                    index,
                    codec.fluidKey(stack),
                    stack.getAmount(),
                    (long) fluids.getTankCapacity(firstSlot + index)));
        }
        case ENERGY -> {
          var energy = (IEnergyStorage) handler;
          entries.add(
              new Entry(
                  index,
                  ResourceKey.ENERGY,
                  energy.getEnergyStored(),
                  (long) energy.getMaxEnergyStored()));
        }
      }
    }
    return new ExternalStockPage(entries, end == total ? -1 : end);
  }
}
