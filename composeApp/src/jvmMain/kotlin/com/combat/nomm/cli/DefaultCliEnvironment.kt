package com.combat.nomm.cli

import com.combat.nomm.*
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.filesDir
import java.io.File

/** Connects the CLI to NOMM's settings, catalog cache, downloader and game launcher. */
class DefaultCliEnvironment : CliEnvironment {
    init {
        FileKit.init("NOMM")
    }

    override val appVersion: String = BuildKonfig.VERSION

    override val dataDir: File = FileKit.filesDir.file

    override fun configuredGameFolder(): File? = SettingsManager.gameFolder

    override suspend fun loadCatalog(offline: Boolean): CatalogResult {
        if (!offline) {
            NetworkClient.fetchManifest()?.let { return CatalogResult(it, CatalogResult.Source.LIVE) }
        }
        val cached = SettingsManager.cachedManifest.value.manifest
        return CatalogResult(cached, if (cached.isEmpty()) CatalogResult.Source.UNAVAILABLE else CatalogResult.Source.CACHED)
    }

    override val downloader: ModDownloader = { id, url, dir, hash ->
        if (!Installer.install(id, url, dir, hash)) {
            throw ModException(ModErrorKind.BUSY, "$id is already being installed by this NOMM process.")
        }
    }

    override suspend fun installBepInEx(gameFolder: File) {
        try {
            Installer.install("BepInEx", BEPINEX_DOWNLOAD_URL, gameFolder, hash = null, isBepInEx = true)
        } catch (e: Exception) {
            throw ModException(ModErrorKind.DOWNLOAD, "Installing BepInEx failed: ${e.message}")
        }
        writeDefaultBepInExConfig(gameFolder)
    }

    override fun isGameRunning(): Boolean = isNuclearOptionRunning()

    override fun launchGame(gameFolder: File?): Boolean {
        if (runCatching { launchSteamPlatformSpecific(NUCLEAR_OPTION_STEAM_URI) }.getOrDefault(false)) return true
        val exe = gameFolder?.let { File(it, "NuclearOption.exe") }?.takeIf { it.isFile } ?: return false
        return runCatching {
            ProcessBuilder(exe.absolutePath).directory(exe.parentFile).start()
            true
        }.getOrDefault(false)
    }

    override suspend fun close() {
        SettingsManager.awaitPendingManifestSave()
    }
}
