package com.mindtravel.reduction.model.chat

import java.io.DataInputStream
import java.util.PriorityQueue
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream

/** The byte-level BPE and ordered pre-tokenization rules from DeepSeek's official V4 tokenizer. */
internal object DeepSeekTokenizer {
    private val bytes = IntArray(256)
    private val splits: List<Pattern>
    private val specials = HashMap<String, Int>()
    private val specialLengths: List<Int>
    private val specialStarts: Set<Char>
    private val pairs: LongArray
    private val merged: IntArray

    init {
        val resource = checkNotNull(javaClass.getResourceAsStream("/deepseek/v4.bpe.gz"))
        DataInputStream(GZIPInputStream(resource).buffered()).use { input ->
            for (i in bytes.indices) bytes[i] = input.readInt()
            splits = List(input.readInt()) {
                // Android and the JVM otherwise disagree on which spaces match \s.
                val white = "\\p{Z}\\u0009-\\u000d\\u0085"
                Pattern.compile(input.string().replace("\\s", "[$white]").replace("\\S", "[^$white]"))
            }
            repeat(input.readInt()) { specials[input.string()] = input.readInt() }
            val count = input.readInt()
            pairs = LongArray(count)
            merged = IntArray(count)
            for (i in pairs.indices) {
                pairs[i] = pair(input.readInt(), input.readInt())
                merged[i] = input.readInt()
            }
        }
        specialLengths = specials.keys.map { it.length }.distinct().sortedDescending()
        specialStarts = specials.keys.mapTo(HashSet()) { it.first() }
    }

    private fun DataInputStream.string() = String(ByteArray(readInt()).also(::readFully), Charsets.UTF_8)
    private fun pair(left: Int, right: Int) = (left.toLong() shl 32) or right.toLong()

    fun encode(text: String, checkActive: () -> Unit = {}): IntArray {
        val result = TokenBuffer()
        fun split(start: Int, end: Int, stage: Int) {
            if (start >= end) return
            if (stage == splits.size) {
                checkActive()
                encodePiece(text.substring(start, end).toByteArray(Charsets.UTF_8), result, checkActive)
                return
            }
            val matcher = splits[stage].matcher(text).region(start, end)
            var cursor = start
            while (matcher.find()) {
                split(cursor, matcher.start(), stage + 1)
                split(matcher.start(), matcher.end(), stage + 1)
                cursor = matcher.end()
            }
            split(cursor, end, stage + 1)
        }

        var plain = 0
        var at = 0
        while (at < text.length) {
            if (text[at] !in specialStarts) { at++; continue }
            var end = at
            for (length in specialLengths) {
                if (length > text.length - at) continue
                val token = specials[text.substring(at, at + length)] ?: continue
                split(plain, at, 0)
                result.add(token)
                end = at + length
                plain = end
                break
            }
            at = if (end > at) end else at + 1
        }
        split(plain, text.length, 0)
        return result.toArray()
    }

    private data class Merge(val token: Int, val left: Int, val right: Int)

    private fun encodePiece(raw: ByteArray, out: TokenBuffer, checkActive: () -> Unit) {
        if (raw.size == 1) { out.add(bytes[raw[0].toInt() and 255]); return }
        val tokens = IntArray(raw.size) { bytes[raw[it].toInt() and 255] }
        val next = IntArray(raw.size) { it + 1 }
        val prev = IntArray(raw.size) { it - 1 }
        val agenda = PriorityQueue(compareBy<Merge> { it.token }.thenBy { it.left })
        fun offer(left: Int) {
            if (left < 0 || next[left] >= raw.size) return
            val right = next[left]
            val found = pairs.binarySearch(pair(tokens[left], tokens[right]))
            if (found >= 0) agenda.add(Merge(merged[found], left, right))
        }
        for (i in 0 until raw.lastIndex) offer(i)
        var steps = 0
        while (agenda.isNotEmpty()) {
            if (++steps % 4096 == 0) checkActive()
            val (token, left, right) = agenda.remove()
            if (next[left] != right || tokens[left] < 0 || tokens[right] < 0) continue
            // A neighbor may have changed in place since this pair was enqueued.
            val found = pairs.binarySearch(pair(tokens[left], tokens[right]))
            if (found < 0 || merged[found] != token) continue
            tokens[left] = token
            tokens[right] = -1
            next[left] = next[right]
            if (next[left] < raw.size) prev[next[left]] = left
            offer(prev[left])
            offer(left)
        }
        for (token in tokens) if (token >= 0) out.add(token)
    }
}

/** Primitive storage for token IDs, including when an input is hundreds of thousands of tokens. */
internal class TokenBuffer {
    private var values = IntArray(32)
    private var size = 0
    fun add(token: Int) {
        ensure(1)
        values[size++] = token
    }
    fun addAll(tokens: IntArray) {
        ensure(tokens.size)
        tokens.copyInto(values, size)
        size += tokens.size
    }
    fun toArray() = values.copyOf(size)
    private fun ensure(extra: Int) {
        if (size + extra > values.size) values = values.copyOf(maxOf(size + extra, values.size * 2))
    }
}
