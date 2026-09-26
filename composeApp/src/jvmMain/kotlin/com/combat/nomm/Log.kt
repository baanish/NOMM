package com.combat.nomm

import io.github.vinceglb.filekit.*
import java.io.PrintStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object Log {
    private val lock = Any()
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** Where log lines are echoed. The CLI moves this off stdout, which it keeps for command output. */
    var console: PrintStream? = System.out

    fun log(message: String) {
        console?.println("[NOMM] $message")
        runCatching {
            val logFile = FileKit.filesDir / "logs" / "nomm.log"
            logFile.parent()?.createDirectories()
            
            val timestamp = LocalDateTime.now().format(timestampFormat)
            synchronized(lock) {
                logFile.file.appendText("[$timestamp] $message\n")
            }
        }
    }
}
