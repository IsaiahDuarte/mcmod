package dev.izzy.factorycore.platform.signal;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.SignalRule;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.session.DeviceStatus;
import dev.izzy.factorycore.platform.session.MenuSession;
import dev.izzy.factorycore.platform.storage.InterfaceSwitch;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;

/**
 * C1 Signal Interface settings. Anyone with VIEW may look; changing the watched resource or
 * applying a rule needs EDIT, and the applying player becomes the owner whose grants the signal
 * reads under. The watched resource is a server-side draft until Apply.
 */
public final class SignalMenu extends AbstractContainerMenu implements DeviceStatus.Holder {
  private static final int POLL_TICKS = 5;

  private final MenuSession session;
  private final SignalInterfaceEntity signal;
  private ResourceKey draft;
  private boolean drafted;
  private SignalPackets.Status status = SignalPackets.Status.READY;
  private long revision;
  private SignalPackets.View view;
  private DeviceStatus.View deviceStatus;

  public SignalMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
    this(id, inventory, MenuSession.read(buffer), null);
  }

  private SignalMenu(int id, Inventory inventory, UUID nonce, SignalInterfaceEntity signal) {
    super(SignalContent.MENU.get(), id);
    session = new MenuSession(this, inventory.player, nonce, signal != null, POLL_TICKS);
    this.signal = signal;
    view =
        new SignalPackets.View(
            id,
            nonce,
            0,
            SignalRule.DEFAULT,
            "",
            "",
            false,
            0,
            "unset",
            false,
            SignalPackets.Status.LOADING,
            "");
    deviceStatus = DeviceStatus.View.pending(id, nonce);
  }

  public static void open(ServerPlayer player, SignalInterfaceEntity signal) {
    MenuSession.open(
        player,
        Component.translatable("gui.factorycore.signal.title"),
        (id, inventory, nonce) -> new SignalMenu(id, inventory, nonce, signal));
  }

  public SignalPackets.View view() {
    return view;
  }

  public UUID session() {
    return session.id();
  }

  @Override
  public DeviceStatus.View deviceStatus() {
    return deviceStatus;
  }

  @Override
  public void acceptDeviceStatus(DeviceStatus.View packet) {
    if (!session.server())
      deviceStatus = DeviceStatus.accept(containerId, session.id(), deviceStatus, packet);
  }

  public void accept(SignalPackets.View packet) {
    if (!session.server()
        && session.owns(packet.menu(), packet.session())
        && packet.revision() >= view.revision()) view = packet;
  }

  private boolean allowed(ServerPlayer player, NetworkPermissions.Permission permission) {
    return WorldNetworks.get(player.server).allows(Actors.of(player), signal.origin(), permission);
  }

  private ResourceKey watched() {
    return drafted ? draft : signal.resource();
  }

  public void command(ServerPlayer sender, SignalPackets.Command packet) {
    if (signal == null
        || !session.admitCommand(sender, packet.menu(), packet.session())
        || !signal.canUse(sender)) return;
    if (!allowed(sender, NetworkPermissions.Permission.EDIT)) {
      status = SignalPackets.Status.DENIED;
      publish();
      return;
    }
    var codec = new PlatformResourceCodec(sender.registryAccess());
    try {
      switch (packet.action()) {
        case SignalPackets.Command.RESOURCE -> {
          var id = ResourceLocation.tryParse(packet.text().strip());
          if (id == null) throw new IllegalArgumentException("Not an ID");
          if (packet.fluid()) {
            if (!BuiltInRegistries.FLUID.containsKey(id))
              throw new IllegalArgumentException("Fluid");
            var fluid = BuiltInRegistries.FLUID.get(id);
            if (fluid == Fluids.EMPTY) throw new IllegalArgumentException("Empty fluid");
            draft = codec.fluidKey(new FluidStack(fluid, 1));
          } else {
            if (!BuiltInRegistries.ITEM.containsKey(id)) throw new IllegalArgumentException("Item");
            var item = BuiltInRegistries.ITEM.get(id);
            if (new ItemStack(item).isEmpty()) throw new IllegalArgumentException("Empty item");
            draft = codec.itemKey(new ItemStack(item));
          }
          drafted = true;
          status = SignalPackets.Status.DRAFT;
        }
        case SignalPackets.Command.CARRIED -> {
          var carried = getCarried();
          if (carried.isEmpty()) return;
          // A held fluid container watches its fluid; anything else watches the exact item.
          var fluid = FluidUtil.getFluidContained(carried.copyWithCount(1));
          draft =
              fluid.isPresent() && !fluid.get().isEmpty()
                  ? codec.fluidKey(fluid.get())
                  : codec.itemKey(carried);
          drafted = true;
          status = SignalPackets.Status.DRAFT;
        }
        case SignalPackets.Command.LABEL -> {
          signal.label(packet.text());
          status = SignalPackets.Status.LABELED;
        }
        case SignalPackets.Command.APPLY -> {
          signal.configure(sender.getUUID(), packet.rule(), watched());
          drafted = false;
          draft = null;
          status = SignalPackets.Status.APPLIED;
        }
        default -> {
          return;
        }
      }
    } catch (IllegalArgumentException invalid) {
      status = SignalPackets.Status.INVALID;
    }
    revision++;
    publish();
  }

  private void publish() {
    if (signal == null || !session.server()) return;
    var resource = watched();
    String id = "", name = "";
    boolean fluid = false;
    if (resource != null) {
      id = resource.registryId();
      fluid = resource.kind() == ResourceKind.FLUID;
      try {
        var codec = new PlatformResourceCodec(session.player().registryAccess());
        name =
            (fluid
                    ? codec.fluidStack(resource, 1).getHoverName()
                    : codec.itemStack(resource, 1).getHoverName())
                .getString()
                .replaceAll("[\\p{Cntrl}§]", "");
      } catch (IllegalArgumentException unsupported) {
        name = id;
      }
      if (name.length() > 96) name = name.substring(0, 96);
    }
    view =
        session.publish(
            view,
            new SignalPackets.View(
                containerId,
                session.id(),
                revision,
                signal.rule(),
                id,
                name,
                fluid,
                signal.power(),
                signal.reason(),
                allowed(session.player(), NetworkPermissions.Permission.EDIT),
                status,
                signal.label()));
  }

  @Override
  public void broadcastChanges() {
    super.broadcastChanges();
    if (!session.due(() -> signal != null && signal.canUse(session.player()))) return;
    deviceStatus =
        DeviceStatus.publish(
            session.player(),
            deviceStatus,
            DeviceStatus.of(
                session.player().server,
                containerId,
                session.id(),
                signal.origin(),
                signal.reason().isEmpty() ? "" : "reason.factorycore.signal." + signal.reason(),
                ""));
    publish();
  }

  /** The Interface mode row (ADR 0059): switching away from Signal, like the other modes. */
  @Override
  public boolean clickMenuButton(Player player, int button) {
    var target = InterfaceSwitch.requested(button);
    if (target == null || signal == null || !(player instanceof ServerPlayer sender)) return false;
    var outcome = InterfaceSwitch.request(sender, signal.getBlockPos(), target);
    if (outcome == InterfaceSwitch.Outcome.SWITCHED) {
      sender.closeContainer();
      sender.displayClientMessage(
          Component.translatable(
              "message.factorycore.interface_switched",
              Component.translatable(
                  "gui.factorycore.interface.mode." + target.getSerializedName())),
          true);
    } else {
      status = outcome.as(SignalPackets.Status.class);
      revision++;
      publish();
    }
    return true;
  }

  @Override
  public ItemStack quickMoveStack(Player player, int index) {
    return ItemStack.EMPTY;
  }

  @Override
  public boolean stillValid(Player player) {
    return session.stillValid(player)
        && (signal == null || !(player instanceof ServerPlayer server) || signal.canUse(server));
  }

  @Override
  public void removed(Player player) {
    super.removed(player);
    session.close();
  }
}
