package dev.izzy.factorycore.platform.wireless;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.Loaded;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.WorldStorageRoutes;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Ergonomics Q18(d): Pick Block on a block the player has none of, with a bound Wireless Terminal
 * anywhere in the inventory, takes a stack of it from the network into the hotbar (the selected
 * slot when empty, else the first empty hotbar slot) and selects it. Same rules as a terminal
 * withdrawal through that handheld: its access point in range and powered, Withdraw there, and the
 * handheld's {@link WirelessAccess#ACTION_COST} FE per pick. Also carries the open key (Q14).
 */
public final class WirelessPick {
  /** Q14: the open key was pressed; the server finds the handheld. */
  public record OpenKey() implements CustomPacketPayload {
    public static final Type<OpenKey> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "wireless_key"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenKey> CODEC =
        StreamCodec.unit(new OpenKey());

    @Override
    public Type<OpenKey> type() {
      return TYPE;
    }
  }

  /** The block the player picked; the server reads the block itself. */
  public record Pick(BlockPos pos) implements CustomPacketPayload {
    public static final Type<Pick> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "wireless_pick"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Pick> CODEC =
        StreamCodec.composite(BlockPos.STREAM_CODEC, Pick::pos, Pick::new);

    @Override
    public Type<Pick> type() {
      return TYPE;
    }
  }

  private WirelessPick() {}

  static void register(RegisterPayloadHandlersEvent event) {
    event
        .registrar("2")
        .playToServer(
            Pick.TYPE,
            Pick.CODEC,
            // Handlers run on the server thread, as in MenuPayloads.
            (packet, context) -> {
              if (context.player() instanceof ServerPlayer player) {
                var reason = pick(player, packet.pos());
                if (!reason.isEmpty())
                  player.displayClientMessage(
                      Component.translatable("message.factorycore.wireless_pick." + reason), true);
              }
            })
        .playToServer(
            OpenKey.TYPE,
            OpenKey.CODEC,
            (packet, context) -> {
              if (context.player() instanceof ServerPlayer player) {
                var reason = WirelessTerminalItem.openFromInventory(player);
                if (!reason.isEmpty())
                  player.displayClientMessage(
                      Component.translatable("message.factorycore.wireless_" + reason), true);
              }
            });
  }

  /** Main-inventory slot of a bound handheld, or -1. */
  static int handheld(ServerPlayer player) {
    var inventory = player.getInventory();
    for (int slot = 0; slot < inventory.items.size(); slot++) {
      var stack = inventory.items.get(slot);
      if (stack.is(WirelessContent.HANDHELD.get())
          && stack.get(WirelessContent.BINDING.get()) != null) return slot;
    }
    return -1;
  }

  /**
   * Starts the pick; returns "" when a route was started (or nothing was needed), else a refusal
   * reason key. The block must be within the player's reach.
   */
  public static String pick(ServerPlayer player, BlockPos pos) {
    // Creative players get vanilla's own pick (abilities follow the game mode).
    if (player.getAbilities().instabuild || !player.canInteractWithBlock(pos, 1.0)) return "";
    var level = player.serverLevel();
    var state = level.getBlockState(pos);
    // The same stack vanilla Pick Block would take (NeoForge's hit-aware form).
    var wanted =
        state.getCloneItemStack(
            new net.minecraft.world.phys.BlockHitResult(
                Vec3.atCenterOf(pos), net.minecraft.core.Direction.UP, pos, false),
            level,
            pos,
            player);
    if (wanted.isEmpty()) return "";
    var inventory = player.getInventory();
    for (var held : inventory.items)
      if (ItemStack.isSameItemSameComponents(held, wanted)) return "";
    int handheldSlot = handheld(player);
    if (handheldSlot < 0) return "";
    var handheld = inventory.items.get(handheldSlot);
    var binding = handheld.get(WirelessContent.BINDING.get());
    if (!player.level().dimension().location().toString().equals(binding.dimension()))
      return "out_of_range";
    var pointPos = BlockPos.of(binding.position());
    if (Loaded.chunk(level, pointPos) == null
        || !(level.getBlockEntity(pointPos) instanceof AccessPointEntity point)
        || !point.current()) return "missing";
    if (player.distanceToSqr(Vec3.atCenterOf(pointPos)) > (double) point.range() * point.range())
      return "out_of_range";
    if (point.energy() < point.tickCost()) return "no_power";
    var actor = Actors.of(player);
    var networks = WorldNetworks.get(player.server);
    if (!networks.allows(actor, point.origin(), NetworkPermissions.Permission.WITHDRAW))
      return "denied";
    int slot =
        Inventory.isHotbarSlot(inventory.selected) && inventory.getSelected().isEmpty()
            ? inventory.selected
            : -1;
    for (int index = 0; slot < 0 && index < Inventory.getSelectionSize(); index++)
      if (inventory.items.get(index).isEmpty()) slot = index;
    if (slot < 0) return "hotbar_full";
    var energy = handheld.getCapability(Capabilities.EnergyStorage.ITEM);
    if (energy == null
        || energy.extractEnergy((int) WirelessAccess.ACTION_COST, true)
            < WirelessAccess.ACTION_COST) return "handheld_power";
    var key = new PlatformResourceCodec(player.registryAccess()).itemKey(wanted);
    int target = slot;
    var admission =
        networks
            .transfers()
            .routes()
            .priority(
                UUID.randomUUID(),
                actor,
                point.origin(),
                networks.transfers().inventory(player, target),
                key,
                Math.min(64, wanted.getMaxStackSize()),
                WorldStorageRoutes.Direction.FROM_STORAGE,
                result -> {
                  if (!player.isRemoved() && !inventory.items.get(target).isEmpty()) {
                    inventory.selected = target;
                    player.connection.send(new ClientboundSetCarriedItemPacket(target));
                  } else if (!player.isRemoved())
                    player.displayClientMessage(
                        Component.translatable("message.factorycore.wireless_pick.none"), true);
                });
    if (admission.rejection() != dev.izzy.factorycore.core.resource.OperationResult.Reason.OK)
      return "none";
    energy.extractEnergy((int) WirelessAccess.ACTION_COST, false);
    return "";
  }
}
