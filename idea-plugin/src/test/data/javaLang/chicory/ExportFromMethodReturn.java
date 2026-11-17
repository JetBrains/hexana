import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.ExportFunction;

public class ExportFromMethodReturn {
    Instance createInstance() {
        WasmModule module = Parser.parse(ExportFromMethodReturn.class.getResourceAsStream("simple.wasm"));
        return Instance.builder(module).build();
    }

    void use() {
        Instance instance = createInstance();
        ExportFunction func = instance.export("<caret>");
    }
}
