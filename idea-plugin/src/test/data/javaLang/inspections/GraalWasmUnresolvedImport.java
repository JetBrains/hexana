import java.util.Map;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

public class GraalWasmUnresolvedImport {
    public static void main(String[] args) throws Exception {
        Source source = Source.newBuilder("wasm", GraalWasmUnresolvedImport.class.getResource("simple.wasm")).build();
        Context context = Context.newBuilder("wasm").build();
        Value module = context.eval(source);

        Value instance = module.newInstance(ProxyObject.fromMap(
            Map.of(
                // valid module name — no warning
                "my_namespace", ProxyObject.fromMap(
                    Map.of(
                        // valid function name — no warning
                        "imported_func", (ProxyExecutable) (a) -> null,
                        // invalid function name — should be highlighted
                        <warning descr="Import function 'bad_func' not found in module 'my_namespace'">"bad_func"</warning>, (ProxyExecutable) (a) -> null
                    )
                ),
                // invalid module name — should be highlighted
                <warning descr="Import module 'bad_module' not found in WebAssembly module">"bad_module"</warning>, ProxyObject.fromMap(Map.of())
            )
        ));
    }
}
