import java.util.Map;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyObject;

public class ImportMapPassedToMethod {
    void initModule(ProxyObject imports) throws Exception {
        Source source = Source.newBuilder("wasm", ImportMapPassedToMethod.class.getResource("simple.wasm")).build();
        Context context = Context.newBuilder("wasm").build();
        Value module = context.eval(source);
        Value instance = module.newInstance(imports);
    }

    void caller() throws Exception {
        initModule(ProxyObject.fromMap(
            Map.of("<caret>")
        ));
    }
}
