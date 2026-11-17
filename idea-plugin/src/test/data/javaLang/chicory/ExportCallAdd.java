import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.ExportFunction;

public class ExportCallAdd {
    public static void main(String[] args) {
        WasmModule module = Parser.parse(ExportCallAdd.class.getResourceAsStream("add.wasm"));
        Instance instance = Instance.builder(module).build();
        ExportFunction func = instance.export("<caret>");
    }
}
