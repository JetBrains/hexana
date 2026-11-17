import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

public class ExportFromMethodReturn {
    Value getExports() throws Exception {
        Source source = Source.newBuilder("wasm", ExportFromMethodReturn.class.getResource("simple.wasm")).build();
        Context context = Context.newBuilder("wasm").build();
        Value module = context.eval(source);
        Value instance = module.newInstance();
        return instance.getMember("exports");
    }

    void use() throws Exception {
        Value exports = getExports();
        exports.invokeMember("<caret>");
    }
}
