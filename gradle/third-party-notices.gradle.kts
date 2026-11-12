// Shared generator of THIRD-PARTY-NOTICES.md for every shipped Hexana artifact.
//
// Apply it from a project build script and call the registration function it stores in `extra`:
//
//     apply(from = rootProject.file("gradle/third-party-notices.gradle.kts"))
//     val registerThirdPartyNotices = extra["registerThirdPartyNotices"] as (Map<String, Any?>) -> TaskProvider<Task>
//
// Supported spec keys (all optional unless noted):
//   taskName            task name, default "generateThirdPartyNotices"
//   output (required)   Provider<RegularFile> of the generated notices file
//   listing             Provider<RegularFile> of a TSV "kind<TAB>id<TAB>file name" listing of every component
//   title               document title, default "Third-Party Notices"
//   description         task description
//   mavenClasspaths     List<Provider<Configuration>> of resolvable runtime classpaths (JVM or Kotlin/JS)
//   npmPackages         List<Map> with "nodeModules" (File or Provider<Directory>) and "roots" (List<String>
//                       npm package names); every runtime dependency of the roots is listed
//   npmPackageJsons     List<Provider<File>> of package.json files whose `dependencies` are the npm roots
//                       (used together with "npmNodeModules")
//   npmNodeModules      File or Provider<Directory> holding the installed npm packages
//   staticComponents    List<String> ids from license/third_party/components.json
//   rustModules         List<String> wasm-modules crates (names used in license/third_party/rust/crates.json)
//   requiredModules     List<String> "group:name" coordinates that must be present
//   dependsOn           List<Any> tasks that must run first (e.g. the npm install of Kotlin/JS)
//
// Static component data lives in license/third_party; see license/README.md.

import groovy.json.JsonSlurper
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

class NoticeText(val label: String, val text: String)

class NoticeComponent(
    val kind: String,
    val id: String,
    val fileNames: List<String>,
    val name: String?,
    val url: String?,
    val licenses: List<String>,
    val note: String?,
    val texts: List<NoticeText>,
)

val noticeTextFileName = Regex("""(?i)^(META-INF/)?(licen[sc]e|notice|copying)([-._].*)?$""")
val noticeTextSizeLimit = 256 * 1024

// Licence facts that the Maven metadata does not state. Each entry cites where it comes from.
val noticeLicenseNotes = mapOf(
    "net.java.dev.jna:jna" to "Dual-licensed under LGPL-2.1-or-later or Apache-2.0; either may be chosen " +
        "(https://github.com/java-native-access/jna/blob/5.14.0/LICENSE). Hexana uses it under Apache-2.0.",
    "com.github.jnr:jffi" to "Dual-licensed under Apache-2.0 or LGPL-3.0 as listed in its POM; Hexana uses it under " +
        "Apache-2.0. The native libraries are linked against libffi (MIT); both texts are included below.",
)

val embeddedWasmModules = mapOf(
    "wasmprinter_wrapper.wasm" to "wasmprinter_wrapper",
    "wat2wasm_full.wasm" to "wat2wasm",
    "disassembler.wasm" to "disassembler",
)

fun noticeDirectChild(element: org.w3c.dom.Element, tagName: String): org.w3c.dom.Element? {
    val children = element.childNodes
    for (index in 0 until children.length) {
        val child = children.item(index)
        if (child is org.w3c.dom.Element && child.tagName == tagName) return child
    }
    return null
}

fun noticeChildText(element: org.w3c.dom.Element, tagName: String): String? =
    noticeDirectChild(element, tagName)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

class PomInfo(
    val name: String?,
    val url: String?,
    val licenses: List<String>,
    val parent: String?,
)

fun parseNoticePom(pomFile: File): PomInfo {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isExpandEntityReferences = false
        isNamespaceAware = false
        isValidating = false
    }
    val project = factory.newDocumentBuilder().parse(pomFile).documentElement
    val licenseNodes = project.getElementsByTagName("license")
    val licenses = buildList {
        for (index in 0 until licenseNodes.length) {
            val license = licenseNodes.item(index) as? org.w3c.dom.Element ?: continue
            val name = noticeChildText(license, "name") ?: continue
            val url = noticeChildText(license, "url")
            add(if (url == null) name else "$name ($url)")
        }
    }.distinct()
    val parent = noticeDirectChild(project, "parent")?.let { element ->
        val group = noticeChildText(element, "groupId")
        val artifact = noticeChildText(element, "artifactId")
        val version = noticeChildText(element, "version")
        if (group != null && artifact != null && version != null) "$group:$artifact:$version" else null
    }
    return PomInfo(noticeChildText(project, "name"), noticeChildText(project, "url"), licenses, parent)
}

fun looksLikeText(bytes: ByteArray): Boolean = bytes.size <= noticeTextSizeLimit && bytes.none { it.toInt() == 0 }

fun zipNoticeTexts(archive: File): List<NoticeText> {
    if (!archive.isFile || archive.extension !in setOf("jar", "klib", "zip")) return emptyList()
    return ZipFile(archive).use { zip ->
        zip.entries().asSequence()
            .filter { !it.isDirectory }
            .filter { entry ->
                val name = entry.name
                noticeTextFileName.matches(name) || (name.startsWith("META-INF/licenses/") && !name.endsWith(".class"))
            }
            .sortedBy { it.name }
            .mapNotNull { entry ->
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                if (looksLikeText(bytes)) NoticeText("${archive.name}!/${entry.name}", String(bytes, Charsets.UTF_8)) else null
            }
            .toList()
    }
}

fun zipEmbeddedWasmModules(archive: File): Set<String> {
    if (!archive.isFile || archive.extension !in setOf("jar", "klib", "zip")) return emptySet()
    return ZipFile(archive).use { zip ->
        zip.entries().asSequence()
            .mapNotNull { embeddedWasmModules[it.name.substringAfterLast('/')] }
            .toSet()
    }
}

fun directoryNoticeTexts(directory: File, prefix: String): List<NoticeText> =
    directory.listFiles().orEmpty()
        .filter { it.isFile && noticeTextFileName.matches(it.name) }
        .sortedBy { it.name }
        .mapNotNull { file ->
            val bytes = file.readBytes()
            if (looksLikeText(bytes)) NoticeText("$prefix/${file.name}", String(bytes, Charsets.UTF_8)) else null
        }

fun readNoticeJson(file: File): Any? = JsonSlurper().parse(file)

@Suppress("UNCHECKED_CAST")
fun npmDependencyNames(packageJson: Map<String, Any?>): List<String> =
    ((packageJson["dependencies"] as? Map<String, Any?>).orEmpty().keys +
        (packageJson["optionalDependencies"] as? Map<String, Any?>).orEmpty().keys).toList()

@Suppress("UNCHECKED_CAST")
fun npmLicenseNames(packageJson: Map<String, Any?>): List<String> {
    val single = packageJson["license"]
    val many = packageJson["licenses"] as? List<Any?>
    return buildList {
        when (single) {
            is String -> add(single)
            is Map<*, *> -> (single["type"] as? String)?.let(::add)
        }
        many?.forEach { entry ->
            when (entry) {
                is Map<*, *> -> (entry["type"] as? String)?.let(::add)
                is String -> add(entry)
            }
        }
    }.distinct()
}

@Suppress("UNCHECKED_CAST")
fun npmClosure(nodeModules: File, roots: List<String>, fallbackTexts: (String) -> List<NoticeText>): List<NoticeComponent> {
    val projectDir = nodeModules.parentFile
    fun locate(name: String, from: File): File? {
        var dir: File? = from
        while (dir != null) {
            val candidate = File(dir, "node_modules/$name")
            if (File(candidate, "package.json").isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }

    val found = linkedMapOf<String, NoticeComponent>()
    fun visit(name: String, from: File, required: Boolean) {
        val packageDir = locate(name, from)
        if (packageDir == null) {
            if (required) throw GradleException("npm package `$name` is not installed under $nodeModules")
            return
        }
        val json = readNoticeJson(File(packageDir, "package.json")) as Map<String, Any?>
        val version = json["version"] as? String ?: "unknown"
        val id = "$name@$version"
        if (id in found) return
        val repository = json["repository"]
        val url = (json["homepage"] as? String)
            ?: (repository as? String)
            ?: (repository as? Map<String, Any?>)?.get("url") as? String
        found[id] = NoticeComponent(
            kind = "npm",
            id = id,
            fileNames = emptyList(),
            name = name,
            url = url,
            licenses = npmLicenseNames(json),
            note = null,
            texts = directoryNoticeTexts(packageDir, "npm/$name@$version").ifEmpty { fallbackTexts(name) },
        )
        npmDependencyNames(json).forEach { dependency -> visit(dependency, packageDir, required = false) }
    }
    roots.forEach { visit(it, projectDir, required = true) }
    return found.values.sortedBy { it.id }
}

@Suppress("UNCHECKED_CAST")
fun loadMavenLicenseRules(thirdPartyDir: File): List<Map<String, Any?>> =
    readNoticeJson(File(thirdPartyDir, "maven-licenses.json")) as List<Map<String, Any?>>

@Suppress("UNCHECKED_CAST")
fun mavenRuleTexts(thirdPartyDir: File, rules: List<Map<String, Any?>>, group: String, module: String): List<NoticeText> {
    val rule = rules.firstOrNull { entry ->
        (entry["match"] as List<String>).any { pattern ->
            pattern == "$group:$module" || (pattern.endsWith(":*") && pattern.removeSuffix(":*") == group)
        }
    } ?: return emptyList()
    return (rule["files"] as List<String>).map { relative ->
        val file = File(thirdPartyDir, relative)
        if (!file.isFile) throw GradleException("Licence file `$relative` referenced by maven-licenses.json is missing")
        NoticeText("license/third_party/$relative", file.readText())
    }
}

class StaticNoticeData(val components: Map<String, Map<String, Any?>>, val rustCrates: List<Map<String, Any?>>)

@Suppress("UNCHECKED_CAST")
fun loadStaticNoticeData(thirdPartyDir: File): StaticNoticeData {
    val components = (readNoticeJson(File(thirdPartyDir, "components.json")) as List<Map<String, Any?>>)
        .associateBy { it["id"] as String }
    val rustCrates = readNoticeJson(File(thirdPartyDir, "rust/crates.json")) as List<Map<String, Any?>>
    return StaticNoticeData(components, rustCrates)
}

@Suppress("UNCHECKED_CAST")
fun staticComponent(thirdPartyDir: File, entry: Map<String, Any?>): NoticeComponent {
    val files = (entry["files"] as? List<String>).orEmpty()
    val texts = files.map { relative ->
        val file = File(thirdPartyDir, relative)
        if (!file.isFile) throw GradleException("Licence file `$relative` referenced by `${entry["id"]}` is missing under $thirdPartyDir")
        NoticeText("license/third_party/$relative", file.readText())
    }
    val label = "${entry["name"]} ${entry["version"]}".trim()
    val scope = entry["scope"] as? String
    return NoticeComponent(
        kind = "static",
        id = "${entry["id"]}:${entry["version"]}",
        fileNames = emptyList(),
        name = label,
        url = entry["url"] as? String,
        licenses = listOf(entry["spdx"] as String),
        note = listOfNotNull(scope?.let { "Applies to: $it." }, entry["note"] as? String).joinToString(" ").ifEmpty { null },
        texts = texts,
    )
}

@Suppress("UNCHECKED_CAST")
fun rustComponent(thirdPartyDir: File, entry: Map<String, Any?>, usedBy: Set<String>): NoticeComponent {
    val files = (entry["files"] as? List<String>).orEmpty()
    val texts = files.map { relative ->
        val file = File(thirdPartyDir, relative)
        if (!file.isFile) throw GradleException("Licence file `$relative` of Rust crate `${entry["name"]}` is missing under $thirdPartyDir")
        NoticeText("license/third_party/$relative", file.readText())
    }
    return NoticeComponent(
        kind = "rust",
        id = "cargo:${entry["name"]}@${entry["version"]}",
        fileNames = emptyList(),
        name = "${entry["name"]} (Rust crate embedded in ${usedBy.sorted().joinToString(", ") { "`$it.wasm`" }})",
        url = entry["repository"] as? String,
        licenses = listOf(entry["license"] as? String ?: "not declared in Cargo.toml"),
        note = entry["note"] as? String,
        texts = texts,
    )
}

fun fenceFor(text: String): String {
    val longest = Regex("~+").findAll(text).maxOfOrNull { it.value.length } ?: 0
    return "~".repeat(maxOf(4, longest + 1))
}

fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

fun renderNotices(title: String, components: List<NoticeComponent>): String {
    val textIds = linkedMapOf<String, Int>()
    val textBodies = linkedMapOf<String, String>()
    val textLabels = linkedMapOf<String, MutableList<String>>()
    components.forEach { component ->
        component.texts.forEach { text ->
            val key = sha256Hex(text.text.trim())
            textIds.getOrPut(key) { textIds.size + 1 }
            textBodies.putIfAbsent(key, text.text.trim('\n', '\r'))
            textLabels.getOrPut(key) { mutableListOf() } += text.label
        }
    }
    return buildString {
        appendLine("# $title")
        appendLine()
        appendLine("This Hexana distribution includes third-party software components.")
        appendLine("Each component remains licensed under its own licence terms. The component list is generated from the")
        appendLine("runtime dependencies that are packaged into this artifact; the licence names come from each component's")
        appendLine("published metadata, and the licence and notice texts are the files shipped with the component or taken")
        appendLine("from its upstream project (see `license/README.md` in the Hexana repository).")
        appendLine()
        appendLine("Generated by: `gradle/third-party-notices.gradle.kts`")
        appendLine()
        appendLine("## Components")
        appendLine()
        components.forEach { component ->
            appendLine("### `${component.id}`")
            component.name?.let { appendLine("- Name: $it") }
            component.url?.let { appendLine("- Project: $it") }
            if (component.licenses.isEmpty()) {
                appendLine("- License: not declared in the component metadata")
            } else {
                component.licenses.forEach { appendLine("- License: $it") }
            }
            component.note?.let { appendLine("- Note: $it") }
            if (component.texts.isNotEmpty()) {
                val numbers = component.texts.map { textIds.getValue(sha256Hex(it.text.trim())) }.distinct().sorted()
                appendLine("- Texts: ${numbers.joinToString(", ") { "[$it](#text-$it)" }}")
            }
            appendLine()
        }
        appendLine("## Licence and notice texts")
        appendLine()
        textIds.forEach { (key, number) ->
            val labels = textLabels.getValue(key).distinct()
            appendLine("<a id=\"text-$number\"></a>")
            appendLine("### Text $number")
            appendLine()
            appendLine("Shipped as: " + labels.take(6).joinToString(", ") { "`$it`" } +
                if (labels.size > 6) " and ${labels.size - 6} more" else "")
            appendLine()
            val body = textBodies.getValue(key)
            val fence = fenceFor(body)
            appendLine("$fence" + "text")
            appendLine(body)
            appendLine(fence)
            appendLine()
        }
    }.trimEnd() + "\n"
}

extra["registerThirdPartyNotices"] = { spec: Map<String, Any?> ->
    val taskName = spec["taskName"] as? String ?: "generateThirdPartyNotices"
    @Suppress("UNCHECKED_CAST")
    val output = spec["output"] as Provider<RegularFile>
    @Suppress("UNCHECKED_CAST")
    val listing = spec["listing"] as? Provider<RegularFile>
    val title = spec["title"] as? String ?: "Third-Party Notices"
    @Suppress("UNCHECKED_CAST")
    val classpaths = spec["mavenClasspaths"] as? List<Provider<Configuration>> ?: emptyList()
    @Suppress("UNCHECKED_CAST")
    val npmPackages = spec["npmPackages"] as? List<Map<String, Any?>> ?: emptyList()
    @Suppress("UNCHECKED_CAST")
    val npmPackageJsons = spec["npmPackageJsons"] as? List<Provider<File>> ?: emptyList()
    val npmNodeModules = spec["npmNodeModules"]
    @Suppress("UNCHECKED_CAST")
    val staticIds = spec["staticComponents"] as? List<String> ?: emptyList()
    @Suppress("UNCHECKED_CAST")
    val rustModules = spec["rustModules"] as? List<String> ?: emptyList()
    @Suppress("UNCHECKED_CAST")
    val requiredModules = spec["requiredModules"] as? List<String> ?: emptyList()
    val extraDependsOn = spec["dependsOn"] as? List<Any> ?: emptyList()
    val thirdPartyDir = rootProject.layout.projectDirectory.dir("license/third_party").asFile

    fun toFile(value: Any?): File = when (value) {
        is File -> value
        is FileSystemLocation -> value.asFile
        is Provider<*> -> toFile(value.get())
        else -> throw GradleException("Expected a File, a FileSystemLocation or a Provider of one, got $value")
    }

    tasks.register(taskName) {
        group = "distribution"
        description = spec["description"] as? String ?: "Generate third-party notices."
        extraDependsOn.forEach { dependsOn(it) }
        inputs.files(classpaths)
        inputs.dir(thirdPartyDir)
        outputs.file(output)
        listing?.let { outputs.file(it) }
        outputs.upToDateWhen { false }

        doLast {
            val resolved = classpaths.flatMap { it.get().incoming.artifacts.artifacts }
            val moduleArtifacts = resolved
                .filter { it.id.componentIdentifier is ModuleComponentIdentifier }
                .groupBy { it.id.componentIdentifier as ModuleComponentIdentifier }
            val moduleIds = moduleArtifacts.keys
                .distinctBy { listOf(it.group, it.module, it.version) }
                .sortedWith(compareBy<ModuleComponentIdentifier> { it.group }.thenBy { it.module }.thenBy { it.version })
            val projectArtifacts = resolved.filter { it.id.componentIdentifier is ProjectComponentIdentifier }
            val localFileArtifacts = resolved.filter {
                it.id.componentIdentifier !is ModuleComponentIdentifier && it.id.componentIdentifier !is ProjectComponentIdentifier
            }

            val pomFiles = dependencies.createArtifactResolutionQuery()
                .forComponents(moduleIds)
                .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java)
                .execute()
                .resolvedComponents
                .associate { component ->
                    component.id to component.getArtifacts(MavenPomArtifact::class.java)
                        .filterIsInstance<ResolvedArtifactResult>()
                        .singleOrNull()?.file
                }

            fun pomFor(coordinates: String): File? = runCatching {
                configurations.detachedConfiguration(dependencies.create("$coordinates@pom")).apply {
                    isTransitive = false
                }.singleFile
            }.getOrNull()

            fun inheritedLicenses(info: PomInfo): List<String> {
                var current = info
                repeat(5) {
                    if (current.licenses.isNotEmpty()) return current.licenses
                    val parentFile = current.parent?.let(::pomFor) ?: return emptyList()
                    current = parseNoticePom(parentFile)
                }
                return current.licenses
            }

            val mavenRules = loadMavenLicenseRules(thirdPartyDir)
            val apacheText = NoticeText(
                "license/third_party/licenses/Apache-2.0.txt",
                File(thirdPartyDir, "licenses/Apache-2.0.txt").readText(),
            )
            val npmFallback = { name: String -> mavenRuleTexts(thirdPartyDir, mavenRules, "npm", name) }
            val components = mutableListOf<NoticeComponent>()
            val wasmModules = rustModules.toMutableSet()

            moduleIds.forEach { moduleId ->
                val coordinates = "${moduleId.group}:${moduleId.module}:${moduleId.version}"
                val files = moduleArtifacts.getValue(moduleId).map { it.file }.distinct()
                val info = pomFiles[moduleId]?.let(::parseNoticePom)
                val licenses = info?.let(::inheritedLicenses).orEmpty()
                val bundled = files.flatMap(::zipNoticeTexts)
                val fromRules = mavenRuleTexts(thirdPartyDir, mavenRules, moduleId.group, moduleId.module)
                val isApache = licenses.any { it.contains("apache", ignoreCase = true) }
                val texts = bundled + fromRules +
                    if (isApache && (bundled + fromRules).none { it.text.contains("Apache License") }) listOf(apacheText) else emptyList()
                files.forEach { wasmModules += zipEmbeddedWasmModules(it) }
                components += NoticeComponent(
                    kind = "maven",
                    id = coordinates,
                    fileNames = files.map { it.name },
                    name = info?.name,
                    url = info?.url,
                    licenses = licenses,
                    note = noticeLicenseNotes["${moduleId.group}:${moduleId.module}"],
                    texts = texts,
                )
            }
            (projectArtifacts + localFileArtifacts).forEach { artifact ->
                wasmModules += zipEmbeddedWasmModules(artifact.file)
            }

            requiredModules.forEach { required ->
                check(moduleIds.any { "${it.group}:${it.module}" == required }) {
                    "Third-party notices must include $required."
                }
            }

            val npmRoots = npmPackageJsons.flatMap { provider ->
                @Suppress("UNCHECKED_CAST")
                val json = readNoticeJson(provider.get()) as Map<String, Any?>
                @Suppress("UNCHECKED_CAST")
                (json["dependencies"] as? Map<String, Any?>).orEmpty()
                    .filterValues { version -> !(version as String).startsWith("file:") }
                    .keys
            }.distinct()
            if (npmRoots.isNotEmpty()) {
                components += npmClosure(toFile(npmNodeModules), npmRoots, npmFallback)
            }
            npmPackages.forEach { entry ->
                @Suppress("UNCHECKED_CAST")
                components += npmClosure(toFile(entry["nodeModules"]), entry["roots"] as List<String>, npmFallback)
            }

            val staticData = loadStaticNoticeData(thirdPartyDir)
            staticIds.forEach { id ->
                val entry = staticData.components[id] ?: throw GradleException("Unknown static component `$id`")
                components += staticComponent(thirdPartyDir, entry)
            }
            @Suppress("UNCHECKED_CAST")
            staticData.rustCrates
                .filter { crate -> (crate["modules"] as List<String>).any { it in wasmModules } }
                .forEach { crate ->
                    @Suppress("UNCHECKED_CAST")
                    val used = (crate["modules"] as List<String>).filter { it in wasmModules }.toSet()
                    components += rustComponent(thirdPartyDir, crate, used)
                }

            val sorted = components
                .distinctBy { it.id }
                .sortedWith(compareBy<NoticeComponent>({ it.kind }, { it.id }))
            val withoutLicenseInfo = sorted.filter { it.licenses.isEmpty() || it.texts.isEmpty() }
            check(withoutLicenseInfo.isEmpty()) {
                "No licence name or text for: ${withoutLicenseInfo.joinToString { it.id }}. " +
                    "Add a rule to license/third_party/maven-licenses.json (Maven), a static component to " +
                    "license/third_party/components.json, or a licence note to gradle/third-party-notices.gradle.kts."
            }

            val outputFile = output.get().asFile
            outputFile.parentFile.mkdirs()
            outputFile.writeText(renderNotices(title, sorted))
            listing?.get()?.asFile?.let { listingFile ->
                listingFile.parentFile.mkdirs()
                val projectLines = projectArtifacts.map { "project\t${it.id.componentIdentifier.displayName}\t${it.file.name}" } +
                    localFileArtifacts.map { "file\t${it.file.name}\t${it.file.name}" }
                val componentLines = sorted.flatMap { component ->
                    if (component.fileNames.isEmpty()) listOf("${component.kind}\t${component.id}\t")
                    else component.fileNames.map { "${component.kind}\t${component.id}\t$it" }
                }
                listingFile.writeText((projectLines + componentLines).distinct().sorted().joinToString("\n", postfix = "\n"))
            }
        }
    }
}
