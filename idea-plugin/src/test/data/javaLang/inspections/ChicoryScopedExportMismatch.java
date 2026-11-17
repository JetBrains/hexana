import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;

/**
 * Same file uses two different .wasm modules. Each export call should be
 * validated against the module fed into THAT instance, not the file-level
 * "first wasm reference" — so:
 *  - addInstance.export("exported_func") flags (exported_func is in simple.wasm only)
 *  - simpleInstance.export("add")        flags (add is in add.wasm only)
 *  - addInstance.export("add")           OK
 *  - simpleInstance.export("exported_func") OK
 */
public class ChicoryScopedExportMismatch {

    static void runAdd() {
        WasmModule addModule = Parser.parse(
            ChicoryScopedExportMismatch.class.getResourceAsStream("add.wasm"));
        Instance addInstance = Instance.builder(addModule).build();

        addInstance.export("add");              // OK — add.wasm exports `add`
        addInstance.export("exported_func");    // FLAG — that's a simple.wasm export
    }

    static void runSimple() {
        WasmModule simpleModule = Parser.parse(
            ChicoryScopedExportMismatch.class.getResourceAsStream("simple.wasm"));
        Instance simpleInstance = Instance.builder(simpleModule).build();

        simpleInstance.export("exported_func"); // OK — simple.wasm exports `exported_func`
        simpleInstance.export("add");           // FLAG — that's an add.wasm export
    }
}
