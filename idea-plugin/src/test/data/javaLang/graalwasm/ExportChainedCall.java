import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

public class ExportChainedCall {
    public static void main(String[] args) throws Exception {
        Source source = Source.newBuilder("wasm", ExportChainedCall.class.getResource("simple.wasm")).build();
        Context context = Context.newBuilder("wasm").build();
        Value module = context.eval(source);
        Value instance = module.newInstance();
        // Chained: no intermediate variable for exports
        instance.getMember("exports").invokeMember("<caret>");
    }
}
