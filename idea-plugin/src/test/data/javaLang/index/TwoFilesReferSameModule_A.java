import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

public class TwoFilesReferSameModule_A {
    public static void main(String[] args) throws Exception {
        Source source = Source.newBuilder("wasm", TwoFilesReferSameModule_A.class.getResource("simple.wasm")).build();
        Context context = Context.newBuilder("wasm").build();
        Value module = context.eval(source);
    }
}
