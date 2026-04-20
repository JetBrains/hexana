import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

public class GraalWasmUnresolvedExport {
    public static void main(String[] args) throws Exception {
        Source source = Source.newBuilder("wasm", GraalWasmUnresolvedExport.class.getResource("simple.wasm")).build();
        Context context = Context.newBuilder("wasm").build();
        Value module = context.eval(source);
        Value instance = module.newInstance();
        Value exports = instance.getMember("exports");

        // valid export — no warning
        exports.invokeMember("exported_func");

        // invalid export — should be highlighted
        exports.invokeMember("nonexistent_func");

        // getMember with invalid name — should be highlighted
        exports.getMember("bad_export");

        // hasMember is a check, not an invocation — no warning
        exports.hasMember("nonexistent_func");
    }
}
