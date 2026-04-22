import java.io.IOException;
import java.net.URL;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

public class ImportFunctionNameInTryBlock {
    public static Object javaIncrement(Value... v) {
        return Value.asValue(v[0].asInt() + 1);
    }

    public static void main(String[] args) throws IOException {
        URL wasmFile = ImportFunctionNameInTryBlock.class.getResource("simple.wasm");
        Source source = Source.newBuilder("wasm", wasmFile).name("example").build();

        try (Context context = Context.newBuilder("wasm")
                .option("wasm.Builtins", "wasi_snapshot_preview1")
                .build()) {
            Value module = context.eval(source);
            Value instance = module.newInstance(ProxyObject.fromMap(
                Map.of("my_namespace", ProxyObject.fromMap(
                        Map.of("<caret>", (ProxyExecutable) ImportFunctionNameInTryBlock::javaIncrement)
                ))
            ));
        }
    }
}
