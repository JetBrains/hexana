import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;

public class MultipleScopedModulesSimple {

    static void runAdd() {
        WasmModule addModule = Parser.parse(
            MultipleScopedModulesSimple.class.getResourceAsStream("add.wasm"));
        Instance addInstance = Instance.builder(addModule).build();
        addInstance.export("add").apply(1L, 2L);
    }

    static void runSimple() {
        WasmModule simpleModule = Parser.parse(
            MultipleScopedModulesSimple.class.getResourceAsStream("simple.wasm"));
        Instance simpleInstance = Instance.builder(simpleModule).build();
        simpleInstance.export("<caret>").apply();
    }
}
