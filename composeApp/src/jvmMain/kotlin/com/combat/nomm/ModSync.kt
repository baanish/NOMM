package com.combat.nomm

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Coordinates mod changes between NOMM processes, such as the GUI and CLI calls from a terminal.
 * Both keep their lock and marker files in NOMM's data folder.
 */
class ModSync(dataDir: File) {
    val lock = ModLock(File(dataDir, "mods.lock"))
    private val changeMarker = File(dataDir, "mods.changed")

    /** Tells other NOMM processes that installed mods changed and they should rescan. */
    fun markChanged() {
        runCatching {
            changeMarker.parentFile?.mkdirs()
            changeMarker.writeText(System.currentTimeMillis().toString())
        }
    }

    fun lastChange(): String? = runCatching { changeMarker.readText() }.getOrNull()
}

/**
 * A lock held across processes through an OS file lock, and reentrant within this process.
 * The OS releases the file lock if a process dies, so a crash can't leave mods locked.
 * Hold it only around file changes, never across a download.
 */
class ModLock(private val lockFile: File) {
    private val threadLock = ReentrantLock()
    private var holdCount = 0
    private var channel: FileChannel? = null
    private var fileLock: FileLock? = null

    fun <T> withLock(timeout: Duration = 30.seconds, action: () -> T): T {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        if (!threadLock.tryLock(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) throw busy()
        try {
            if (holdCount == 0) acquire(deadline)
            holdCount++
            try {
                return action()
            } finally {
                holdCount--
                if (holdCount == 0) release()
            }
        } finally {
            threadLock.unlock()
        }
    }

    private fun acquire(deadline: Long) {
        lockFile.parentFile?.mkdirs()
        val openedChannel = FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        try {
            while (true) {
                val acquired = try {
                    openedChannel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (acquired != null) {
                    channel = openedChannel
                    fileLock = acquired
                    return
                }
                if (System.nanoTime() >= deadline) throw busy()
                Thread.sleep(100)
            }
        } catch (e: Throwable) {
            openedChannel.close()
            throw e
        }
    }

    private fun release() {
        runCatching { fileLock?.release() }
        runCatching { channel?.close() }
        fileLock = null
        channel = null
    }

    private fun busy() = ModException(
        ModErrorKind.BUSY,
        "Another NOMM process is changing mods. Try again when it finishes (lock: ${lockFile.path})."
    )
}
