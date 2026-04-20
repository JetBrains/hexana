import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.ExportFunction;

public class ChicoryUnresolvedExport {
    public static void main(String[] args) {
        WasmModule module = Parser.parse(ChicoryUnresolvedExport.class.getResourceAsStream("simple.wasm"));
        Instance instance = Instance.builder(module).build();

        // valid export — no warning
        ExportFunction validFunc = instance.export("exported_func");

        // invalid export — should be highlighted
        ExportFunction badFunc = instance.export(<warning descr="Export 'nonexistent_func' not found in WebAssembly module">"nonexistent_func"</warning>);
    }
}
