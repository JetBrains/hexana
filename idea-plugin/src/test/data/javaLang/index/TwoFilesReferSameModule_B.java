import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.runtime.Instance;

public class TwoFilesReferSameModule_B {
    public static void main(String[] args) {
        WasmModule module = Parser.parse(TwoFilesReferSameModule_B.class.getResourceAsStream("simple.wasm"));
        Instance instance = Instance.builder(module).build();
    }
}
