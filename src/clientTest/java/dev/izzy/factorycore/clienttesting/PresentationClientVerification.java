package dev.izzy.factorycore.clienttesting;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.client.BufferSettingsScreen;
import dev.izzy.factorycore.platform.client.ConnectorSettingsScreen;
import dev.izzy.factorycore.platform.client.DriveScreen;
import dev.izzy.factorycore.platform.client.GatewayPolicyScreen;
import dev.izzy.factorycore.platform.client.ItemDescriptions;
import dev.izzy.factorycore.platform.client.MachineSettingsScreen;
import dev.izzy.factorycore.platform.client.NetworkAccessScreen;
import dev.izzy.factorycore.platform.client.ProgramScreen;
import dev.izzy.factorycore.platform.client.RecoveryScreen;
import dev.izzy.factorycore.platform.client.TerminalScreen;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.guide.GuideContent;
import dev.izzy.factorycore.platform.network.DeviceLamp;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.BufferedInterfaceEntity;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.MachineInterfaceEntity;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.platform.storage.TerminalJobPackets;
import dev.izzy.factorycore.platform.storage.TerminalPackets;
import dev.izzy.factorycore.platform.wireless.AccessPointEntity;
import dev.izzy.factorycore.platform.wireless.LinkModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import org.lwjgl.glfw.GLFW;

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
  private final Captures captures = new Captures("presentation", report, evidence);
  private final List<Step> steps = new ArrayList<>();
  private final List<BlockPos> fed = new ArrayList<>();
  private final List<String> chat = new java.util.concurrent.CopyOnWriteArrayList<>();
  private int index;
  private int ticks;
  private int entered;
  private int scale;
  private boolean requested;

  public PresentationClientVerification() {
    buildSteps();
    NeoForge.EVENT_BUS.addListener(this::tick);
    NeoForge.EVENT_BUS.addListener(this::feed);
    NeoForge.EVENT_BUS.addListener(
        (net.neoforged.neoforge.client.event.ClientChatReceivedEvent.System event) ->
            chat.add(event.getMessage().getString()));
  }

  // ------------------------------------------------------------------ plumbing

  /** Keeps gallery wireless devices powered: 1,000 FE per tick each, their real input limit. */
  private void feed(ServerTickEvent.Post event) {
    var level = event.getServer().overworld();
    for (var position : List.copyOf(fed)) {
      var entity = level.getBlockEntity(position);
      if (entity instanceof NetworkNodeEntity node && node.link() != null)
        node.link().energyStorage().receiveEnergy(1_000, false);
      if (entity instanceof AccessPointEntity point)
        point.energyStorage().receiveEnergy(1_000, false);
    }
  }

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
              + (client.screen == null ? "none" : client.screen.getClass().getName())
              + (client.screen instanceof TerminalScreen screen
                  ? " jobs offset="
                      + screen.getMenu().jobView().offset()
                      + " total="
                      + screen.getMenu().jobView().total()
                      + " status="
                      + screen.getMenu().jobView().status()
                      + " rows="
                      + screen.getMenu().jobView().craftables()
                  : ""));
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
          captures.grab(client, capture);
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
        if (widget instanceof EditBox field
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
              captures.grab(client, name + "-scale-" + scale + ".png");
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

  private static TerminalScreen terminal(Minecraft client) {
    return (TerminalScreen) client.screen;
  }

  private static boolean useGhost(Minecraft client, String id) {
    var screen = terminal(client);
    var view = screen.getMenu().view();
    if (view.busy() || waitedForRows(screen)) return false;
    for (int row = 0; row < Math.min(8, view.rows().size()); row++) {
      if (!view.rows().get(row).icon().equals(id)) continue;
      screen.keyPressed(GLFW.GLFW_KEY_1 + row, 0, 0);
      return press(client, "Use selected");
    }
    return false;
  }

  private static boolean waitedForRows(TerminalScreen screen) {
    return screen.getMenu().view().size() != screen.columns() * screen.rows();
  }

  private void panelCaptures(String name) {
    for (int target : new int[] {1, 4}) {
      step(
          client -> {
            setScale(client, target);
            return true;
          });
      step(
          client -> {
            if (waited() < 6 || waitedForRows(terminal(client))) return false;
            evidence
                .append("fit ")
                .append(name)
                .append(" scale=")
                .append(target)
                .append(": ")
                .append(checkFits(client, client.screen))
                .append('\n');
            check(
                terminal(client).columns() >= 9 && terminal(client).rows() >= 4,
                "Expanded crafting panel keeps a usable resource browser");
            captures.grab(client, name + "-scale-" + target + ".png");
            return true;
          });
    }
  }

  private void countGhost(int cell, int amount) {
    for (int value = 2; value <= amount; value++) {
      int previous = value - 1;
      step(
          client ->
              terminal(client).getMenu().gridView().counts().get(cell) == previous
                  && waited() > 2
                  && press(client, "+"));
    }
    step(
        client ->
            terminal(client).getMenu().gridView().counts().get(cell) == amount && waited() > 4);
  }

  /** Actual panel controls, crafts, both encoders and exact fluid units at scales 1 and 4. */
  private void craftingTour(BlockPos base) {
    server(
        player -> {
          var drive = (StorageDriveEntity) player.serverLevel().getBlockEntity(base.east());
          check(
              drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_FLUID))) == 2,
              "Fluid cell installed");
          player.getInventory().clearContent();
          player.getInventory().setItem(22, new ItemStack(CraftingContent.BLANK_PATTERN.get(), 2));
        });
    serverUntil(
        player -> {
          var drive = (StorageDriveEntity) player.serverLevel().getBlockEntity(base.east());
          var key =
              new PlatformResourceCodec(player.registryAccess())
                  .fluidKey(new FluidStack(Fluids.WATER, 1));
          return valid(player, base.east(2))
              && DeviceSavedData.get(player.server)
                  .registry()
                  .stock(CellHandleData.read(drive.cell(2)), key)
                  .loaded();
        });
    server(
        player -> {
          var drive = (StorageDriveEntity) player.serverLevel().getBlockEntity(base.east());
          var saved = DeviceSavedData.get(player.server);
          var codec = new PlatformResourceCodec(player.registryAccess());
          check(
              saved
                      .registry()
                      .insert(
                          CellHandleData.read(drive.cell(1)),
                          codec.itemKey(new ItemStack(Items.OAK_LOG)),
                          1)
                      .amount()
                  == 1,
              "Craft log admitted");
          check(
              saved
                      .registry()
                      .insert(
                          CellHandleData.read(drive.cell(2)),
                          codec.fluidKey(new FluidStack(Fluids.WATER, 1)),
                          4000)
                      .amount()
                  == 4000,
              "Processing water admitted");
        });
    use(base.east(2), Direction.SOUTH, false);
    step(
        client ->
            client.screen instanceof TerminalScreen && waited() > 4 && press(client, "Crafting »"));
    step(
        client ->
            terminal(client).craftingOpen()
                && waited() > 6
                && useGhost(client, "minecraft:oak_log"));
    step(
        client ->
            terminal(client).getMenu().gridView().result().equals("minecraft:oak_planks")
                && waited() > 4);
    panelCaptures("crafting-table");
    step(client -> press(client, "Encode"));
    step(
        client -> terminal(client).getMenu().gridView().status().equals("encoded") && waited() > 4);
    server(
        player -> {
          var patterns =
              player.getInventory().items.stream()
                  .filter(stack -> stack.has(CraftingContent.PATTERN.get()))
                  .toList();
          check(
              patterns.size() == 1
                  && patterns.getFirst().get(CraftingContent.PATTERN.get()).output().getCount()
                      == 4,
              "Table Encode creates one exact four-plank pattern");
          check(
              player.getInventory().countItem(CraftingContent.BLANK_PATTERN.get()) == 1,
              "Table Encode consumes one blank");
        });
    step(client -> press(client, "Craft"));
    step(
        client -> terminal(client).getMenu().gridView().status().equals("crafted") && waited() > 4);
    server(
        player ->
            check(
                player.getInventory().countItem(Items.OAK_PLANKS) == 4,
                "Craft returns four real planks"));
    step(client -> press(client, "Clear"));
    step(
        client ->
            terminal(client).getMenu().gridView().cells().stream().allMatch(String::isEmpty)
                && waited() > 4
                && press(client, "Inputs"));
    step(
        client ->
            terminal(client).getMenu().gridView().processing()
                && waited() > 4
                && press(client, "Fluids"));
    step(
        client ->
            terminal(client).getMenu().view().kind() == 1
                && waited() > 6
                && useGhost(client, "minecraft:water"));
    step(client -> terminal(client).getMenu().gridView().fluid(0) && waited() > 4);
    countGhost(0, 4);
    step(client -> press(client, "−"));
    step(
        client ->
            terminal(client).getMenu().gridView().amount(0) == 750
                && waited() > 4
                && press(client, "+"));
    step(client -> terminal(client).getMenu().gridView().amount(0) == 1000 && waited() > 4);
    panelCaptures("crafting-inputs");
    step(client -> press(client, "Outputs"));
    step(client -> waited() > 4 && useGhost(client, "minecraft:water"));
    step(client -> terminal(client).getMenu().gridView().fluid(9) && waited() > 4);
    countGhost(9, 2);
    step(client -> press(client, "Items"));
    step(client -> terminal(client).getMenu().view().kind() == 0 && waited() > 6);
    step(
        client -> {
          var screen = terminal(client);
          screen.mouseClicked(
              screen.getGuiLeft() + screen.gridX() + 27,
              screen.getGuiTop() + screen.gridY() + 9,
              0);
          return useGhost(client, "minecraft:stone");
        });
    step(
        client ->
            terminal(client).getMenu().gridView().cells().get(10).equals("minecraft:stone")
                && waited() > 4);
    countGhost(10, 2);
    panelCaptures("crafting-outputs");
    step(client -> press(client, "« Crafting"));
    step(
        client -> {
          if (waited() < 6) return false;
          check(!terminal(client).craftingOpen(), "Panel stays collapsed with populated ghosts");
          check(
              terminal(client).getMenu().gridView().amount(0) == 1000
                  && terminal(client).getMenu().gridView().amount(9) == 500,
              "Collapse preserves all ghosts");
          return press(client, "Crafting »");
        });
    step(client -> terminal(client).craftingOpen() && waited() > 6 && press(client, "Encode"));
    step(
        client -> terminal(client).getMenu().gridView().status().equals("encoded") && waited() > 4);
    server(
        player -> {
          var pattern =
              player.getInventory().items.stream()
                  .filter(stack -> stack.has(CraftingContent.PROCESSING.get()))
                  .findFirst()
                  .orElseThrow()
                  .get(CraftingContent.PROCESSING.get());
          check(
              pattern.inputs().isEmpty()
                  && pattern.fluidInputs().size() == 1
                  && pattern.fluidInputs().getFirst().getAmount() == 1000
                  && pattern.outputs().size() == 1
                  && pattern.outputs().getFirst().is(Items.STONE)
                  && pattern.outputs().getFirst().getCount() == 2
                  && pattern.fluidOutputs().size() == 1
                  && pattern.fluidOutputs().getFirst().getAmount() == 500,
              "Processing Encode preserves exact item and mB declarations");
          check(
              player.getInventory().countItem(CraftingContent.BLANK_PATTERN.get()) == 0,
              "Both encoders each consume exactly one blank");
          var drive = (StorageDriveEntity) player.serverLevel().getBlockEntity(base.east());
          var codec = new PlatformResourceCodec(player.registryAccess());
          var registry = DeviceSavedData.get(player.server).registry();
          check(
              registry
                          .stock(
                              CellHandleData.read(drive.cell(2)),
                              codec.fluidKey(new FluidStack(Fluids.WATER, 1)))
                          .total()
                      == 4000
                  && registry
                          .stock(
                              CellHandleData.read(drive.cell(1)),
                              codec.itemKey(new ItemStack(Items.STONE)))
                          .total()
                      == 1,
              "Ghost edits and encoding consume no declared resources");
          player.getInventory().clearContent();
        });
    step(client -> press(client, "Table"));
    step(
        client -> {
          if (waited() < 6 || terminal(client).getMenu().gridView().processing()) return false;
          check(
              terminal(client).getMenu().gridView().fluidMask() == 0,
              "Table drops fluid ghosts under the existing contract");
          evidence.append(
              "crafting panel: real table craft and both encoders; 1000 mB water input -> 2 Stone + 500 mB water output, no declared stock consumed; collapse/reopen and scale changes retain ghosts.\n");
          client.player.closeContainer();
          setScale(client, 2);
          return true;
        });
    step(client -> client.screen == null && waited() > 4);
  }

  /**
   * Terminal Jobs mode through real clicks: a pattern's craftable row, ×8 preview, Start, the
   * finished delivery, then a ×64 shortage preview at scale 4 with fit checks.
   */
  /**
   * Each sort mode and direction over the count-rendering items, then water and lava drawn with
   * their still textures. Leaves the per-player sort at Name ascending for later tours.
   */
  private void sortAndFluidTour(BlockPos base) {
    step(
        client -> {
          client.setScreen(null);
          return true;
        });
    server(
        player -> {
          var drive = (StorageDriveEntity) player.serverLevel().getBlockEntity(base.east());
          var codec = new PlatformResourceCodec(player.registryAccess());
          var registry = DeviceSavedData.get(player.server).registry();
          var fluids = CellHandleData.read(drive.cell(2));
          check(
              registry.insert(fluids, codec.fluidKey(new FluidStack(Fluids.LAVA, 1)), 2000).amount()
                  == 2000,
              "Lava admitted");
          check(
              registry
                      .insert(fluids, codec.fluidKey(new FluidStack(Fluids.WATER, 1)), 1000)
                      .amount()
                  == 1000,
              "Water admitted");
        });
    use(base.east(2), Direction.SOUTH, false);
    step(
        client -> {
          if (!(client.screen instanceof TerminalScreen) || waited() < 6) return false;
          setScale(client, 4);
          return true;
        });
    String[][] orders = {{"Qty", "↓"}, {"Mod", "↓"}, {"Mod", "↑"}, {"Name", "↑"}, {"Name", "↓"}};
    for (var order : orders) {
      String mode = order[0], arrow = order[1];
      step(
          client -> {
            var screen = terminal(client);
            var view = screen.getMenu().view();
            if (view.busy() || waited() < 4) return false;
            boolean modeShown = hasButton(client, mode), arrowShown = hasButton(client, arrow);
            if (modeShown && arrowShown) return !view.rows().isEmpty();
            if (!modeShown) press(client, labelOfSort(client));
            else press(client, arrow.equals("↓") ? "↑" : "↓");
            return false;
          });
      waitTicks(4);
      step(
          client -> {
            captures.grab(
                client,
                "terminal-sort-"
                    + mode.toLowerCase(java.util.Locale.ROOT)
                    + (arrow.equals("↑") ? "-reversed" : "")
                    + "-scale-4.png");
            evidence.append(
                "terminal sort "
                    + mode
                    + " "
                    + arrow
                    + ": "
                    + terminal(client).getMenu().view().rows().stream()
                        .map(row -> row.icon())
                        .toList()
                    + "\n");
            return true;
          });
    }
    step(client -> press(client, "Fluids"));
    for (int value : new int[] {4, 1}) {
      step(
          client -> {
            setScale(client, value);
            return true;
          });
      step(
          client -> {
            var view = terminal(client).getMenu().view();
            if (view.busy() || waited() < 8 || view.rows().size() < 2) return false;
            captures.grab(client, "terminal-fluids-scale-" + value + ".png");
            return true;
          });
    }
    step(
        client -> {
          client.setScreen(null);
          return true;
        });
  }

  private boolean toastCaptured;
  private int toastSeen = -1;

  /** A job notice for {@code line}: a toast while a screen is open, otherwise a chat line. */
  private boolean finished(Minecraft client, String line) {
    // A toast lasts five seconds, so remember each notice once seen.
    if (client
            .getToasts()
            .getToast(
                dev.izzy.factorycore.platform.client.JobToast.class,
                "Crafting job finished " + line)
        != null) seenNotices.add(line);
    return seenNotices.contains(line) || chat.contains("Crafting job finished: " + line);
  }

  private final java.util.Set<String> seenNotices = new java.util.HashSet<>();

  /**
   * B1: Machine Presets ghost slots through real input. Three IDs are dropped through the screen's
   * recipe-viewer entry point (what a JEI drag calls); real clicks then cycle the planks slot Item
   * → Exact → Tag and the diamond slot to Exact. The draft is discarded afterwards.
   */
  private void filterTour(BlockPos base) {
    use(base.east(4), Direction.SOUTH, false);
    step(
        client -> {
          if (!(client.screen instanceof MachineSettingsScreen) || waited() < 4) return false;
          setScale(client, 4);
          return press(client, "Presets");
        });
    String[] ids = {"minecraft:iron_ingot", "minecraft:oak_planks", "minecraft:diamond"};
    for (int index = 0; index < ids.length; index++) {
      int count = index + 1;
      String id = ids[index];
      step(
          client -> {
            var screen = (MachineSettingsScreen) client.screen;
            int rules = screen.getMenu().presetView().rules().size();
            if (rules >= count) return true;
            if (waited() % 10 == 5)
              screen.addFilterId(dev.izzy.factorycore.core.resource.ResourceKind.ITEM, id);
            return false;
          });
    }
    int[][] clicks = {{1, 1}, {1, 2}, {2, 1}};
    for (var click : clicks) {
      int slot = click[0], type = click[1];
      step(
          client -> {
            var screen = (MachineSettingsScreen) client.screen;
            var rules = screen.getMenu().presetView().rules();
            if (rules.get(slot).type() == type) return true;
            if (waited() % 10 == 5) {
              var area = screen.filterArea();
              screen.mouseClicked(area.getX() + 18 * slot + 9, area.getY() + 9, 0);
            }
            return false;
          });
    }
    step(
        client -> {
          if (waited() < 6) return false;
          evidence
              .append("machine preset ghost slots: ")
              .append(((MachineSettingsScreen) client.screen).getMenu().presetView().rules())
              .append('\n');
          captures.grab(client, "machine-presets-filter-scale-4.png");
          setScale(client, 1);
          return true;
        });
    step(
        client -> {
          if (waited() < 6) return false;
          captures.grab(client, "machine-presets-filter-scale-1.png");
          setScale(client, 2);
          client.screen.onClose();
          return true;
        });
    step(client -> client.screen == null && waited() > 4);
  }

  private static boolean hasButton(Minecraft client, String label) {
    for (var child : client.screen.children())
      if (child instanceof Button button
          && button.visible
          && button.getMessage().getString().equals(label)) return true;
    return false;
  }

  /** The sort button's current label: whichever of the three mode names is shown. */
  private static String labelOfSort(Minecraft client) {
    for (var label : new String[] {"Name", "Qty", "Mod"})
      if (hasButton(client, label)) return label;
    return "";
  }

  private void jobsTour(BlockPos base) {
    server(PresentationScenes::jobs);
    serverUntil(player -> valid(player, PresentationScenes.JOB_UNIT) && planks(player) == 0);
    server(PresentationScenes::stockJobs);
    use(base.east(2), Direction.SOUTH, false);
    step(
        client -> client.screen instanceof TerminalScreen && waited() > 4 && press(client, "Jobs"));
    step(client -> !jobView(client).craftables().isEmpty() && press(client, "×8"));
    step(
        client -> {
          var view = jobView(client);
          if (view.preview().icon().isEmpty() || waited() < 6) return false;
          check(
              view.previewReason().isEmpty(),
              "Eight planks are craftable: "
                  + view.previewReason()
                  + " "
                  + view.missing()
                  + " "
                  + view.preview());
          captures.grab(client, "jobs-preview-scale-2.png");
          return press(client, "Start");
        });
    step(
        client -> {
          if (waited() < 6) return false;
          captures.grab(client, "jobs-started-scale-2.png");
          return true;
        });
    serverUntil(
        player ->
            DeviceSavedData.get(player.server).craftingJobs().all().isEmpty()
                && planks(player) == 8);
    // A1: type 37 into the amount box, preview with Enter and start it.
    server(PresentationScenes::stockJobs);
    step(
        client -> {
          // A5: with the terminal open the notice is a toast, not a chat line.
          if (!finished(client, "8 × Oak Planks")) return false;
          // Toasts slide in over about half a second; capture it fully shown.
          if (toastSeen < 0) toastSeen = waited();
          if (!toastCaptured && waited() - toastSeen < 20) return false;
          if (!toastCaptured) {
            toastCaptured = true;
            captures.grab(client, "jobs-finished-toast-scale-2.png");
          }
          if (waited() < 10 || !jobView(client).jobs().isEmpty()) return false;
          var screen = terminal(client);
          for (var child : screen.children())
            if (child instanceof net.minecraft.client.gui.components.EditBox box
                && box.visible
                && box.getMessage().getString().equals("Qty")) {
              screen.setFocused(box);
              box.setFocused(true);
              box.setValue("");
              for (char c : "37".toCharArray()) screen.charTyped(c, 0);
              return screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
            }
          throw new IllegalStateException("Jobs amount box shown");
        });
    step(
        client -> {
          var view = jobView(client);
          if (!view.preview().amount().equals("37") || waited() < 6) return false;
          check(view.previewReason().isEmpty(), "37 planks are craftable: " + view.missing());
          captures.grab(client, "jobs-typed-37-scale-2.png");
          return press(client, "Start");
        });
    step(
        client -> {
          var jobs = jobView(client).jobs();
          if (jobs.isEmpty() || waited() < 4) return false;
          check(jobs.getFirst().output().amount().equals("37"), "Started job is for 37: " + jobs);
          captures.grab(client, "jobs-typed-37-started-scale-2.png");
          // A4: open the running job's steps with a real click on its row.
          var screen = terminal(client);
          return screen.mouseClicked(
              screen.getGuiLeft() + TerminalScreen.JOBS_X + 20, screen.getGuiTop() + 50, 0);
        });
    step(
        client -> {
          var detail = jobView(client).detail();
          if (detail.steps().isEmpty() || waited() < 2) return false;
          evidence.append("jobs monitor running: ").append(detail).append('\n');
          captures.grab(client, "jobs-monitor-running-scale-2.png");
          return press(client, "Back");
        });
    step(
        client ->
            finished(client, "37 × Oak Planks")
                && waited() > 10
                && jobView(client).jobs().isEmpty());
    server(PresentationScenes::drainLogs);
    step(client -> waited() > 4 && press(client, "×64"));
    step(
        client -> {
          var view = jobView(client);
          if (view.missing().isEmpty() || waited() < 4) return false;
          check(!view.previewReason().isEmpty(), "A 64-plank plan reports its shortage");
          setScale(client, 4);
          return true;
        });
    step(
        client -> {
          if (waited() < 6) return false;
          evidence
              .append("fit jobs scale=4: ")
              .append(checkFits(client, client.screen))
              .append('\n');
          captures.grab(client, "jobs-missing-scale-4.png");
          // Keep 64 planks with only 6 logs: the rule waits and the row must show it is blocked.
          return press(client, "Keep");
        });
    step(
        client -> {
          var rows = jobView(client).craftables();
          if (rows.isEmpty() || !rows.getFirst().note().equals("missing") || waited() < 6)
            return false;
          captures.grab(client, "jobs-keep-waiting-scale-4.png");
          press(client, "Keep");
          return true;
        });
    // P4: 23 craftables scroll through the eight-wide strip with the real wheel handler. New
    // Crafters rebuild the network, which closes an open terminal, so reopen it afterwards.
    step(
        client -> {
          if (waited() < 4) return false;
          client.screen.onClose();
          return true;
        });
    step(client -> client.screen == null && waited() > 4);
    server(PresentationScenes::manyJobs);
    serverUntil(
        player ->
            valid(player, PresentationScenes.EXTRA_UNITS[0])
                && valid(player, PresentationScenes.EXTRA_UNITS[1])
                && valid(player, base.east(2)));
    use(base.east(2), Direction.SOUTH, false);
    step(
        client -> client.screen instanceof TerminalScreen && waited() > 4 && press(client, "Jobs"));
    step(client -> jobView(client).total() >= 20 && waited() > 6);
    step(
        client -> {
          captures.grab(client, "jobs-scroll-top-scale-4.png");
          var screen = terminal(client);
          return screen.mouseScrolled(screen.getGuiLeft() + 60, screen.getGuiTop() + 60, 0, -1);
        });
    step(
        client -> {
          var view = jobView(client);
          if (view.offset() == 0 || waited() < 4) return false;
          check(view.offset() == 1, "One wheel notch scrolls one craftable: " + view.offset());
          return true;
        });
    step(
        client -> {
          // One notch every few ticks, as a person scrolls, to the last full window.
          var view = jobView(client);
          if (view.offset() == view.total() - TerminalJobPackets.ROWS) return true;
          var screen = terminal(client);
          if (waited() % 4 == 3)
            screen.mouseScrolled(screen.getGuiLeft() + 60, screen.getGuiTop() + 60, 0, -1);
          return false;
        });
    step(
        client -> {
          var view = jobView(client);
          if (waited() < 6) return false;
          evidence
              .append("jobs scroll: total=")
              .append(view.total())
              .append(" last window offset=")
              .append(view.offset())
              .append(' ')
              .append(view.craftables().stream().map(row -> row.icon()).toList())
              .append('\n');
          captures.grab(client, "jobs-scrolled-end-scale-4.png");
          setScale(client, 1);
          return true;
        });
    step(
        client -> {
          if (waited() < 6) return false;
          captures.grab(client, "jobs-scrolled-end-scale-1.png");
          setScale(client, 2);
          press(client, "Items");
          client.screen.onClose();
          return true;
        });
    step(client -> client.screen == null && waited() > 4);
    server(PresentationScenes::fewJobs);
    serverUntil(
        player -> valid(player, base.east(2)) && valid(player, PresentationScenes.JOB_UNIT));
    planTour(base);
  }

  /**
   * A2: the Plan panel for a two-level plan with a shortage (200 sticks from 48 stored planks and
   * no logs), a coal/charcoal loop, and a 91-resource plan past the 64-row bound.
   */
  private void planTour(BlockPos base) {
    server(PresentationScenes::planJobs);
    serverUntil(
        player -> valid(player, PresentationScenes.EXTRA_UNITS[0]) && valid(player, base.east(2)));
    serverUntil(
        player -> {
          var drive = (StorageDriveEntity) player.serverLevel().getBlockEntity(base.east());
          return DeviceSavedData.get(player.server)
              .registry()
              .stock(
                  CellHandleData.read(drive.cell(0)),
                  new PlatformResourceCodec(player.registryAccess())
                      .itemKey(new ItemStack(Items.RAW_IRON)))
              .loaded();
        });
    server(PresentationScenes::stockIron);
    use(base.east(2), Direction.SOUTH, false);
    // A3: Show craftable lists absent craftables (+) in the Items grid; clicking one requests it.
    step(
        client -> {
          if (!(client.screen instanceof TerminalScreen) || waited() < 6) return false;
          setScale(client, 4);
          return press(client, "⚒");
        });
    step(
        client -> {
          var view = terminal(client).getMenu().view();
          if (!view.craftables()
              || view.busy()
              || view.rows().stream().noneMatch(row -> row.craftable() && row.total().equals("0"))
              || waited() < 8) return false;
          evidence
              .append("items craftable: ")
              .append(
                  view.rows().stream()
                      .filter(TerminalPackets.Row::craftable)
                      .map(row -> row.icon() + "=" + row.total())
                      .toList())
              .append('\n');
          captures.grab(client, "items-craftable-scale-4.png");
          var screen = terminal(client);
          for (int index = 0; index < view.rows().size(); index++)
            if (view.rows().get(index).icon().equals("minecraft:stick"))
              return screen.mouseClicked(screen.itemX(index) + 9, screen.itemY(index) + 9, 0);
          throw new IllegalStateException("Stick is a zero-count craftable row");
        });
    step(
        client -> {
          var jobs = jobView(client);
          if (jobs.craftables().stream().noneMatch(e -> e.icon().equals("minecraft:stick"))
              || waited() < 6) return false;
          captures.grab(client, "items-request-scale-4.png");
          return true;
        });
    step(client -> press(client, "Items"));
    step(client -> waited() > 4 && press(client, "⚒"));
    step(client -> !terminal(client).getMenu().view().craftables() && waited() > 4);
    step(
        client -> client.screen instanceof TerminalScreen && waited() > 4 && press(client, "Jobs"));
    step(
        client -> {
          if (jobView(client).total() < 13 || waited() < 6) return false;
          setScale(client, 4);
          return true;
        });
    planCapture("minecraft:stick", "200", "jobs-plan-two-level-shortage-scale-4.png", false);
    planCapture("minecraft:coal", "1", "jobs-plan-cycle-scale-4.png", false);
    planCapture(null, "1", "jobs-plan-bound-scale-4.png", true);
    // A4: a waiting processing job's steps and reason in the monitor.
    planCapture("minecraft:iron_ingot", "1", "jobs-plan-iron-scale-4.png", false);
    step(client -> waited() > 2 && press(client, "Back"));
    step(
        client -> {
          var jobs = jobView(client).jobs();
          // The session's command rate gate may drop a Start sent right after other commands.
          if (jobs.isEmpty()) {
            if (waited() % 20 == 10) press(client, "Start");
            return false;
          }
          if (!jobs.getFirst().reason().equals("machine_missing")) return false;
          var screen = terminal(client);
          return screen.mouseClicked(
              screen.getGuiLeft() + TerminalScreen.JOBS_X + 20, screen.getGuiTop() + 50, 0);
        });
    step(
        client -> {
          var detail = jobView(client).detail();
          if (detail.steps().isEmpty() || waited() < 6) return false;
          evidence.append("jobs monitor waiting: ").append(detail).append('\n');
          captures.grab(client, "jobs-monitor-waiting-scale-4.png");
          return press(client, "Cancel");
        });
    step(client -> jobView(client).jobs().isEmpty() && waited() > 4);
    step(
        client -> {
          setScale(client, 2);
          if (hasButton(client, "Back")) press(client, "Back");
          press(client, "Items");
          client.screen.onClose();
          return true;
        });
    step(client -> client.screen == null && waited() > 4);
    server(PresentationScenes::fewJobs);
    serverUntil(
        player -> valid(player, base.east(2)) && valid(player, PresentationScenes.JOB_UNIT));
  }

  /**
   * Selects craftable {@code id} (null: the plan tree's top item), previews {@code amount} through
   * the typed box, opens the Plan panel and captures it; {@code toEnd} also scrolls the breakdown
   * to its last line ("…and N more") and captures that.
   */
  private static String planTarget(String id) {
    return id != null
        ? id
        : net.minecraft.core.registries.BuiltInRegistries.ITEM
            .getKey(PresentationScenes.planTop)
            .toString();
  }

  private void planCapture(String id, String amount, String capture, boolean toEnd) {
    step(
        client -> {
          var target = planTarget(id);
          var screen = terminal(client);
          var view = jobView(client);
          if (hasButton(client, "Back")) press(client, "Back");
          // Number keys select craftables only while no text box has focus.
          for (var child : screen.children())
            if (child instanceof net.minecraft.client.gui.components.EditBox box)
              box.setFocused(false);
          screen.setFocused(null);
          for (int index = 0; index < view.craftables().size(); index++)
            if (view.craftables().get(index).icon().equals(target)) {
              screen.keyPressed(GLFW.GLFW_KEY_1 + index, 0, 0);
              for (var child : screen.children())
                if (child instanceof net.minecraft.client.gui.components.EditBox box
                    && box.visible
                    && box.getMessage().getString().equals("Qty")) {
                  screen.setFocused(box);
                  box.setFocused(true);
                  box.setValue("");
                  for (char c : amount.toCharArray()) screen.charTyped(c, 0);
                  screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
                  box.setFocused(false);
                  screen.setFocused(null);
                  return true;
                }
              throw new IllegalStateException("Jobs amount box shown");
            }
          // Not in this window: the strip is in key order, so scroll one notch toward the target.
          if (waited() % 4 == 3 && !view.craftables().isEmpty()) {
            boolean before = target.compareTo(view.craftables().getFirst().icon()) < 0;
            screen.mouseScrolled(
                screen.getGuiLeft() + 60, screen.getGuiTop() + 60, 0, before ? 1 : -1);
          }
          return false;
        });
    step(
        client -> {
          var view = jobView(client);
          if (view.preview().icon().equals(planTarget(id))
              && view.preview().amount().equals(amount)
              && !view.plan().isEmpty()
              && waited() >= 6) return press(client, "Plan");
          // The session's command rate gate can drop a preview sent right after scrolling.
          if (waited() % 20 == 19) {
            var screen = terminal(client);
            for (var child : screen.children())
              if (child instanceof net.minecraft.client.gui.components.EditBox box
                  && box.visible
                  && box.getMessage().getString().equals("Qty")) {
                screen.setFocused(box);
                box.setFocused(true);
                screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
                box.setFocused(false);
                screen.setFocused(null);
              }
          }
          return false;
        });
    step(
        client -> {
          if (waited() < 6) return false;
          var view = jobView(client);
          evidence
              .append(capture)
              .append(": rows=")
              .append(view.plan().size())
              .append(" more=")
              .append(view.planMore())
              .append(" cycles=")
              .append(view.cycles())
              .append(" first=")
              .append(view.plan().getFirst())
              .append('\n');
          captures.grab(client, capture);
          return true;
        });
    if (!toEnd) return;
    step(
        client -> {
          var screen = terminal(client);
          for (int notch = 0; notch < 100; notch++)
            screen.mouseScrolled(screen.getGuiLeft() + 60, screen.getGuiTop() + 60, 0, -1);
          return true;
        });
    step(
        client -> {
          if (waited() < 4) return false;
          captures.grab(client, capture.replace("-scale-4", "-end-scale-4"));
          return true;
        });
  }

  private static long planks(ServerPlayer player) {
    var drive =
        (StorageDriveEntity) player.serverLevel().getBlockEntity(PresentationScenes.SCREENS.east());
    var key =
        new PlatformResourceCodec(player.registryAccess()).itemKey(new ItemStack(Items.OAK_PLANKS));
    var registry = DeviceSavedData.get(player.server).registry();
    long total = 0;
    for (int i = 0; i < 4; i++) {
      var cell = drive.cell(i);
      if (cell.isEmpty()) continue;
      var handle = CellHandleData.read(cell);
      if (registry.ownership(handle.backing()).definition().kind() != ResourceKind.ITEM) continue;
      var stock = registry.stock(handle, key);
      if (!stock.loaded()) return -1;
      total = Math.addExact(total, stock.total());
    }
    return total;
  }

  private static TerminalJobPackets.View jobView(Minecraft client) {
    check(client.screen instanceof TerminalScreen, "Terminal closed during the jobs tour");
    return ((TerminalScreen) client.screen).getMenu().jobView();
  }

  /** Presses the visible, active button with this exact label; false when none is pressable. */
  private static boolean press(Minecraft client, String label) {
    for (var child : client.screen.children())
      if (child instanceof Button button
          && button.visible
          && button.active
          && button.getMessage().getString().equals(label)) {
        client.screen.mouseClicked(
            button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0, 0);
        return true;
      }
    return false;
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
        new ServerboundPlayerCommandPacket(
            client.player,
            down
                ? ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY
                : ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
  }

  private static NodeRegistry.Position at(ServerPlayer player, BlockPos position) {
    return Positions.of(player.serverLevel(), position);
  }

  private static boolean valid(ServerPlayer player, BlockPos position) {
    return WorldNetworks.get(player.server).membership(at(player, position)).status()
        == NetworkTopology.Status.VALID;
  }

  /** Installs through the actual client interaction path, then checks consumption and synced UI. */
  private void upgrade(BlockPos target, ThroughputTier tier, boolean buffer) {
    String item = tier == ThroughputTier.ADVANCED ? "throughput_upgrade" : "overdrive_upgrade";
    String name = (buffer ? "buffer-" : "machine-") + item.replace("_upgrade", "");
    int maximum = buffer ? tier.bufferRate() : tier.portRate(ResourceKind.ITEM);
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
                screen.getMenu().view().tier().equals(tier.name().toLowerCase(Locale.ROOT)),
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
      String legend = ItemDescriptions.lampLegendKey(BuiltInRegistries.ITEM.get(id));
      if (legend != null)
        check(lines.contains(I18n.get(legend)), "Lamp legend missing from tooltip of " + id);
      evidence.append("tooltip ").append(id.getPath()).append(": ").append(lines).append('\n');
    }
    // Crafter (ADR 0060) replaced two described crafting blocks with one.
    check(described >= 13, "Too few described items: " + described);
  }

  // ------------------------------------------------------------------ script

  @SuppressWarnings("PMD.NcssCount") // One linear, readable scenario script.
  private void buildSteps() {
    step(
        client -> {
          if (client.screen instanceof AccessibilityOnboardingScreen onboarding)
            onboarding.onClose();
          if (!(client.screen instanceof TitleScreen)) return false;
          var window = client.getWindow();
          double ratio = (double) window.getWidth() / Math.max(1, window.getScreenWidth());
          window.setWindowed(
              (int) Math.round(MIN_WIDTH / ratio), (int) Math.round(MIN_HEIGHT / ratio));
          client.options.guiScale().set(2);
          client.options.pauseOnLostFocus = false;
          client.getTutorial().setStep(TutorialSteps.NONE);
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
          var drive = (StorageDriveEntity) level.getBlockEntity(PresentationScenes.SCREENS.east());
          drive.installCell(new ItemStack(StorageContent.cell(CellTier.ADVANCED_ITEM)));
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
                  // Links have no lamp since ADR 0061; their gateways report the link state.
                  && link(level, 50, z + 3).equals("")
                  && link(level, 52, z + 3).equals("")
                  && link(level, 54, z + 3).equals("no_power")
                  && link(level, 58, z + 3).equals("unpaired");
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
                      + WorldNetworks.get(player.server).membership(at(player, position)).status()
                      + (LinkModule.loaded(level, position) == null
                          ? ""
                          : " link=" + LinkModule.loaded(level, position).diagnostic()));
            }
          }
          return ready;
        });
    step(
        client -> {
          evidence.append(
              "lamps: root active, joined roots fault, rootless gateway fault, rooted gateway active;"
                  + " programs off/active/paused/fault; access points active/fault; linked"
                  + " gateways active/active/no_power/unpaired\n");
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
            captures.grab(client, name);
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
                          stack("starter_energy_bank"), stack("wireless_link")),
                      targetCorner));
              setScale(client, targetScale);
              return true;
            });
        step(
            client -> {
              if (!(client.screen instanceof TooltipBoard) || waited() < 6) return false;
              String edge = targetCorner == 0 ? "edges" : "corner-" + targetCorner;
              captures.grab(client, "tooltips-" + edge + "-scale-" + targetScale + ".png");
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
    server(
        player -> {
          var drive = (StorageDriveEntity) player.serverLevel().getBlockEntity(base.east());
          var handle = CellHandleData.read(drive.cell(1));
          var registry = DeviceSavedData.get(player.server).registry();
          var codec = new PlatformResourceCodec(player.registryAccess());
          ItemStack[] examples = {
            new ItemStack(Items.STONE),
            new ItemStack(Items.IRON_INGOT),
            new ItemStack(Items.GOLD_INGOT),
            new ItemStack(Items.DIAMOND),
            new ItemStack(Items.EMERALD)
          };
          long[] counts = {1, 23, 64, 1234, 999000};
          for (int i = 0; i < examples.length; i++)
            check(
                registry.insert(handle, codec.itemKey(examples[i]), counts[i]).amount()
                    == counts[i],
                "Count rendering stock admitted");
          evidence.append(
              "terminal count rendering: Stone=1, Iron=23, Gold=64, Diamond=1k (1234), Emerald=999k (999000); vanilla overlay layer; inspect full glyphs at scales 1–4.\n");
        });
    use(base.east(2), Direction.SOUTH, false);
    step(
        client -> {
          if (!(client.screen instanceof TerminalScreen)) return false;
          setScale(client, 4);
          return true;
        });
    step(
        client -> {
          if (!(client.screen instanceof TerminalScreen screen)
              || waited() < 6
              || screen.getMenu().view().size() != screen.columns() * screen.rows()) return false;
          var cell =
              screen.children().stream()
                  .filter(
                      child ->
                          child instanceof AbstractButton button
                              && !(child instanceof Button)
                              && button.visible
                              && button.active)
                  .findFirst()
                  .orElse(null);
          if (cell == null) return false;
          screen.setFocused(cell);
          return true;
        });
    waitTicks(6);
    step(
        client -> {
          captures.grab(client, "terminal-keyboard-scale-4.png");
          return client.screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
        });
    step(
        client ->
            !client.player.containerMenu.getCarried().isEmpty()
                && waited() > 4
                && client.screen instanceof TerminalScreen screen
                && !screen.getMenu().view().busy()
                && !screen.getMenu().view().rows().isEmpty());
    step(
        client -> {
          var screen = (TerminalScreen) client.screen;
          return screen.mouseClicked(screen.itemX(0) + 9, screen.itemY(0) + 9, 0);
        });
    step(client -> client.player.containerMenu.getCarried().isEmpty() && waited() > 4);
    scales("terminal", TerminalScreen.class, true);
    craftingTour(base);
    jobsTour(base);
    sortAndFluidTour(base);
    use(base.east(), Direction.SOUTH, false);
    step(client -> client.screen instanceof DriveScreen && waited() > 4);
    step(
        client -> {
          var screen = (DriveScreen) client.screen;
          return screen.mouseClicked(screen.getGuiLeft() + 16, screen.getGuiTop() + 26, 0);
        });
    step(client -> !client.player.containerMenu.getCarried().isEmpty());
    step(
        client -> {
          var screen = (DriveScreen) client.screen;
          return screen.mouseClicked(screen.getGuiLeft() + 16, screen.getGuiTop() + 26, 0);
        });
    step(client -> client.player.containerMenu.getCarried().isEmpty() && waited() > 4);
    scales("drive", DriveScreen.class, false);
    // B1: one ghost slot per match kind (Item, Exact, Tag) in the Buffer's saved filter.
    server(
        player -> {
          var buffer =
              (dev.izzy.factorycore.platform.storage.BufferedInterfaceEntity)
                  player.serverLevel().getBlockEntity(base.east(3));
          var codec = new PlatformResourceCodec(player.registryAccess());
          var filter =
              new dev.izzy.factorycore.core.resource.ResourceFilter(
                  dev.izzy.factorycore.core.resource.ResourceKind.ITEM,
                  dev.izzy.factorycore.core.resource.ResourceFilter.Mode.INCLUDE,
                  List.of(
                      new dev.izzy.factorycore.core.resource.ResourceFilter.Registry(
                          "minecraft:iron_ingot"),
                      new dev.izzy.factorycore.core.resource.ResourceFilter.Exact(
                          codec.itemKey(new ItemStack(Items.DIAMOND))),
                      new dev.izzy.factorycore.core.resource.ResourceFilter.Tag(
                          "minecraft:planks")));
          buffer.configure(
              new dev.izzy.factorycore.core.network.NetworkPermissions.Actor(
                  player.getUUID(), false),
              dev.izzy.factorycore.core.storage.BufferRegistry.Mode.PAUSED,
              new dev.izzy.factorycore.core.storage.BufferPolicy(filter, 64, 64));
        });
    use(base.east(3), Direction.SOUTH, false);
    scales("buffer", BufferSettingsScreen.class, false);
    use(base.east(4), Direction.SOUTH, false);
    scales("machine", MachineSettingsScreen.class, false);
    filterTour(base);
    use(base.east(6).north(), Direction.SOUTH, false);
    step(
        client ->
            client.screen instanceof ConnectorSettingsScreen screen
                && screen.getMenu().view().generation() > 0
                && screen.getMenu().view().binding().equals("ok"));
    scales("connector", ConnectorSettingsScreen.class, false);
    // P5: a real click on the slot strip picks slot 3 of the 27-slot chest (draft only).
    use(base.east(6).north(), Direction.SOUTH, false);
    step(
        client -> {
          if (!(client.screen instanceof ConnectorSettingsScreen)) return false;
          setScale(client, 4);
          return true;
        });
    step(
        client -> {
          if (!(client.screen instanceof ConnectorSettingsScreen screen) || waited() < 4)
            return false;
          for (var child : screen.children())
            if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget
                && widget.visible
                && widget.getClass().getSimpleName().equals("SlotPicker")) {
              int cell = widget.getWidth() / 27;
              return screen.mouseClicked(
                  widget.getX() + cell * 3 + cell / 2.0, widget.getY() + 4, 0);
            }
          throw new IllegalStateException("Slot picker shown for a 27-slot chest");
        });
    step(
        client -> {
          if (waited() < 4) return false;
          captures.grab(client, "connector-picked-scale-4.png");
          // B2: the Filter tab, with cobblestone dropped as a recipe viewer would.
          return press(client, "Filter…");
        });
    step(
        client -> {
          var screen = (ConnectorSettingsScreen) client.screen;
          if (!screen.getMenu().view().insertRules().isEmpty()) return waited() > 4;
          if (waited() % 10 == 5)
            screen.addFilterId(
                dev.izzy.factorycore.core.resource.ResourceKind.ITEM, "minecraft:cobblestone");
          return false;
        });
    step(
        client -> {
          captures.grab(client, "connector-filter-scale-4.png");
          setScale(client, 1);
          return true;
        });
    step(
        client -> {
          if (waited() < 6) return false;
          captures.grab(client, "connector-filter-scale-1.png");
          setScale(client, 2);
          client.screen.onClose();
          return true;
        });
    upgrade(base.east(4), ThroughputTier.ADVANCED, false);
    upgrade(base.east(4), ThroughputTier.ENDGAME, false);
    upgrade(base.east(3), ThroughputTier.ADVANCED, true);
    upgrade(base.east(3), ThroughputTier.ENDGAME, true);
    use(base.east(5), Direction.SOUTH, false);
    scales("program", ProgramScreen.class, false);
    // Plain use opens the gateway; sneak-use would pair its link (ADR 0061).
    use(base.east(7), Direction.SOUTH, false);
    step(
        client ->
            client.screen instanceof GatewayPolicyScreen screen
                && screen.getMenu().linkState().equals("unpaired"));
    scales("gateway-policy", GatewayPolicyScreen.class, false);
    // U8: the Custom choice reveals the three columns of toggles.
    use(base.east(7), Direction.SOUTH, false);
    step(
        client -> {
          if (!(client.screen instanceof GatewayPolicyScreen screen)) return false;
          for (var child : screen.children())
            if (child instanceof net.minecraft.client.gui.components.Button button
                && button.visible
                && button.active
                && button.getMessage().getString().equals("Custom")) {
              button.onPress();
              return true;
            }
          throw new IllegalStateException("Gateway Custom choice missing");
        });
    scales("gateway-custom", GatewayPolicyScreen.class, false);
    // Network Access (ADR 0055): a second principal row so grant toggles are on screen.
    server(
        player ->
            check(
                WorldNetworks.get(player.getServer())
                        .setGrants(
                            new NetworkPermissions.Actor(player.getUUID(), false),
                            at(player, base),
                            UUID.nameUUIDFromBytes(
                                "presentation-guest".getBytes(StandardCharsets.UTF_8)),
                            // U8: a Member role, so the row shows a named role.
                            NetworkPermissions.AccessRole.MEMBER.grants())
                    == NetworkPermissions.Change.OK,
                "Owner grants a second principal before opening Network Access"));
    use(base, Direction.SOUTH, true);
    step(
        client -> {
          if (!(client.screen instanceof NetworkAccessScreen)) return false;
          shift(client, false);
          return true;
        });
    scales("access", NetworkAccessScreen.class, false);
    // U8: Advanced swaps the role buttons for the seven permission toggles.
    use(base, Direction.SOUTH, false);
    step(
        client -> {
          if (!(client.screen instanceof NetworkAccessScreen screen)) return false;
          for (var child : screen.children())
            if (child instanceof net.minecraft.client.gui.components.Button button
                && button.visible
                && button.getMessage().getString().equals("Advanced")) {
              button.onPress();
              return true;
            }
          return false;
        });
    scales("access-advanced", NetworkAccessScreen.class, false);
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
            client.screen.keyPressed(GLFW.GLFW_KEY_PAGE_DOWN, 0, 0);
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
            captures.grab(client, "upgrade-tooltip-" + targetCorner + "-scale-4.png");
            return true;
          });
    }
    step(
        client -> {
          writeReport(client);
          return true;
        });
  }

  private static DeviceLamp lamp(ServerLevel level, int x, int z) {
    return level.getBlockState(new BlockPos(x, 65, z)).getValue(DeviceLamp.PROPERTY);
  }

  /** The diagnostic of the link installed in the gateway at (x, 65, z), or "none". */
  private static String link(ServerLevel level, int x, int z) {
    var link = LinkModule.loaded(level, new BlockPos(x, 65, z));
    return link == null ? "none" : link.diagnostic();
  }

  private static ItemStack stack(String path) {
    return new ItemStack(
        BuiltInRegistries.ITEM.get(
            ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, path)));
  }

  private void writeReport(Minecraft client) {
    try {
      Files.writeString(
          report,
          "PASS\n"
              + evidence
              + "Orientation: every FACING device in all six facings, horizontal devices in four;"
              + " two opposite corner views show every front.\n"
              + "Screens: terminal, drive, buffer settings, machine settings, connector settings, program editor, gateway"
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
