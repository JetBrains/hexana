import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

public class NoWasmReference {
    public static void main(String[] args) throws Exception {
        Source source = Source.newBuilder("js", "console.log('hello')").buildLiteral();
        Context context = Context.newBuilder().build();
        Value module = context.eval(source);
        Value exports = module.getMember("exports");
        exports.invokeMember("<caret>");
    }
}
