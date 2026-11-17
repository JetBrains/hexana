package org.jetbrains.hexana.jit.jmh;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.openjdk.jmh.infra.BenchmarkParams;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@code -javaagent} that makes every <em>forked</em> JMH benchmark JVM attach
 * Hexana's libjitview JVMTI agent, writing one {@code ClassName_method.jit} dump
 * per benchmark.
 *
 * <p>Why a {@code -javaagent} and not a JMH {@code ExternalProfiler}: a profiler
 * is selected by {@code -prof}, which is a JMH <em>program</em> argument and is
 * only honoured when the run actually reaches {@code org.openjdk.jmh.Main}'s
 * parser. The JMH IDE-plugin gutter run and the Gradle JMH plugin both drive the
 * runner directly and never see {@code -prof}. A {@code -javaagent} is a JVM
 * option that is in effect for any JMH run type, and it never pollutes the
 * benchmark's own program arguments.
 *
 * <p>Mechanism: this agent's {@link #premain} installs a {@link ClassFileTransformer}
 * that ASM-rewrites {@code org.openjdk.jmh.runner.Runner.getForkedMainCommand(...)}.
 * That package-private method builds the {@code java ...} command line for each
 * forked benchmark JVM. We intercept its returned {@link List} and splice
 * {@code -agentpath:<libjitview>=<ClassName_method>.jit} in right after the java
 * executable, so libjitview attaches to the fork.
 *
 * <p>This instruments a JMH-internal method, so it is version-fragile: if JMH
 * renames or changes the signature of {@code getForkedMainCommand}, the transformer
 * matches nothing and the run proceeds unchanged. Every failure mode here is
 * designed to no-op safely and never break the benchmark.
 *
 * <p>Agent arguments: {@code agent=<libjitview path>[,dir=<dump output dir>]}.
 */
public final class HexanaJmhForkAgent {

    private static final String RUNNER_INTERNAL_NAME = "org/openjdk/jmh/runner/Runner";
    private static final String TARGET_METHOD_NAME = "getForkedMainCommand";
    private static final String TARGET_METHOD_DESC =
            "(Lorg/openjdk/jmh/infra/BenchmarkParams;Ljava/util/List;Ljava/lang/String;I)Ljava/util/List;";

    /** libjitview path, parsed from {@code agent=...}. Null/blank disables augmentation. */
    private static volatile String libjitviewPath;
    /** Optional output directory for the per-benchmark dumps, parsed from {@code dir=...}. */
    private static volatile String dumpDir;

    private HexanaJmhForkAgent() {
    }

    /** Static-attach entry point ({@code -javaagent:...=agent=...,dir=...}). */
    public static void premain(String agentArgs, Instrumentation inst) {
        install(agentArgs, inst);
    }

    /** Dynamic-attach entry point. Retransforms {@code Runner} if it is already loaded. */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        install(agentArgs, inst);
        if (inst.isRetransformClassesSupported()) {
            for (Class<?> c : inst.getAllLoadedClasses()) {
                if ("org.openjdk.jmh.runner.Runner".equals(c.getName())) {
                    try {
                        inst.retransformClasses(c);
                    } catch (Throwable t) {
                        System.err.println("[hexana-jmh] retransform of Runner failed: " + t);
                    }
                }
            }
        }
    }

    private static void install(String agentArgs, Instrumentation inst) {
        try {
            parseArgs(agentArgs);
            inst.addTransformer(new RunnerTransformer(), inst.isRetransformClassesSupported());
        } catch (Throwable t) {
            // Never let agent setup abort the host JVM.
            System.err.println("[hexana-jmh] agent install failed (benchmark runs unchanged): " + t);
        }
    }

    private static void parseArgs(String agentArgs) {
        String agent = null;
        String outDir = null;
        if (agentArgs != null && !agentArgs.isBlank()) {
            for (String tok : agentArgs.split(",")) {
                int eq = tok.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String k = tok.substring(0, eq).trim();
                String v = tok.substring(eq + 1).trim();
                if (k.equals("agent")) {
                    agent = v;
                } else if (k.equals("dir")) {
                    outDir = v;
                }
            }
        }
        libjitviewPath = agent;
        dumpDir = (outDir == null || outDir.isBlank()) ? null : outDir;
    }

    /**
     * Invoked from the rewritten {@code getForkedMainCommand} on each {@code ARETURN}.
     * Returns a new mutable command list with {@code -agentpath:...} spliced in after
     * the java executable. Falls back to the original list on any problem.
     */
    public static List<String> augment(List<String> cmd, BenchmarkParams params) {
        try {
            String lib = libjitviewPath;
            if (lib == null || lib.isBlank() || cmd == null) {
                return cmd;
            }
            String name = dumpFileName(params.getBenchmark());
            String dir = dumpDir;
            Path out = (dir != null) ? Path.of(dir).resolve(name) : Path.of(name);
            // The original list may be immutable (List.of / Arrays.asList); copy.
            List<String> augmented = new ArrayList<>(cmd);
            int idx = augmented.isEmpty() ? 0 : 1;
            augmented.add(idx, "-agentpath:" + lib + "=" + out.toAbsolutePath());
            return augmented;
        } catch (Throwable t) {
            System.err.println("[hexana-jmh] augment failed (fork command unchanged): " + t);
            return cmd;
        }
    }

    /**
     * Derives {@code SimpleClassName_method.jit} from a JMH fully-qualified
     * benchmark id ({@code pkg.OuterClass.method} — JMH joins the declaring class
     * and method with a dot).
     */
    static String dumpFileName(String fqn) {
        int d = fqn.lastIndexOf('.');
        String m = d >= 0 ? fqn.substring(d + 1) : fqn;
        String cf = d >= 0 ? fqn.substring(0, d) : "";
        int cd = cf.lastIndexOf('.');
        String sc = cd >= 0 ? cf.substring(cd + 1) : cf;
        if (sc.isEmpty()) {
            sc = "benchmark";
        }
        return sc + "_" + m + ".jit";
    }

    /**
     * Rewrites {@code Runner.getForkedMainCommand} so each {@code ARETURN} routes the
     * returned command list through {@link #augment}. Any failure leaves the class
     * untouched (returns {@code null} from {@link #transform}).
     */
    static final class RunnerTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (!RUNNER_INTERNAL_NAME.equals(className)) {
                return null;
            }
            try {
                ClassReader reader = new ClassReader(classfileBuffer);
                LoaderAwareClassWriter writer =
                        new LoaderAwareClassWriter(reader, ClassWriter.COMPUTE_FRAMES, loader);
                boolean[] hit = {false};
                ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (TARGET_METHOD_NAME.equals(name) && TARGET_METHOD_DESC.equals(descriptor)) {
                            hit[0] = true;
                            return new AugmentingMethodVisitor(mv);
                        }
                        return mv;
                    }
                };
                reader.accept(visitor, ClassReader.EXPAND_FRAMES);
                if (!hit[0]) {
                    System.err.println("[hexana-jmh] " + TARGET_METHOD_NAME
                            + " not found on Runner (JMH layout changed?); fork instrumentation disabled.");
                    return null;
                }
                return writer.toByteArray();
            } catch (Throwable t) {
                System.err.println("[hexana-jmh] Runner instrumentation failed (benchmark unchanged): " + t);
                return null;
            }
        }
    }

    /**
     * On each {@code ARETURN}: the returned {@code List} is on top of the stack.
     * Load local 1 ({@code BenchmarkParams}) and call {@link #augment}, leaving the
     * augmented list on the stack, then return it.
     */
    private static final class AugmentingMethodVisitor extends MethodVisitor {
        AugmentingMethodVisitor(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.ARETURN) {
                // stack: ..., List
                mv.visitVarInsn(Opcodes.ALOAD, 1); // BenchmarkParams (instance method: local 1)
                // stack: ..., List, BenchmarkParams
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/jetbrains/hexana/jit/jmh/HexanaJmhForkAgent",
                        "augment",
                        "(Ljava/util/List;Lorg/openjdk/jmh/infra/BenchmarkParams;)Ljava/util/List;",
                        false);
                // stack: ..., List
            }
            super.visitInsn(opcode);
        }
    }

    /**
     * {@link ClassWriter} whose {@code getCommonSuperClass} resolves types through the
     * class loader of the class being transformed, not the agent's own loader, so
     * {@code COMPUTE_FRAMES} can see JMH's own types.
     */
    private static final class LoaderAwareClassWriter extends ClassWriter {
        private final ClassLoader loader;

        LoaderAwareClassWriter(ClassReader reader, int flags, ClassLoader loader) {
            super(reader, flags);
            this.loader = (loader != null) ? loader : ClassLoader.getSystemClassLoader();
        }

        @Override
        protected ClassLoader getClassLoader() {
            return loader;
        }
    }
}
