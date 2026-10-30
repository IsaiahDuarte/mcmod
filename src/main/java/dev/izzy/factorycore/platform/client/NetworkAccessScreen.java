package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkPermissions.Permission;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.platform.client.ui.ChoiceButton;
import dev.izzy.factorycore.platform.client.ui.PanelLayout;
import dev.izzy.factorycore.platform.client.ui.Panels;
import dev.izzy.factorycore.platform.client.ui.Rect;
import dev.izzy.factorycore.platform.network.AccessPackets;
import dev.izzy.factorycore.platform.network.NetworkAccessMenu;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Inventory;
import org.lwjgl.glfw.GLFW;

/**
 * Network Access screen (ADR 0055; on the panel kit since playtest H3b). A list of players, one
 * line each with their role; the selected player's role is a row of choices under the list (U8:
 * Guest, Member, Builder, Admin; Advanced shows the seven permission toggles instead), with Revoke
 * and an explicit two-step ownership transfer. A toggle shows its letter and is selected when
 * granted; a trailing {@code *} marks a grant that a gateway above this point blocks, so meaning
 * never depends on color alone. All state comes from the server's bounded view.
 */
public final class NetworkAccessScreen extends AbstractContainerScreen<NetworkAccessMenu> {
  private static final int ROWS = AccessPackets.PAGE_ROWS, PERMISSIONS = Permission.values().length;
  private static final NetworkPermissions.AccessRole[] ROLES =
      NetworkPermissions.AccessRole.values();
  private static final String LETTERS = "VDWCEPM";

  /** Panel-relative layout: four info lines, the player list, its controls, then the result. */
  private static final int INFO_Y = PanelLayout.BODY,
      LIST_Y = INFO_Y + 4 * PanelLayout.LINE + PanelLayout.GAP,
      LINE_HEIGHT = 12,
      LIST_HEIGHT = ROWS * LINE_HEIGHT + 4,
      ROLE_Y = LIST_Y + LIST_HEIGHT + PanelLayout.GAP,
      ACTION_Y = ROLE_Y + PanelLayout.ROW + PanelLayout.GAP,
      MESSAGE_Y = ACTION_Y + PanelLayout.ROW + PanelLayout.GAP;

  private final PanelLayout layout =
      new PanelLayout(
          PanelLayout.WIDE, PanelLayout.heightFor(MESSAGE_Y + PanelLayout.LINE - PanelLayout.BODY));
  private final Rect list =
      new Rect(PanelLayout.EDGE, LIST_Y, PanelLayout.WIDE - 2 * PanelLayout.EDGE, LIST_HEIGHT);

  private final List<ChoiceButton> roles = new ArrayList<>(), toggles = new ArrayList<>();
  private Button previous, next, recovery, confirm, cancel, advancedButton, revoke, owner;

  /** Advanced shows the per-permission letter toggles instead of the role choices. */
  private boolean advanced;

  private int selected;
  private long shown = -1;
  private Rect footer;

  public NetworkAccessScreen(NetworkAccessMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = layout.width();
    imageHeight = layout.height();
  }

  @Override
  protected void init() {
    super.init();
    shown = -1;
    roles.clear();
    toggles.clear();
    var roleRow = new Rect(list.x(), ROLE_Y, list.width(), PanelLayout.ROW);
    var roleWidths = new int[ROLES.length];
    for (int i = 0; i < ROLES.length; i++) roleWidths[i] = font.width(roleName(ROLES[i])) + 10;
    var roleCells = PanelLayout.fit(roleRow, roleWidths, PanelLayout.GAP);
    for (int i = 0; i < ROLES.length; i++) {
      int target = ROLES[i].ordinal();
      roles.add(
          addRenderableWidget(
              new ChoiceButton(
                  roleCells.get(i).offset(leftPos, topPos),
                  roleName(ROLES[i]),
                  button -> act(AccessPackets.ROLE, target))));
    }
    for (int bit = 0; bit < PERMISSIONS; bit++) {
      int permission = bit;
      toggles.add(
          addRenderableWidget(
              new ChoiceButton(
                  new Rect(leftPos + list.x() + bit * 20, topPos + ROLE_Y, 18, PanelLayout.ROW),
                  Component.literal(String.valueOf(LETTERS.charAt(bit))),
                  button -> toggle(permission))));
    }

    // The selected player's actions on the left; Roles/Advanced on the right.
    var revokeLabel = Component.translatable("gui.factorycore.access.revoke");
    var ownerLabel = Component.translatable("gui.factorycore.access.make_owner");
    revoke =
        addRenderableWidget(
            Button.builder(revokeLabel, button -> act(AccessPackets.REVOKE_ALL, 0))
                .bounds(leftPos + list.x(), topPos + ACTION_Y, font.width(revokeLabel) + 12, 18)
                .tooltip(
                    Tooltip.create(Component.translatable("gui.factorycore.access.revoke.tooltip")))
                .build());
    owner =
        addRenderableWidget(
            Button.builder(ownerLabel, button -> act(AccessPackets.TRANSFER, 0))
                .bounds(
                    revoke.getRight() + PanelLayout.GAP,
                    topPos + ACTION_Y,
                    font.width(ownerLabel) + 12,
                    18)
                .tooltip(
                    Tooltip.create(
                        Component.translatable("gui.factorycore.access.make_owner.tooltip")))
                .build());
    int advancedWidth =
        Math.max(
                font.width(Component.translatable("gui.factorycore.access.advanced")),
                font.width(Component.translatable("gui.factorycore.access.roles")))
            + 12;
    advancedButton =
        addRenderableWidget(
            Button.builder(
                    Component.empty(),
                    button -> {
                      advanced = !advanced;
                      refresh();
                    })
                .bounds(
                    leftPos + list.right() - advancedWidth, topPos + ACTION_Y, advancedWidth, 18)
                .tooltip(
                    Tooltip.create(Component.translatable("gui.factorycore.access.advanced.help")))
                .build());

    // The ownership confirmation, in place of the list's controls.
    var confirmLabel = Component.translatable("gui.factorycore.access.confirm");
    var cancelLabel = Component.translatable("gui.factorycore.access.cancel");
    var confirmCells =
        PanelLayout.alignRight(
            new Rect(list.x(), ACTION_Y, list.width(), PanelLayout.ROW),
            new int[] {font.width(cancelLabel) + 12, font.width(confirmLabel) + 12},
            PanelLayout.GAP);
    cancel =
        addRenderableWidget(
            Button.builder(
                    cancelLabel, button -> menu.send(AccessPackets.CANCEL, AccessPackets.NONE, 0))
                .bounds(
                    leftPos + confirmCells.get(0).x(),
                    topPos + ACTION_Y,
                    confirmCells.get(0).width(),
                    18)
                .build());
    confirm =
        addRenderableWidget(
            Button.builder(
                    confirmLabel,
                    button -> menu.send(AccessPackets.CONFIRM, menu.view().pending(), 0))
                .bounds(
                    leftPos + confirmCells.get(1).x(),
                    topPos + ACTION_Y,
                    confirmCells.get(1).width(),
                    18)
                .build());

    // Footer: paging on the left, Recovery on the right.
    footer = layout.footer();
    previous =
        addRenderableWidget(
            Button.builder(Component.literal("<"), button -> page(-1))
                .bounds(leftPos + footer.x(), topPos + footer.y(), 18, 18)
                .tooltip(Tooltip.create(Component.translatable("gui.factorycore.access.previous")))
                .build());
    next =
        addRenderableWidget(
            Button.builder(Component.literal(">"), button -> page(1))
                .bounds(leftPos + footer.x() + 58, topPos + footer.y(), 18, 18)
                .tooltip(Tooltip.create(Component.translatable("gui.factorycore.access.next")))
                .build());
    var recoveryLabel = Component.translatable("gui.factorycore.access.recovery");
    int recoveryWidth = font.width(recoveryLabel) + 12;
    recovery =
        addRenderableWidget(
            Button.builder(
                    recoveryLabel,
                    button -> menu.send(AccessPackets.RECOVERY, AccessPackets.NONE, 0))
                .bounds(
                    leftPos + footer.right() - recoveryWidth,
                    topPos + footer.y(),
                    recoveryWidth,
                    18)
                .tooltip(
                    Tooltip.create(
                        Component.translatable("gui.factorycore.access.recovery.tooltip")))
                .build());
    refresh();
  }

  private AccessPackets.Row selectedPrincipal() {
    var rows = menu.view().rows();
    return selected < rows.size() ? rows.get(selected) : null;
  }

  private void toggle(int bit) {
    var principal = selectedPrincipal();
    if (principal == null) return;
    boolean granted = (principal.granted() & 1 << bit) != 0;
    menu.send(granted ? AccessPackets.REVOKE : AccessPackets.GRANT, principal.id(), bit);
  }

  private void act(int action, int value) {
    var principal = selectedPrincipal();
    if (principal != null) menu.send(action, principal.id(), value);
  }

  private static NetworkPermissions.AccessRole role(int mask) {
    var grants = EnumSet.noneOf(Permission.class);
    for (int bit = 0; bit < PERMISSIONS; bit++)
      if ((mask & 1 << bit) != 0) grants.add(Permission.values()[bit]);
    return NetworkPermissions.AccessRole.matching(grants);
  }

  private static Component roleName(NetworkPermissions.AccessRole role) {
    return Component.translatable(
        "gui.factorycore.access.role." + role.name().toLowerCase(Locale.ROOT));
  }

  private static Component roleName(int mask) {
    var role = role(mask);
    return mask == 0
        ? Component.translatable("gui.factorycore.access.role.none")
        : role == null
            ? Component.translatable("gui.factorycore.access.role.custom")
            : roleName(role);
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

  /** The list row under a screen point, or -1. */
  private int rowAt(double mouseX, double mouseY) {
    double x = mouseX - leftPos - list.x(), y = mouseY - topPos - list.y() - 2;
    if (x < 0 || x >= list.width() || y < 0) return -1;
    int row = (int) (y / LINE_HEIGHT);
    return row < menu.view().rows().size() ? row : -1;
  }

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    var view = menu.view();
    int row = rowAt(mouseX, mouseY);
    if (button == 0 && row >= 0 && view.manage() && view.pending().equals(AccessPackets.NONE)) {
      selected = row;
      refresh();
      return true;
    }
    return super.mouseClicked(mouseX, mouseY, button);
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
    selected = Math.max(0, Math.min(selected, view.rows().size() - 1));
    var principal = selectedPrincipal();
    boolean editing = view.manage() && !pending && principal != null;
    NetworkPermissions.AccessRole current = principal == null ? null : role(principal.granted());
    for (int i = 0; i < ROLES.length; i++) {
      var button = roles.get(i);
      button.visible = editing && !advanced;
      boolean chosen = principal != null && principal.granted() != 0 && ROLES[i] == current;
      button.setSelected(chosen);
      button.active = !chosen;
      if (principal != null)
        button.setTooltip(
            Tooltip.create(
                Component.translatable(
                    "gui.factorycore.access.role.help",
                    displayName(principal.name()),
                    names(principal.granted(), (1 << PERMISSIONS) - 1))));
    }
    for (int bit = 0; bit < PERMISSIONS; bit++) {
      var button = toggles.get(bit);
      button.visible = editing && advanced;
      if (principal == null) continue;
      boolean granted = (principal.granted() & 1 << bit) != 0;
      boolean blocked = granted && (view.branch() & 1 << bit) == 0;
      button.setSelected(granted);
      button.setMessage(Component.literal(LETTERS.charAt(bit) + (blocked ? "*" : "")));
      button.setTooltip(
          Tooltip.create(
              Component.translatable(
                  "gui.factorycore.access.toggle."
                      + (blocked ? "blocked" : granted ? "granted" : "absent"),
                  permission(bit),
                  displayName(principal.name()))));
    }
    revoke.visible = editing && !principal.candidate();
    owner.visible = editing && view.transfer();
    if (!revoke.visible) owner.setX(leftPos + list.x());
    else owner.setX(revoke.getRight() + PanelLayout.GAP);
    previous.visible = next.visible = view.manage() && !pending && pages(view) > 1;
    previous.active = view.page() > 0;
    next.active = view.page() + 1 < pages(view);
    recovery.visible = !pending;
    advancedButton.visible = view.manage() && !pending;
    advancedButton.setMessage(
        Component.translatable(
            advanced ? "gui.factorycore.access.roles" : "gui.factorycore.access.advanced"));
    confirm.visible = cancel.visible = pending;
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
    Panels.panel(graphics, leftPos, topPos, layout);
    var area = list.offset(leftPos, topPos);
    Panels.inset(graphics, area);
    var view = menu.view();
    if (!view.manage() || !view.pending().equals(AccessPackets.NONE)) return;
    int hovered = rowAt(mouseX, mouseY);
    for (int row = 0; row < view.rows().size(); row++) {
      int y = area.y() + 2 + row * LINE_HEIGHT;
      Panels.row(
          graphics,
          new Rect(area.x() + 1, y, area.width() - 2, LINE_HEIGHT),
          row == selected,
          row == hovered);
    }
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
        font,
        font.plainSubstrByWidth(text.getString(), list.width()),
        PanelLayout.EDGE + 1,
        y,
        color,
        false);
  }

  private static final java.util.regex.Pattern UUID_TEXT =
      java.util.regex.Pattern.compile("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");

  /**
   * The server sends a player's current or cached profile name; a bare UUID means neither exists
   * (the player never joined this server), so it is shortened under a readable label.
   */
  static String displayName(String name) {
    return UUID_TEXT.matcher(name).matches()
        ? Component.translatable("gui.factorycore.access.unknown_player", name.substring(0, 6))
            .getString()
        : name;
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
    super.render(graphics, mouseX, mouseY, partial);
    var rows = menu.view().rows();
    int row = rowAt(mouseX, mouseY);
    if (menu.view().manage() && menu.view().pending().equals(AccessPackets.NONE) && row >= 0)
      graphics.renderTooltip(
          font,
          List.of(
              Component.literal(displayName(rows.get(row).name())),
              Component.literal(rows.get(row).id().toString())),
          Optional.empty(),
          mouseX,
          mouseY);
    else renderTooltip(graphics, mouseX, mouseY);
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    var view = menu.view();
    int all = (1 << PERMISSIONS) - 1;
    Panels.title(graphics, font, title, 0, 0);
    int strip = PanelLayout.EDGE + 1 + font.width(title) + 8;
    StatusStrip.drawOnPanel(
        graphics, font, menu.deviceStatus(), strip, PanelLayout.TITLE_Y, imageWidth - 8 - strip);
    if (view.manage())
      line(
          graphics,
          Component.translatable(
              view.ownerSelf()
                  ? "gui.factorycore.access.owner_self"
                  : "gui.factorycore.access.owner",
              displayName(view.owner())),
          INFO_Y,
          Panels.TEXT);
    else
      line(
          graphics,
          Component.translatable(
              view.ownerSelf()
                  ? "gui.factorycore.access.owner_blocked"
                  : "gui.factorycore.access.readonly"),
          INFO_Y,
          Panels.WARN);
    line(
        graphics,
        Component.translatable("gui.factorycore.access.self", names(view.self(), all)),
        INFO_Y + PanelLayout.LINE,
        Panels.TEXT);
    if (view.gateways() == 0)
      line(
          graphics,
          Component.translatable("gui.factorycore.access.root"),
          INFO_Y + 2 * PanelLayout.LINE,
          Panels.MUTED);
    else {
      line(
          graphics,
          Component.translatable(
              "gui.factorycore.access.limits", view.gateways(), names(view.branch(), all)),
          INFO_Y + 2 * PanelLayout.LINE,
          Panels.WARN);
      line(
          graphics,
          Component.translatable(
              "gui.factorycore.access.inherited",
              names(view.inherited(), all),
              kinds(view.kinds())),
          INFO_Y + 3 * PanelLayout.LINE,
          Panels.WARN);
    }
    var inner = new Rect(list.x() + 3, list.y() + 2, list.width() - 6, list.height() - 4);
    if (!view.pending().equals(AccessPackets.NONE))
      Panels.wrapped(
          graphics,
          font,
          Component.translatable("gui.factorycore.access.confirm_text", view.pendingName()),
          inner,
          Panels.TEXT);
    else if (!view.manage())
      Panels.wrapped(
          graphics,
          font,
          Component.translatable("gui.factorycore.access.readonly_help"),
          inner,
          Panels.MUTED);
    else if (view.rows().isEmpty())
      Panels.wrapped(
          graphics,
          font,
          Component.translatable("gui.factorycore.access.empty"),
          inner,
          Panels.MUTED);
    else
      for (int row = 0; row < view.rows().size(); row++) {
        var principal = view.rows().get(row);
        int y = inner.y() + row * LINE_HEIGHT + 2;
        var role = roleName(principal.granted());
        int roleWidth = font.width(role);
        String name =
            (principal.candidate() ? "+ " : "")
                + displayName(principal.name())
                + (principal.online()
                    ? ""
                    : Component.translatable("gui.factorycore.access.offline").getString());
        graphics.drawString(
            font,
            font.plainSubstrByWidth(name, inner.width() - roleWidth - 8),
            inner.x(),
            y,
            principal.candidate() ? Panels.MUTED : Panels.TEXT,
            false);
        graphics.drawString(font, role, inner.right() - roleWidth, y, Panels.MUTED, false);
      }
    if (previous.visible)
      graphics.drawCenteredString(
          font,
          (view.page() + 1) + "/" + pages(view),
          footer.x() + 38,
          footer.y() + 5,
          Panels.TEXT);
    if (view.status() != AccessPackets.Status.READY)
      line(
          graphics,
          Component.translatable("gui.factorycore.access.status." + view.status().key()),
          MESSAGE_Y,
          view.status().good() ? Panels.GOOD : Panels.WARN);
  }
}
