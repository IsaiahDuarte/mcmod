package dev.izzy.factorycore.clienttesting;

import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.client.BufferSettingsScreen;
import dev.izzy.factorycore.platform.client.GatewayPolicyScreen;
import dev.izzy.factorycore.platform.client.ItemDescriptions;
import dev.izzy.factorycore.platform.client.MachineSettingsScreen;
import dev.izzy.factorycore.platform.client.NetworkAccessScreen;
import dev.izzy.factorycore.platform.client.ProgramScreen;
import dev.izzy.factorycore.platform.client.RecoveryScreen;
import dev.izzy.factorycore.platform.client.TerminalScreen;
import dev.izzy.factorycore.platform.guide.GuideContent;
import dev.izzy.factorycore.platform.network.DeviceLamp;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.storage.BufferedInterfaceEntity;
import dev.izzy.factorycore.platform.storage.MachineInterfaceEntity;
import dev.izzy.factorycore.platform.wireless.AccessPointEntity;
import dev.izzy.factorycore.platform.wireless.LinkEndpointEntity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Graphical presentation fixture ({@code runPresentationClient}): device orientations, status lamps
 * and drive bays by day and night, cable arms at sockets, first/third-person held items, real item
 * tooltips at screen edges, and every Factory Core screen at GUI scales 1–4 in the smallest
 * supported window (427×240 GUI pixels at scale 4). Each screen at each scale must keep its panel
 * and every visible control on screen, without overlap and with button labels that fit.
 */
public final class PresentationClientVerification {
  /** Smallest supported framebuffer: 427×240 GUI pixels at scale 4. */
  static final int MIN_WIDTH = 1708;

  static final int MIN_HEIGHT = 960;

  private interface Step {
    /** True when the step is complete; called once per client tick. */
    boolean tick(Minecraft client);
  }

  private final Path report = Path.of(System.getProperty("factorycore.client.report"));
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final AtomicBoolean serverDone = new AtomicBoolean();
  private final StringBuilder evidence = new StringBuilder();
  private final List<Step> steps = new ArrayList<>();
  private final List<BlockPos> fed = new ArrayList<>();
  private int index;
  private int ticks;
  private int entered;
  private int scale;
  private boolean requested;

  public PresentationClientVerification() {
    buildSteps();
    NeoForge.EVENT_BUS.addListener(this::tick);
    NeoForge.EVENT_BUS.addListener(this::feed);
  }

  // ------------------------------------------------------------------ plumbing

  /** Keeps gallery wireless devices powered: 1,000 FE per tick each, their real input limit. */
  @SuppressWarnings("PMD.CloseResource") // Borrowed server level.
  private void feed(ServerTickEvent.Post event) {
    var level = event.getServer().overworld();
    for (var position : List.copyOf(fed)) {
      var entity = level.getBlockEntity(position);
      if (entity instanceof LinkEndpointEntity link)
        link.energyStorage().receiveEnergy(1_000, false);
      if (entity instanceof AccessPointEntity point)
        point.energyStorage().receiveEnergy(1_000, false);
    }
  }

  @SuppressWarnings("PMD.CloseResource") // Borrowed singleton; Minecraft owns its lifecycle.
  private void tick(ClientTickEvent.Post event) {
    var client = Minecraft.getInstance();
    ticks++;
    if (index >= steps.size()) return;
    if (failure.get() != null)
      throw new IllegalStateException("Presentation fixture failed", failure.get());
    if (ticks - entered > 1200)
      throw new IllegalStateException(
          "Presentation stage timeout: "
              + index
              + " screen="
              + (client.screen == null ? "none" : client.screen.getClass().getName()));
    if (steps.get(index).tick(client)) {
      index++;
      entered = ticks;
      requested = false;
      serverDone.set(false);
      System.out.println("FACTORYCORE_PRESENTATION_STAGE " + index);
    }
  }

  private int waited() {
    return ticks - entered;
  }

  private void step(Step step) {
    steps.add(step);
  }

  /** Runs {@code action} on the server thread once; the step completes when it has run. */
  private void server(Consumer<ServerPlayer> action) {
    step(
        client -> {
          if (!requested) {
            requested = true;
            var server = client.getSingleplayerServer();
            var id = client.player.getUUID();
            server.execute(
                () -> {
                  try {
                    action.accept(server.getPlayerList().getPlayer(id));
                    serverDone.set(true);
                  } catch (RuntimeException error) {
                    failure.set(error);
                  }
                });
          }
          return serverDone.get();
        });
  }

  /** Waits until a server-side condition holds (checked once per client tick). */
  private void serverUntil(Predicate<ServerPlayer> condition) {
    step(
        client -> {
          if (serverDone.get()) return true;
          var server = client.getSingleplayerServer();
          var id = client.player.getUUID();
          server.execute(
              () -> {
                try {
                  if (condition.test(server.getPlayerList().getPlayer(id))) serverDone.set(true);
                } catch (RuntimeException error) {
                  failure.set(error);
                }
              });
          return false;
        });
  }

  private void waitTicks(int count) {
    step(client -> waited() >= count);
  }

  @SuppressWarnings("PMD.CloseResource") // Borrowed window; the client owns it.
  private void capture(Minecraft client, String name) {
    try {
      Files.createDirectories(report.getParent().resolve("screenshots"));
    } catch (IOException error) {
      throw new IllegalStateException("Cannot prepare screenshot directory", error);
    }
    var window = client.getWindow();
    evidence
        .append(name)
        .append(" framebuffer=")
        .append(window.getWidth())
        .append('x')
        .append(window.getHeight())
        .append(" gui=")
        .append(window.getGuiScaledWidth())
        .append('x')
        .append(window.getGuiScaledHeight())
        .append(" scale=")
        .append(window.getGuiScale())
        .append('\n');
    Screenshot.grab(
        report.getParent().toFile(),
        name,
        client.getMainRenderTarget(),
        component -> System.out.println("FACTORYCORE_CLIENT_SCREENSHOT " + component.getString()));
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  private static void setScale(Minecraft client, int scale) {
    client.options.guiScale().set(scale);
    client.resizeDisplay();
  }

  /** Holds the camera on a view once the teleport has arrived; false until then. */
  private static boolean aim(Minecraft client, PresentationScenes.View view) {
    var player = client.player;
    if (player.distanceToSqr(view.x(), view.y(), view.z()) > 1) return false;
    player.getAbilities().flying = true;
    player.setDeltaMovement(Vec3.ZERO);
    player.setYRot(view.yaw());
    player.setXRot(view.pitch());
    player.yRotO = view.yaw();
    player.xRotO = view.pitch();
    return true;
  }

  private void view(PresentationScenes.View view, String capture, int settle) {
    server(player -> PresentationScenes.teleport(player, view));
    step(client -> aim(client, view) && waited() >= settle);
    step(
        client -> {
          aim(client, view);
          capture(client, capture);
          return true;
        });
  }

  // ------------------------------------------------------------------ screen fit

  /**
   * The screen's panel and every visible control lie inside the GUI, controls do not overlap and
   * button labels fit their buttons.
   */
  static String checkFits(Minecraft client, Screen screen) {
    int width = screen.width;
    int height = screen.height;
    var bounds = new StringBuilder();
    if (screen instanceof AbstractContainerScreen<?> container) {
      int left = container.getGuiLeft();
      int top = container.getGuiTop();
      bounds.append(container.getXSize()).append('x').append(container.getYSize());
      check(
          left >= 0
              && top >= 0
              && left + container.getXSize() <= width
              && top + container.getYSize() <= height,
          "Panel "
              + bounds
              + " at "
              + left
              + ","
              + top
              + " clipped in "
              + width
              + "x"
              + height
              + ": "
              + screen.getClass().getSimpleName());
    }
    var widgets =
        screen.children().stream()
            .filter(child -> child instanceof AbstractWidget widget && widget.visible)
            .map(child -> (AbstractWidget) child)
            .toList();
    if (screen instanceof BufferSettingsScreen) {
      for (var widget : widgets)
        if (widget instanceof net.minecraft.client.gui.components.EditBox field
            && (field.getMessage().getString().equals(I18n.get("gui.factorycore.buffer.rate"))
                || field
                    .getMessage()
                    .getString()
                    .equals(I18n.get("gui.factorycore.buffer.target"))))
          check(!field.getValue().isEmpty(), "Buffer numeric field lost its value after resize");
    }
    for (var widget : widgets)
      check(
          widget.getX() >= 0
              && widget.getY() >= 0
              && widget.getRight() <= width
              && widget.getBottom() <= height,
          "Control off screen at "
              + width
              + "x"
              + height
              + ": "
              + widget.getMessage().getString()
              + " "
              + widget.getRectangle());
    for (int first = 0; first < widgets.size(); first++)
      for (int second = first + 1; second < widgets.size(); second++) {
        var a = widgets.get(first);
        var b = widgets.get(second);
        check(
            a.getRight() <= b.getX()
                || b.getRight() <= a.getX()
                || a.getBottom() <= b.getY()
                || b.getBottom() <= a.getY(),
            "Controls overlap: " + a.getMessage().getString() + " / " + b.getMessage().getString());
      }
    for (var widget : widgets)
      if (widget instanceof Button button)
        check(
            client.font.width(button.getMessage()) <= button.getWidth() - 4,
            "Label does not fit its button: " + button.getMessage().getString());
    return screen.getClass().getSimpleName()
        + " panel="
        + (bounds.isEmpty() ? "none" : bounds)
        + " gui="
        + width
        + "x"
        + height
        + " controls="
        + widgets.size();
  }

  /** Visits scales 1–4 with {@code screen} open; captures scales 1 and 4 (all four if asked). */
  private void scales(String name, Class<? extends Screen> type, boolean captureAll) {
    for (int value = 1; value <= 4; value++) {
      int target = value;
      step(
          client -> {
            setScale(client, target);
            scale = target;
            return true;
          });
      step(
          client -> {
            if (!type.isInstance(client.screen) || waited() < 6) return false;
            check(
                client.getWindow().getGuiScale() == scale,
                "GUI scale " + scale + " not available: " + client.getWindow().getGuiScale());
            evidence
                .append("fit ")
                .append(name)
                .append(" scale=")
                .append(scale)
                .append(": ")
                .append(checkFits(client, client.screen))
                .append('\n');
            if (captureAll || scale == 1 || scale == 4)
              capture(client, name + "-scale-" + scale + ".png");
            return true;
          });
    }
    step(
        client -> {
          setScale(client, 2);
          if (client.screen != null) client.screen.onClose();
          if (client.player.containerMenu != client.player.inventoryMenu)
            client.player.closeContainer();
          return true;
        });
    step(client -> client.screen == null && waited() > 4);
  }

  private void use(BlockPos target, Direction face, boolean sneak) {
    step(
        client -> {
          if (client.screen != null || waited() < 4) return false;
          if (sneak) shift(client, true);
          client.gameMode.useItemOn(
              client.player,
              InteractionHand.MAIN_HAND,
              new BlockHitResult(Vec3.atCenterOf(target), face, target, false));
          return true;
        });
  }

  private static void shift(Minecraft client, boolean down) {
    client.player.connection.send(
        new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(
            client.player,
            down
                ? net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action
                    .PRESS_SHIFT_KEY
                : net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action
                    .RELEASE_SHIFT_KEY));
  }

  private static NodeRegistry.Position at(ServerPlayer player, BlockPos position) {
    return new NodeRegistry.Position(
        player.serverLevel().dimension().location().toString(), position.asLong());
  }

  private static boolean valid(ServerPlayer player, BlockPos position) {
    return WorldNetworks.get(player.server).membership(at(player, position)).status()
        == NetworkTopology.Status.VALID;
  }

  /** Installs through the actual client interaction path, then checks consumption and synced UI. */
  private void upgrade(BlockPos target, ThroughputTier tier, boolean buffer) {
    String item = tier == ThroughputTier.ADVANCED ? "throughput_upgrade" : "overdrive_upgrade";
    String name = (buffer ? "buffer-" : "machine-") + item.replace("_upgrade", "");
    int maximum =
        buffer
            ? tier.bufferRate()
            : tier.portRate(dev.izzy.factorycore.core.resource.ResourceKind.ITEM);
    server(
        player -> {
          player.setGameMode(GameType.SURVIVAL);
          player.getInventory().clearContent();
          player.getInventory().selected = 0;
          player.getInventory().setItem(0, stack(item));
        });
    step(client -> client.player.getMainHandItem().is(stack(item).getItem()));
    use(target, Direction.SOUTH, false);
    serverUntil(
        player -> {
          var entity = player.serverLevel().getBlockEntity(target);
          boolean installed =
              buffer
                  ? entity instanceof BufferedInterfaceEntity device && device.tier() == tier
                  : entity instanceof MachineInterfaceEntity machine && machine.tier() == tier;
          return installed && player.getMainHandItem().isEmpty() && valid(player, target);
        });
    step(client -> client.player.getMainHandItem().isEmpty());
    use(target, Direction.SOUTH, false);
    step(
        client -> {
          if (buffer) {
            if (!(client.screen instanceof BufferSettingsScreen screen)
                || screen.getMenu().view().rate() != maximum) return false;
          } else {
            if (!(client.screen instanceof MachineSettingsScreen screen)
                || screen.getMenu().view().maxRate() != maximum
                || screen.getMenu().view().form().rate() != maximum) return false;
            check(
                screen
                    .getMenu()
                    .view()
                    .tier()
                    .equals(tier.name().toLowerCase(java.util.Locale.ROOT)),
                "Wrong installed tier in machine payload");
          }
          evidence
              .append(name)
              .append(": real survival use consumed one; synced rate=")
              .append(maximum)
              .append('\n');
          return true;
        });
    scales(name, buffer ? BufferSettingsScreen.class : MachineSettingsScreen.class, false);
  }

  // ------------------------------------------------------------------ tooltips

  /** Real item tooltips (including the ItemTooltipEvent lines) drawn at the four GUI corners. */
  static final class TooltipBoard extends Screen {
    private final List<ItemStack> stacks;
    private final int corner;

    TooltipBoard(List<ItemStack> stacks, int corner) {
      super(Component.literal("Tooltip board"));
      this.stacks = stacks;
      this.corner = corner;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      super.render(graphics, mouseX, mouseY, partialTick);
      int[][] corners = {{4, 20}, {width - 4, 20}, {4, height - 4}, {width - 4, height - 4}};
      graphics.renderTooltip(font, stacks.get(corner), corners[corner][0], corners[corner][1]);
    }
  }

  private void checkTooltips(Minecraft client) {
    int described = 0;
    for (var id : BuiltInRegistries.ITEM.keySet().stream().sorted().toList()) {
      if (!id.getNamespace().equals(FactoryCoreMod.MOD_ID)) continue;
      var stack = new ItemStack(BuiltInRegistries.ITEM.get(id));
      var lines =
          Screen.getTooltipFromItem(client, stack).stream().map(Component::getString).toList();
      for (var line : lines)
        check(
            !line.contains("tooltip.factorycore")
                && !line.contains("item.factorycore")
                && !line.contains("block.factorycore"),
            "Untranslated tooltip line for " + id + ": " + line);
      String key = ItemDescriptions.descriptionKey(id.getPath());
      if (I18n.exists(key)) {
        check(lines.contains(I18n.get(key)), "Description missing from tooltip of " + id);
        described++;
      }
      String legend = ItemDescriptions.lampLegendKey(id.getPath());
      if (legend != null)
        check(lines.contains(I18n.get(legend)), "Lamp legend missing from tooltip of " + id);
      evidence.append("tooltip ").append(id.getPath()).append(": ").append(lines).append('\n');
    }
    check(described >= 14, "Too few described items: " + described);
  }

  // ------------------------------------------------------------------ script

  @SuppressWarnings("PMD.NcssCount") // One linear, readable scenario script.
  private void buildSteps() {
    step(
        client -> {
          if (client.screen
              instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen onboarding)
            onboarding.onClose();
          if (!(client.screen instanceof TitleScreen)) return false;
          var window = client.getWindow();
          double ratio = (double) window.getWidth() / Math.max(1, window.getScreenWidth());
          window.setWindowed(
              (int) Math.round(MIN_WIDTH / ratio), (int) Math.round(MIN_HEIGHT / ratio));
          client.options.guiScale().set(2);
          client.options.pauseOnLostFocus = false;
          client.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
          client
              .createWorldOpenFlows()
              .createFreshLevel(
                  "factorycore-presentation-verification",
                  new LevelSettings(
                      "Presentation verification",
                      GameType.CREATIVE,
                      false,
                      Difficulty.PEACEFUL,
                      true,
                      new GameRules(),
                      WorldDataConfiguration.DEFAULT),
                  new WorldOptions(0x51A7EL, false, false),
                  registries ->
                      registries
                          .registryOrThrow(Registries.WORLD_PRESET)
                          .getHolderOrThrow(WorldPresets.FLAT)
                          .value()
                          .createWorldDimensions(),
                  client.screen);
          return true;
        });
    step(
        client ->
            client.player != null
                && client.screen == null
                && client.getSingleplayerServer() != null);
    step(
        client -> {
          var window = client.getWindow();
          check(
              window.getWidth() == MIN_WIDTH && window.getHeight() == MIN_HEIGHT,
              "Window framebuffer " + window.getWidth() + "x" + window.getHeight());
          return true;
        });
    server(
        player -> {
          var level = player.serverLevel();
          level.setDayTime(6000);
          level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, player.server);
          level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, player.server);
          player.getInventory().clearContent();
          evidence
              .append("orientations: placed ")
              .append(PresentationScenes.orientations(level))
              .append(" blocks\n");
          evidence.append("states: ").append(PresentationScenes.states(player)).append('\n');
          for (int x : new int[] {50, 52})
            fed.add(new BlockPos(x, 65, PresentationScenes.STATE_Z + 3));
          fed.add(new BlockPos(46, 65, PresentationScenes.STATE_Z + 3));
          PresentationScenes.screens(player);
        });
    step(
        client -> {
          client.options.hideGui = true;
          return true;
        });

    // Orientation gallery from two opposite corners covers every facing's front.
    view(PresentationScenes.ORIENT_NORTH_EAST_UP, "orientation-north-east-up.png", 80);
    view(PresentationScenes.ORIENT_SOUTH_WEST_DOWN, "orientation-south-west-down.png", 60);

    // Status lamps follow real state within the refresh period.
    serverUntil(
        player -> {
          int z = PresentationScenes.STATE_Z;
          var level = player.serverLevel();
          boolean ready =
              lamp(level, 40, z) == DeviceLamp.ACTIVE
                  && lamp(level, 42, z) == DeviceLamp.FAULT
                  && lamp(level, 45, z) == DeviceLamp.FAULT
                  && lamp(level, 47, z) == DeviceLamp.ACTIVE
                  && lamp(level, 40, z + 3) == DeviceLamp.OFF
                  && lamp(level, 41, z + 3) == DeviceLamp.ACTIVE
                  && lamp(level, 42, z + 3) == DeviceLamp.PAUSED
                  && lamp(level, 43, z + 3) == DeviceLamp.FAULT
                  && lamp(level, 46, z + 3) == DeviceLamp.ACTIVE
                  && lamp(level, 48, z + 3) == DeviceLamp.FAULT
                  && lamp(level, 50, z + 3) == DeviceLamp.ACTIVE
                  && lamp(level, 52, z + 3) == DeviceLamp.ACTIVE
                  && lamp(level, 54, z + 3) == DeviceLamp.FAULT
                  && lamp(level, 58, z + 3) == DeviceLamp.OFF;
          if (!ready && level.getGameTime() % 100 == 0) {
            for (var position :
                List.of(
                    new BlockPos(40, 65, z), new BlockPos(42, 65, z),
                    new BlockPos(45, 65, z), new BlockPos(47, 65, z),
                    new BlockPos(40, 65, z + 3), new BlockPos(41, 65, z + 3),
                    new BlockPos(42, 65, z + 3), new BlockPos(43, 65, z + 3),
                    new BlockPos(46, 65, z + 3), new BlockPos(48, 65, z + 3),
                    new BlockPos(50, 65, z + 3), new BlockPos(52, 65, z + 3),
                    new BlockPos(54, 65, z + 3), new BlockPos(58, 65, z + 3))) {
              System.out.println(
                  "FACTORYCORE_LAMP_WAIT "
                      + position
                      + " lamp="
                      + level.getBlockState(position).getValue(DeviceLamp.PROPERTY)
                      + " membership="
                      + WorldNetworks.get(player.server).membership(at(player, position)).status());
            }
          }
          return ready;
        });
    step(
        client -> {
          evidence.append(
              "lamps: root active, joined roots fault, rootless gateway fault, rooted gateway active;"
                  + " programs off/active/paused/fault; access points active/fault; links"
                  + " active/active/fault/off\n");
          return true;
        });
    view(PresentationScenes.STATES, "states-day.png", 40);
    view(PresentationScenes.STATES_CLOSE, "states-day-close.png", 20);
    server(player -> player.serverLevel().setDayTime(18000));
    view(PresentationScenes.STATES, "states-night.png", 40);
    view(PresentationScenes.STATES_CLOSE, "states-night-close.png", 20);
    server(player -> player.serverLevel().setDayTime(6000));

    // Held items: first person with main/off hand pairs, then third person front.
    String[][] pairs = {
      {"network_controller", "starter_item_cell"},
      {"network_cable", "wireless_terminal"},
      {"factory_guide", "processing_pattern"},
      {"gateway", "infinite_item_cell"}
    };
    step(
        client -> {
          client.options.hideGui = false;
          return true;
        });
    for (int i = 0; i < pairs.length; i++) {
      var pair = pairs[i];
      boolean third = i == pairs.length - 1;
      server(
          player -> {
            player.getInventory().clearContent();
            player.getInventory().selected = 0;
            player.getInventory().setItem(0, stack(pair[0]));
            player.setItemInHand(InteractionHand.OFF_HAND, stack(pair[1]));
            PresentationScenes.teleport(player, PresentationScenes.HELD);
          });
      step(
          client -> {
            client.options.setCameraType(
                third ? CameraType.THIRD_PERSON_FRONT : CameraType.FIRST_PERSON);
            return aim(client, PresentationScenes.HELD)
                && client.player.getMainHandItem().is(stack(pair[0]).getItem())
                && waited() >= 30;
          });
      String name = (third ? "held-third-person-" : "held-first-person-") + pair[0] + ".png";
      step(
          client -> {
            capture(client, name);
            return true;
          });
    }
    step(
        client -> {
          client.options.setCameraType(CameraType.FIRST_PERSON);
          return true;
        });
    server(player -> player.getInventory().clearContent());

    // Tooltips: every item's real lines are translated; corners exercise edge positioning.
    for (int targetScale : new int[] {4, 2}) {
      for (int corner = 0; corner < 4; corner++) {
        int targetCorner = corner;
        step(
            client -> {
              checkTooltips(client);
              client.setScreen(
                  new TooltipBoard(
                      List.of(
                          stack("network_controller"), stack("program_controller"),
                          stack("starter_energy_bank"), stack("wireless_link_endpoint")),
                      targetCorner));
              setScale(client, targetScale);
              return true;
            });
        step(
            client -> {
              if (!(client.screen instanceof TooltipBoard) || waited() < 6) return false;
              String edge = targetCorner == 0 ? "edges" : "corner-" + targetCorner;
              capture(client, "tooltips-" + edge + "-scale-" + targetScale + ".png");
              return true;
            });
      }
    }
    step(
        client -> {
          client.setScreen(null);
          return true;
        });

    // Screens at scales 1–4 in the smallest window.
    var base = PresentationScenes.SCREENS;
    server(
        player -> {
          player.getAbilities().flying = false;
          player.onUpdateAbilities();
          player.teleportTo(
              player.serverLevel(), base.getX() + 4.5, 65, base.getZ() + 2.5, 180, 15);
        });
    serverUntil(player -> valid(player, base.east(2)) && valid(player, base.east(8)));
    use(base.east(2), Direction.SOUTH, false);
    scales("terminal", TerminalScreen.class, true);
    use(base.east(3), Direction.SOUTH, false);
    scales("buffer", BufferSettingsScreen.class, false);
    use(base.east(4), Direction.SOUTH, false);
    scales("machine", MachineSettingsScreen.class, false);
    upgrade(base.east(4), ThroughputTier.ADVANCED, false);
    upgrade(base.east(4), ThroughputTier.ENDGAME, false);
    upgrade(base.east(3), ThroughputTier.ADVANCED, true);
    upgrade(base.east(3), ThroughputTier.ENDGAME, true);
    use(base.east(5), Direction.SOUTH, false);
    scales("program", ProgramScreen.class, false);
    use(base.east(7), Direction.SOUTH, true);
    step(
        client -> {
          if (!(client.screen instanceof GatewayPolicyScreen)) return false;
          shift(client, false);
          return true;
        });
    scales("gateway-policy", GatewayPolicyScreen.class, false);
    // Network Access (ADR 0055): a second principal row so grant toggles are on screen.
    server(
        player ->
            check(
                WorldNetworks.get(player.getServer())
                        .setGrants(
                            new dev.izzy.factorycore.core.network.NetworkPermissions.Actor(
                                player.getUUID(), false),
                            at(player, base),
                            java.util.UUID.nameUUIDFromBytes(
                                "presentation-guest"
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                            java.util.EnumSet.of(
                                dev.izzy.factorycore.core.network.NetworkPermissions.Permission
                                    .VIEW,
                                dev.izzy.factorycore.core.network.NetworkPermissions.Permission
                                    .DEPOSIT))
                    == dev.izzy.factorycore.core.network.NetworkPermissions.Change.OK,
                "Owner grants a second principal before opening Network Access"));
    use(base, Direction.SOUTH, true);
    step(
        client -> {
          if (!(client.screen instanceof NetworkAccessScreen)) return false;
          shift(client, false);
          return true;
        });
    scales("access", NetworkAccessScreen.class, false);
    // Transfer Recovery opens through its real player command.
    server(
        player ->
            player
                .getServer()
                .getCommands()
                .performPrefixedCommand(
                    player.createCommandSourceStack(), "factorycore recovery screen"));
    scales("recovery", RecoveryScreen.class, false);
    server(player -> player.getInventory().setItem(0, new ItemStack(GuideContent.GUIDE.get())));
    step(
        client -> {
          if (!client.player.getInventory().getItem(0).is(GuideContent.GUIDE.get())) return false;
          client.player.getInventory().selected = 0;
          client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
          return true;
        });
    step(client -> client.screen instanceof BookViewScreen);
    for (int page = 1; page < GuideContent.PAGES; page++) {
      step(
          client -> {
            check(client.screen instanceof BookViewScreen, "Guide closed while paging");
            client.screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN, 0, 0);
            return true;
          });
    }
    scales("guide-throughput", BookViewScreen.class, false);
    // Reopen at its first page to retain the original guide captures.
    step(
        client -> {
          client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
          return true;
        });
    scales("guide", BookViewScreen.class, false);
    for (int corner = 0; corner < 2; corner++) {
      int targetCorner = corner;
      step(
          client -> {
            client.setScreen(
                new TooltipBoard(
                    List.of(stack("throughput_upgrade"), stack("overdrive_upgrade")),
                    targetCorner));
            setScale(client, 4);
            return true;
          });
      step(
          client -> {
            if (waited() < 6) return false;
            capture(client, "upgrade-tooltip-" + targetCorner + "-scale-4.png");
            return true;
          });
    }
    step(
        client -> {
          writeReport(client);
          return true;
        });
  }

  @SuppressWarnings("PMD.CloseResource")
  private static DeviceLamp lamp(net.minecraft.server.level.ServerLevel level, int x, int z) {
    return level.getBlockState(new BlockPos(x, 65, z)).getValue(DeviceLamp.PROPERTY);
  }

  private static ItemStack stack(String path) {
    return new ItemStack(
        BuiltInRegistries.ITEM.get(
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                FactoryCoreMod.MOD_ID, path)));
  }

  private void writeReport(Minecraft client) {
    try {
      Files.writeString(
          report,
          "PASS\n"
              + evidence
              + "Orientation: every FACING device in all six facings, horizontal devices in four;"
              + " two opposite corner views show every front.\n"
              + "Screens: terminal, buffer settings, machine settings, program editor, gateway"
              + " policy, network access, transfer recovery and guide at GUI scales 1-4 in a "
              + MIN_WIDTH
              + "x"
              + MIN_HEIGHT
              + " framebuffer (427x240 at scale 4): panels and controls on screen, no overlap,"
              + " labels fit.\n");
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
    client.level.disconnect();
    client.disconnect();
    client.stop();
  }
}
