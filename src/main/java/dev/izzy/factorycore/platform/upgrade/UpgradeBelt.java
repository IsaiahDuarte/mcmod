package dev.izzy.factorycore.platform.upgrade;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Q113: what an Upgrade Toolbelt carries, at most {@link #MAX} of each upgrade. Upgrades in it are
 * ordinary items kept as counts; installing from it uses the same device contract as the cursor.
 */
public record UpgradeBelt(int throughput, int overdrive) {
  public static final int MAX = 64;
  public static final UpgradeBelt EMPTY = new UpgradeBelt(0, 0);

  public UpgradeBelt {
    if (throughput < 0 || throughput > MAX || overdrive < 0 || overdrive > MAX)
      throw new IllegalArgumentException("Upgrade belt holds 0–64 of each upgrade");
  }

  public static final Codec<UpgradeBelt> CODEC =
      RecordCodecBuilder.create(
          belt ->
              belt.group(
                      Codec.intRange(0, MAX)
                          .fieldOf("throughput")
                          .forGetter(UpgradeBelt::throughput),
                      Codec.intRange(0, MAX).fieldOf("overdrive").forGetter(UpgradeBelt::overdrive))
                  .apply(belt, UpgradeBelt::new));

  public static final StreamCodec<ByteBuf, UpgradeBelt> STREAM_CODEC =
      StreamCodec.composite(
          ByteBufCodecs.VAR_INT,
          UpgradeBelt::throughput,
          ByteBufCodecs.VAR_INT,
          UpgradeBelt::overdrive,
          UpgradeBelt::new);

  /** How many of the upgrade for {@code tier} it holds (none for the base tier). */
  public int count(ThroughputTier tier) {
    return switch (tier) {
      case BASE -> 0;
      case ADVANCED -> throughput;
      case ENDGAME -> overdrive;
    };
  }

  /** This belt with {@code delta} more of the upgrade for {@code tier}. */
  public UpgradeBelt add(ThroughputTier tier, int delta) {
    return switch (tier) {
      case BASE -> throw new IllegalArgumentException("The base tier has no upgrade");
      case ADVANCED -> new UpgradeBelt(throughput + delta, overdrive);
      case ENDGAME -> new UpgradeBelt(throughput, overdrive + delta);
    };
  }

  public boolean isEmpty() {
    return throughput == 0 && overdrive == 0;
  }
}
