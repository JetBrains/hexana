package org.jetbrains.hexana.runner;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin GraalWasm launcher that replaces the deprecated {@code wasm}
 * binary removed from GraalVM JDK distributions.
 * <p>
 * Accepts the same CLI interface as the old launcher:
 * <pre>
 *   HexanaGraalWasmRunner [options] &lt;file.wasm&gt; [args...]
 * </pre>
 * <p>
 * Supported options:
 * <ul>
 *   <li>{@code --entry-point <name>} — export function to invoke</li>
 *   <li>{@code --experimental-options} — allow experimental engine options</li>
 *   <li>{@code --wasm.<Key>=<Value>} — GraalWasm context option</li>
 * </ul>
 * <p>
 * When no {@code --entry-point} is specified the module's {@code _start}
 * export is invoked (WASI convention).
 * <p>
 * This file is bundled as a plugin resource and compiled on-the-fly
 * by the Hexana IntelliJ plugin when launching an external GraalVM
 * process.
 */
public final class HexanaGraalWasmRunner {

    public static void main(String[] args) throws Exception {
        String entryPoint = null;
        boolean experimentalOptions = false;
        Map<String, String> wasmOptions = new LinkedHashMap<>();
        String wasmFilePath = null;
        List<String> programArgs = new ArrayList<>();

        // ── Parse arguments ───────────────────────────────────────
        int i = 0;
        while (i < args.length) {
            String arg = args[i];
            if ("--entry-point".equals(arg) && i + 1 < args.length) {
                entryPoint = args[++i];
            } else if (arg.startsWith("--entry-point=")) {
                entryPoint = arg.substring("--entry-point=".length());
            } else if ("--experimental-options".equals(arg)) {
                experimentalOptions = true;
            } else if (arg.startsWith("--wasm.")) {
                int eq = arg.indexOf('=');
                if (eq > 0) {
                    // Strip leading "--" → "wasm.Key"
                    String key = arg.substring(2, eq);
                    String value = arg.substring(eq + 1);
                    wasmOptions.put(key, value);
                }
            } else if (arg.startsWith("--")) {
                // Unknown option – skip
            } else {
                // First non-option is the wasm file
                wasmFilePath = arg;
                // Everything after is program arguments
                for (int j = i + 1; j < args.length; j++) {
                    programArgs.add(args[j]);
                }
                break;
            }
            i++;
        }

        if (wasmFilePath == null) {
            System.err.println("Usage: HexanaGraalWasmRunner [options] <file.wasm> [args...]");
            System.exit(1);
            return;
        }

        File wasmFile = new File(wasmFilePath);
        if (!wasmFile.exists()) {
            System.err.println("Error: Wasm file not found: " + wasmFilePath);
            System.exit(1);
            return;
        }

        // ── Build context ─────────────────────────────────────────
        Context.Builder builder = Context.newBuilder("wasm")
                .option("wasm.Builtins", "wasi_snapshot_preview1")
                .option("engine.WarnInterpreterOnly", "false")
                .out(System.out)
                .err(System.err);

        if (experimentalOptions) {
            builder.allowExperimentalOptions(true);
        }
        for (Map.Entry<String, String> entry : wasmOptions.entrySet()) {
            builder.option(entry.getKey(), entry.getValue());
        }
        if (entryPoint == null) {
            builder.arguments("wasm", programArgs.toArray(new String[0]));
        }

        // ── Run ───────────────────────────────────────────────────
        try (Context context = builder.build()) {
            Source source = Source.newBuilder("wasm", wasmFile)
                    .name(wasmFile.getName().replaceFirst("\\.wasm$", ""))
                    .build();

            Value module = context.eval(source);
            Value instance = module.newInstance();
            Value exports = instance.getMember("exports");

            if (entryPoint != null) {
                Value func = exports.getMember(entryPoint);
                if (func == null || !func.canExecute()) {
                    System.err.println("Error: Export '" + entryPoint + "' not found or not callable");
                    System.exit(1);
                    return;
                }
                // Pass program args as strings — GraalWasm coerces
                // them to the declared parameter types.
                Object[] typedArgs = new Object[programArgs.size()];
                for (int k = 0; k < programArgs.size(); k++) {
                    typedArgs[k] = tryParseNumeric(programArgs.get(k));
                }
                Value result = func.execute(typedArgs);
                if (result != null && !result.isNull()) {
                    System.out.println(result.toString());
                }
            } else {
                Value startFunc = exports.getMember("_start");
                if (startFunc != null && startFunc.canExecute()) {
                    startFunc.execute();
                }
            }
        }
    }

    /**
     * Attempts to parse a string argument as a numeric type
     * (int, long, float, double) for passing to Wasm exports.
     */
    private static Object tryParseNumeric(String arg) {
        // Try integer first
        try {
            return Integer.parseInt(arg);
        } catch (NumberFormatException ignored) {
        }
        // Try long
        try {
            return Long.parseLong(arg);
        } catch (NumberFormatException ignored) {
        }
        // Try double (covers both float and double ranges)
        try {
            return Double.parseDouble(arg);
        } catch (NumberFormatException ignored) {
        }
        return arg;
    }
}
