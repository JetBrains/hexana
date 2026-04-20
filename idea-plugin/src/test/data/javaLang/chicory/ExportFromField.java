import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.ExportFunction;

public class ExportFromField {
    private Instance instance;

    void init() {
        WasmModule module = Parser.parse(ExportFromField.class.getResourceAsStream("simple.wasm"));
        instance = Instance.builder(module).build();
    }

    void use() {
        ExportFunction func = instance.export("<caret>");
    }
}
