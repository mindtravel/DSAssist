package com.mindtravel.reduction.feature.chat.data

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Replace a complete file without deleting the last good copy before the move. */
internal fun File.installFrom(tmp: File) {
    try {
        Files.move(tmp.toPath(), toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(tmp.toPath(), toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

/** The conversation so far, kept in [file]. */
class HistoryRepository(private val file: File) {

    internal val checkpoint = ContextCheckpoint(File(file.parentFile, "${file.name}.context"))

    fun load(): String = if (file.exists()) {
        runCatching { file.readText() }.getOrDefault("[]")
    } else {
        "[]"
    }

    @Synchronized
    fun save(json: String): Boolean = runCatching {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json)
        file.installFrom(tmp)
    }.isSuccess

    @Synchronized
    fun clear() {
        runCatching { file.delete() }
    }

    private val clearedFile = File(file.parentFile, "${file.name}.cleared")

    /**
     * When the conversation was last cleared; 0 when it never was. Kept so that a device
     * this one shares its conversation with drops what was cleared here too.
     */
    var clearedAt: Long
        @Synchronized get() = runCatching { clearedFile.readText().trim().toLong() }.getOrDefault(0L)
        @Synchronized set(value) {
            runCatching { clearedFile.writeText(value.toString()) }
        }
}
