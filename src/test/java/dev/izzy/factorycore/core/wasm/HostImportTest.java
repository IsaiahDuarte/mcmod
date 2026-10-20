package dev.izzy.factorycore.core.wasm;

import static org.junit.jupiter.api.Assertions.*;

import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The Rust SDK, the preflight allow-list and the runtime bindings share one host ABI. */
class HostImportTest {
  private static final Pattern FUNCTION =
      Pattern.compile("pub fn (\\w+)\\(([^)]*)\\)\\s*(?:->\\s*(\\w+))?;");

  @Test
  void rustSdkDeclaresExactlyTheHostImports() throws Exception {
    var source = Files.readString(Path.of(System.getProperty("factorycore.test.sdk")));
    int start = source.indexOf("#[link(wasm_import_module = \"" + WasmPreflight.MODULE + "\")]");
    assertTrue(start >= 0, "factory_v1 extern block");
    var block = source.substring(start, source.indexOf("\n    }\n", start));
    var rust = new TreeMap<String, FunctionType>();
    var matcher = FUNCTION.matcher(block);
    while (matcher.find()) {
      var parameters = new ArrayList<ValType>();
      for (var parameter : matcher.group(2).split(",")) {
        if (parameter.isBlank()) continue;
        parameters.add(type(parameter.substring(parameter.indexOf(':') + 1).trim()));
      }
      var result = matcher.group(3) == null ? List.<ValType>of() : List.of(type(matcher.group(3)));
      rust.put(matcher.group(1), FunctionType.of(parameters, result));
    }
    Map<String, FunctionType> host =
        Arrays.stream(HostImport.values())
            .collect(
                Collectors.toMap(
                    HostImport::importName, HostImport::type, (a, b) -> a, TreeMap::new));
    assertEquals(host, rust);
  }

  @Test
  void preflightAdmitsOnlyHostImports() {
    assertEquals(HostImport.NAMES, WasmPreflight.IMPORTS);
    assertEquals(HostImport.values().length, HostImport.NAMES.size(), "unique import names");
  }

  /** wasm32 lowers pointers and i32 to I32, i64 to I64. */
  private static ValType type(String rust) {
    if (rust.equals("i64")) return ValType.I64;
    if (rust.equals("i32") || rust.startsWith("*")) return ValType.I32;
    throw new IllegalArgumentException("unmapped Rust type " + rust);
  }
}
