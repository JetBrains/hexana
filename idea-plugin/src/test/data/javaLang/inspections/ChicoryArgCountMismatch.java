import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.ExportFunction;

public class ChicoryArgCountMismatch {
    public static void main(String[] args) {
        WasmModule module = Parser.parse(ChicoryArgCountMismatch.class.getResourceAsStream("add.wasm"));
        Instance instance = Instance.builder(module).build();

        // add(i32, i32) -> i32: correct call with 2 args
        ExportFunction addFunc = instance.export("add");
        addFunc.apply(1L, 2L);

        // too few arguments
        addFunc.apply();

        // too many arguments
        addFunc.apply(1L, 2L, 3L);
    }
}
