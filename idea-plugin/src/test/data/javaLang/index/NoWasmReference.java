import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

public class NoWasmReference {
    public static void main(String[] args) throws Exception {
        Source source = Source.newBuilder("js", "console.log('hello')").buildLiteral();
        Context context = Context.newBuilder().build();
        Value result = context.eval(source);
    }
}
