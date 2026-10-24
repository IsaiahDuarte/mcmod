package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.core.network.NetworkPermissions.Permission;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.platform.network.AccessPackets;
import dev.izzy.factorycore.platform.network.NetworkAccessMenu;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Inventory;
import org.lwjgl.glfw.GLFW;

/**
 * Network Access screen (ADR 0055): one row per principal with seven permission toggles, a revoke
 * button and an explicit two-step ownership transfer. Toggle text is a letter when granted and a
 * dot when not; a trailing {@code *} marks a grant that a gateway above this point blocks, so
 * meaning never depends on color alone. All state comes from the server's bounded view.
 */
public final class NetworkAccessScreen extends AbstractContainerScreen<NetworkAccessMenu> {
  private static final int ROWS = AccessPackets.PAGE_ROWS, PERMISSIONS = Permission.values().length;
  private static final int ROW_Y = 66, ROW_HEIGHT = 18, TOGGLE_X = 96, TOGGLE_STEP = 17;
  private static final String LETTERS = "VDWCEPM";
  private final Button[][] toggles = new Button[ROWS][PERMISSIONS];
  private final Button[] revokes = new Button[ROWS], owners = new Button[ROWS];
  private Button previous, next, recovery, confirm, cancel;
  private long shown = -1;

  public NetworkAccessScreen(NetworkAccessMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = 300;
    imageHeight = 200;
  }

  @Override
  protected void init() {
    super.init();
    shown = -1;
    for (int row = 0; row < ROWS; row++) {
      int index = row;
      int y = topPos + ROW_Y + row * ROW_HEIGHT;
      for (int bit = 0; bit < PERMISSIONS; bit++) {
        int permission = bit;
        toggles[row][bit] =
            addRenderableWidget(
                Button.builder(Component.empty(), button -> toggle(index, permission))
                    .bounds(leftPos + TOGGLE_X + bit * TOGGLE_STEP, y, 16, 16)
                    .build());
      }
      revokes[row] =
          addRenderableWidget(
              Button.builder(
                      Component.translatable("gui.factorycore.access.revoke"),
                      button -> act(index, AccessPackets.REVOKE_ALL))
                  .bounds(leftPos + 216, y, 40, 16)
                  .tooltip(
                      Tooltip.create(
                          Component.translatable("gui.factorycore.access.revoke.tooltip")))
                  .build());
      owners[row] =
          addRenderableWidget(
              Button.builder(
                      Component.translatable("gui.factorycore.access.make_owner"),
                      button -> act(index, AccessPackets.TRANSFER))
                  .bounds(leftPos + 258, y, 34, 16)
                  .tooltip(
                      Tooltip.create(
                          Component.translatable("gui.factorycore.access.make_owner.tooltip")))
                  .build());
    }
    int footer = topPos + ROW_Y + ROWS * ROW_HEIGHT + 2;
    previous =
        addRenderableWidget(
            Button.builder(Component.literal("<"), button -> page(-1))
                .bounds(leftPos + 8, footer, 16, 16)
                .tooltip(Tooltip.create(Component.translatable("gui.factorycore.access.previous")))
                .build());
    next =
        addRenderableWidget(
            Button.builder(Component.literal(">"), button -> page(1))
                .bounds(leftPos + 56, footer, 16, 16)
                .tooltip(Tooltip.create(Component.translatable("gui.factorycore.access.next")))
                .build());
    recovery =
        addRenderableWidget(
            Button.builder(
                    Component.translatable("gui.factorycore.access.recovery"),
                    button -> menu.send(AccessPackets.RECOVERY, AccessPackets.NONE, 0))
                .bounds(leftPos + 218, footer, 74, 16)
                .tooltip(
                    Tooltip.create(
                        Component.translatable("gui.factorycore.access.recovery.tooltip")))
                .build());
    confirm =
        addRenderableWidget(
            Button.builder(
                    Component.translatable("gui.factorycore.access.confirm"),
                    button -> menu.send(AccessPackets.CONFIRM, menu.view().pending(), 0))
                .bounds(leftPos + 40, topPos + 146, 108, 18)
                .build());
    cancel =
        addRenderableWidget(
            Button.builder(
                    Component.translatable("gui.factorycore.access.cancel"),
                    button -> menu.send(AccessPackets.CANCEL, AccessPackets.NONE, 0))
                .bounds(leftPos + 152, topPos + 146, 108, 18)
                .build());
    refresh();
  }

  private void toggle(int row, int bit) {
    var rows = menu.view().rows();
    if (row >= rows.size()) return;
    var principal = rows.get(row);
    boolean granted = (principal.granted() & 1 << bit) != 0;
    menu.send(granted ? AccessPackets.REVOKE : AccessPackets.GRANT, principal.id(), bit);
  }

  private void act(int row, int action) {
    var rows = menu.view().rows();
    if (row < rows.size()) menu.send(action, rows.get(row).id(), 0);
  }

  private void page(int delta) {
    var view = menu.view();
    int target = view.page() + delta;
    if (target >= 0 && target < pages(view))
      menu.send(AccessPackets.PAGE, AccessPackets.NONE, target);
  }

  private static int pages(AccessPackets.View view) {
    return Math.max(1, (view.total() + ROWS - 1) / ROWS);
  }

  @Override
  public boolean keyPressed(int key, int scan, int modifiers) {
    if (key == GLFW.GLFW_KEY_PAGE_UP) {
      page(-1);
      return true;
    }
    if (key == GLFW.GLFW_KEY_PAGE_DOWN) {
      page(1);
      return true;
    }
    return super.keyPressed(key, scan, modifiers);
  }

  @Override
  protected void containerTick() {
    super.containerTick();
    if (menu.view().revision() != shown) refresh();
  }

  private static Component permission(int bit) {
    return Component.translatable(
        "permission.factorycore." + Permission.values()[bit].name().toLowerCase(Locale.ROOT));
  }

  /** Rebuilds widget text, visibility and tooltips from the latest accepted view. */
  private void refresh() {
    var view = menu.view();
    shown = view.revision();
    boolean pending = !view.pending().equals(AccessPackets.NONE);
    for (int row = 0; row < ROWS; row++) {
      var principal = row < view.rows().size() ? view.rows().get(row) : null;
      boolean visible = view.manage() && principal != null;
      for (int bit = 0; bit < PERMISSIONS; bit++) {
        var button = toggles[row][bit];
        button.visible = visible && !pending;
        if (!visible) continue;
        boolean granted = (principal.granted() & 1 << bit) != 0;
        boolean blocked = granted && (view.branch() & 1 << bit) == 0;
        button.setMessage(
            Component.literal(granted ? LETTERS.charAt(bit) + (blocked ? "*" : "") : "·"));
        button.setTooltip(
            Tooltip.create(
                Component.translatable(
                    "gui.factorycore.access.toggle."
                        + (blocked ? "blocked" : granted ? "granted" : "absent"),
                    permission(bit),
                    principal.name())));
      }
      revokes[row].visible = visible && !pending && !principal.candidate();
      owners[row].visible = visible && !pending && view.transfer();
    }
    previous.visible = next.visible = view.manage() && !pending && pages(view) > 1;
    previous.active = view.page() > 0;
    next.active = view.page() + 1 < pages(view);
    recovery.visible = !pending;
    confirm.visible = cancel.visible = pending;
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
    graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff282c30);
    graphics.fill(
        leftPos + 4, topPos + 4, leftPos + imageWidth - 4, topPos + imageHeight - 4, 0xff44484d);
    if (!menu.view().pending().equals(AccessPackets.NONE))
      graphics.fill(leftPos + 8, topPos + 60, leftPos + imageWidth - 8, topPos + 170, 0xff5a3c2c);
  }

  private MutableComponent names(int mask, int all) {
    if (mask == 0) return Component.translatable("gui.factorycore.access.none");
    if (mask == all) return Component.translatable("gui.factorycore.access.all");
    MutableComponent text = Component.empty();
    boolean first = true;
    for (int bit = 0; bit < PERMISSIONS; bit++)
      if ((mask & 1 << bit) != 0) {
        if (!first) text.append(", ");
        text.append(permission(bit));
        first = false;
      }
    return text;
  }

  private MutableComponent kinds(int mask) {
    if (mask == 0) return Component.translatable("gui.factorycore.access.none");
    MutableComponent text = Component.empty();
    boolean first = true;
    for (var kind : ResourceKind.values())
      if ((mask & 1 << kind.ordinal()) != 0) {
        if (!first) text.append(", ");
        text.append(
            Component.translatable("kind.factorycore." + kind.name().toLowerCase(Locale.ROOT)));
        first = false;
      }
    return text;
  }

  private void line(GuiGraphics graphics, Component text, int y, int color) {
    graphics.drawString(
        font, font.plainSubstrByWidth(text.getString(), imageWidth - 16), 8, y, color, false);
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    var view = menu.view();
    int all = (1 << PERMISSIONS) - 1;
    graphics.drawString(font, title, 8, 6, 0xffffff, false);
    int strip = 8 + font.width(title) + 10;
    StatusStrip.draw(graphics, font, menu.deviceStatus(), strip, 6, imageWidth - 8 - strip);
    if (view.manage())
      line(
          graphics,
          Component.translatable(
              view.ownerSelf()
                  ? "gui.factorycore.access.owner_self"
                  : "gui.factorycore.access.owner",
              view.owner()),
          18,
          0xe4bc83);
    else
      line(
          graphics,
          Component.translatable(
              view.ownerSelf()
                  ? "gui.factorycore.access.owner_blocked"
                  : "gui.factorycore.access.readonly"),
          18,
          0xe4bc83);
    line(
        graphics,
        Component.translatable("gui.factorycore.access.self", names(view.self(), all)),
        30,
        0xd8dee9);
    if (view.gateways() == 0)
      line(graphics, Component.translatable("gui.factorycore.access.root"), 42, 0xa3be8c);
    else {
      line(
          graphics,
          Component.translatable(
              "gui.factorycore.access.limits", view.gateways(), names(view.branch(), all)),
          42,
          0xebcb8b);
      line(
          graphics,
          Component.translatable(
              "gui.factorycore.access.inherited",
              names(view.inherited(), all),
              kinds(view.kinds())),
          52,
          0xebcb8b);
    }
    if (!view.pending().equals(AccessPackets.NONE)) {
      int y = 66;
      for (var text :
          font.split(
              Component.translatable("gui.factorycore.access.confirm_text", view.pendingName()),
              imageWidth - 24)) {
        graphics.drawString(font, text, 12, y, 0xffffff, false);
        y += 10;
      }
    } else if (view.manage()) {
      graphics.drawString(
          font,
          Component.translatable("gui.factorycore.access.player"),
          8,
          ROW_Y - 9,
          0xe4bc83,
          false);
      for (int bit = 0; bit < PERMISSIONS; bit++)
        graphics.drawString(
            font,
            String.valueOf(LETTERS.charAt(bit)),
            TOGGLE_X + bit * TOGGLE_STEP + 5,
            ROW_Y - 9,
            (view.branch() & 1 << bit) == 0 ? 0xbf616a : 0xe4bc83,
            false);
      for (int row = 0; row < view.rows().size(); row++) {
        var principal = view.rows().get(row);
        String name =
            (principal.candidate() ? "+ " : "")
                + principal.name()
                + (principal.online()
                    ? ""
                    : Component.translatable("gui.factorycore.access.offline").getString());
        graphics.drawString(
            font,
            font.plainSubstrByWidth(name, TOGGLE_X - 12),
            8,
            ROW_Y + row * ROW_HEIGHT + 4,
            principal.candidate() ? 0xa0a8b0 : 0xffffff,
            false);
      }
      if (view.rows().isEmpty())
        line(graphics, Component.translatable("gui.factorycore.access.empty"), ROW_Y + 4, 0xd8dee9);
      if (pages(view) > 1)
        graphics.drawCenteredString(
            font,
            (view.page() + 1) + "/" + pages(view),
            40,
            ROW_Y + ROWS * ROW_HEIGHT + 6,
            0xffffff);
    } else {
      int y = ROW_Y;
      for (var text :
          font.split(
              Component.translatable("gui.factorycore.access.readonly_help"), imageWidth - 16)) {
        graphics.drawString(font, text, 8, y, 0xd8dee9, false);
        y += 10;
      }
    }
    var status =
        font.plainSubstrByWidth(
            Component.translatable("gui.factorycore.access.status." + view.status()).getString(),
            136);
    graphics.drawString(font, status, 78, ROW_Y + ROWS * ROW_HEIGHT + 6, 0xd8dee9, false);
  }
}
