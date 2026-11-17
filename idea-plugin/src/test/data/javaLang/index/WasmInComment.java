import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

public class WasmInComment {
    // This loads "commented.wasm" but only in a comment, not real code
    public static void main(String[] args) throws Exception {
        Source source = Source.newBuilder("wasm", WasmInComment.class.getResource("real.wasm")).build();
        Context context = Context.newBuilder("wasm").build();
        Value module = context.eval(source);
    }
}
