package dev.izzy.factorycore.testing;

import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Test-only isolated tag member, so reload regressions never rebind shared vanilla item tags. */
public final class PolicyFixture {
  private static final DeferredRegister.Items ITEMS =
      DeferredRegister.createItems("factorycore_tests");
  public static final DeferredItem<Item> PROBE = ITEMS.registerSimpleItem("policy_probe");

  private PolicyFixture() {}

  public static void register(IEventBus bus) {
    ITEMS.register(bus);
  }
}
