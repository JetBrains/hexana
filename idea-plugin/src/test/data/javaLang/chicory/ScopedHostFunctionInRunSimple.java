import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;

import java.util.List;

/**
 * Two methods, two modules. add.wasm has zero imports; simple.wasm imports
 * my_namespace.imported_func(i32). The HostFunction is built inside runSimple,
 * so its imported-function-name completion must come from simple.wasm — not
 * from add.wasm (which the file-level lookup might pick instead).
 */
public class ScopedHostFunctionInRunSimple {

    static void runAdd() {
        WasmModule addModule = Parser.parse(
            ScopedHostFunctionInRunSimple.class.getResourceAsStream("add.wasm"));
        Instance addInstance = Instance.builder(addModule).build();
        addInstance.export("add").apply(1L, 2L);
    }

    static void runSimple() {
        WasmModule module = Parser.parse(
            ScopedHostFunctionInRunSimple.class.getResourceAsStream("simple.wasm"));

        HostFunction func = new HostFunction(
            "my_namespace",
            "<caret>",
            FunctionType.of(List.of(ValType.I32), List.of()),
            (inst, params) -> null);

        Instance instance = Instance.builder(module)
            .withImportValues(ImportValues.builder().addFunction(func).build())
            .build();

        instance.export("exported_func").apply();
    }
}
