package com.combat.nomm

import io.github.vinceglb.filekit.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object Log {
    private val lock = Any()
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun log(message: String) {
        println("[NOMM] $message")
        runCatching {
            val logFile = (FileKit.filesDir / "logs" / "nomm.log").file
            logFile.parentFile?.mkdirs()
            val timestamp = LocalDateTime.now().format(timestampFormat)
            synchronized(lock) {
                logFile.appendText("[$timestamp] $message\n")
            }
        }
    }
}
