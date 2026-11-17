import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Store;
import java.util.List;

public class HostFunctionImportFuncName {
    public static void main(String[] args) {
        WasmModule module = Parser.parse(HostFunctionImportFuncName.class.getResourceAsStream("simple.wasm"));

        var func = new HostFunction(
            "my_namespace",
            "<caret>",
            FunctionType.of(List.of(ValType.I32), List.of()),
            (inst, a) -> null
        );

        Store store = new Store();
        store.addFunction(func);
        Instance instance = store.instantiate("main", module);
    }
}
