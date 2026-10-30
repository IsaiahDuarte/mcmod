package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.crafting.WorldCrafting;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.TerminalPatternPackets.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-side Patterns view of one terminal session (ergonomics Q3): the Crafters this terminal's
 * segment can use, as {@link WorldCrafting#visible} lists them (so a gateway that withholds CRAFT
 * hides a branch's Crafters here too), filtered by the terminal search on pattern outputs. A click
 * takes, inserts or swaps one pattern through the cursor after the same EDIT check as the Crafter
 * screen. Nothing is computed until the client first opens the view.
 */
final class TerminalPatterns {
  private final ServerPlayer viewer;
  private final TerminalAccess terminal;
  private final AbstractContainerMenu menu;
  private final UUID session;
  private boolean active;
  private int offset;
  private long revision;
  private Status status = Status.READY;
  private List<WorldCrafting.Visible> shown = List.of();
  private TerminalPatternPackets.View sent;

  TerminalPatterns(
      ServerPlayer viewer, TerminalAccess terminal, AbstractContainerMenu menu, UUID session) {
    this.viewer = viewer;
    this.terminal = terminal;
    this.menu = menu;
    this.session = session;
    sent = TerminalPatternPackets.View.empty(menu.containerId, session);
  }

  TerminalPatternPackets.View view() {
    return sent;
  }

  /** Applies one accepted command, then republishes. {@code text} is the terminal search. */
  void command(TerminalPatternPackets.Command command, String text) {
    active = true;
    switch (command.action()) {
      case TerminalPatternPackets.WINDOW -> offset = command.selection();
      case TerminalPatternPackets.CLICK -> {
        // A click on a window the client no longer has is answered with the current one.
        if (command.revision() == sent.revision()) status = click(command, text);
      }
      default -> {}
    }
    publish(text, true);
  }

  /** A wireless terminal without the energy for a click. */
  void noPower(String text) {
    active = true;
    status = Status.NO_POWER;
    publish(text, true);
  }

  private Status click(TerminalPatternPackets.Command command, String text) {
    if (command.selection() >= shown.size()) return Status.GONE;
    var unit = shown.get(command.selection()).unit();
    if (!unit.current() || matching(text).stream().noneMatch(visible -> visible.unit() == unit))
      return Status.GONE;
    if (!unit.editableBy(viewer)) return Status.DENIED;
    var patterns = unit.patterns();
    int slot = command.slot();
    var carried = menu.getCarried();
    var held = patterns.getStackInSlot(slot);
    if (carried.isEmpty()) {
      if (held.isEmpty()) return Status.READY;
      menu.setCarried(patterns.extractItem(slot, 1, false));
      return Status.TAKEN;
    }
    if (carried.getCount() != 1 && !held.isEmpty()) return Status.REFUSED;
    if (!patterns.isItemValid(slot, carried)) return Status.REFUSED;
    if (held.isEmpty()) {
      if (!patterns.insertItem(slot, carried.copyWithCount(1), true).isEmpty())
        return Status.REFUSED;
      patterns.insertItem(slot, carried.copyWithCount(1), false);
      carried.shrink(1);
      menu.setCarried(carried);
      return Status.INSERTED;
    }
    var taken = patterns.extractItem(slot, 1, false);
    if (taken.isEmpty()) return Status.REFUSED;
    patterns.insertItem(slot, carried.copy(), false);
    menu.setCarried(taken);
    return Status.SWAPPED;
  }

  /**
   * Holders visible to this terminal's segment, in stable order, at most {@link
   * TerminalPatternPackets#MAX_OFFSET}, keeping those with a pattern whose output matches {@code
   * text}. Empty while the segment is not VALID.
   */
  private List<WorldCrafting.Visible> matching(String text) {
    var member = WorldNetworks.get(viewer.server).membership(terminal.origin());
    if (member.status() != NetworkTopology.Status.VALID) return List.of();
    var codec = new PlatformResourceCodec(viewer.registryAccess());
    var units = new ArrayList<WorldCrafting.Visible>();
    for (var visible : WorldCrafting.get(viewer.server).visible(member.scope())) {
      if (units.size() == TerminalPatternPackets.MAX_OFFSET) break;
      if (text.isBlank()) units.add(visible);
      else
        for (int slot = 0; slot < TerminalPatternPackets.SLOTS; slot++)
          if (entry(codec, visible.unit().patterns().getStackInSlot(slot), text).match()) {
            units.add(visible);
            break;
          }
    }
    return units;
  }

  /** One slot as sent: the pattern's output, or the pattern item itself if it cannot be read. */
  private static TerminalPatternPackets.Entry entry(
      PlatformResourceCodec codec, ItemStack stack, String text) {
    if (stack.isEmpty()) return TerminalPatternPackets.Entry.EMPTY;
    var crafting = stack.get(CraftingContent.PATTERN.get());
    var processing = stack.get(CraftingContent.PROCESSING.get());
    ResourceKey output = null;
    boolean matched = false;
    try {
      var pattern =
          crafting != null
              ? crafting.pattern(codec)
              : processing != null ? processing.pattern(codec) : null;
      if (pattern != null) {
        output = pattern.output();
        matched = matches(codec, output, text);
        for (var byproduct : pattern.byproducts().keySet())
          matched |= matches(codec, byproduct, text);
      }
    } catch (IllegalArgumentException unreadable) {
      output = null;
    }
    if (output == null)
      return new TerminalPatternPackets.Entry(
          BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
          false,
          processing != null,
          text.isBlank());
    return new TerminalPatternPackets.Entry(
        output.registryId(),
        output.kind() == ResourceKind.FLUID,
        processing != null,
        text.isBlank() || matched);
  }

  private static boolean matches(PlatformResourceCodec codec, ResourceKey key, String text) {
    return text.isBlank() || TerminalSearchSupport.matches(codec, key, text);
  }

  /**
   * Rebuilds the window from the current Crafters and sends it when it changed (or when {@code
   * force}, so a refused click still reaches the client). Does nothing until the view is opened.
   */
  void publish(String text, boolean force) {
    if (!active) return;
    var units = matching(text);
    int total = units.size(), rows = TerminalPatternPackets.ROWS;
    // Clamped to the last Crafter, not the last full window: a short screen shows fewer rows and
    // keeps its own window full.
    offset = Math.max(0, Math.min(offset, total - 1));
    shown = List.copyOf(units.subList(offset, Math.min(total, offset + rows)));
    var codec = new PlatformResourceCodec(viewer.registryAccess());
    var crafters = new ArrayList<TerminalPatternPackets.Crafter>(shown.size());
    for (var visible : shown) {
      var unit = visible.unit();
      var entries = new ArrayList<TerminalPatternPackets.Entry>(TerminalPatternPackets.SLOTS);
      for (int slot = 0; slot < TerminalPatternPackets.SLOTS; slot++)
        entries.add(entry(codec, unit.patterns().getStackInSlot(slot), text));
      var origin = unit.origin();
      crafters.add(
          new TerminalPatternPackets.Crafter(
              unit.getBlockPos(),
              origin.dimension(),
              unit.label(),
              visible.published(),
              unit.editableBy(viewer),
              entries));
    }
    var next =
        new TerminalPatternPackets.View(
            menu.containerId, session, revision, offset, total, status, crafters);
    if (!force && next.equals(sent)) return;
    sent =
        new TerminalPatternPackets.View(
            menu.containerId, session, ++revision, offset, total, status, crafters);
    PacketDistributor.sendToPlayer(viewer, sent);
  }
}
