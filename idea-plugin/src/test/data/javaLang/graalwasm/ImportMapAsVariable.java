import java.util.Map;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

public class ImportMapAsVariable {
    public static void main(String[] args) throws Exception {
        Source source = Source.newBuilder("wasm", ImportMapAsVariable.class.getResource("simple.wasm")).build();
        Context context = Context.newBuilder("wasm").build();
        Value module = context.eval(source);

        ProxyObject envImports = ProxyObject.fromMap(
            Map.of("<caret>")
        );

        ProxyObject allImports = ProxyObject.fromMap(
            Map.of("my_namespace", envImports)
        );

        Value instance = module.newInstance(allImports);
    }
}
