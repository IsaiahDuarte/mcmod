package dev.izzy.factorycore.core.wasm;

import static com.dylibso.chicory.wasm.types.ValType.I32;
import static com.dylibso.chicory.wasm.types.ValType.I64;

import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The {@code factory_v1} host ABI: every import a guest may declare, with its exact signature.
 * {@link WasmPreflight} admits only these names and {@link WasmProgram} binds each one; the Rust
 * SDK's {@code extern "C"} block is checked against this table by {@code HostImportTest}.
 */
public enum HostImport {
  REGISTER_RULE("register_rule", List.of(I32, I32, I32), I32),
  MACHINES("machines", List.of(I32, I32), I64),
  KEEP_STOCKED("keep_stocked", List.of(I32, I32, I32, I32, I64), I32),
  MOVE_TO_STORAGE("move_to_storage", List.of(I32, I32, I32, I32, I64), I32),
  MOVE_FROM_STORAGE("move_from_storage", List.of(I32, I32, I32, I32, I64), I32),
  CRAFT("craft", List.of(I32, I32, I64), I32),
  STOCK("stock", List.of(I32, I32, I32), I64),
  LOG("log", List.of(I32, I32), I32),
  STATE_READ("state_read", List.of(I32, I32), I32),
  STATE_WRITE("state_write", List.of(I32, I32), I32),
  // Additive language-v2 imports (ADR 0062, parity D7); older guests never declare them.
  /** {@code with_access(machine, side, first, last)}: a handle for that face/slots (-1 keeps). */
  WITH_ACCESS("with_access", List.of(I32, I32, I32, I32), I32),
  /** {@code move2(from, to, kind, id, id_len, limit)}: either side -1 for storage, not both. */
  MOVE2("move2", List.of(I32, I32, I32, I32, I32, I64), I32),
  /** {@code has(machine, kind, id, id_len)}: matching units held by the machine. */
  HAS("has", List.of(I32, I32, I32, I32), I64),
  /** {@code redstone_in(machine)}: 1 when its Interface is powered, else 0. */
  REDSTONE_IN("redstone_in", List.of(I32), I32),
  /** {@code emit(label, label_len, strength)}: drive labelled Signal Interfaces at 0–15. */
  EMIT("emit", List.of(I32, I32, I32), I32),
  /** Traps with the guest's message; never returns. */
  PANIC("panic", List.of(I32, I32), null);

  public static final Set<String> NAMES =
      Arrays.stream(values()).map(HostImport::importName).collect(Collectors.toUnmodifiableSet());

  private final String importName;
  private final FunctionType type;

  /** A {@code null} result declares no return value. */
  HostImport(String importName, List<ValType> parameters, ValType result) {
    this.importName = importName;
    type = FunctionType.of(parameters, result == null ? List.of() : List.of(result));
  }

  public String importName() {
    return importName;
  }

  public FunctionType type() {
    return type;
  }
}
