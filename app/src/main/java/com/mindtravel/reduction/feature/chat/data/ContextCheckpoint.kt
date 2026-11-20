package com.mindtravel.reduction.feature.chat.data

import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.feature.chat.model.ContextBudget
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** The previous successful initial input on this device. Its window start also survives process death. */
internal class ContextCheckpoint(private val file: File) {
    fun load(clearedAt: Long): ContextBudget.Snapshot? {
        if (!file.isFile) return null
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != 1 || input.readLong() != clearedAt) return null
                val scope = input.readUTF()
                val first = input.readUTF()
                val size = input.readInt()
                val count = input.readInt()
                require(size in 1..ContextBudget.MODEL_CONTEXT && count in 0..size)
                val sharedEnds = IntArray(count)
                for (i in sharedEnds.indices) {
                    sharedEnds[i] = input.readInt()
                    require(sharedEnds[i] in 1..size && (i == 0 || sharedEnds[i - 1] < sharedEnds[i]))
                }
                ContextBudget.Snapshot(scope, first, IntArray(size) { input.readInt() }, sharedEnds)
            }
        }.onFailure { Logs.add("Previous chat context unreadable: ${it.message}") }.getOrNull()
    }

    fun save(snapshot: ContextBudget.Snapshot, clearedAt: Long) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        runCatching {
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(1)
                out.writeLong(clearedAt)
                out.writeUTF(snapshot.scope)
                out.writeUTF(snapshot.firstId)
                out.writeInt(snapshot.tokens.size)
                out.writeInt(snapshot.sharedEnds.size)
                snapshot.sharedEnds.forEach(out::writeInt)
                snapshot.tokens.forEach(out::writeInt)
            }
            file.installFrom(tmp)
        }.onFailure { tmp.delete(); Logs.add("Could not save previous chat context: ${it.message}") }
    }

    fun clear() { file.delete() }
}
