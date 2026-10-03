package dev.izzy.factorycore.core.resource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.UUID;

/** Version-one bounded binary component codec, independent of world save/ownership policy. */
public final class LedgerStateCodec {
  public static final int MAX_BYTES = 8 * 1024 * 1024;
  public static final int MAX_CATALOG = 4096;
  public static final int MAX_RESERVATIONS = 8192;
  private static final int MAGIC = 0x46434c52;
  private static final int VERSION = 1;
  private static final int HASH_BYTES = 32;

  private LedgerStateCodec() {}

  public static byte[] encode(LedgerState state) {
    checkLimits(state.catalogLimit(), state.reservationLimit());
    long size = 8 + 16 + 1 + 8 + 1 + 4 + 4 + 4 + 4 + HASH_BYTES;
    for (var entry : state.contents().entrySet()) {
      size += keySize(entry.getKey()) + 8;
    }
    for (var claim : state.reservations()) {
      size += 16 + keySize(claim.key()) + 8;
    }
    if (size > MAX_BYTES) {
      throw new IllegalArgumentException("Ledger exceeds serialized technical byte limit");
    }
    var bytes = new ByteArrayOutputStream((int) size);
    try (var output = new DataOutputStream(bytes)) {
      output.writeInt(MAGIC);
      output.writeInt(VERSION);
      writeId(output, state.id());
      output.writeByte(state.kind().ordinal());
      output.writeLong(state.capacity());
      output.writeByte(state.infinite() ? 1 : 0);
      output.writeInt(state.catalogLimit());
      output.writeInt(state.reservationLimit());
      var stock = new ArrayList<>(state.contents().entrySet());
      stock.sort(java.util.Map.Entry.comparingByKey());
      output.writeInt(stock.size());
      for (var entry : stock) {
        writeKey(output, entry.getKey());
        output.writeLong(entry.getValue());
      }
      var claims = new ArrayList<>(state.reservations());
      claims.sort(
          Comparator.comparing(LedgerState.Reservation::owner)
              .thenComparing(LedgerState.Reservation::key));
      output.writeInt(claims.size());
      for (var claim : claims) {
        writeId(output, claim.owner());
        writeKey(output, claim.key());
        output.writeLong(claim.amount());
      }
      output.flush();
      output.write(digest(bytes.toByteArray(), bytes.size()));
    } catch (IOException impossible) {
      throw new IllegalStateException("In-memory ledger encoding failed", impossible);
    }
    return bytes.toByteArray();
  }

  public static LedgerState decode(byte[] encoded) {
    if (encoded.length < 82 || encoded.length > MAX_BYTES) {
      throw new IllegalArgumentException("Invalid ledger envelope size");
    }
    encoded = encoded.clone();
    int payload = encoded.length - HASH_BYTES;
    if (!MessageDigest.isEqual(
        digest(encoded, payload), Arrays.copyOfRange(encoded, payload, encoded.length))) {
      throw new IllegalArgumentException("Ledger checksum mismatch; preserve bytes for recovery");
    }
    try (var input = new DataInputStream(new ByteArrayInputStream(encoded, 0, payload))) {
      if (input.readInt() != MAGIC || input.readInt() != VERSION) {
        throw new IllegalArgumentException(
            "Unsupported ledger schema; preserve bytes for migration");
      }
      UUID id = readId(input);
      int kindId = input.readUnsignedByte();
      if (kindId >= ResourceKind.values().length) {
        throw new IllegalArgumentException("Unknown resource kind");
      }
      ResourceKind kind = ResourceKind.values()[kindId];
      long capacity = input.readLong();
      int infinite = input.readUnsignedByte();
      if (infinite > 1) {
        throw new IllegalArgumentException("Invalid infinite flag");
      }
      int catalog = input.readInt();
      int reservationLimit = input.readInt();
      checkLimits(catalog, reservationLimit);
      int count = count(input, catalog);
      var contents = new HashMap<ResourceKey, Long>();
      for (int i = 0; i < count; i++) {
        var key = readKey(input, kind);
        long amount = input.readLong();
        if (contents.putIfAbsent(key, amount) != null) {
          throw new IllegalArgumentException("Duplicate stock identity");
        }
      }
      int claimCount = count(input, reservationLimit);
      var reservations = new ArrayList<LedgerState.Reservation>(claimCount);
      for (int i = 0; i < claimCount; i++) {
        reservations.add(
            new LedgerState.Reservation(readId(input), readKey(input, kind), input.readLong()));
      }
      if (input.available() != 0) {
        throw new IllegalArgumentException("Trailing ledger bytes");
      }
      return new LedgerState(
          id, kind, capacity, infinite == 1, catalog, reservationLimit, contents, reservations);
    } catch (IOException malformed) {
      throw new IllegalArgumentException(
          "Truncated ledger data; preserve bytes for recovery", malformed);
    }
  }

  private static void checkLimits(int catalog, int reservations) {
    if (catalog <= 0
        || catalog > MAX_CATALOG
        || reservations <= 0
        || reservations > MAX_RESERVATIONS) {
      throw new IllegalArgumentException("Ledger configuration exceeds codec limits");
    }
  }

  private static int keySize(ResourceKey key) {
    return 2 + key.registryId().length() + 4 + key.componentByteCount();
  }

  private static void writeKey(DataOutputStream output, ResourceKey key) throws IOException {
    byte[] id = key.registryId().getBytes(StandardCharsets.US_ASCII);
    output.writeShort(id.length);
    output.write(id);
    byte[] components = key.components();
    output.writeInt(components.length);
    output.write(components);
  }

  private static ResourceKey readKey(DataInputStream input, ResourceKind kind) throws IOException {
    int length = input.readUnsignedShort();
    if (length == 0 || length > 256 || length > input.available()) {
      throw new IllegalArgumentException("Invalid registry-ID length");
    }
    byte[] id = input.readNBytes(length);
    for (byte value : id) {
      if (value < 0) throw new IllegalArgumentException("Registry ID must be ASCII");
    }
    int componentLength = count(input, ResourceKey.MAX_COMPONENT_BYTES);
    if (componentLength > input.available()) {
      throw new IllegalArgumentException("Truncated resource components");
    }
    return new ResourceKey(
        kind, new String(id, StandardCharsets.US_ASCII), input.readNBytes(componentLength));
  }

  private static int count(DataInputStream input, int maximum) throws IOException {
    int count = input.readInt();
    if (count < 0 || count > maximum) {
      throw new IllegalArgumentException("Invalid or excessive ledger declaration count");
    }
    return count;
  }

  private static UUID readId(DataInputStream input) throws IOException {
    return new UUID(input.readLong(), input.readLong());
  }

  private static void writeId(DataOutputStream output, UUID id) throws IOException {
    output.writeLong(id.getMostSignificantBits());
    output.writeLong(id.getLeastSignificantBits());
  }

  private static byte[] digest(byte[] bytes, int length) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      digest.update(bytes, 0, length);
      return digest.digest();
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("Required SHA-256 unavailable", unavailable);
    }
  }
}
