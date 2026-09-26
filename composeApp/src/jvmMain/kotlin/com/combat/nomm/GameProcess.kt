package com.combat.nomm

import java.io.File

const val NUCLEAR_OPTION_STEAM_URI = "steam://rungameid/2168680"

/** Checks for a running NuclearOption.exe, natively on Windows or through Proton on Linux. */
fun isNuclearOptionRunning(): Boolean = try {
    ProcessHandle.allProcesses().anyMatch { process ->
        process.info().command()
            .map { command ->
                val name = command.replace('\\', '/').substringAfterLast('/').lowercase()
                name == "nuclearoption.exe" || name == "nuclearoption"
            }
            .orElse(false)
    }
} catch (_: Exception) {
    false
}

fun bepInExLogFile(gameFolder: File): File = File(gameFolder, "BepInEx/LogOutput.log")

fun unityPlayerLogFile(gameFolder: File?): File = File(getNuclearOptionFolder(gameFolder), "Player.log")
