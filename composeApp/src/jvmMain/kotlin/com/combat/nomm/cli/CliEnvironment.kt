package com.combat.nomm.cli

import com.combat.nomm.Extension
import com.combat.nomm.ModDownloader
import java.io.File

/** What the CLI needs from the rest of NOMM, kept behind an interface so commands can be tested. */
interface CliEnvironment {
    val appVersion: String

    /** NOMM's data folder, shared with the GUI for the mod lock and change marker. */
    val dataDir: File

    /** The game folder from NOMM's settings, or found through Steam. */
    fun configuredGameFolder(): File?

    /** The mod catalog. Falls back to NOMM's cached copy when [offline] or the download fails. */
    suspend fun loadCatalog(offline: Boolean): CatalogResult

    val downloader: ModDownloader

    suspend fun installBepInEx(gameFolder: File)

    fun isGameRunning(): Boolean

    /** Starts the game through Steam, or the executable directly. Returns false if neither worked. */
    fun launchGame(gameFolder: File?): Boolean

    /** Finishes writes started in the background before the process exits. */
    suspend fun close() {}
}

data class CatalogResult(
    val extensions: List<Extension>,
    val source: Source,
) {
    enum class Source { LIVE, CACHED, UNAVAILABLE }
}
