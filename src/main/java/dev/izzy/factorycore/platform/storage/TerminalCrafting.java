package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.crafting.GridRequirement;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.crafting.PatternContents;
import dev.izzy.factorycore.platform.crafting.ProcessingContents;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Server-owned manual crafting grid for one terminal session. Cells are exact ghost keys; no items
 * are held. Crafting consumes only the player's main inventory and commits each bounded batch
 * atomically on the server thread after outputs and remainders are proven to fit.
 */
final class TerminalCrafting {
  static final int SLOTS = 36;

  /** Result of one bounded request. A deficit means more ingredients must be gathered first. */
  record Outcome(
      int crafted, TerminalCraftingPackets.GridStatus status, GridRequirement.Deficit deficit) {}

  /** Nine input cells plus three processing output cells. */
  static final int ALL_CELLS = GridRequirement.CELLS + 3;

  private final ResourceKey[] cells = new ResourceKey[GridRequirement.CELLS];
  private final ResourceKey[] outputs = new ResourceKey[3];
  private final int[] counts = new int[ALL_CELLS];
  private boolean processing;
  private long revision = 1;

  TerminalCrafting() {
    Arrays.fill(counts, 1);
  }

  private long cachedRevision;
  private Optional<RecipeHolder<CraftingRecipe>> cached = Optional.empty();

  long revision() {
    return revision;
  }

  ResourceKey cell(int index) {
    return index < cells.length ? cells[index] : outputs[index - cells.length];
  }

  int count(int index) {
    return counts[index];
  }

  boolean processing() {
    return processing;
  }

  /** Processing mode encodes declared inputs/outputs instead of a crafting-table recipe. */
  void processing(boolean enabled) {
    processing = enabled;
    // A crafting-table grid cannot hold fluids; leaving processing mode drops fluid cells.
    if (!enabled)
      for (int index = 0; index < ALL_CELLS; index++)
        if (cell(index) != null && cell(index).kind() == ResourceKind.FLUID) set(index, null);
    revision++;
  }

  void set(int index, ResourceKey key) {
    if (index < 0 || index >= ALL_CELLS) throw new IllegalArgumentException("Grid cell");
    if (index < cells.length) cells[index] = key;
    else outputs[index - cells.length] = key;
    counts[index] = 1;
    revision++;
  }

  /** Per-cell unit count used only by processing patterns. */
  void count(int index, int amount) {
    if (index < 0 || index >= ALL_CELLS || amount < 1 || amount > 64)
      throw new IllegalArgumentException("Grid count");
    counts[index] = amount;
    revision++;
  }

  void clear() {
    Arrays.fill(cells, null);
    Arrays.fill(outputs, null);
    Arrays.fill(counts, 1);
    revision++;
  }

  GridRequirement requirement() {
    return new GridRequirement(Arrays.asList(cells.clone()));
  }

  private static List<ItemStack> ghosts(ResourceKey[] keys, PlatformResourceCodec codec) {
    var stacks = new ArrayList<ItemStack>(keys.length);
    for (var key : keys) stacks.add(key == null ? ItemStack.EMPTY : codec.itemStack(key, 1));
    return stacks;
  }

  /** Current recipe for the ghost selections, if any; advisory until a craft revalidates it. */
  Optional<RecipeHolder<CraftingRecipe>> recipe(ServerPlayer player) {
    if (processing) return Optional.empty();
    if (cachedRevision == revision) return cached;
    cachedRevision = revision;
    cached = lookup(player);
    return cached;
  }

  private Optional<RecipeHolder<CraftingRecipe>> lookup(ServerPlayer player) {
    if (requirement().empty()) return Optional.empty();
    try {
      var codec = new PlatformResourceCodec(player.registryAccess());
      var input = CraftingInput.of(3, 3, ghosts(cells, codec));
      return player
          .serverLevel()
          .getRecipeManager()
          .getRecipeFor(RecipeType.CRAFTING, input, player.serverLevel());
    } catch (IllegalArgumentException unsupported) {
      return Optional.empty();
    }
  }

  ItemStack preview(ServerPlayer player) {
    var codec = new PlatformResourceCodec(player.registryAccess());
    if (processing) {
      for (int index = 0; index < outputs.length; index++)
        if (outputs[index] != null)
          try {
            if (outputs[index].kind() == ResourceKind.FLUID) {
              // Fluids preview as their bucket (or nothing when the fluid has none).
              var bucket = codec.fluidStack(outputs[index], 1).getFluid().getBucket();
              return bucket == Items.AIR ? ItemStack.EMPTY : new ItemStack(bucket);
            }
            return codec.itemStack(outputs[index], counts[cells.length + index]);
          } catch (IllegalArgumentException unsupported) {
            return ItemStack.EMPTY;
          }
      return ItemStack.EMPTY;
    }
    return recipe(player)
        .map(
            holder ->
                holder
                    .value()
                    .assemble(
                        CraftingInput.of(3, 3, ghosts(cells, codec)), player.registryAccess()))
        .orElse(ItemStack.EMPTY);
  }

  /** Exact main-inventory units for each distinct grid key. */
  Map<ResourceKey, Long> owned(ServerPlayer player) {
    var codec = new PlatformResourceCodec(player.registryAccess());
    var counts = new HashMap<ResourceKey, Long>();
    var wanted = requirement().perCraft().keySet();
    for (int slot = 0; slot < SLOTS; slot++) {
      var stack = player.getInventory().getItem(slot);
      if (stack.isEmpty()) continue;
      var key = keyOf(codec, stack);
      if (key != null && wanted.contains(key))
        counts.merge(key, (long) stack.getCount(), Long::sum);
    }
    return counts;
  }

  /**
   * Crafts up to {@code crafts} times. Returns a deficit (with nothing consumed) when the inventory
   * cannot cover every requested craft, so the caller may gather and retry.
   */
  Outcome craft(ServerPlayer player, int crafts) {
    if (processing) return new Outcome(0, TerminalCraftingPackets.GridStatus.PROCESSING_MODE, null);
    var requirement = requirement();
    if (requirement.empty()) return new Outcome(0, TerminalCraftingPackets.GridStatus.EMPTY, null);
    var holder = recipe(player);
    if (holder.isEmpty()) return new Outcome(0, TerminalCraftingPackets.GridStatus.NO_RECIPE, null);
    var deficit = requirement.deficit(crafts, owned(player));
    if (deficit != null)
      return new Outcome(0, TerminalCraftingPackets.GridStatus.SHORTAGE, deficit);
    var codec = new PlatformResourceCodec(player.registryAccess());
    var inventory = player.getInventory();
    var committed = new ArrayList<ItemStack>(SLOTS);
    for (int slot = 0; slot < SLOTS; slot++) committed.add(inventory.getItem(slot).copy());
    var recipe = holder.get().value();
    int done = 0;
    ItemStack first = ItemStack.EMPTY;
    var status = TerminalCraftingPackets.GridStatus.CRAFTED;
    for (; done < crafts; done++) {
      var trial = new ArrayList<ItemStack>(SLOTS);
      for (var stack : committed) trial.add(stack.copy());
      var used = new ArrayList<ItemStack>(cells.length);
      boolean missing = false;
      for (var key : cells) {
        if (key == null) {
          used.add(ItemStack.EMPTY);
          continue;
        }
        var taken = take(trial, key, codec);
        if (taken.isEmpty()) {
          missing = true;
          break;
        }
        used.add(taken);
      }
      var input = missing ? null : CraftingInput.of(3, 3, used);
      if (missing || !recipe.matches(input, player.serverLevel())) {
        status = TerminalCraftingPackets.GridStatus.SHORTAGE;
        break;
      }
      var output = recipe.assemble(input, player.registryAccess());
      var remainders = recipe.getRemainingItems(input);
      boolean fits = output.isEmpty() || add(trial, output.copy());
      for (var remainder : remainders)
        if (fits && !remainder.isEmpty()) fits = add(trial, remainder.copy());
      if (!fits) {
        status = TerminalCraftingPackets.GridStatus.FULL;
        break;
      }
      if (first.isEmpty()) first = output.copy();
      committed = trial;
    }
    if (done == 0) return new Outcome(0, status, null);
    for (int slot = 0; slot < SLOTS; slot++)
      if (!ItemStack.matches(inventory.getItem(slot), committed.get(slot)))
        inventory.setItem(
            slot, committed.get(slot).isEmpty() ? ItemStack.EMPTY : committed.get(slot));
    inventory.setChanged();
    first.onCraftedBy(player.serverLevel(), player, first.getCount() * done);
    player.awardRecipes(List.of(holder.get()));
    return new Outcome(done, status, null);
  }

  /** Exact key, or null for stacks the resource codec cannot represent; those are never used. */
  /**
   * Writes the current recipe onto one blank pattern from the main inventory. The encoded output
   * and remainders come from the server recipe; nothing else is consumed.
   */
  Outcome encode(ServerPlayer player) {
    return encode(player, false);
  }

  /**
   * As {@link #encode(ServerPlayer)}; {@code substitutes} (E1, PD4) records, per cell, the other
   * items its recipe ingredient accepts, so jobs may use them. Processing patterns stay exact.
   */
  Outcome encode(ServerPlayer player, boolean substitutes) {
    if (processing) return encodeProcessing(player);
    var holder = recipe(player);
    if (holder.isEmpty()) return new Outcome(0, TerminalCraftingPackets.GridStatus.NO_RECIPE, null);
    var codec = new PlatformResourceCodec(player.registryAccess());
    var ghosts = ghosts(cells, codec);
    var input = CraftingInput.of(3, 3, ghosts);
    var recipe = holder.get().value();
    var output = recipe.assemble(input, player.registryAccess());
    var remainders = new ArrayList<ItemStack>();
    for (var remainder : recipe.getRemainingItems(input))
      if (!remainder.isEmpty()) remainders.add(remainder);
    ItemStack encoded;
    try {
      var alternatives = new ArrayList<List<String>>();
      for (var ghost : ghosts) {
        List<String> ids = List.of();
        if (substitutes && !ghost.isEmpty())
          for (var ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty() || !ingredient.test(ghost)) continue;
            var found = new java.util.LinkedHashSet<String>();
            for (var option : ingredient.getItems())
              if (!option.is(ghost.getItem()))
                found.add(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(option.getItem())
                        .toString());
            // A huge ingredient (more than a vanilla tag) stays exact rather than truncated.
            if (found.size() <= dev.izzy.factorycore.core.crafting.Pattern.MAX_ALTERNATIVES)
              ids = List.copyOf(found);
            break;
          }
        alternatives.add(ids);
      }
      var contents =
          new PatternContents(UUID.randomUUID(), 1, 0, ghosts, output, remainders, alternatives);
      contents.pattern(codec);
      encoded = new ItemStack(CraftingContent.CRAFTING_PATTERN.get());
      encoded.set(CraftingContent.PATTERN.get(), contents);
    } catch (IllegalArgumentException unsupported) {
      return new Outcome(0, TerminalCraftingPackets.GridStatus.UNSUPPORTED, null);
    }
    return write(player, encoded);
  }

  /** Fluid processing cells count in units of this many mB (1–64 units: 250–16,000 mB). */
  static final int FLUID_UNIT = 250;

  /**
   * Declared processing pattern: input cells with counts and up to three output cells. Item cells
   * count items; fluid cells count {@link #FLUID_UNIT} mB units.
   */
  private Outcome encodeProcessing(ServerPlayer player) {
    var codec = new PlatformResourceCodec(player.registryAccess());
    var in = new ArrayList<ItemStack>();
    var out = new ArrayList<ItemStack>();
    var fluidIn = new ArrayList<FluidStack>();
    var fluidOut = new ArrayList<FluidStack>();
    ItemStack encoded;
    try {
      for (int index = 0; index < cells.length; index++)
        if (cells[index] != null)
          if (cells[index].kind() == ResourceKind.FLUID)
            fluidIn.add(codec.fluidStack(cells[index], counts[index] * FLUID_UNIT));
          else in.add(codec.itemStack(cells[index], counts[index]));
      for (int index = 0; index < outputs.length; index++)
        if (outputs[index] != null) {
          int count = counts[cells.length + index];
          if (outputs[index].kind() == ResourceKind.FLUID)
            fluidOut.add(codec.fluidStack(outputs[index], count * FLUID_UNIT));
          else out.add(codec.itemStack(outputs[index], count));
        }
      if (in.isEmpty() && fluidIn.isEmpty() || out.isEmpty() && fluidOut.isEmpty())
        return new Outcome(0, TerminalCraftingPackets.GridStatus.NO_RECIPE, null);
      var contents = new ProcessingContents(UUID.randomUUID(), 1, 0, in, out, fluidIn, fluidOut);
      contents.pattern(codec);
      encoded = new ItemStack(CraftingContent.PROCESSING_PATTERN.get());
      encoded.set(CraftingContent.PROCESSING.get(), contents);
    } catch (IllegalArgumentException unsupported) {
      return new Outcome(0, TerminalCraftingPackets.GridStatus.UNSUPPORTED, null);
    }
    return write(player, encoded);
  }

  /** Replaces one blank pattern in the main inventory with {@code encoded}, or changes nothing. */
  private Outcome write(ServerPlayer player, ItemStack encoded) {
    var inventory = player.getInventory();
    var trial = new ArrayList<ItemStack>(SLOTS);
    for (int slot = 0; slot < SLOTS; slot++) trial.add(inventory.getItem(slot).copy());
    boolean blank = false;
    for (var stack : trial)
      if (stack.is(CraftingContent.BLANK_PATTERN.get())) {
        stack.shrink(1);
        blank = true;
        break;
      }
    if (!blank) return new Outcome(0, TerminalCraftingPackets.GridStatus.NO_BLANK, null);
    if (!add(trial, encoded)) return new Outcome(0, TerminalCraftingPackets.GridStatus.FULL, null);
    for (int slot = 0; slot < SLOTS; slot++)
      if (!ItemStack.matches(inventory.getItem(slot), trial.get(slot)))
        inventory.setItem(slot, trial.get(slot).isEmpty() ? ItemStack.EMPTY : trial.get(slot));
    inventory.setChanged();
    return new Outcome(0, TerminalCraftingPackets.GridStatus.ENCODED, null);
  }

  private static ResourceKey keyOf(PlatformResourceCodec codec, ItemStack stack) {
    try {
      return codec.itemKey(stack);
    } catch (IllegalArgumentException unsupported) {
      return null;
    }
  }

  private static ItemStack take(
      List<ItemStack> slots, ResourceKey key, PlatformResourceCodec codec) {
    for (var stack : slots) {
      if (!stack.isEmpty() && key.equals(keyOf(codec, stack))) return stack.split(1);
    }
    return ItemStack.EMPTY;
  }

  /** Merges into matching stacks, then empty slots; false when any unit does not fit. */
  private static boolean add(List<ItemStack> slots, ItemStack stack) {
    int limit = Math.min(stack.getMaxStackSize(), 64);
    for (var held : slots) {
      if (stack.isEmpty()) return true;
      if (!held.isEmpty() && ItemStack.isSameItemSameComponents(held, stack)) {
        int moved = Math.min(stack.getCount(), limit - held.getCount());
        if (moved > 0) {
          held.grow(moved);
          stack.shrink(moved);
        }
      }
    }
    for (int slot = 0; slot < slots.size() && !stack.isEmpty(); slot++) {
      if (!slots.get(slot).isEmpty()) continue;
      slots.set(slot, stack.split(Math.min(stack.getCount(), limit)));
    }
    return stack.isEmpty();
  }
}
