package org.jetbrains.hexana.ui

import com.intellij.ide.starter.ide.IdeDownloader
import com.intellij.ide.starter.ide.installer.IdeInstallerFile
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.tools.ide.util.common.logOutput
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

object HexanaTeamCityIdeDownloader : IdeDownloader {
  override fun downloadIdeInstaller(ideInfo: IdeInfo, installerDirectory: Path): IdeInstallerFile {
    val teamCityIdeInfo = ideInfo.copy(
      buildType = HexanaTeamCityArtifacts.resolveIdeBuildType(ideInfo),
      buildNumber = "",
      tag = null
    )

    val buildRef = HexanaTeamCityArtifacts.resolveLatestSuccessfulBuild(teamCityIdeInfo.buildType)
    val buildId = buildRef.buildId
    val buildNumber = buildRef.buildNumber
    val installerName = "${ideInfo.installerFilePrefix}-$buildNumber${ideInfo.installerFileExt}"

    val destinationDir = installerDirectory.createDirectories()
    val installerFile = destinationDir.resolve(installerName)

    if (!installerFile.exists()) {
      logOutput("Downloading IDE installer '$installerName' from TeamCity build $buildId")
      HexanaTeamCityArtifacts.downloadArtifact(buildId, installerName, installerFile)
    } else {
      logOutput("Using cached IDE installer: $installerFile")
    }

    return IdeInstallerFile(installerFile, buildNumber)
  }
}
