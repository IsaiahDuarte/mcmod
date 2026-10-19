package dev.izzy.factorycore.platform.resource;

import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * An explicit platform adapter for genuine indexed tank mutation. Each method may touch only the
 * named tank, preserves exact fluid components, and follows IFluidHandler simulation/return rules.
 * Tank indexes are those exposed by getFluidInTank, stable across aliases and sided wrappers for an
 * ExternalBackingProvider that opts into indexed ports. Never emulate this by whole-handler
 * drain/fill followed by a refund: that can mutate unrelated tanks or lose fluid.
 */
public interface IndexedFluidHandler extends IFluidHandler {
  int fillTank(int tank, FluidStack resource, FluidAction action);

  FluidStack drainTank(int tank, FluidStack resource, FluidAction action);
}
