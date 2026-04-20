import java.util.Map;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

public class ImportFunctionName {
    public static void main(String[] args) throws Exception {
        Source source = Source.newBuilder("wasm", ImportFunctionName.class.getResource("simple.wasm")).build();
        Context context = Context.newBuilder("wasm").build();
        Value module = context.eval(source);
        Value instance = module.newInstance(ProxyObject.fromMap(
            Map.of("my_namespace", ProxyObject.fromMap(
                Map.of("<caret>")
            ))
        ));
    }
}
