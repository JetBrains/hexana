import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

public class MultipleModules {
    public void loadFirst() throws Exception {
        Source source = Source.newBuilder("wasm", MultipleModules.class.getResource("simple.wasm")).build();
        Context context = Context.newBuilder("wasm").build();
        Value module = context.eval(source);
    }

    public void loadSecond() throws Exception {
        Source source = Source.newBuilder("wasm", MultipleModules.class.getResource("add.wasm")).build();
        Context context = Context.newBuilder("wasm").build();
        Value module = context.eval(source);
    }
}
