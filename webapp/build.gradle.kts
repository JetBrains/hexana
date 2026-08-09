import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    val composeRuntimeForCompiler = compose.runtime

    js(IR) {
        val parserWorker = compilations.create("parserWorker") {
            defaultSourceSet {
                kotlin.srcDir("src/parserWorkerMain/kotlin")
                kotlin.srcDir("src/parserWorkerShared/kotlin")
                dependencies {
                    implementation(project(":wasmParser"))
                    implementation(project(":encdec"))
                    implementation(project(":binaryProvider"))
                    implementation(project(":plugins-shared"))
                    implementation(libs.kotlinx.coroutines.core)
                    compileOnly(composeRuntimeForCompiler)
                }
            }
        }

        browser {
            commonWebpackConfig {
                outputFileName = "webapp.js"
                sourceMaps = false
            }
            testTask {
                useKarma {
                    useChrome()
                    useConfigDirectory(project.projectDir.resolve("karma.config.d/web"))
                }
            }
        }
        binaries.executable()
        parserWorker.binaries.executable(parserWorker)
    }

    sourceSets {
        jsMain {
            kotlin.srcDir("src/parserWorkerShared/kotlin")
            dependencies {
                implementation(project(":web-ui"))
            }
        }

        jsTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.browser)
            }
        }
    }
}

val parserWorkerCompileSync = tasks.named("jsParserWorkerParserWorkerProductionExecutableCompileSync")
val parserWorkerOutputDir = layout.buildDirectory.dir("parser-worker/production")
val parserWorkerMeasurementsEnabled = providers.environmentVariable("HX_WEB_PARSER_WORKER_MEASUREMENTS")
    .map { it == "1" }
    .orElse(false)

val parserWorkerBrowserProductionWebpack by tasks.registering(Exec::class) {
    group = "verification"
    description = "Bundles the isolated parser worker for the public browser distribution."
    dependsOn(parserWorkerCompileSync, rootProject.tasks.named("kotlinNpmInstall"))

    val rootJsDir = rootProject.layout.buildDirectory.dir("js")
    val workerEntry = rootJsDir.map {
        it.file("packages/hexana-webapp-parserWorker/kotlin/hexana-webapp-parserWorker.js")
    }
    inputs.dir(rootJsDir.map { it.dir("packages/hexana-webapp-parserWorker/kotlin") })
    outputs.file(parserWorkerOutputDir.map { it.file("parser-worker.js") })
    workingDir(rootJsDir)
    doFirst {
        commandLine(
            "node",
            rootJsDir.get().file("node_modules/webpack/bin/webpack.js").asFile,
            "--mode", "production",
            "--entry", workerEntry.get().asFile.absolutePath,
            "--output-path", parserWorkerOutputDir.get().asFile.absolutePath,
            "--output-filename", "parser-worker.js",
        )
    }
}

tasks.named<Copy>("jsTestProcessResources") {
    dependsOn(parserWorkerBrowserProductionWebpack)
    from(parserWorkerOutputDir) {
        include("parser-worker.js")
    }
    into("parser-worker-fixtures") {
        from(rootProject.file("wasmParser/src/commonTest/resources/snippets/addition.wasm"))
        from(rootProject.file("wasmParser/src/commonTest/resources/component/composed.wasm"))
        if (parserWorkerMeasurementsEnabled.get()) {
            from(rootProject.file("wasmParser/src/commonTest/resources/faustide.wasm"))
            from(rootProject.file("wasmParser/src/commonTest/resources/mpp-demo.wasm"))
        }
    }
}

tasks.named<org.gradle.api.tasks.Sync>("jsBrowserDistribution") {
    dependsOn(parserWorkerBrowserProductionWebpack)
    from(parserWorkerOutputDir) {
        include("parser-worker.js")
    }
}

// Copy wasmprinter_wrapper.wasm into the production webpack output directory
// so the webapp can fetch it at runtime via the browser WebAssembly API.
val copyWasmPrinter by tasks.registering(Copy::class) {
    from(rootProject.file("graalwasm/wasmprinter/src/main/resources/wasmprinter_wrapper.wasm"))
    into(layout.buildDirectory.dir("kotlin-webpack/js/productionExecutable"))
    dependsOn(tasks.named("jsBrowserProductionWebpack"))
}

tasks.named("jsBrowserProductionWebpack") {
    finalizedBy(copyWasmPrinter)
}

tasks.named("jsBrowserDistribution") {
    dependsOn(copyWasmPrinter)
}

// Copy editor.css into production output (provides VS Code-compatible base styles).
val copyEditorCss by tasks.registering(Copy::class) {
    from(rootProject.file("vscode-plugin/media/editor.css"))
    into(layout.buildDirectory.dir("kotlin-webpack/js/productionExecutable"))
    dependsOn(tasks.named("jsBrowserProductionWebpack"))
}

tasks.named("jsBrowserProductionWebpack") {
    finalizedBy(copyEditorCss)
}

tasks.named("jsBrowserDistribution") {
    dependsOn(copyEditorCss)
}

private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
    .digest(file.readBytes())
    .joinToString("") { "%02x".format(it) }

private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

private fun json(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        append(
            when (character) {
                '\\' -> "\\\\"
                '"' -> "\\\""
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                else -> character.toString()
            }
        )
    }
    append('"')
}

private fun packageFailure(message: String): Nothing = throw GradleException("Invalid production webapp package: $message")

private val productionSourceAssets = listOf(
    "index.html",
    "boot.js",
    "webapp.js",
    "parser-worker.js",
    "wasmprinter_wrapper.wasm",
    "theme.css",
    "editor.css",
)

private data class ResolvedRuntimeComponent(
    val identity: String,
    val bomRef: String,
    val type: String,
    val group: String,
    val name: String,
    val version: String,
    val runtimes: Set<String>,
)

private fun resolvedRuntimeComponents(revision: String): List<ResolvedRuntimeComponent> {
    val runtimeNames = listOf("jsRuntimeClasspath", "jsParserWorkerRuntimeClasspath")
    val components = mutableMapOf<String, ResolvedRuntimeComponent>()
    runtimeNames.forEach { runtimeName ->
        configurations.getByName(runtimeName).incoming.resolutionResult.allComponents.forEach { component ->
            val projectComponent = component.id as? ProjectComponentIdentifier
            val resolved = if (projectComponent != null) {
                ResolvedRuntimeComponent(
                    identity = "project:${projectComponent.projectPath}@$revision",
                    bomRef = "urn:hexana:component:${sha256(projectComponent.projectPath)}",
                    type = "application",
                    group = "org.jetbrains.hexana",
                    name = projectComponent.projectPath,
                    version = revision,
                    runtimes = emptySet(),
                )
            } else {
                val module = component.moduleVersion ?: packageFailure("unversioned resolved component ${component.id.displayName}")
                ResolvedRuntimeComponent(
                    identity = "module:${module.group}:${module.name}:${module.version}",
                    bomRef = "pkg:maven/${module.group}/${module.name}@${module.version}",
                    type = "library",
                    group = module.group,
                    name = module.name,
                    version = module.version,
                    runtimes = emptySet(),
                )
            }
            val previous = components[resolved.identity]
            components[resolved.identity] = resolved.copy(runtimes = (previous?.runtimes.orEmpty() + runtimeName))
        }
    }
    return components.values.sortedBy { it.identity }
}

private fun componentJson(component: ResolvedRuntimeComponent): String {
    val runtimes = component.runtimes.sorted().joinToString(",") { json(it) }
    return "{\"identity\":${json(component.identity)},\"bomRef\":${json(component.bomRef)},\"type\":${json(component.type)},\"group\":${json(component.group)},\"name\":${json(component.name)},\"version\":${json(component.version)},\"runtimes\":[$runtimes]}"
}

private fun resolutionEvidenceJson(components: List<ResolvedRuntimeComponent>): String =
    "{\"schemaVersion\":1,\"kind\":\"resolution-evidence\",\"lockStatus\":\"not-a-lock\",\"components\":[${components.joinToString(",", transform = ::componentJson)}]}\n"

private fun sbomJson(version: String, components: List<ResolvedRuntimeComponent>): String {
    val sbomComponents = components.joinToString(",") { component ->
        "{\"type\":${json(component.type)},\"bom-ref\":${json(component.bomRef)},\"group\":${json(component.group)},\"name\":${json(component.name)},\"version\":${json(component.version)},\"properties\":[{\"name\":\"hexana.runtime\",\"value\":${json(component.runtimes.sorted().joinToString(","))}}]}"
    }
    return "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.5\",\"version\":1,\"metadata\":{\"component\":{\"type\":\"application\",\"bom-ref\":\"urn:hexana:webapp\",\"name\":\"hexana-webapp\",\"version\":${json(version)}}},\"components\":[$sbomComponents]}\n"
}

private val productionCsp = "default-src 'self'; script-src 'self' 'unsafe-eval' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; worker-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

private fun productionCacheControl(path: String): String = when {
    path == "index.html" -> "no-cache"
    path.startsWith("assets/") -> "public, max-age=31536000, immutable"
    else -> "no-store"
}

private fun hostingPolicyJson(): String = "{\"schemaVersion\":1,\"headers\":{\"Content-Security-Policy\":${json(productionCsp)},\"X-Content-Type-Options\":\"nosniff\",\"Referrer-Policy\":\"no-referrer\",\"Permissions-Policy\":\"geolocation=(), microphone=(), camera=(), payment=(), usb=()\",\"X-Frame-Options\":\"DENY\",\"Cross-Origin-Opener-Policy\":\"same-origin\",\"Cross-Origin-Embedder-Policy\":\"require-corp\",\"Cross-Origin-Resource-Policy\":\"same-origin\"},\"edgeHeaders\":{\"Strict-Transport-Security\":\"max-age=31536000\"},\"hstsScope\":\"HTTPS edge-only: configure this header at the TLS terminator; the static origin does not terminate TLS.\",\"cache\":{\"index.html\":\"no-cache\",\"assets/*\":\"public, max-age=31536000, immutable\",\"evidence\":\"no-store\"},\"coopCoepDecision\":\"enabled: all app, worker, and Wasm resources are same-origin and the production-package smoke verifies worker parsing under COOP/COEP\"}\n"

private fun verifyProductionPackage(packageDir: File) {
    if (!packageDir.isDirectory) packageFailure("missing package directory ${packageDir.absolutePath}")
    val manifest = packageDir.resolve("package-manifest.json")
    val checksums = packageDir.resolve("checksums.sha256")
    val packageChecksum = packageDir.resolve("package.sha256")
    listOf(manifest, checksums, packageChecksum).forEach { file ->
        if (!file.isFile) packageFailure("missing evidence file ${file.name}")
    }
    val checksumEntries = checksums.readLines().filter { it.isNotBlank() }.map { line ->
        val fields = line.split("  ", limit = 2)
        if (fields.size != 2 || fields[0].length != 64) packageFailure("malformed checksum entry $line")
        fields[1] to fields[0]
    }
    if (checksumEntries.map { it.first }.distinct().size != checksumEntries.size) {
        packageFailure("duplicate checksum paths")
    }
    val actualFiles = packageDir.walkTopDown().filter { it.isFile }.map {
        it.relativeTo(packageDir).invariantSeparatorsPath
    }.sorted().toList()
    val expectedFiles = (checksumEntries.map { it.first } + "checksums.sha256" + "package.sha256").sorted()
    if (actualFiles != expectedFiles) packageFailure("allowlist mismatch: expected $expectedFiles, found $actualFiles")
    val revision = webappRevision.get()
    val version = webappArtifactVersion.get()
    val versionSource = webappArtifactVersionSource.get()
    val expectedIdentity = "{\"schemaVersion\":1,\"product\":\"hexana-webapp\",\"version\":${json(version)},\"versionSource\":${json(versionSource)},\"revision\":${json(revision)}}\n"
    if (packageDir.resolve("build-identity.json").readText() != expectedIdentity) {
        packageFailure("identity does not match the declared revision and webapp artifact version")
    }
    val resolvedComponents = resolvedRuntimeComponents(revision)
    if (resolvedComponents.isEmpty()) packageFailure("resolved dependency evidence is empty")
    if (packageDir.resolve("resolved-dependencies.json").readText() != resolutionEvidenceJson(resolvedComponents)) {
        packageFailure("resolved dependency evidence omits, duplicates, or changes a runtime component")
    }
    if (packageDir.resolve("sbom.cdx.json").readText() != sbomJson(version, resolvedComponents)) {
        packageFailure("SBOM does not match the complete resolved runtime dependency evidence")
    }
    if (packageDir.resolve("hosting-policy.json").readText() != hostingPolicyJson()) {
        packageFailure("deployed-equivalent hosting policy is missing or changed")
    }
    checksumEntries.forEach { (path, hash) ->
        val file = packageDir.resolve(path)
        if (!file.isFile || sha256(file) != hash) packageFailure("hash mismatch for $path")
    }
    if (sha256(checksums) != packageChecksum.readText().trim()) packageFailure("overall checksum mismatch")
    val manifestText = manifest.readText()
    if (!manifestText.contains("\"schemaVersion\":1") || !manifestText.contains("\"cacheControl\"")) {
        packageFailure("machine-readable manifest is incomplete")
    }
    val nonAssetAllowlist = listOf(
        "index.html",
        "build-identity.json",
        "sbom.cdx.json",
        "resolved-dependencies.json",
        "hosting-policy.json",
        "provenance.json",
        "package-manifest.json",
        "checksums.sha256",
        "package.sha256",
    )
    if (actualFiles.filterNot { it.startsWith("assets/") } != nonAssetAllowlist.sorted()) {
        packageFailure("non-asset allowlist mismatch")
    }
    val manifestAssetPaths = Regex("\\\"path\\\":\\\"(assets/[^\\\"]+)\\\"")
        .findAll(manifestText)
        .map { it.groupValues[1] }
        .toList()
        .distinct()
        .sorted()
    if (actualFiles.filter { it.startsWith("assets/") } != manifestAssetPaths) {
        packageFailure("asset allowlist does not match the manifest")
    }
    if (actualFiles.any { it.endsWith(".map") || it.contains("node_modules") || it.contains("parser-worker-fixtures") }) {
        packageFailure("source maps or development artifacts shipped")
    }
    val index = packageDir.resolve("index.html").readText()
    if (!index.contains("http-equiv=\"Content-Security-Policy\"") || !index.contains(productionCsp) || "http://" in index || "https://" in index) {
        packageFailure("index.html has an unsafe connection or script policy")
    }
    val policy = packageDir.resolve("hosting-policy.json").readText()
    listOf("object-src 'none'", "base-uri 'none'", "form-action 'none'", "frame-ancestors 'none'", "X-Content-Type-Options\":\"nosniff", "Referrer-Policy\":\"no-referrer", "Permissions-Policy", "X-Frame-Options\":\"DENY", "Cross-Origin-Opener-Policy\":\"same-origin", "Cross-Origin-Embedder-Policy\":\"require-corp", "Strict-Transport-Security\":\"max-age=31536000\"", "HTTPS edge-only").forEach { required ->
        if (required !in policy) packageFailure("hosting policy omits $required")
    }
    if ("api.osv.dev" in policy || "connect-src *" in policy || "frame-ancestors *" in policy) {
        packageFailure("hosting policy grants a forbidden capability")
    }
    val immutableAssets = packageDir.resolve("assets").listFiles()?.filter { it.isFile }.orEmpty()
    if (immutableAssets.isEmpty()) packageFailure("missing immutable assets")
    immutableAssets.forEach { asset ->
        val hashPrefix = asset.name.substringBeforeLast('.').substringAfterLast('.', "")
        if (hashPrefix.length != 16 || !sha256(asset).startsWith(hashPrefix)) {
            packageFailure("asset name is not content-addressed: ${asset.name}")
        }
    }
    Regex("(?:src|href)=\"([^\"]+)\"").findAll(index).forEach { match ->
        val target = match.groupValues[1]
        if (!target.startsWith("data:") && !packageDir.resolve(target).isFile) {
            packageFailure("index.html references missing asset $target")
        }
    }
    listOf(packageDir.resolve("index.html"))
        .plus(immutableAssets.filter { it.extension == "js" })
        .forEach { file ->
            Regex("assets/[A-Za-z0-9_.-]+").findAll(file.readText()).forEach { match ->
                if (!packageDir.resolve(match.value).isFile) packageFailure("${file.name} references missing ${match.value}")
            }
        }
    val textAssets = packageDir.walkTopDown().filter { it.isFile && it.extension in setOf("js", "html", "css") }
    textAssets.forEach { file ->
        val text = file.readText()
        listOf("api.osv.dev", "/v1/querybatch", "/v1/vulns/").forEach { forbidden ->
            if (forbidden in text) packageFailure("${file.name} contains forbidden $forbidden")
        }
        if (Regex("//[#@]\\s*sourceMappingURL=").containsMatchIn(text)) {
            packageFailure("${file.name} contains a source map reference")
        }
    }
}

val suppliedWebappRevision = providers.gradleProperty("hexanaWebappRevision")
val gitWebappRevision = providers.exec { commandLine("git", "rev-parse", "HEAD") }
    .standardOutput.asText.map(String::trim)
val webappRevision = suppliedWebappRevision.orElse(gitWebappRevision)
// Supply -PhexanaWebappVersion=<release-version> when a release assigns one;
// otherwise the complete source revision is the artifact version.
val webappArtifactVersion = providers.gradleProperty("hexanaWebappVersion").orElse(webappRevision)
val webappArtifactVersionSource = providers.gradleProperty("hexanaWebappVersion")
    .map { "hexanaWebappVersion" }
    .orElse("git-revision")

val packageProductionWebapp by tasks.registering {
    group = "distribution"
    description = "Produces a deterministic, immutable static package from the browser distribution."
    dependsOn(tasks.named("jsBrowserDistribution"))

    val sourceDir = layout.buildDirectory.dir("dist/js/productionExecutable")
    val packageDir = layout.buildDirectory.dir("production-package")
    inputs.dir(sourceDir)
    inputs.files(
        rootProject.file("gradle/libs.versions.toml"),
        rootProject.file("gradle/wrapper/gradle-wrapper.properties"),
        project.file("build.gradle.kts"),
    )
    inputs.property("webappRevision", webappRevision)
    inputs.property("webappArtifactVersion", webappArtifactVersion)
    inputs.property("webappArtifactVersionSource", webappArtifactVersionSource)
    outputs.dir(packageDir)

    doLast {
        val source = sourceDir.get().asFile
        val output = packageDir.get().asFile
        val sourceFiles = source.walkTopDown().filter { it.isFile }.map {
            it.relativeTo(source).invariantSeparatorsPath
        }.sorted().toList()
        if (sourceFiles != productionSourceAssets.sorted()) {
            packageFailure("production distribution allowlist mismatch: expected ${productionSourceAssets.sorted()}, found $sourceFiles")
        }
        output.deleteRecursively()
        output.mkdirs()
        val assets = output.resolve("assets").apply { mkdirs() }
        fun immutableName(sourceName: String): String {
            val sourceFile = source.resolve(sourceName)
            val dot = sourceName.lastIndexOf('.')
            return sourceName.substring(0, dot) + "." + sha256(sourceFile).take(16) + sourceName.substring(dot)
        }
        val workerName = immutableName("parser-worker.js")
        val wasmName = immutableName("wasmprinter_wrapper.wasm")
        val themeName = immutableName("theme.css")
        val editorName = immutableName("editor.css")
        source.resolve("parser-worker.js").copyTo(assets.resolve(workerName), overwrite = true)
        source.resolve("wasmprinter_wrapper.wasm").copyTo(assets.resolve(wasmName), overwrite = true)
        source.resolve("theme.css").copyTo(assets.resolve(themeName), overwrite = true)
        source.resolve("editor.css").copyTo(assets.resolve(editorName), overwrite = true)
        val webappText = source.resolve("webapp.js").readText().replace("parser-worker.js", "assets/$workerName")
        if (webappText == source.resolve("webapp.js").readText()) packageFailure("webapp bundle did not reference parser-worker.js")
        val webappName = "webapp." + MessageDigest.getInstance("SHA-256").digest(webappText.toByteArray()).joinToString("") { "%02x".format(it) }.take(16) + ".js"
        assets.resolve(webappName).writeText(webappText, StandardCharsets.UTF_8)
        val bootText = source.resolve("boot.js").readText().replace("./wasmprinter_wrapper.wasm", "./assets/$wasmName")
        val bootName = "boot." + MessageDigest.getInstance("SHA-256").digest(bootText.toByteArray()).joinToString("") { "%02x".format(it) }.take(16) + ".js"
        assets.resolve(bootName).writeText(bootText, StandardCharsets.UTF_8)
        source.resolve("index.html").readText()
            .replace("theme.css", "assets/$themeName")
            .replace("editor.css", "assets/$editorName")
            .replace("boot.js", "assets/$bootName")
            .replace("webapp.js", "assets/$webappName")
            .also { output.resolve("index.html").writeText(it, StandardCharsets.UTF_8) }

        val revision = webappRevision.get()
        val version = webappArtifactVersion.get()
        val versionSource = webappArtifactVersionSource.get()
        val resolvedComponents = resolvedRuntimeComponents(revision)
        val payloadFiles = output.walkTopDown().filter { it.isFile }.map { file ->
            file.relativeTo(output).invariantSeparatorsPath to sha256(file)
        }.sortedBy { it.first }.toList()
        val cacheEntries = payloadFiles.joinToString(",") { (path, hash) ->
            "{\"path\":${json(path)},\"sha256\":${json(hash)},\"cacheControl\":${json(productionCacheControl(path))}}"
        }
        output.resolve("build-identity.json").writeText(
            "{\"schemaVersion\":1,\"product\":\"hexana-webapp\",\"version\":${json(version)},\"versionSource\":${json(versionSource)},\"revision\":${json(revision)}}\n",
            StandardCharsets.UTF_8,
        )
        output.resolve("sbom.cdx.json").writeText(sbomJson(version, resolvedComponents), StandardCharsets.UTF_8)
        output.resolve("resolved-dependencies.json").writeText(
            resolutionEvidenceJson(resolvedComponents),
            StandardCharsets.UTF_8,
        )
        output.resolve("hosting-policy.json").writeText(hostingPolicyJson(), StandardCharsets.UTF_8)
        val evidenceFiles = listOf("gradle/libs.versions.toml", "gradle/wrapper/gradle-wrapper.properties", "webapp/build.gradle.kts")
        val evidence = evidenceFiles.joinToString(",") { path -> "{\"path\":${json(path)},\"sha256\":${json(sha256(rootProject.file(path)))}}" }
        output.resolve("provenance.json").writeText(
            "{\"schemaVersion\":1,\"builder\":\"Gradle :webapp:packageProductionWebapp\",\"revision\":${json(revision)},\"inputs\":[$evidence]}\n",
            StandardCharsets.UTF_8,
        )
        val manifestEntries = output.walkTopDown().filter { it.isFile }.map { file ->
            file.relativeTo(output).invariantSeparatorsPath to sha256(file)
        }.sortedBy { it.first }.joinToString(",") { (path, hash) ->
            "{\"path\":${json(path)},\"sha256\":${json(hash)},\"cacheControl\":${json(productionCacheControl(path))}}"
        }
        output.resolve("package-manifest.json").writeText(
            "{\"schemaVersion\":1,\"files\":[$manifestEntries],\"payload\":[$cacheEntries]}\n",
            StandardCharsets.UTF_8,
        )
        val checksums = output.walkTopDown().filter { it.isFile }.map { file ->
            "${sha256(file)}  ${file.relativeTo(output).invariantSeparatorsPath}"
        }.sorted().joinToString("\n", postfix = "\n")
        output.resolve("checksums.sha256").writeText(checksums, StandardCharsets.UTF_8)
        output.resolve("package.sha256").writeText(sha256(output.resolve("checksums.sha256")) + "\n", StandardCharsets.UTF_8)
    }
}

tasks.register("verifyProductionPackage") {
    group = "verification"
    description = "Verifies the immutable production webapp package allowlist, integrity, references, and policy."
    dependsOn(packageProductionWebapp)
    doLast { verifyProductionPackage(layout.buildDirectory.dir("production-package").get().asFile) }
}

tasks.register<Exec>("verifyCloudConsoleDeploymentContract") {
    group = "verification"
    description = "Verifies the static Cloud Console Docker, Nginx, Skaffold, and values contract."
    commandLine("node", project.file("verify-cloud-console-contract.mjs").absolutePath)
}

tasks.register<Exec>("productionPackageChromeSmoke") {
    group = "verification"
    description = "Serves the immutable package with its deployed-equivalent policy and verifies Chrome worker parsing."
    dependsOn(packageProductionWebapp)
    commandLine(
        "node",
        project.file("production-package-smoke.mjs").absolutePath,
        layout.buildDirectory.dir("production-package").get().asFile.absolutePath,
        rootProject.file("wasmParser/src/commonTest/resources/snippets/addition.wasm").absolutePath,
    )
}

tasks.register("verifyPublicWebappCapabilityPolicy") {
    group = "verification"
    description = "Verifies the public webapp has no OSV-network capability."
    dependsOn(tasks.named("jsBrowserDistribution"))

    doLast {
        val distributionDir = layout.buildDirectory.dir("dist/js/productionExecutable").get().asFile
        check(distributionDir.isDirectory) {
            "Final public distribution is missing: ${distributionDir.absolutePath}"
        }
        val indexFile = distributionDir.resolve("index.html")
        check(indexFile.isFile) {
            "Final public distribution is missing index.html: ${indexFile.absolutePath}"
        }
        val index = indexFile.readText()
        check("api.osv.dev" !in index) { "Public CSP must not permit OSV networking" }
        check("worker-src 'self'" in index) { "Public CSP must permit only same-origin workers" }
        check(distributionDir.resolve("parser-worker.js").isFile) {
            "Public distribution is missing the dedicated parser worker asset"
        }
        check(distributionDir.resolve("parser-worker-fixtures").exists().not()) {
            "Test-only parser-worker fixtures must not ship in the public distribution"
        }

        val forbiddenLiterals = listOf("api.osv.dev", "/v1/querybatch", "/v1/vulns/")
        val productionBundles = distributionDir.walkTopDown()
            .filter { it.isFile && it.extension == "js" }
            .toList()
        check(productionBundles.isNotEmpty()) {
            "No JavaScript bundles were found in ${distributionDir.absolutePath}"
        }
        productionBundles.forEach { bundle ->
            val bundleText = bundle.readText()
            forbiddenLiterals.forEach { literal ->
                check(literal !in bundleText) {
                    "Public bundle ${bundle.name} contains forbidden capability literal: $literal"
                }
            }
        }
    }
}

// Developer-only preview server. It is not production hosting and does not serve
// the immutable package created by packageProductionWebapp.
tasks.register<Exec>("serve") {
    dependsOn(tasks.named("jsBrowserDistribution"))
    workingDir = layout.buildDirectory.dir("dist/js/productionExecutable").get().asFile
    commandLine("python3", "-m", "http.server", "8123")
}

// Developer-only experiment: build a Component Model binary that embeds the
// un-packaged distribution. It is outside the production static-package contract.
// Runs `cargo build --release` for the webapp_server crate and copies the
// resulting component to webapp/build/component/hexana-webapp.component.wasm.
val buildComponent by tasks.registering(Exec::class) {
    dependsOn(tasks.named("jsBrowserDistribution"))
    val distDir = layout.buildDirectory.dir("dist/js/productionExecutable").get().asFile
    val crateDir = rootProject.file("wasm-modules/webapp_server")
    val outDir = layout.buildDirectory.dir("component").get().asFile
    inputs.dir(distDir)
    inputs.dir(crateDir)
    outputs.dir(outDir)
    environment("HEXANA_WEBAPP_DIST", distDir.absolutePath)
    workingDir = crateDir
    commandLine("cargo", "build", "--release", "--target", "wasm32-wasip2")
    doLast {
        outDir.mkdirs()
        val src = crateDir.resolve("target/wasm32-wasip2/release/webapp_server.wasm")
        val dst = outDir.resolve("hexana-webapp.component.wasm")
        src.copyTo(dst, overwrite = true)
        logger.lifecycle("Component written to ${dst.absolutePath} (${dst.length()} bytes)")
    }
}

// Developer-only Component Model preview via wasmtime; not production hosting.
tasks.register<Exec>("serveComponent") {
    dependsOn(buildComponent)
    val component = layout.buildDirectory.file("component/hexana-webapp.component.wasm").get().asFile
    commandLine(
        "wasmtime", "serve",
        "-S", "cli=y",
        "--addr", "0.0.0.0:8124",
        component.absolutePath
    )
}

// Dev build copies.
val copyWasmPrinterDev by tasks.registering(Copy::class) {
    from(rootProject.file("graalwasm/wasmprinter/src/main/resources/wasmprinter_wrapper.wasm"))
    into(layout.buildDirectory.dir("kotlin-webpack/js/developmentExecutable"))
    dependsOn(tasks.named("jsBrowserDevelopmentWebpack"))
}

tasks.named("jsBrowserDevelopmentWebpack") {
    finalizedBy(copyWasmPrinterDev)
}

val copyEditorCssDev by tasks.registering(Copy::class) {
    from(rootProject.file("vscode-plugin/media/editor.css"))
    into(layout.buildDirectory.dir("kotlin-webpack/js/developmentExecutable"))
    dependsOn(tasks.named("jsBrowserDevelopmentWebpack"))
}

tasks.named("jsBrowserDevelopmentWebpack") {
    finalizedBy(copyEditorCssDev)
}
