// -----------------------------------------------------------------------------
// jvm-jit-jmh — carries a -javaagent (HexanaJmhForkAgent) that instruments JMH's
// fork-command builder.
//
// JMH forks every benchmark into a fresh JVM via ProcessBuilder, so a JVMTI agent
// attached to the JMH *launcher* never reaches the forks. A JMH ExternalProfiler
// (-prof) could rewrite a fork's command line, but -prof is a JMH *program*
// argument that only takes effect when the run reaches org.openjdk.jmh.Main's
// parser — the IDE-plugin gutter run and the Gradle JMH plugin both drive the
// runner directly and never see it. So instead this module is a JVM-option
// -javaagent: its premain ASM-instruments org.openjdk.jmh.runner.Runner's
// package-private getForkedMainCommand(...) and splices
// -agentpath:<libjitview>=<ClassName_method>.jit into each forked java command.
// That works for any JMH run type and never pollutes the benchmark's app args.
//
// jmh-core is `compileOnly`: only BenchmarkParams is referenced for typing, and
// JMH is on the launcher JVM's classpath at runtime. ASM, however, is NOT on the
// launcher's classpath, so it must be bundled into the agent jar — see the SHADED
// nested jar (jmh/jitview-jmh.jar) below.
// -----------------------------------------------------------------------------

plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// ASM is bundled into the agent jar (the launcher JVM has JMH + the benchmark on
// its classpath, but NOT ASM). A dedicated configuration lets us unpack it into
// the shaded nested jar without leaking it onto this module's own classpath users.
val agentShade: Configuration by configurations.creating

dependencies {
    // Provided by the benchmark/launcher module at runtime; never bundled.
    compileOnly("org.openjdk.jmh:jmh-core:1.37")
    // Visitor + tree-free instrumentation. Bundled into the agent jar (agentShade)
    // and also visible at compile time.
    compileOnly("org.ow2.asm:asm:9.7.1")
    agentShade("org.ow2.asm:asm:9.7.1")
}

tasks.named<JavaCompile>("compileJava") {
    sourceCompatibility = "21"
    targetCompatibility = "21"
}

// -----------------------------------------------------------------------------
// Bundle the agent as a SHADED nested jar (jmh/jitview-jmh.jar) inside THIS
// module's own resources — exactly the way :jvm-jit ships its libjitview native
// slice as a jvmMain resource (jvm-jit/build.gradle.kts: bundleJitviewAgent +
// resources.srcDir). idea-plugin receives it transitively via
// implementation(project(":jvm-jit-jmh")); JitviewJmhLocator extracts
// /jmh/jitview-jmh.jar and passes it as the -javaagent path.
//
// The nested jar is a JVMTI-style agent jar: its manifest declares Premain-Class /
// Agent-Class / Can-Retransform-Classes, and it bundles ASM (org/objectweb/asm/…)
// because the launcher JVM's classpath does not provide it. A dedicated Jar task
// (not this module's own `jar`) builds it from the compiled agent classes + the
// unpacked ASM jar, so it never reads the generated resources dir — avoiding a
// self-jar cycle.
// -----------------------------------------------------------------------------
val profilerResourcesDir = layout.buildDirectory.dir("generated/jitview-jmh-resources")

val profilerJar by tasks.registering(Jar::class) {
    description = "Packages the -javaagent (agent classes + ASM, agent manifest) into " +
        "jmh/jitview-jmh.jar for bundling as a resource."
    archiveFileName.set("jitview-jmh.jar")
    destinationDirectory.set(profilerResourcesDir.map { it.dir("jmh") })

    // Agent classes.
    from(tasks.named<JavaCompile>("compileJava").map { it.destinationDirectory })
    // Bundle ASM (unpacked) so the launcher JVM — which has JMH but not ASM —
    // can load the transformer. zipTree unpacks each asm-*.jar into class files.
    from(agentShade.elements.map { jars -> jars.map { zipTree(it.asFile) } }) {
        // Drop the upstream module-info / signatures so the merged jar stays a
        // plain agent jar without conflicting module descriptors.
        exclude("META-INF/maven/**", "module-info.class", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }

    manifest {
        attributes(
            "Premain-Class" to "org.jetbrains.hexana.jit.jmh.HexanaJmhForkAgent",
            "Agent-Class" to "org.jetbrains.hexana.jit.jmh.HexanaJmhForkAgent",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "true",
        )
    }
}

sourceSets {
    main {
        resources.srcDir(profilerResourcesDir)
    }
}

tasks.named("processResources") {
    dependsOn(profilerJar)
}
