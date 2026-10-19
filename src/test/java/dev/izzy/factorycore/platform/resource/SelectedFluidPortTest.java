package dev.izzy.factorycore.platform.resource;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SelectedFluidPortTest {
  private static PlatformResourceCodec codec;

  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
    codec =
        new PlatformResourceCodec(
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
  }

  private static final class Tanks implements IndexedFluidHandler {
    final FluidTank[] tanks = {new FluidTank(1000), new FluidTank(1000), new FluidTank(1000)};
    boolean wrongDrain;
    int reported = tanks.length;

    @Override
    public int getTanks() {
      return reported;
    }

    @Override
    public FluidStack getFluidInTank(int tank) {
      return tanks[tank].getFluid();
    }

    @Override
    public int getTankCapacity(int tank) {
      return tanks[tank].getCapacity();
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
      return true;
    }

    @Override
    public int fillTank(int tank, FluidStack stack, FluidAction action) {
      return tanks[tank].fill(stack, action);
    }

    @Override
    public FluidStack drainTank(int tank, FluidStack stack, FluidAction action) {
      return wrongDrain
          ? new FluidStack(Fluids.LAVA, stack.getAmount())
          : tanks[tank].drain(stack, action);
    }

    @Override
    public int fill(FluidStack stack, FluidAction action) {
      throw new AssertionError("Whole-handler mutation must not substitute for indexed fill");
    }

    @Override
    public FluidStack drain(FluidStack stack, FluidAction action) {
      throw new AssertionError("Whole-handler mutation must not substitute for indexed drain");
    }

    @Override
    public FluidStack drain(int amount, FluidAction action) {
      throw new AssertionError("Indexed drain must retain requested identity");
    }
  }

  @Test
  void selectedPartialInsertionSimulationAndComponentsConserveExcludedTanks() {
    var handler = new Tanks();
    var named = new FluidStack(Fluids.WATER, 800);
    named.set(DataComponents.CUSTOM_NAME, Component.literal("Coolant"));
    handler.tanks[0].setFluid(named.copy());
    handler.tanks[1].setFluid(named.copy());
    var key = codec.fluidKey(named);
    var port = new FluidHandlerPort(UUID.randomUUID(), handler, codec, 1, 1);
    assertEquals(200, port.simulateInsert(key, 700));
    assertEquals(800, handler.tanks[1].getFluidAmount());
    assertEquals(200, port.insert(key, 700));
    assertEquals(0, port.extract(codec.fluidKey(new FluidStack(Fluids.WATER, 1)), 500));
    assertEquals(750, port.extract(key, 750));
    assertEquals(800, handler.tanks[0].getFluidAmount());
    assertEquals(250, handler.tanks[1].getFluidAmount());
    assertEquals(0, handler.tanks[2].getFluidAmount());
    var page = ExternalStockPage.read(ResourceKind.FLUID, handler, codec, 1, 1, 0);
    assertEquals(1, page.entries().size());
    assertEquals(key, page.entries().getFirst().key());
    assertEquals(250, page.entries().getFirst().stored());
    assertEquals(-1, page.next());
  }

  @Test
  void boundedMultiTankWindowClampsToLiveCountAndRetainsExactUnits() {
    var handler = new Tanks();
    var key = codec.fluidKey(new FluidStack(Fluids.WATER, 1));
    var port = new FluidHandlerPort(UUID.randomUUID(), handler, codec, 1, 64);
    assertEquals(2000, port.insert(key, 2500));
    assertEquals(0, handler.tanks[0].getFluidAmount());
    assertEquals(1500, port.extract(key, 1500));
    assertEquals(0, handler.tanks[1].getFluidAmount());
    assertEquals(500, handler.tanks[2].getFluidAmount());
    handler.reported = 1;
    assertThrows(IllegalStateException.class, () -> port.extract(key, 1));
  }

  @Test
  void unsupportedAndMalformedIndexedProvidersFailWithoutWholeHandlerFallback() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new FluidHandlerPort(UUID.randomUUID(), new FluidTank(1000), codec, 0, 1));
    var handler = new Tanks();
    assertThrows(
        IllegalArgumentException.class,
        () -> new FluidHandlerPort(UUID.randomUUID(), handler, codec, Integer.MAX_VALUE, 1));
    var port = new FluidHandlerPort(UUID.randomUUID(), handler, codec, 1, 1);
    handler.wrongDrain = true;
    var key = codec.fluidKey(new FluidStack(Fluids.WATER, 1));
    assertThrows(IllegalStateException.class, () -> port.extract(key, 300));
    assertEquals(0, handler.tanks[0].getFluidAmount());
  }
}
