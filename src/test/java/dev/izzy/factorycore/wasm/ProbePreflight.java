package dev.izzy.factorycore.wasm;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Experimental MVP-only declaration fence before the allocating third-party parser. */
final class ProbePreflight {
  private static final byte[] HEADER = {0, 97, 115, 109, 1, 0, 0, 0};

  private ProbePreflight() {}

  static byte[] validate(byte[] artifact) {
    if (artifact.length < 8
        || artifact.length > 65536
        || !Arrays.equals(HEADER, Arrays.copyOf(artifact, 8))) {
      throw new IllegalArgumentException("artifact size/header");
    }
    Reader input = new Reader(artifact, 8, artifact.length);
    ByteArrayOutputStream stripped = new ByteArrayOutputStream(artifact.length);
    stripped.writeBytes(HEADER);
    boolean[] seen = new boolean[13];
    while (input.remaining() > 0) {
      int begin = input.position;
      int section = input.byteValue();
      int length = input.count(input.remaining());
      Reader body = input.slice(length);
      if (section == 0) {
        continue; // Custom/debug/name vectors are never handed to the parser.
      }
      if (section > 12 || seen[section]) {
        throw new IllegalArgumentException("unknown/duplicate section");
      }
      seen[section] = true;
      validateSection(section, body);
      body.requireEnd();
      stripped.write(artifact, begin, input.position - begin);
    }
    return stripped.toByteArray();
  }

  private static void validateSection(int section, Reader body) {
    int count = body.count(section == 2 || section == 9 || section == 11 ? 16 : 256);
    if (section == 8) {
      throw new IllegalArgumentException("implicit startup forbidden");
    }
    if (section == 12) {
      if (count > 16) {
        throw new IllegalArgumentException("data segment limit");
      }
      return;
    }
    if ((section == 4 || section == 5) && count > 1) {
      throw new IllegalArgumentException("one table/memory only");
    }
    for (int i = 0; i < count; i++) {
      switch (section) {
        case 1 -> {
          body.expect(0x60);
          int parameters = body.count(16);
          for (int j = 0; j < parameters; j++) {
            body.scalar();
          }
          int returns = body.count(1);
          for (int j = 0; j < returns; j++) {
            body.scalar();
          }
        }
        case 2 -> {
          body.name("factory_probe");
          body.name("record");
          body.expect(0); // Only explicitly provided function imports.
          body.count(255);
        }
        case 3 -> body.count(255);
        case 4 -> {
          body.expect(0x70);
          limits(body, 256, false);
        }
        case 5 -> limits(body, 32, true);
        case 6 -> {
          body.scalar();
          if (body.byteValue() > 1) {
            throw new IllegalArgumentException("global mutability");
          }
          constant(body);
        }
        case 7 -> {
          body.skip(body.count(128));
          if (body.byteValue() > 3) {
            throw new IllegalArgumentException("export kind");
          }
          body.count(255);
        }
        case 9 -> {
          body.expect(0);
          constant(body);
          int entries = body.count(256);
          for (int j = 0; j < entries; j++) {
            body.count(255);
          }
        }
        case 10 -> function(body.slice(body.count(body.remaining())));
        case 11 -> {
          body.expect(0);
          constant(body);
          body.skip(body.count(body.remaining()));
        }
        default -> throw new IllegalArgumentException("unsupported section");
      }
    }
  }

  private static void limits(Reader reader, int maximum, boolean requireMaximum) {
    int flags = reader.byteValue();
    if (flags > 1 || (requireMaximum && flags != 1)) {
      throw new IllegalArgumentException("unbounded/shared memory");
    }
    int initial = reader.count(maximum);
    if (flags == 1 && reader.count(maximum) < initial) {
      throw new IllegalArgumentException("invalid limits");
    }
  }

  private static void constant(Reader reader) {
    switch (reader.byteValue()) {
      case 0x41 -> reader.signed(5);
      case 0x42 -> reader.signed(10);
      case 0x43 -> reader.skip(4);
      case 0x44 -> reader.skip(8);
      case 0x23 -> reader.count(255);
      default -> throw new IllegalArgumentException("constant expression");
    }
    reader.expect(0x0b);
  }

  private static void function(Reader body) {
    int groups = body.count(128);
    int locals = 0;
    for (int i = 0; i < groups; i++) {
      locals += body.count(128 - locals);
      body.scalar();
    }
    int instructions = 0;
    int depth = 1;
    while (body.remaining() > 0) {
      if (++instructions > 8192) {
        throw new IllegalArgumentException("instruction limit");
      }
      int opcode = body.byteValue();
      switch (opcode) {
        case 0x00, 0x01, 0x05, 0x0f, 0x1a, 0x1b -> {}
        case 0x02, 0x03, 0x04 -> {
          int block = body.byteValue();
          if (block != 0x40 && (block < 0x7c || block > 0x7f)) {
            throw new IllegalArgumentException("multi-value block");
          }
          if (++depth > 64) {
            throw new IllegalArgumentException("control depth");
          }
        }
        case 0x0b -> {
          if (--depth == 0) {
            body.requireEnd();
            return;
          }
        }
        case 0x0c, 0x0d, 0x10, 0x20, 0x21, 0x22, 0x23, 0x24 -> body.count(65535);
        case 0x0e -> {
          int entries = body.count(256);
          for (int i = 0; i <= entries; i++) {
            body.count(63);
          }
        }
        case 0x11 -> {
          body.count(255);
          body.expect(0);
        }
        case 0x3f, 0x40 -> body.expect(0);
        case 0x41 -> body.signed(5);
        case 0x42 -> body.signed(10);
        case 0x43 -> body.skip(4);
        case 0x44 -> body.skip(8);
        default -> {
          if (opcode >= 0x28 && opcode <= 0x3e) {
            body.count(16);
            body.unsigned();
          } else if (opcode < 0x45 || opcode > 0xc4) {
            throw new IllegalArgumentException("unsupported opcode " + opcode);
          }
        }
      }
    }
    throw new IllegalArgumentException("unterminated function");
  }

  private static final class Reader {
    private final byte[] bytes;
    private final int end;
    private int position;

    Reader(byte[] bytes, int position, int end) {
      this.bytes = bytes;
      this.position = position;
      this.end = end;
    }

    int remaining() {
      return end - position;
    }

    int byteValue() {
      if (remaining() == 0) {
        throw new IllegalArgumentException("truncated artifact");
      }
      return bytes[position++] & 255;
    }

    void expect(int expected) {
      if (byteValue() != expected) {
        throw new IllegalArgumentException("unexpected encoding");
      }
    }

    void scalar() {
      int type = byteValue();
      if (type < 0x7c || type > 0x7f) {
        throw new IllegalArgumentException("non-scalar type");
      }
    }

    long unsigned() {
      long value = 0;
      for (int i = 0; i < 5; i++) {
        int next = byteValue();
        if (i == 4 && (next & 0xf0) != 0) {
          throw new IllegalArgumentException("oversized u32");
        }
        value |= (long) (next & 127) << (7 * i);
        if (next < 128) {
          return value;
        }
      }
      throw new IllegalArgumentException("oversized LEB");
    }

    int count(int maximum) {
      long count = unsigned();
      if (count > maximum) {
        throw new IllegalArgumentException("declaration exceeds limit " + maximum);
      }
      return (int) count;
    }

    void signed(int maximumBytes) {
      for (int i = 0; i < maximumBytes; i++) {
        if (byteValue() < 128) {
          return;
        }
      }
      throw new IllegalArgumentException("oversized signed LEB");
    }

    void skip(int count) {
      if (count < 0 || count > remaining()) {
        throw new IllegalArgumentException("truncated bytes");
      }
      position += count;
    }

    Reader slice(int count) {
      int begin = position;
      skip(count);
      return new Reader(bytes, begin, position);
    }

    void name(String expected) {
      int length = count(128);
      if (length != expected.length()) {
        throw new IllegalArgumentException("import not allowed");
      }
      for (int i = 0; i < length; i++) {
        expect(expected.charAt(i));
      }
    }

    void requireEnd() {
      if (remaining() != 0) {
        throw new IllegalArgumentException("trailing bytes");
      }
    }
  }
}
