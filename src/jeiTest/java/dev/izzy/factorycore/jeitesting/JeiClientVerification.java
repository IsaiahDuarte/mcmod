package dev.izzy.factorycore.jeitesting;

import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.platform.client.ProgramScreen;
import dev.izzy.factorycore.platform.client.ProgramTextEditor;
import dev.izzy.factorycore.platform.client.TerminalScreen;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.program.ProgramContent;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.platform.storage.TerminalContent;
import dev.izzy.factorycore.platform.storage.TerminalMenu;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.neoforge.NeoForgeTypes;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * JEI integration on a real client with JEI loaded (ADR 0043). It drives Factory Core's handlers
 * through JEI's own runtime: the terminal transfer handler JEI resolves for the open terminal menu,
 * fed by JEI's recipe layout for the vanilla crafting table, and the program editor ghost handler
 * JEI returns for the screen. Physical drag and the RecipesGui "+" click are not simulated.
 */
final class JeiClientVerification {
  private static final BlockPos ROOT = new BlockPos(1, 65, 1),
      DRIVE = new BlockPos(2, 65, 1),
      TERMINAL = new BlockPos(1, 65, 2),
      PROGRAM = new BlockPos(1, 65, 0);
  private static final ResourceLocation CRAFTING_TABLE =
      ResourceLocation.withDefaultNamespace("crafting_table");
  private final Path report = Path.of(System.getProperty("factorycore.client.report"));
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final AtomicBoolean ready = new AtomicBoolean();
  private final StringBuilder evidence = new StringBuilder();
  private IRecipeCategory<RecipeHolder<CraftingRecipe>> category;
  private RecipeHolder<CraftingRecipe> recipe;
  private volatile dev.izzy.factorycore.core.storage.DeviceRegistry.Handle cell;
  private int stage, ticks, entered;

  void register() {
    NeoForge.EVENT_BUS.addListener(this::tick);
  }

  @SuppressWarnings("PMD.CloseResource") // Minecraft and its integrated server are borrowed.
  private void tick(ClientTickEvent.Post event) {
    var client = Minecraft.getInstance();
    ticks++;
    if (stage == 99) return;
    if (failure.get() != null) throw new IllegalStateException("JEI fixture failed", failure.get());
    if (ticks - entered > 1200)
      throw new IllegalStateException(
          "JEI stage timeout: "
              + stage
              + " runtime="
              + (RuntimeCapture.runtime != null)
              + " screen="
              + (client.screen == null ? "none" : client.screen.getClass().getName()));
    switch (stage) {
      case 0 -> {
        if (client.screen
            instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen onboarding) {
          onboarding.onClose();
          return;
        }
        if (!(client.screen instanceof TitleScreen)) return;
        client.getWindow().setWindowed(1280, 960);
        client.options.guiScale().set(2);
        client.options.pauseOnLostFocus = false;
        client
            .createWorldOpenFlows()
            .createFreshLevel(
                "factorycore-jei-verification",
                new LevelSettings(
                    "JEI verification",
                    GameType.CREATIVE,
                    false,
                    Difficulty.PEACEFUL,
                    true,
                    new GameRules(),
                    WorldDataConfiguration.DEFAULT),
                new WorldOptions(0x7E4A1L, false, false),
                registries ->
                    registries
                        .registryOrThrow(Registries.WORLD_PRESET)
                        .getHolderOrThrow(WorldPresets.FLAT)
                        .value()
                        .createWorldDimensions(),
                client.screen);
        next();
      }
      case 1 -> {
        if (client.player == null
            || client.screen != null
            || client.getSingleplayerServer() == null) return;
        var server = client.getSingleplayerServer();
        var principal = client.player.getUUID();
        server.execute(
            () -> {
              try {
                setup(server.getPlayerList().getPlayer(principal));
              } catch (RuntimeException error) {
                failure.set(error);
              }
            });
        next();
      }
      case 2 -> {
        var server = client.getSingleplayerServer();
        var principal = client.player.getUUID();
        if (!ready.get()) {
          server.execute(() -> seed(server.getPlayerList().getPlayer(principal)));
          return;
        }
        var runtime = RuntimeCapture.runtime;
        if (runtime == null || ticks - entered < 40) return;
        category = runtime.getRecipeManager().getRecipeCategory(RecipeTypes.CRAFTING);
        recipe =
            runtime
                .getRecipeManager()
                .createRecipeLookup(RecipeTypes.CRAFTING)
                .get()
                .filter(holder -> holder.id().equals(CRAFTING_TABLE))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("JEI lists the crafting table"));
        client.gameMode.useItemOn(
            client.player,
            InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(TERMINAL), Direction.UP, TERMINAL, false));
        next();
      }
      case 3 -> {
        if (!(client.screen instanceof TerminalScreen screen)
            || !screen.getMenu().view().status().equals("ready")
            || screen.getMenu().view().rows().isEmpty()
            || ticks - entered < 20) return;
        var runtime = RuntimeCapture.runtime;
        check(
            runtime.getIngredientListOverlay().isListDisplayed(),
            "JEI shows its ingredient list beside the terminal");
        capture(client, "jei-terminal-overlay.png");
        runtime.getRecipesGui().showRecipes(category, List.of(recipe), List.of());
        next();
      }
      case 4 -> {
        if (client.screen instanceof TerminalScreen
            || client.screen == null
            || ticks - entered < 20) return;
        var runtime = RuntimeCapture.runtime;
        check(
            runtime.getRecipesGui().getParentScreen().orElse(null) instanceof TerminalScreen,
            "JEI recipe view opened over the terminal");
        capture(client, "jei-recipe-over-terminal.png");
        var terminal = (TerminalScreen) runtime.getRecipesGui().getParentScreen().orElseThrow();
        TerminalMenu menu = terminal.getMenu();
        var handler =
            runtime
                .getRecipeTransferManager()
                .getRecipeTransferHandler(menu, category)
                .orElseThrow(
                    () ->
                        new IllegalStateException("JEI resolves Factory Core's terminal handler"));
        var layout =
            runtime
                .getRecipeManager()
                .createRecipeLayoutDrawable(
                    category,
                    recipe,
                    runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup())
                .orElseThrow();
        @SuppressWarnings("removal")
        var preview =
            handler.transferRecipe(
                menu, recipe, layout.getRecipeSlotsView(), client.player, false, false);
        check(preview == null, "JEI transfer preview reports no error: " + preview);
        @SuppressWarnings("removal")
        var result =
            handler.transferRecipe(
                menu, recipe, layout.getRecipeSlotsView(), client.player, false, true);
        check(result == null, "JEI transfer reports no error: " + result);
        client.setScreen(terminal);
        evidence
            .append("jei: resolved terminal transfer handler ")
            .append(handler.getClass().getName())
            .append('\n');
        next();
      }
      case 5 -> {
        if (!(client.screen instanceof TerminalScreen screen)) return;
        var grid = screen.getMenu().gridView();
        if (!grid.result().equals("minecraft:crafting_table") || ticks - entered < 10) return;
        long planks =
            grid.cells().stream().filter(cell -> cell.equals("minecraft:oak_planks")).count();
        check(planks == 4, "Transfer chose the oak planks the terminal stocks: " + grid.cells());
        capture(client, "jei-terminal-filled.png");
        evidence
            .append("jei: crafting table recipe filled the ghost grid ")
            .append(grid.cells())
            .append('\n');
        client.player.closeContainer();
        next();
      }
      case 6 -> {
        if (client.screen != null) return;
        client.gameMode.useItemOn(
            client.player,
            InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(PROGRAM), Direction.UP, PROGRAM, false));
        next();
      }
      case 7 -> {
        if (!(client.screen instanceof ProgramScreen screen) || ticks - entered < 10) return;
        var runtime = RuntimeCapture.runtime;
        IGhostIngredientHandler<ProgramScreen> handler =
            runtime.getScreenHelper().getGhostIngredientHandlers(screen).stream()
                .findFirst()
                .orElseThrow(
                    () -> new IllegalStateException("JEI resolves the program ghost handler"));
        var editor =
            screen.children().stream()
                .filter(child -> child instanceof ProgramTextEditor)
                .map(child -> (ProgramTextEditor) child)
                .findFirst()
                .orElseThrow();
        editor.setValue("");
        drop(
            handler,
            screen,
            runtime
                .getIngredientManager()
                .createTypedIngredient(
                    VanillaTypes.ITEM_STACK, new ItemStack(Items.IRON_INGOT), false)
                .orElseThrow());
        drop(
            handler,
            screen,
            runtime
                .getIngredientManager()
                .createTypedIngredient(
                    NeoForgeTypes.FLUID_STACK, new FluidStack(Fluids.WATER, 1000), false)
                .orElseThrow());
        check(
            editor.getValue().equals("item(\"minecraft:iron_ingot\") fluid(\"minecraft:water\")"),
            "Ghost drops type item and fluid expressions: " + editor.getValue());
        evidence
            .append("jei: program ghost drops -> ")
            .append(editor.getValue().replace('\n', ' '))
            .append('\n');
        next();
      }
      case 8 -> {
        // Captures read the last rendered frame, so capture a few ticks after the drops.
        if (ticks - entered < 5) return;
        capture(client, "jei-program-ghost.png");
        next();
      }
      case 9 -> {
        if (ticks - entered < 5) return;
        try {
          Files.writeString(
              report,
              "PASS\n"
                  + evidence
                  + "Handlers are resolved and fed by JEI's own runtime; physical drag and the"
                  + " RecipesGui transfer button click are not simulated.\n");
        } catch (IOException error) {
          throw new IllegalStateException(error);
        }
        stage = 99;
        client.player.closeContainer();
        client.level.disconnect();
        client.disconnect();
        client.stop();
      }
      default -> throw new IllegalStateException("Unknown JEI stage " + stage);
    }
  }

  private static <I> void drop(
      IGhostIngredientHandler<ProgramScreen> handler,
      ProgramScreen screen,
      ITypedIngredient<I> ingredient) {
    var targets = handler.getTargetsTyped(screen, ingredient, false);
    check(targets.size() == 1, "One editor drop target");
    var area = targets.getFirst().getArea();
    check(
        area.getWidth() > 0
            && area.getX() >= 0
            && area.getY() >= 0
            && area.getX() + area.getWidth() <= screen.width
            && area.getY() + area.getHeight() <= screen.height,
        "Drop target lies on screen");
    targets.getFirst().accept(ingredient.getIngredient());
    handler.onComplete();
  }

  @SuppressWarnings(
      "PMD.CloseResource") // The player's live ServerLevel remains owned by Minecraft.
  private void setup(ServerPlayer player) {
    var level = player.serverLevel();
    level.setDayTime(6000);
    for (int x = -2; x < 8; x++)
      for (int z = -2; z < 8; z++)
        level.setBlock(new BlockPos(x, 64, z), Blocks.STONE.defaultBlockState(), 3);
    level.setBlock(ROOT, NetworkContent.CONTROLLER.get().defaultBlockState(), 3);
    ((NetworkNodeEntity) level.getBlockEntity(ROOT)).commission(player.getUUID());
    level.setBlock(DRIVE, StorageContent.DRIVE.get().defaultBlockState(), 3);
    var drive = (StorageDriveEntity) level.getBlockEntity(DRIVE);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    cell = CellHandleData.read(drive.cell(0));
    level.setBlock(TERMINAL, TerminalContent.BLOCK.get().defaultBlockState(), 3);
    level.setBlock(PROGRAM, ProgramContent.BLOCK.get().defaultBlockState(), 3);
    player.getInventory().clearContent();
    player.teleportTo(4.5, 65, 4.5);
    player.setYRot(135);
    player.setXRot(10);
  }

  /** Seeds once the terminal is usable, i.e. the network has validated. */
  @SuppressWarnings(
      "PMD.CloseResource") // The player's live ServerLevel remains owned by Minecraft.
  private void seed(ServerPlayer player) {
    try {
      if (ready.get()
          || !((dev.izzy.factorycore.platform.storage.TerminalEntity)
                  player.serverLevel().getBlockEntity(TERMINAL))
              .canUse(player)) return;
      var planks =
          new PlatformResourceCodec(player.registryAccess())
              .itemKey(new ItemStack(Items.OAK_PLANKS));
      check(
          DeviceSavedData.get(player.server).registry().insert(cell, planks, 16).amount() == 16,
          "Seed sixteen oak planks");
      ready.set(true);
    } catch (RuntimeException error) {
      failure.set(error);
    }
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  private void next() {
    stage++;
    entered = ticks;
    System.out.println("FACTORYCORE_JEI_STAGE " + stage);
  }

  @SuppressWarnings("PMD.CloseResource") // Screenshot borrows the game's active Window.
  private void capture(Minecraft client, String name) {
    try {
      Files.createDirectories(report.getParent().resolve("screenshots"));
    } catch (IOException error) {
      throw new IllegalStateException("Cannot prepare screenshot directory", error);
    }
    evidence.append(name).append('\n');
    Screenshot.grab(
        report.getParent().toFile(),
        name,
        client.getMainRenderTarget(),
        component -> System.out.println("FACTORYCORE_JEI_SCREENSHOT " + component.getString()));
  }
}
