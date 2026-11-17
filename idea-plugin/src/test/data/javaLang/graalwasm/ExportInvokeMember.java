import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

public class ExportInvokeMember {
    public static void main(String[] args) throws Exception {
        Source source = Source.newBuilder("wasm", ExportInvokeMember.class.getResource("simple.wasm")).build();
        Context context = Context.newBuilder("wasm").build();
        Value module = context.eval(source);
        Value instance = module.newInstance();
        Value exports = instance.getMember("exports");
        exports.invokeMember("<caret>");
    }
}
