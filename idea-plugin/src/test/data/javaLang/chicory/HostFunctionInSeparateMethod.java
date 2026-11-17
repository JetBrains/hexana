import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Store;
import java.util.List;

public class HostFunctionInSeparateMethod {
    HostFunction buildImport() {
        return new HostFunction(
            "<caret>",
            "imported_func",
            FunctionType.of(List.of(ValType.I32), List.of()),
            (inst, a) -> null
        );
    }

    void init() {
        WasmModule module = Parser.parse(HostFunctionInSeparateMethod.class.getResourceAsStream("simple.wasm"));
        Store store = new Store();
        store.addFunction(buildImport());
        Instance instance = store.instantiate("main", module);
    }
}
