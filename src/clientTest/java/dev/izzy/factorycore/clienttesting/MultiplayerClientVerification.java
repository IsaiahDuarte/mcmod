package dev.izzy.factorycore.clienttesting;

import static dev.izzy.factorycore.clienttesting.MultiplayerSync.*;

import dev.izzy.factorycore.platform.client.GatewayPolicyScreen;
import dev.izzy.factorycore.platform.client.NetworkAccessScreen;
import dev.izzy.factorycore.platform.client.TerminalScreen;
import dev.izzy.factorycore.platform.client.ui.ToggleButton;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.TerminalPackets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * One real graphical client of the multiplayer check ({@code owner} or {@code stranger}). It joins
 * the dedicated server over TCP and performs its role's part of each server-published step with
 * native screens, real C2S packets and chat commands, then acknowledges the step. Only outcomes the
 * client itself observed (screens, synced views, chat/action-bar keys, own inventory) are checked
 * here; the server checks canonical state.
 */
public final class MultiplayerClientVerification {
  private static final int STEP_DEADLINE = 1800;
  private final String role = System.getProperty("factorycore.multiplayer.role");
  private final boolean owner = "owner".equals(role);
  private final MultiplayerSync sync =
      new MultiplayerSync(Path.of(System.getProperty("factorycore.multiplayer.dir")));
  private final Path report = sync.directory().resolve(role).resolve("result.txt");
  private final Map<String, Integer> received = new HashMap<>();
  private final StringBuilder evidence = new StringBuilder();
  private final Captures captures = new Captures("multiplayer-" + role, report, evidence);
  private boolean connecting, leaving, done;
  private int ticks, step, acknowledged, entered, sub, mark, policyMask, retries;

  public MultiplayerClientVerification() {
    NeoForge.EVENT_BUS.addListener(this::tick);
    NeoForge.EVENT_BUS.addListener(this::chat);
  }

  private void chat(ClientChatReceivedEvent event) {
    collect(event.getMessage());
    System.out.println(
        "FACTORYCORE_MULTIPLAYER_CHAT "
            + role
            + (event instanceof ClientChatReceivedEvent.System system && system.isOverlay()
                ? " overlay: "
                : ": ")
            + event.getMessage().getString());
  }

  /** Counts every translation key in a message, including wrapped command feedback. */
  private void collect(Component component) {
    if (component.getContents() instanceof TranslatableContents translatable) {
      received.merge(translatable.getKey(), 1, Integer::sum);
      for (var argument : translatable.getArgs())
        if (argument instanceof Component nested) collect(nested);
    }
    for (var sibling : component.getSiblings()) collect(sibling);
  }

  private int count(String key) {
    return received.getOrDefault(key, 0);
  }

  private void tick(ClientTickEvent.Post event) {
    if (done) return;
    var client = Minecraft.getInstance();
    ticks++;
    try {
      if (client.screen instanceof DisconnectedScreen disconnected && !leaving)
        throw new IllegalStateException(
            "Disconnected: " + disconnected.getNarrationMessage().getString());
      if (!connecting) {
        connect(client);
        return;
      }
      if (client.player == null || client.level == null) return;
      // Tutorial/social/chat-verification toasts would cover the captured screens.
      client.getToasts().clear();
      int published = sync.step();
      if (published > step) {
        step = published;
        entered = ticks;
        sub = 0;
        System.out.println("FACTORYCORE_MULTIPLAYER_CLIENT " + role + " step " + step);
      }
      if (step == 0 || acknowledged == step) return;
      if (ticks - entered > STEP_DEADLINE)
        throw new IllegalStateException("Step " + step + " deadline at substep " + sub);
      if (owner ? ownerStep(client) : strangerStep(client)) {
        acknowledged = step;
        sync.acknowledge(role, step);
        if (step == FINISH) leave(client);
      }
    } catch (RuntimeException error) {
      failed(client, error);
    }
  }

  private void connect(Minecraft client) {
    if (client.screen instanceof AccessibilityOnboardingScreen onboarding) {
      onboarding.onClose();
      return;
    }
    if (!(client.screen instanceof TitleScreen)
        || !Files.isRegularFile(sync.directory().resolve("server.ready"))) return;
    String port;
    try {
      port = Files.readString(sync.directory().resolve("port.txt")).trim();
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
    client.getWindow().setWindowed(1024, 768);
    client.options.guiScale().set(2);
    client.options.pauseOnLostFocus = false;
    client.getTutorial().setStep(TutorialSteps.NONE);
    client.resizeDisplay();
    require(
        client.getUser().getName().equals(owner ? OWNER : STRANGER),
        "Offline username " + client.getUser().getName());
    var address = "127.0.0.1:" + port;
    ConnectScreen.startConnecting(
        client.screen,
        client,
        ServerAddress.parseString(address),
        new ServerData("Factory Core multiplayer check", address, ServerData.Type.OTHER),
        false,
        null);
    connecting = true;
    evidence.append(role).append(" connected as ").append(client.getUser().getName());
    evidence.append(" to ").append(address).append(" over TCP\n");
  }

  /** Owner role; returns true once this step's part is complete. */
  private boolean ownerStep(Minecraft client) {
    return switch (step) {
      case OWNER_POLICY -> ownerPolicy(client);
      case GRANT_VIEW -> command(client, "grant", "view");
      case GRANT_MOVE -> command(client, "grant", "deposit withdraw");
      case BOTH_OPEN -> openTerminal(client, SEEDED_IRON, "owner-terminal-concurrent.png");
      case STRANGER_TAKES -> observe(client, 63, "owner-sees-stranger-take.png");
      case OWNER_TAKES -> take(client, 1, 62, "owner-took-one.png");
      case STRANGER_DEPOSITS -> observe(client, 63, "owner-sees-stranger-deposit.png");
      case BOTH_TAKE -> take(client, 2, 61, "owner-concurrent-take.png");
      case REVOKE -> ownerRevoke(client);
      case OPERATOR_GRANT -> ownerAccess(client);
      case FINISH -> finish(client);
      default -> true;
    };
  }

  /** Stranger role; returns true once this step's part is complete. */
  private boolean strangerStep(Minecraft client) {
    return switch (step) {
      case STRANGER_DENIED -> strangerDenied(client);
      case STRANGER_POLICY -> strangerPolicy(client);
      case GRANT_VIEW -> notified(client, 1);
      case VIEW_ONLY -> viewOnly(client);
      case GRANT_MOVE -> notified(client, 2);
      case BOTH_OPEN -> openTerminal(client, SEEDED_IRON, "stranger-terminal-concurrent.png");
      case STRANGER_TAKES -> take(client, 1, 63, "stranger-took-one.png");
      case OWNER_TAKES -> observe(client, 62, "stranger-sees-owner-take.png");
      case STRANGER_DEPOSITS -> deposit(client);
      case BOTH_TAKE -> take(client, 1, 61, "stranger-concurrent-take.png");
      case REVOKE -> strangerRevoked(client);
      case OPERATOR_GRANT -> operatorGranted(client);
      case FINISH -> finish(client);
      default -> true;
    };
  }

  private boolean strangerDenied(Minecraft client) {
    switch (sub) {
      case 0 -> {
        if (ticks - entered < 10) return false;
        mark = count("message.factorycore.terminal_unavailable");
        use(client, TERMINAL, Direction.NORTH);
        sub++;
      }
      case 1 -> {
        if (count("message.factorycore.terminal_unavailable") <= mark) return false;
        require(client.screen == null, "No terminal screen opens for a stranger");
        mark = ticks;
        sub++;
      }
      case 2 -> {
        if (ticks - mark < 5) return false;
        require(client.screen == null, "Still no terminal screen");
        captures.grab(client, "stranger-terminal-denied.png");
        mark = count("command.factorycore.access.denied");
        client.player.connection.sendCommand(
            "factorycore access grant " + TERMINAL_ARGS + " " + STRANGER + " all");
        sub++;
      }
      case 3 -> {
        if (count("command.factorycore.access.denied") <= mark) return false;
        mark = ticks;
        sub++;
      }
      case 4 -> {
        if (ticks - mark < 5) return false;
        captures.grab(client, "stranger-self-grant-refused.png");
        evidence.append("stranger: terminal use refused (action bar) and self-grant refused\n");
        return true;
      }
      default -> {}
    }
    return false;
  }

  private boolean ownerPolicy(Minecraft client) {
    switch (sub) {
      case 0 -> {
        shift(client, true);
        use(client, GATEWAY, Direction.UP);
        sub++;
      }
      case 1 -> {
        if (!(client.screen instanceof GatewayPolicyScreen screen) || !screen.getMenu().editable())
          return false;
        shift(client, false);
        // U8: the screen opens on its presets; Custom reveals the individual toggles.
        click(screen, "Custom");
        policyMask = screen.getMenu().policy().mask();
        mark = ticks;
        sub++;
      }
      case 2 -> {
        if (ticks - mark < 5) return false;
        var screen = (GatewayPolicyScreen) client.screen;
        var first = firstButton(screen);
        require(first.active, "Owner toggles are enabled");
        screen.mouseClicked(
            first.getX() + first.getWidth() / 2.0, first.getY() + first.getHeight() / 2.0, 0);
        sub++;
      }
      case 3 -> {
        var screen = (GatewayPolicyScreen) client.screen;
        if (screen.getMenu().policy().mask() == policyMask) return false;
        require(
            (screen.getMenu().policy().mask() ^ policyMask) == 1,
            "Owner click flips exactly the first bit");
        mark = ticks;
        sub++;
      }
      case 4 -> {
        if (ticks - mark < 5) return false;
        captures.grab(client, "owner-policy-editable.png");
        client.player.closeContainer();
        evidence.append("owner: gateway policy editable; first branch bit toggled\n");
        return true;
      }
      default -> {}
    }
    return false;
  }

  private boolean strangerPolicy(Minecraft client) {
    switch (sub) {
      case 0 -> {
        shift(client, true);
        use(client, GATEWAY, Direction.UP);
        sub++;
      }
      case 1 -> {
        if (!(client.screen instanceof GatewayPolicyScreen screen)
            || screen.getMenu().policy().mask() == 0) return false;
        shift(client, false);
        mark = ticks;
        sub++;
      }
      case 2 -> {
        if (ticks - mark < 10) return false;
        var screen = (GatewayPolicyScreen) client.screen;
        require(!screen.getMenu().editable(), "The stranger's policy editor is read-only");
        require(!firstButton(screen).active, "The stranger's toggles are disabled");
        policyMask = screen.getMenu().policy().mask();
        // A modified client could still send the click; the server must refuse it.
        client.gameMode.handleInventoryButtonClick(screen.getMenu().containerId, 0);
        mark = ticks;
        sub++;
      }
      case 3 -> {
        if (ticks - mark < 20) return false;
        var screen = (GatewayPolicyScreen) client.screen;
        require(
            screen.getMenu().policy().mask() == policyMask,
            "A forged policy click from the stranger changes nothing");
        captures.grab(client, "stranger-policy-readonly.png");
        client.player.closeContainer();
        evidence.append("stranger: gateway policy read-only; forged click refused\n");
        return true;
      }
      default -> {}
    }
    return false;
  }

  /** Owner chat command against the network at the terminal; waits for its success feedback. */
  private boolean command(Minecraft client, String verb, String permissions) {
    switch (sub) {
      case 0 -> {
        mark = count("command.factorycore.access.updated");
        client.player.connection.sendCommand(
            "factorycore access "
                + verb
                + " "
                + TERMINAL_ARGS
                + " "
                + STRANGER
                + (permissions.isEmpty() ? "" : " " + permissions));
        sub++;
      }
      case 1 -> {
        if (count("command.factorycore.access.updated") <= mark) return false;
        evidence.append("owner: /factorycore access ").append(verb).append(' ');
        evidence.append(permissions.isEmpty() ? "(all)" : permissions).append(" accepted\n");
        return true;
      }
      default -> {}
    }
    return false;
  }

  private boolean notified(Minecraft client, int expected) {
    if (count("command.factorycore.access.notified") < expected) return false;
    evidence.append("stranger: access change notification ").append(expected).append('\n');
    return true;
  }

  private boolean viewOnly(Minecraft client) {
    switch (sub) {
      case 0 -> {
        use(client, TERMINAL, Direction.NORTH);
        sub++;
      }
      case 1 -> {
        if (ironTotal(client) != SEEDED_IRON) return false;
        retries = 0;
        captures.grab(client, "stranger-view-only.png");
        mark = ticks;
        takeOne(client);
        sub++;
      }
      case 2 -> {
        var view = terminal(client).getMenu().view();
        // An admission refusal changes the status without a new revision. A background rescan
        // can bump the revision first, so the server drops the request as stale; resend then.
        if (!view.status().equals("denied") && !view.busy() && ticks - mark > 40) {
          retries++;
          require(retries <= 5, "View-only withdrawal kept losing its revision");
          takeOne(client);
          mark = ticks;
          return false;
        }
        if (!view.status().equals("denied")) return false;
        require(view.status().equals("denied"), "View-only withdrawal is denied: " + view.status());
        require(iron(client) == 0, "No item reached the view-only stranger");
        mark = ticks;
        sub++;
      }
      case 3 -> {
        if (ticks - mark < 5) return false;
        captures.grab(client, "stranger-take-denied.png");
        client.player.closeContainer();
        evidence.append("stranger: View-only terminal shows 64; Take 1 refused (denied)\n");
        return true;
      }
      default -> {}
    }
    return false;
  }

  private boolean openTerminal(Minecraft client, int total, String capture) {
    switch (sub) {
      case 0 -> {
        use(client, TERMINAL, Direction.NORTH);
        sub++;
      }
      case 1 -> {
        if (ironTotal(client) != total) return false;
        mark = ticks;
        sub++;
      }
      case 2 -> {
        if (ticks - mark < 5) return false;
        captures.grab(client, capture);
        evidence.append(role).append(": terminal open showing ").append(total).append('\n');
        return true;
      }
      default -> {}
    }
    return false;
  }

  /**
   * Takes one iron through the real C2S withdraw payload; retried only if the server dropped it as
   * stale.
   */
  private boolean take(Minecraft client, int expectedIron, int expectedTotal, String capture) {
    switch (sub) {
      case 0 -> {
        takeOne(client);
        mark = ticks;
        sub++;
      }
      case 1 -> {
        var view = terminal(client).getMenu().view();
        if (iron(client) == expectedIron) {
          sub++;
          return false;
        }
        require(iron(client) < expectedIron, "Exactly one item per Take 1");
        if (ticks - mark > 40 && !view.busy()) {
          // The other player's change bumped the server revision first; the server ignored ours.
          retries++;
          require(retries <= 5, "Take 1 kept losing its revision");
          takeOne(client);
          mark = ticks;
        }
      }
      case 2 -> {
        require(iron(client) == expectedIron, "Exactly one item per Take 1");
        if (ironTotal(client) != expectedTotal) return false;
        mark = ticks;
        sub++;
      }
      case 3 -> {
        if (ticks - mark < 5) return false;
        captures.grab(client, capture);
        evidence.append(role).append(": Take 1 -> holds ").append(expectedIron);
        evidence.append(", terminal shows ").append(expectedTotal);
        evidence.append(" (stale-revision retries so far ").append(retries).append(")\n");
        return true;
      }
      default -> {}
    }
    return false;
  }

  /** Waits without acting until the other player's change reaches this player's open terminal. */
  private boolean observe(Minecraft client, int total, String capture) {
    switch (sub) {
      case 0 -> {
        if (ironTotal(client) != total) return false;
        evidence.append(role).append(": idle terminal updated to ").append(total);
        evidence.append(" after ").append(ticks - entered).append(" client ticks\n");
        mark = ticks;
        sub++;
      }
      case 1 -> {
        if (ticks - mark < 5) return false;
        captures.grab(client, capture);
        return true;
      }
      default -> {}
    }
    return false;
  }

  private boolean deposit(Minecraft client) {
    switch (sub) {
      case 0 -> {
        var screen = terminal(client);
        int slot = -1;
        for (int i = 0; i < 36; i++)
          if (client.player.getInventory().getItem(i).is(Items.IRON_INGOT)) slot = i;
        require(slot >= 0, "The stranger holds the iron it took");
        var menu = screen.getMenu();
        var digest =
            TerminalPackets.fingerprint(
                new PlatformResourceCodec(client.level.registryAccess())
                    .itemKey(client.player.getInventory().getItem(slot)));
        PacketDistributor.sendToServer(
            new TerminalPackets.Command(
                menu.containerId,
                menu.session(),
                menu.view().revision(),
                TerminalPackets.DEPOSIT,
                slot,
                1,
                "",
                digest));
        sub++;
      }
      case 1 -> {
        if (iron(client) != 0 || ironTotal(client) != 63) return false;
        mark = ticks;
        sub++;
      }
      case 2 -> {
        if (ticks - mark < 5) return false;
        captures.grab(client, "stranger-deposited.png");
        evidence.append("stranger: deposited 1 through the real C2S payload; shows 63\n");
        return true;
      }
      default -> {}
    }
    return false;
  }

  private boolean ownerRevoke(Minecraft client) {
    switch (sub) {
      case 0, 1 -> {
        if (!command(client, "revoke", "")) return false;
        sub = 2;
        mark = ticks;
      }
      case 2 -> {
        // The owner's own session re-reads after the permission generation changed.
        var view = terminal(client).getMenu().view();
        // Successful transfers retain their "ok" result across background observations; an idle
        // session uses "ready". Both must remain usable after somebody else's grants change.
        if (ticks - mark < 40
            || view.busy()
            || !(view.status().equals("ready") || view.status().equals("ok"))
            || ironTotal(client) != 61) return false;
        captures.grab(client, "owner-after-revoke.png");
        evidence.append("owner: own session remains usable with 61 after revoking the stranger\n");
        return true;
      }
      default -> {}
    }
    return false;
  }

  private boolean strangerRevoked(Minecraft client) {
    switch (sub) {
      case 0 -> {
        if (client.screen instanceof TerminalScreen
            || count("command.factorycore.access.notified") < 3) return false;
        mark = ticks;
        sub++;
      }
      case 1 -> {
        if (ticks - mark < 10) return false;
        captures.grab(client, "stranger-revoked-closed.png");
        mark = count("message.factorycore.terminal_unavailable");
        use(client, TERMINAL, Direction.NORTH);
        sub++;
      }
      case 2 -> {
        if (count("message.factorycore.terminal_unavailable") <= mark) return false;
        require(client.screen == null, "Reopening after revocation is refused");
        evidence.append("stranger: revocation closed the open terminal; reopen refused\n");
        return true;
      }
      default -> {}
    }
    return false;
  }

  private boolean operatorGranted(Minecraft client) {
    switch (sub) {
      case 0 -> {
        if (count("command.factorycore.access.notified") < 4) return false;
        use(client, TERMINAL, Direction.NORTH);
        sub++;
      }
      case 1 -> {
        if (ironTotal(client) != 61) return false;
        mark = ticks;
        sub++;
      }
      case 2 -> {
        if (ticks - mark < 5) return false;
        captures.grab(client, "stranger-operator-granted.png");
        client.player.closeContainer();
        evidence.append("stranger: operator console grant restored View; terminal shows 61\n");
        return true;
      }
      default -> {}
    }
    return false;
  }

  /** P3: Network Access names the second real player from the server, not by UUID. */
  private boolean ownerAccess(Minecraft client) {
    switch (sub) {
      case 0 -> {
        close(client);
        mark = ticks;
        sub++;
      }
      case 1 -> {
        if (ticks - mark < 5) return false;
        use(client, ROOT, Direction.UP);
        sub++;
      }
      case 2 -> {
        if (!(client.screen instanceof NetworkAccessScreen screen)) return false;
        var rows = screen.getMenu().view().rows();
        if (rows.stream().noneMatch(row -> row.id().equals(STRANGER_ID))) return false;
        var row = rows.stream().filter(entry -> entry.id().equals(STRANGER_ID)).findFirst().get();
        require(row.name().equals(STRANGER), "Access row names the guest: " + row.name());
        require(
            screen.getMenu().view().owner().equals(OWNER),
            "Access owner line names the owner: " + screen.getMenu().view().owner());
        mark = ticks;
        sub++;
      }
      case 3 -> {
        if (ticks - mark < 5) return false;
        captures.grab(client, "owner-access-names.png");
        close(client);
        evidence.append("owner: Network Access lists " + STRANGER + " and owner by name\n");
        return true;
      }
      default -> {}
    }
    return false;
  }

  private boolean close(Minecraft client) {
    if (client.screen != null) client.player.closeContainer();
    return true;
  }

  private boolean finish(Minecraft client) {
    close(client);
    write("PASS\n" + evidence);
    return true;
  }

  private void leave(Minecraft client) {
    leaving = true;
    done = true;
    System.out.println("FACTORYCORE_MULTIPLAYER_CLIENT_PASS " + role);
    client.level.disconnect();
    client.disconnect();
    client.stop();
  }

  private void failed(Minecraft client, Throwable error) {
    done = true;
    leaving = true;
    error.printStackTrace();
    write("FAIL\nstep=" + step + " sub=" + sub + " " + error + "\n" + evidence);
    if (client.level != null) client.level.disconnect();
    client.disconnect();
    client.stop();
  }

  private void write(String text) {
    try {
      Files.createDirectories(report.getParent());
      Files.writeString(report, text);
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
  }

  private static TerminalScreen terminal(Minecraft client) {
    if (client.screen instanceof TerminalScreen screen) return screen;
    throw new IllegalStateException(
        "Terminal screen expected; got "
            + (client.screen == null ? "none" : client.screen.getClass().getSimpleName()));
  }

  /** Iron total shown by this client's open, ready terminal view; -1 when not shown. */
  private static long ironTotal(Minecraft client) {
    if (!(client.screen instanceof TerminalScreen screen)) return -1;
    var view = screen.getMenu().view();
    if (view.busy()) return -1;
    for (var row : view.rows())
      if (row.icon().equals("minecraft:iron_ingot")) return Long.parseLong(row.total());
    return view.status().equals("loading") ? -1 : 0;
  }

  private static int iron(Minecraft client) {
    return client.player.getInventory().countItem(Items.IRON_INGOT);
  }

  /**
   * Withdraws one iron into the inventory with the same payload the grid's Shift-click sends (the
   * grid itself always asks for up to a stack; U3 removed the old Take 1 button).
   */
  private static void takeOne(Minecraft client) {
    var menu = terminal(client).getMenu();
    var rows = menu.view().rows();
    int index = -1;
    for (int i = 0; i < rows.size(); i++)
      if (rows.get(i).icon().equals("minecraft:iron_ingot")) index = i;
    require(index >= 0, "Iron row present");
    PacketDistributor.sendToServer(
        new TerminalPackets.Command(
            menu.containerId,
            menu.session(),
            menu.view().revision(),
            TerminalPackets.WITHDRAW,
            index,
            1,
            "",
            new byte[32]));
  }

  /** The first policy toggle (branch View), whether or not the Custom view shows it. */
  private static ToggleButton firstButton(Screen screen) {
    return screen.children().stream()
        .filter(child -> child instanceof ToggleButton)
        .map(child -> (ToggleButton) child)
        .filter(toggle -> toggle.getMessage().getString().equals("View"))
        .findFirst()
        .orElseThrow();
  }

  private static void click(Screen screen, String text) {
    for (var child : screen.children())
      if (child instanceof Button button
          && button.visible
          && button.getMessage().getString().equals(text)) {
        require(button.active, "Action enabled: " + text);
        screen.mouseClicked(
            button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0, 0);
        return;
      }
    throw new IllegalStateException("Action missing: " + text);
  }

  /** Actual right-click on a block: the server checks reach, sneak state and permissions. */
  private static void use(Minecraft client, BlockPos position, Direction face) {
    client.gameMode.useItemOn(
        client.player,
        InteractionHand.MAIN_HAND,
        new BlockHitResult(Vec3.atCenterOf(position), face, position, false));
  }

  private static void shift(Minecraft client, boolean down) {
    client.player.connection.send(
        new ServerboundPlayerCommandPacket(
            client.player,
            down
                ? ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY
                : ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }
}
