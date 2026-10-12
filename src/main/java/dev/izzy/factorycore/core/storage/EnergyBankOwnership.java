package dev.izzy.factorycore.core.storage;

import dev.izzy.factorycore.core.resource.LedgerStateCodec;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.Objects;

/** Owner-thread physical bank transitions; the returned lease replaces the consumed reference. */
public final class EnergyBankOwnership {
  private final DeviceRegistry registry;

  public EnergyBankOwnership(DeviceRegistry registry) {
    this.registry = Objects.requireNonNull(registry);
  }

  /** Null means a new empty bank. A supplied reference must be portable and match its item tier. */
  public DeviceRegistry.Handle place(
      DeviceRegistry.Handle portable, EnergyBankTier tier, DeviceRegistry.Location location) {
    Objects.requireNonNull(tier);
    Objects.requireNonNull(location);
    if (!registry.vacant(location)) throw new IllegalStateException("Bank location is occupied");
    var handle = portable;
    if (handle == null) {
      handle =
          require(
              registry.create(
                  ResourceKind.ENERGY,
                  tier.capacity(),
                  false,
                  1,
                  LedgerStateCodec.MAX_RESERVATIONS));
    } else {
      var definition = validate(handle, tier);
      if (definition.location() != null)
        throw new IllegalStateException("Bank lease is already placed");
    }
    return require(registry.attach(handle, location));
  }

  /** Reload never consumes a portable lease or activates stock before wired revalidation. */
  public void reload(
      DeviceRegistry.Handle placed, EnergyBankTier tier, DeviceRegistry.Location location) {
    Objects.requireNonNull(location);
    var definition = validate(placed, tier);
    if (definition.location() == null)
      throw new IllegalStateException("Placed bank references a portable lease");
    var rebound = require(registry.attach(placed, location));
    if (!rebound.equals(placed)) throw new IllegalStateException("Bank reload changed its lease");
  }

  /** Break/movement returns a new offline lease; stale copies can no longer spend its energy. */
  public DeviceRegistry.Handle remove(
      DeviceRegistry.Handle placed, EnergyBankTier tier, DeviceRegistry.Location location) {
    validate(placed, tier);
    return require(registry.detach(placed, location));
  }

  /** Only portable banks upgrade; identity, stock, claims and generation are retained. */
  public EnergyBankTier upgrade(
      DeviceRegistry.Handle portable, EnergyBankTier tier, int targetLevel) {
    var target = Objects.requireNonNull(tier).upgrade(targetLevel);
    if (portable != null) {
      var definition = validate(portable, tier);
      if (definition.location() != null)
        throw new IllegalStateException("Remove the bank before upgrading");
      var resized = registry.resize(portable, target.capacity(), false);
      if (resized.reason() != OperationResult.Reason.OK)
        throw new IllegalStateException("Bank upgrade: " + resized.reason());
    }
    return target;
  }

  public DeviceRegistry.Definition validate(DeviceRegistry.Handle handle, EnergyBankTier tier) {
    Objects.requireNonNull(tier);
    var definition = registry.definition(Objects.requireNonNull(handle));
    if (definition.kind() != ResourceKind.ENERGY
        || definition.infinite()
        || definition.capacity() != tier.capacity())
      throw new IllegalStateException("Bank tier does not match its authoritative backing");
    return definition;
  }

  private static DeviceRegistry.Handle require(DeviceRegistry.LeaseResult result) {
    if (result.failure() != DeviceRegistry.Failure.OK)
      throw new IllegalStateException("Bank ownership: " + result.failure());
    return result.handle();
  }
}
