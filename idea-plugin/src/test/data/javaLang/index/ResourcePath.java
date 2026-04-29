import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

public class ResourcePath {
    public static void main(String[] args) throws Exception {
        Source source = Source.newBuilder("wasm", ResourcePath.class.getResource("modules/floyd.wasm")).build();
        Context context = Context.newBuilder("wasm").build();
        Value module = context.eval(source);
    }
}
