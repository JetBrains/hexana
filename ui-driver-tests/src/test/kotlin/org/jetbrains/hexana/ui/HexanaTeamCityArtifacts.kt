package org.jetbrains.hexana.ui

import com.intellij.ide.starter.ci.teamcity.TeamCityClient
import com.intellij.ide.starter.path.GlobalPaths
import com.intellij.ide.starter.utils.HttpClient
import com.intellij.tools.ide.util.common.logOutput
import com.intellij.tools.ide.util.common.withRetryBlocking
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.apache.http.client.methods.HttpGet
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

object HexanaTeamCityArtifacts {
  private const val IDE_BUILD_TYPE_ENV = "HEXANA_IDE_BUILD_TYPE"
  private const val PLUGIN_BUILD_TYPE_ENV = "HEXANA_PLUGIN_BUILD_TYPE"
  private const val TEAMCITY_USERNAME_ENV = "TEAMCITY_USERNAME"
  private const val TEAMCITY_PASSWORD_ENV = "TEAMCITY_PASSWORD"
  private const val LEGACY_TEAMCITY_USER = "teamcity.user"
  private const val LEGACY_TEAMCITY_TOKEN = "teamcity.token"
  private const val TEAMCITY_PROPERTIES_FILE_ENV = "HEXANA_TEAMCITY_PROPERTIES"
  private const val DEFAULT_CREDENTIALS_FILE_NAME = "teamcity.local.properties"

  private val PLUGIN_ARTIFACT_PATTERN = Regex("hexana-.*\\.zip", RegexOption.IGNORE_CASE)
  private val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
  }
  private val teamCityCredentials by lazy { resolveTeamCityCredentials() }
  private val localCredentialsProperties by lazy { loadLocalCredentialsProperties() }

  data class BuildRef(val buildId: String, val buildNumber: String)

  private data class BuildArtifactsInspection(
    val buildRef: BuildRef,
    val artifactCount: Int,
    val matchingArtifacts: List<String>,
    val artifactSample: List<String>
  )

  @Serializable
  private data class TeamCityBuildDto(
    val id: Long? = null,
    val number: String? = null
  )

  @Serializable
  private data class TeamCityArtifactDto(
    val name: String? = null,
    val content: String? = null,
    val children: JsonElement? = null
  )

  fun resolveIdeBuildType(): String = requireBuildTypeEnv(IDE_BUILD_TYPE_ENV, "download an IDE installer from TeamCity")

  private fun pluginBuildType(): String = requireBuildTypeEnv(PLUGIN_BUILD_TYPE_ENV, "download the Hexana plugin from TeamCity")

  internal fun requireBuildTypeEnv(name: String, purpose: String, lookup: (String) -> String? = System::getenv): String {
    return lookup(name)?.trim()?.ifBlank { null }
      ?: error("$name is required to $purpose. Set it to the TeamCity build configuration ID, or use a public IDE version / a local plugin ZIP.")
  }

  fun resolveLatestSuccessfulBuild(buildTypeId: String, buildNumber: String? = null): BuildRef {
    val uri = teamCityRestUri().resolve(buildsLocator(buildTypeId, buildNumber, count = 1))
    val build = decodeFieldAsList(requestJson(uri), "build", TeamCityBuildDto.serializer())
      .firstOrNull()
      ?.asBuildRefOrNull()
      ?: error("No successful builds found for TeamCity build type '$buildTypeId'")

    return build
  }

  fun downloadArtifact(buildId: String, artifactPath: String, destinationPath: Path) {
    val artifactUrl = teamCityRestUri().resolve("builds/id:$buildId/artifacts/content/$artifactPath")

    withRetry("Failure during TeamCity artifact download: $artifactUrl") {
      HttpClient.sendRequest(newRequest(artifactUrl, expectJson = false)) { response ->
        val statusCode = response.statusLine.statusCode
        if (statusCode != 200) {
          val responseBody = response.entity?.content?.bufferedReader()?.use { it.readText() }.orEmpty()
          error(buildTeamCityFailureMessage(artifactUrl, statusCode, responseBody))
        }

        destinationPath.parent?.createDirectories()
        destinationPath.outputStream().use { output ->
          response.entity?.content?.use { input ->
            input.copyTo(output)
          } ?: error("TeamCity returned empty artifact body: $artifactUrl")
        }
      }
    }
  }

  fun downloadHexanaPluginZip(buildNumber: String? = null): Path {
    if (buildNumber != null) {
      return downloadPluginFromBuild(buildNumber)
    }

    val pluginBuildType = pluginBuildType()
    val successfulBuilds = loadSuccessfulBuilds(pluginBuildType)
    if (successfulBuilds.isEmpty()) {
      error(
        "No successful builds found for TeamCity build type '$pluginBuildType' while searching for " +
            "artifact pattern '${PLUGIN_ARTIFACT_PATTERN.pattern}'"
      )
    }

    logOutput(
      "Searching Hexana plugin artifact pattern '${PLUGIN_ARTIFACT_PATTERN.pattern}' in " +
          "${successfulBuilds.size} successful builds of '$pluginBuildType' (${authModeDescription()})"
    )

    val inspections = mutableListOf<BuildArtifactsInspection>()
    for (buildRef in successfulBuilds) {
      val allArtifacts = listArtifactFiles(buildRef.buildId).sorted()
      val matchingArtifacts = allArtifacts.filter { artifactPath ->
        val artifactName = artifactPath.substringAfterLast('/')
        PLUGIN_ARTIFACT_PATTERN.matches(artifactName)
      }

      val inspection = BuildArtifactsInspection(
        buildRef = buildRef,
        artifactCount = allArtifacts.size,
        matchingArtifacts = matchingArtifacts,
        artifactSample = allArtifacts
      )
      inspections += inspection

      logOutput(
        "TeamCity build ${buildRef.buildId} (${buildRef.buildNumber}): " +
            "artifactFiles=${inspection.artifactCount}, matched=${matchingArtifacts.size}, " +
            "sample=${formatArtifactSample(inspection.artifactSample)}"
      )

      when {
        matchingArtifacts.size == 1 -> {
          val artifactPath = matchingArtifacts.single()
          return downloadPluginArtifact(buildRef, artifactPath)
        }

        matchingArtifacts.size > 1 -> {
          error(
            "More than one artifact matches '${PLUGIN_ARTIFACT_PATTERN.pattern}' in build '${buildRef.buildId}' " +
                "(${buildRef.buildNumber}): $matchingArtifacts"
          )
        }
      }
    }

    val inspectionsSummary = inspections.joinToString(separator = "\n") { inspection ->
      val matchesSummary = if (inspection.matchingArtifacts.isEmpty()) {
        "none"
      } else {
        inspection.matchingArtifacts.joinToString(", ")
      }

      "  - buildId=${inspection.buildRef.buildId}, buildNumber=${inspection.buildRef.buildNumber}, " +
          "artifactCount=${inspection.artifactCount}, matches=$matchesSummary, " +
          "sample=${formatArtifactSample(inspection.artifactSample)}"
    }

    error(
      "No artifact matching '${PLUGIN_ARTIFACT_PATTERN.pattern}' found in recent successful builds of '$pluginBuildType'\n" +
          "Inspected ${inspections.size} builds:\n$inspectionsSummary"
    )
  }

  private fun downloadPluginFromBuild(buildNumber: String): Path {
    val pluginBuildType = pluginBuildType()
    logOutput("Resolving Hexana plugin from build number '$buildNumber' of '$pluginBuildType' (${authModeDescription()})")
    val buildRef = resolveLatestSuccessfulBuild(pluginBuildType, buildNumber)

    val allArtifacts = listArtifactFiles(buildRef.buildId).sorted()
    val matchingArtifacts = allArtifacts.filter { artifactPath ->
      val artifactName = artifactPath.substringAfterLast('/')
      PLUGIN_ARTIFACT_PATTERN.matches(artifactName)
    }

    logOutput(
      "TeamCity build ${buildRef.buildId} (${buildRef.buildNumber}): " +
          "artifactFiles=${allArtifacts.size}, matched=${matchingArtifacts.size}"
    )

    when {
      matchingArtifacts.isEmpty() -> error(
        "No artifact matching '${PLUGIN_ARTIFACT_PATTERN.pattern}' found in build '$buildNumber' " +
            "(id=${buildRef.buildId}). Available artifacts: ${formatArtifactSample(allArtifacts, limit = 20)}"
      )

      matchingArtifacts.size > 1 -> error(
        "More than one artifact matches '${PLUGIN_ARTIFACT_PATTERN.pattern}' in build '$buildNumber' " +
            "(id=${buildRef.buildId}): $matchingArtifacts"
      )
    }

    return downloadPluginArtifact(buildRef, matchingArtifacts.single())
  }

  private fun downloadPluginArtifact(buildRef: BuildRef, artifactPath: String): Path {
    val cacheDir = GlobalPaths.instance
      .getCacheDirectoryFor("plugins")
      .resolve("hexana")
      .resolve(buildRef.buildNumber)
      .createDirectories()

    val pluginZip = cacheDir.resolve(artifactPath.substringAfterLast('/'))
    if (!pluginZip.exists()) {
      logOutput("Downloading Hexana plugin '$artifactPath' from TeamCity build ${buildRef.buildId} (${authModeDescription()})")
      downloadArtifact(buildRef.buildId, artifactPath, pluginZip)
    } else {
      logOutput("Using cached Hexana plugin: $pluginZip")
    }
    return pluginZip
  }

  private fun loadSuccessfulBuilds(buildTypeId: String, count: Int = 30): List<BuildRef> {
    val uri = teamCityRestUri().resolve(buildsLocator(buildTypeId, count = count))
    val builds = decodeFieldAsList(requestJson(uri), "build", TeamCityBuildDto.serializer())
      .mapNotNull { it.asBuildRefOrNull() }

    val buildRefsSample = builds.take(5).joinToString { "${it.buildId}(${it.buildNumber})" }
    if (builds.isEmpty()) {
      logOutput("No successful builds were returned by TeamCity for '$buildTypeId'")
    } else {
      val suffix = if (builds.size > 5) " ..." else ""
      logOutput("Loaded ${builds.size} successful builds for '$buildTypeId': $buildRefsSample$suffix")
    }

    return builds
  }

  private fun listArtifactFiles(buildId: String, parentPath: String = ""): List<String> {
    val uri = teamCityRestUri().resolve(artifactChildrenLocator(buildId, parentPath))
    val response = requestJson(uri)
    val reportedCount = response.intFieldOrDefault("count", -1)
    val artifacts = decodeFieldAsList(response, "file", TeamCityArtifactDto.serializer())

    if (reportedCount > 0 && artifacts.isEmpty()) {
      val currentPath = parentPath.ifBlank { "/" }
      logOutput(
        "TeamCity artifacts endpoint for build $buildId returned count=$reportedCount but no 'file' field " +
            "for path '$currentPath'."
      )
    }

    return artifacts.asSequence().flatMap { artifact ->
      val name = artifact.name.orEmpty()
      if (name.isBlank()) {
        return@flatMap emptySequence()
      }

      val fullPath = if (parentPath.isBlank()) name else "$parentPath/$name"
      val isDirectory = artifact.content == null && artifact.children is JsonObject
      if (isDirectory) {
        listArtifactFiles(buildId, fullPath).asSequence()
      } else {
        sequenceOf(fullPath)
      }
    }.toList()
  }

  private fun buildsLocator(buildTypeId: String, buildNumber: String? = null, count: Int): String {
    val buildNumberLocator = buildNumber?.takeIf { it.isNotBlank() }?.let { "number:$it," }.orEmpty()
    return "builds?locator=buildType:$buildTypeId,${buildNumberLocator}status:SUCCESS,state:(finished:true),count:$count,history:false"
  }

  private fun artifactChildrenLocator(buildId: String, parentPath: String): String {
    val endpointPath = if (parentPath.isBlank()) {
      "builds/id:$buildId/artifacts/children"
    } else {
      "builds/id:$buildId/artifacts/children/$parentPath"
    }
    return "$endpointPath?locator=hidden:any&fields=count,file(name,content,children)"
  }

  private fun formatArtifactSample(artifacts: List<String>, limit: Int = 8): String {
    if (artifacts.isEmpty()) return "[]"

    val visible = artifacts.take(limit)
    val suffix = if (artifacts.size > limit) " ...(+${artifacts.size - limit} more)" else ""
    return visible.joinToString(prefix = "[", postfix = "]") + suffix
  }

  private fun TeamCityBuildDto.asBuildRefOrNull(): BuildRef? {
    val idValue = id?.toString().orEmpty()
    val numberValue = number.orEmpty()
    return if (idValue.isBlank() || numberValue.isBlank()) {
      null
    } else {
      BuildRef(idValue, numberValue)
    }
  }

  private fun JsonObject.intFieldOrDefault(fieldName: String, default: Int): Int {
    return this[fieldName]?.jsonPrimitive?.intOrNull ?: default
  }

  private fun <T> decodeFieldAsList(
    payload: JsonObject,
    fieldName: String,
    deserializer: DeserializationStrategy<T>
  ): List<T> {
    val field = payload[fieldName] ?: return emptyList()
    return when (field) {
      JsonNull -> emptyList()
      is JsonArray -> field.map { decodeFieldElement(fieldName, it, deserializer) }
      else -> listOf(decodeFieldElement(fieldName, field, deserializer))
    }
  }

  private fun <T> decodeFieldElement(
    fieldName: String,
    element: JsonElement,
    deserializer: DeserializationStrategy<T>
  ): T {
    return runCatching {
      json.decodeFromJsonElement(deserializer, element)
    }.getOrElse { cause ->
      val elementSnippet = element.toString().asSingleLineSnippet()
      throw IllegalStateException(
        "Failed to decode TeamCity JSON field '$fieldName'. Element: $elementSnippet",
        cause
      )
    }
  }

  private fun requestJson(uri: URI): JsonObject {
    return withRetry("Failure during request to TeamCity: $uri") {
      HttpClient.sendRequest(newRequest(uri, expectJson = true)) { response ->
        val statusCode = response.statusLine.statusCode
        val responseBody = response.entity?.content?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (statusCode != 200) {
          error(buildTeamCityFailureMessage(uri, statusCode, responseBody))
        }
        parseJsonObject(uri, responseBody)
      }
    }
  }

  private fun parseJsonObject(uri: URI, responseBody: String): JsonObject {
    val parsed = runCatching { json.parseToJsonElement(responseBody) }.getOrElse { cause ->
      val snippet = responseBody.asSingleLineSnippet()
      throw IllegalStateException(
        "Failed to parse TeamCity JSON response: $uri. Response: $snippet",
        cause
      )
    }

    return parsed as? JsonObject
      ?: error("TeamCity response is not a JSON object: $uri")
  }

  private fun newRequest(uri: URI, expectJson: Boolean): HttpGet {
    return HttpGet(uri).apply {
      if (expectJson) {
        addHeader("Content-Type", "application/json")
        addHeader("Accept", "application/json")
      }
      teamCityCredentials?.let {
        addHeader("Authorization", it.basicAuthHeader)
      }
    }
  }

  private fun teamCityRestUri(): URI {
    return if (teamCityCredentials == null) TeamCityClient.guestAuthUri else TeamCityClient.restUri
  }

  private fun authModeDescription(): String {
    return if (teamCityCredentials == null) "guestAuth" else "httpAuth"
  }

  private fun buildTeamCityFailureMessage(uri: URI, statusCode: Int, responseBody: String): String {
    val hint = if (teamCityCredentials == null) {
      "Guest auth failed. Provide TeamCity credentials via TEAMCITY_USERNAME/TEAMCITY_PASSWORD " +
          "or teamcity.user/teamcity.token in the local properties file."
    } else {
      "Authenticated request failed. Check TeamCity credentials and access rights."
    }
    return "TeamCity request failed with HTTP $statusCode using ${authModeDescription()}: $uri. " +
        "$hint Response: ${responseBody.asSingleLineSnippet()}"
  }

  private fun String.asSingleLineSnippet(maxLength: Int = 400): String {
    return replace(Regex("\\s+"), " ").trim().take(maxLength)
  }

  private fun resolveTeamCityCredentials(): TeamCityCredentials? {
    val user = envNonBlank(TEAMCITY_USERNAME_ENV)
      ?: propertyNonBlank(LEGACY_TEAMCITY_USER)
    val token = envNonBlank(TEAMCITY_PASSWORD_ENV)
      ?: propertyNonBlank(LEGACY_TEAMCITY_TOKEN)
    if (user == null || token == null) {
      return null
    }
    logOutput("TeamCity credentials were detected. Using authenticated TeamCity REST endpoints.")
    return TeamCityCredentials(
      userName = user,
      token = token
    )
  }

  private fun envNonBlank(name: String): String? {
    return System.getenv(name)?.takeIf { it.isNotBlank() }
  }

  private fun propertyNonBlank(key: String): String? {
    return System.getProperty(key)?.takeIf { it.isNotBlank() }
      ?: localCredentialsProperties.getProperty(key)?.takeIf { it.isNotBlank() }
  }

  private fun loadLocalCredentialsProperties(): Properties {
    val properties = Properties()
    val propertiesPath = locateLocalCredentialsFile() ?: return properties
    propertiesPath.inputStream().use { input ->
      properties.load(input)
    }
    return properties
  }

  private fun locateLocalCredentialsFile(): Path? {
    val envPath = System.getenv(TEAMCITY_PROPERTIES_FILE_ENV)?.trim()?.ifBlank { null }
    val propertiesPath = if (envPath != null) {
      Paths.get(envPath).toAbsolutePath().normalize()
    } else {
      Paths.get("").toAbsolutePath().normalize().resolve(DEFAULT_CREDENTIALS_FILE_NAME)
    }
    if (!propertiesPath.exists()) return null
    logOutput("Loaded TeamCity local credentials file: $propertiesPath")
    return propertiesPath
  }

  private fun <T> withRetry(messageOnFailure: String, action: () -> T): T {
    val result = withRetryBlocking(messageOnFailure = messageOnFailure) {
      action()
    }
    return requireNotNull(result) { messageOnFailure }
  }

  private data class TeamCityCredentials(
    val userName: String,
    val token: String
  ) {
    val basicAuthHeader: String by lazy {
      val payload = "$userName:$token"
      "Basic ${Base64.getEncoder().encodeToString(payload.toByteArray(StandardCharsets.UTF_8))}"
    }
  }
}
