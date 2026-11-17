package org.jetbrains.hexana.runner;

import com.dylibso.chicory.runtime.ByteBufferMemory;
import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.GlobalInstance;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportGlobal;
import com.dylibso.chicory.runtime.ImportMemory;
import com.dylibso.chicory.runtime.ImportTable;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.TableInstance;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.FunctionImport;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.GlobalImport;
import com.dylibso.chicory.wasm.types.Import;
import com.dylibso.chicory.wasm.types.MemoryImport;
import com.dylibso.chicory.wasm.types.Table;
import com.dylibso.chicory.wasm.types.TableImport;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Thin Chicory launcher for running WebAssembly modules from the
 * Hexana IntelliJ plugin.
 * <p>
 * Chicory is a pure-Java WebAssembly runtime that does not ship a
 * standalone CLI executable. This runner is bundled as a plugin
 * resource, compiled on first use with {@code javac} from the
 * configured (or IntelliJ-bundled) JDK, and executed via
 * {@code java -cp}.
 * <p>
 * Usage:
 * <pre>
 *   HexanaChicoryRunner [options] &lt;file.wasm&gt; [args...]
 * </pre>
 * <p>
 * Supported options:
 * <ul>
 *   <li>{@code --entry-point <name>} — export function to invoke
 *       (defaults to {@code _start})</li>
 *   <li>{@code --aot} — use the AOT/compiler backend instead of
 *       the interpreter</li>
 * </ul>
 */
public final class HexanaChicoryRunner {

    public static void main(String[] args) throws Exception {
        String entryPoint = null;
        boolean aot = false;
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
            } else if ("--aot".equals(arg)) {
                aot = true;
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
            System.err.println("Usage: HexanaChicoryRunner [--aot] [--entry-point <name>] <file.wasm> [args...]");
            System.exit(1);
            return;
        }

        File wasmFile = new File(wasmFilePath);
        if (!wasmFile.exists()) {
            System.err.println("Error: Wasm file not found: " + wasmFilePath);
            System.exit(1);
            return;
        }

        // ── Parse module ──────────────────────────────────────────
        WasmModule module = Parser.parse(wasmFile);

        // ── Set up WASI ───────────────────────────────────────────
        List<String> wasiArgs = new ArrayList<>();
        wasiArgs.add(wasmFile.getName());
        wasiArgs.addAll(programArgs);

        WasiOptions wasiOpts = WasiOptions.builder()
                .withStdout(System.out)
                .withStderr(System.err)
                .withStdin(System.in)
                .withArguments(wasiArgs)
                .build();

        try (WasiPreview1 wasi = WasiPreview1.builder()
                .withOptions(wasiOpts)
                .build()) {

            // ── Build import stubs for unsatisfied imports ────────
            HostFunction[] wasiHostFuncs = wasi.toHostFunctions();
            Set<String> satisfiedFunctions = new HashSet<>();
            for (HostFunction hf : wasiHostFuncs) {
                satisfiedFunctions.add(hf.module() + "\0" + hf.name());
            }

            List<HostFunction> stubFunctions = new ArrayList<>();
            List<ImportMemory> stubMemories = new ArrayList<>();
            List<ImportGlobal> stubGlobals = new ArrayList<>();
            List<ImportTable> stubTables = new ArrayList<>();

            int importCount = module.importSection().importCount();
            for (int idx = 0; idx < importCount; idx++) {
                Import imp = module.importSection().getImport(idx);
                String key = imp.module() + "\0" + imp.name();

                if (imp instanceof FunctionImport) {
                    if (!satisfiedFunctions.contains(key)) {
                        FunctionImport fi = (FunctionImport) imp;
                        FunctionType ft = module.typeSection().getType(fi.typeIndex());
                        int returnCount = ft.returns().size();
                        stubFunctions.add(new HostFunction(
                                fi.module(), fi.name(), ft,
                                (inst, _args) -> new long[returnCount]));
                    }
                } else if (imp instanceof MemoryImport) {
                    MemoryImport mi = (MemoryImport) imp;
                    stubMemories.add(new ImportMemory(
                            mi.module(), mi.name(),
                            new ByteBufferMemory(mi.limits())));
                } else if (imp instanceof GlobalImport) {
                    GlobalImport gi = (GlobalImport) imp;
                    stubGlobals.add(new ImportGlobal(
                            gi.module(), gi.name(),
                            new GlobalInstance(0L, 0L, gi.type(), gi.mutabilityType())));
                } else if (imp instanceof TableImport) {
                    TableImport ti = (TableImport) imp;
                    stubTables.add(new ImportTable(
                            ti.module(), ti.name(),
                            new TableInstance(
                                    new Table(ti.entryType(), ti.limits()), 0)));
                }
            }

            ImportValues.Builder ivBuilder = ImportValues.builder()
                    .addFunction(wasiHostFuncs);
            if (!stubFunctions.isEmpty()) {
                ivBuilder.addFunction(stubFunctions.toArray(new HostFunction[0]));
            }
            if (!stubMemories.isEmpty()) {
                ivBuilder.addMemory(stubMemories.toArray(new ImportMemory[0]));
            }
            if (!stubGlobals.isEmpty()) {
                ivBuilder.addGlobal(stubGlobals.toArray(new ImportGlobal[0]));
            }
            if (!stubTables.isEmpty()) {
                ivBuilder.addTable(stubTables.toArray(new ImportTable[0]));
            }
            ImportValues importValues = ivBuilder.build();

            // ── Build instance ────────────────────────────────────
            Instance.Builder builder = Instance.builder(module)
                    .withImportValues(importValues);

            if (aot) {
                // Use the compiler backend (AOT / Redline)
                var machineFactory =
                        com.dylibso.chicory.compiler.MachineFactoryCompiler.compile(module);
                builder.withMachineFactory(machineFactory);
            }

            Instance instance = builder.build();

            // ── Invoke ────────────────────────────────────────────
            if (entryPoint != null) {
                ExportFunction func = instance.export(entryPoint);
                long[] typedArgs = parseArgs(programArgs);
                long[] result = func.apply(typedArgs);
                if (result != null && result.length > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int k = 0; k < result.length; k++) {
                        if (k > 0) sb.append(" ");
                        sb.append(result[k]);
                    }
                    System.out.println(sb);
                }
            }
            // If no entry point specified, Instance.builder().build()
            // automatically invokes _start if present.
        }
    }

    /**
     * Attempts to parse string arguments as raw {@code long} values
     * suitable for {@link ExportFunction#apply(long...)}.
     * <p>
     * Chicory 1.x represents all Wasm values as raw {@code long}s:
     * i32 values are zero-extended, f32/f64 use raw bit patterns.
     */
    private static long[] parseArgs(List<String> args) {
        if (args.isEmpty()) return new long[0];
        long[] result = new long[args.size()];
        for (int i = 0; i < args.size(); i++) {
            result[i] = parseValue(args.get(i));
        }
        return result;
    }

    private static long parseValue(String arg) {
        // Try integer (i32 — zero-extended to long)
        try {
            return Integer.parseInt(arg) & 0xFFFFFFFFL;
        } catch (NumberFormatException ignored) {}
        // Try long (i64)
        try {
            return Long.parseLong(arg);
        } catch (NumberFormatException ignored) {}
        // Try float/double (raw bit pattern)
        try {
            double d = Double.parseDouble(arg);
            if (arg.contains(".") || arg.contains("e") || arg.contains("E")) {
                return Double.doubleToRawLongBits(d);
            }
            return (long) d;
        } catch (NumberFormatException ignored) {}
        // Fall back to 0 for unparseable args
        return 0L;
    }
}
