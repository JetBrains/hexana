import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.ExportFunction;

public class NoWasmReference {
    public static void main(String[] args) {
        Instance instance = null;
        ExportFunction func = instance.export("<caret>");
    }
}
