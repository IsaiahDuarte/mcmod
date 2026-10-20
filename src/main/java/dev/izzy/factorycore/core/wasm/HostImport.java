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
