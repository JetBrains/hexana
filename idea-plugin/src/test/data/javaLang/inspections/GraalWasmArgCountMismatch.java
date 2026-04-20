import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

public class GraalWasmArgCountMismatch {
    public static void main(String[] args) throws Exception {
        Source source = Source.newBuilder("wasm", GraalWasmArgCountMismatch.class.getResource("add.wasm")).build();
        Context context = Context.newBuilder("wasm").build();
        Value module = context.eval(source);
        Value instance = module.newInstance();
        Value exports = instance.getMember("exports");

        // add(i32, i32) -> i32: correct call with 2 args
        exports.invokeMember("add", 1, 2);

        // too few arguments
        exports.invokeMember(<warning descr="Function 'add' expects 2 argument(s), but 0 provided">"add"</warning>);

        // too many arguments
        exports.invokeMember(<warning descr="Function 'add' expects 2 argument(s), but 3 provided">"add"</warning>, 1, 2, 3);
    }
}
