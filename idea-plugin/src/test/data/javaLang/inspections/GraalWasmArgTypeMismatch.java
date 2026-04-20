import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

public class GraalWasmArgTypeMismatch {
    public static void main(String[] args) throws Exception {
        Source source = Source.newBuilder("wasm", GraalWasmArgTypeMismatch.class.getResource("add.wasm")).build();
        Context context = Context.newBuilder("wasm").build();
        Value module = context.eval(source);
        Value instance = module.newInstance();
        Value exports = instance.getMember("exports");

        // add(i32, i32) -> i32

        // correct: int args for i32 params
        exports.invokeMember("add", 1, 2);

        // wrong: String arg for i32 param
        exports.invokeMember("add", "hello", 2);

        // wrong: double arg for i32 param
        exports.invokeMember("add", 1.0, 2);

        // wrong: float arg for i32 param
        exports.invokeMember("add", 1.0f, 2);

        // wrong: long arg for i32 param
        exports.invokeMember("add", 1L, 2);

        // correct: short and byte are compatible with i32
        exports.invokeMember("add", (short) 1, (byte) 2);
    }
}
