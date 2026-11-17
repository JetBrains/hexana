import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.runtime.Instance;
import java.io.File;

public class ChicoryFileConstructor {
    public static void main(String[] args) {
        WasmModule module = Parser.parse(new File("add.wasm"));
        Instance instance = Instance.builder(module).build();
    }
}
