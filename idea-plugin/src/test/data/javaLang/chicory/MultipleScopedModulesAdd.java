import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;

public class MultipleScopedModulesAdd {

    static void runAdd() {
        WasmModule addModule = Parser.parse(
            MultipleScopedModulesAdd.class.getResourceAsStream("add.wasm"));
        Instance addInstance = Instance.builder(addModule).build();
        addInstance.export("<caret>").apply(1L, 2L);
    }

    static void runSimple() {
        WasmModule simpleModule = Parser.parse(
            MultipleScopedModulesAdd.class.getResourceAsStream("simple.wasm"));
        Instance simpleInstance = Instance.builder(simpleModule).build();
        simpleInstance.export("exported_func").apply();
    }
}
