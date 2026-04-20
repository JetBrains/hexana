import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

public class ExportFromField {
    private Value exports;

    void init() throws Exception {
        Source source = Source.newBuilder("wasm", ExportFromField.class.getResource("simple.wasm")).build();
        Context context = Context.newBuilder("wasm").build();
        Value module = context.eval(source);
        Value instance = module.newInstance();
        exports = instance.getMember("exports");
    }

    void use() {
        exports.invokeMember("<caret>");
    }
}
