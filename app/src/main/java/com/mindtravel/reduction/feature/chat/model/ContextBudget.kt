package com.mindtravel.reduction.feature.chat.model

import com.mindtravel.reduction.core.ApiException
import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.model.chat.ChatImage
import com.mindtravel.reduction.model.chat.ChatMessage
import com.mindtravel.reduction.model.chat.DeepSeekTokenizer
import com.mindtravel.reduction.model.chat.Role
import com.mindtravel.reduction.model.chat.TokenBuffer
import com.mindtravel.reduction.model.chat.ToolSpec
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** A persisted conversation line. Tool calls and their results live only in ChatSession. */
internal data class ContextLine(
    val id: String, val role: Role, val content: String, val at: Long,
    val images: List<ChatImage> = emptyList()
) {
    @Volatile private var encoded: IntArray? = null
    fun textTokens(checkActive: () -> Unit) = encoded ?: DeepSeekTokenizer.encode(content, checkActive).also { encoded = it }

    // Image bytes are never text-tokenized or saved in the previous prompt. A changed image breaks the prefix.
    val imageTokens by lazy {
        val out = TokenBuffer()
        for (image in images) {
            val hash = MessageDigest.getInstance("SHA-256").digest(image.base64.toByteArray(Charsets.US_ASCII))
            repeat(ContextBudget.IMAGE_TOKENS) { i -> out.add(-1 - (hash[i % hash.size].toInt() and 255)) }
        }
        out.toArray()
    }
}

internal class ContextBudget(private val maxInput: Int = MODEL_CONTEXT) {
    private class CachedText {
        @Volatile private var last: Pair<String, IntArray>? = null
        fun encode(text: String, checkActive: () -> Unit): IntArray {
            last?.let { if (it.first == text) return it.second }
            return DeepSeekTokenizer.encode(text, checkActive).also { last = text to it }
        }
    }

    private val systemText = CachedText()
    private val toolDeclarations = CachedText()
    data class Snapshot(val scope: String, val firstId: String, val tokens: IntArray, val sharedEnds: IntArray) {
        // The ends are sorted lengths within this input. Older confirmed prefixes remain
        // usable when a more recent, longer prefix is only partially matched.
        fun sharedCountThrough(common: Int): Int {
            val found = sharedEnds.binarySearch(common)
            return if (found < 0) -found - 1 else found + 1
        }

        fun sharedWithNext(common: Int): IntArray {
            val keep = sharedCountThrough(common)
            if (common == 0 || sharedEnds.getOrNull(keep - 1) == common) return sharedEnds.copyOf(keep)
            return sharedEnds.copyOf(keep + 1).also { it[keep] = common }
        }
    }

    data class Estimate(val total: Int, val hit: Int) {
        val miss get() = total - hit
        val cost get() = hit + 50L * miss
        override fun toString(): String {
            val rate = if (total == 0) 0.0 else 100.0 * hit / total
            return "tokens=$total hit=$hit miss=$miss rate=${"%.1f".format(Locale.ROOT, rate)}% cost=$cost"
        }
    }

    data class Choice(
        val messages: List<ChatMessage>, val snapshot: Snapshot,
        val growing: Estimate, val recent: Estimate, val usedRecent: Boolean
    )

    /** Both candidates share this request's system prompt, tools and latest user message. */
    fun choose(
        previous: Snapshot?, system: String, history: List<ContextLine>,
        model: String, apiKey: String, tools: List<ToolSpec>, checkActive: () -> Unit
    ): Choice {
        checkActive()
        require(history.isNotEmpty() && history.last().role == Role.USER)
        val schema = tools.joinToString("\n") { schemaJson(it.toJson().getJSONObject("function")) }
        val scope = digest("$model\u0000$apiKey\u0000$schema")
        val anchor = previous?.let { p -> history.indexOfFirst { it.id == p.firstId } } ?: -1
        val reference = previous?.takeIf { anchor >= 0 && it.scope == scope }
        val start = maxOf(0, anchor)
        val shortStart = maxOf(0, history.size - RECENT)
        val flash = model == "deepseek-flash" || model.startsWith("deepseek-v4-flash")
        val systemTokens = systemText.encode(if (flash) FLASH_REASONING + system else system, checkActive)
        val systemSize = 1 + (if (flash) 1 else 0) + systemTokens.size
        val hasTools = tools.isNotEmpty()
        // Fixed text around the declarations in DeepSeek's published V4.1 Flash and V4 Pro
        // render_tools templates, tokenized with the same vocabulary as the schema below.
        val schemaSize = if (hasTools) toolDeclarations.encode(schema, checkActive).size + (if (flash) 212 else 222) else 0

        // Preserve the existing four-image budget within the most recent 20 messages in both candidates.
        var room = 4
        val visible = HashSet<Int>()
        for (i in history.lastIndex downTo shortStart) {
            val count = history[i].images.size
            if (count in 1..room) { visible.add(i); room -= count }
        }

        fun tokens(from: Int): IntArray {
            val out = TokenBuffer()
            out.add(BOS)
            if (flash) out.add(SYSTEM)
            out.addAll(systemTokens)
            if (hasTools) out.add(SCHEMA)
            var i = from
            while (i < history.size) {
                checkActive()
                val line = history[i]
                if (line.role == Role.ASSISTANT) {
                    if (hasTools) out.add(END_THINK)
                    out.addAll(line.textTokens(checkActive))
                    out.add(EOS)
                    i++
                    continue
                }
                // A cancellation or device merge may leave adjacent user messages. The
                // template joins their text blocks before BPE, including the two newlines.
                if (i == from || history[i - 1].role != Role.USER) out.add(USER) else out.addAll(BLOCK_BREAK)
                val first = i
                while (i < history.lastIndex && i !in visible && history[i + 1].role == Role.USER) i++
                val words = if (first == i) line.textTokens(checkActive) else {
                    val joined = history.subList(first, i + 1).joinToString("\n\n") { it.content }
                    DeepSeekTokenizer.encode(joined, checkActive)
                }
                out.addAll(words)
                if (i in visible) out.addAll(history[i].imageTokens)
                i++
                if (i < history.size && history[i].role == Role.ASSISTANT) {
                    out.add(ASSISTANT)
                    out.add(if (hasTools) START_THINK else END_THINK)
                }
            }
            return out.toArray()
        }

        val full = tokens(start)
        val short = if (shortStart == start) full else tokens(shortStart)
        fun prefix(input: IntArray): Int {
            if (reference == null) return 0
            val limit = minOf(input.size, reference.tokens.size)
            var i = 0
            while (i < limit && input[i] == reference.tokens[i]) i++
            return i
        }
        fun estimate(input: IntArray, common: Int): Estimate {
            // DeepSeek writes complete input endpoints and prefixes shared by earlier requests.
            // A first partial overlap alone is not evidence that this prefix was written.
            val hit = when {
                reference == null -> 0
                common == reference.tokens.size -> common
                else -> reference.sharedEnds.getOrNull(reference.sharedCountThrough(common) - 1) ?: 0
            }
            // The snapshot holds one placeholder for the scoped schema. A matching placeholder
            // also verifies that the system text ended in the same place. Price the two
            // generation markers separately from the reusable user-input endpoint.
            val extra = if (hasTools) schemaSize - 1 else 0
            return Estimate(input.size + extra + 2, hit + if (hit > systemSize) extra else 0)
        }

        val fullPrefix = prefix(full)
        val shortPrefix = if (short === full) fullPrefix else prefix(short)
        val growing = estimate(full, fullPrefix)
        val recent = estimate(short, shortPrefix)
        val fullFits = growing.total < maxInput
        val shortFits = recent.total < maxInput
        if (!fullFits && !shortFits) throw ApiException("候选对话均超过模型上下文容量，请缩短消息或清空对话")
        val useRecent = !fullFits || shortFits && recent.cost < growing.cost
        val from = if (useRecent) shortStart else start
        val messages = buildList {
            add(ChatMessage(Role.SYSTEM, system))
            for (i in from until history.size) {
                val line = history[i]
                add(ChatMessage(line.role, line.content, images = if (i in visible) line.images else emptyList()))
            }
        }
        val chosenPrefix = if (useRecent) shortPrefix else fullPrefix
        val snapshot = Snapshot(scope, history[from].id, if (useRecent) short else full,
            reference?.sharedWithNext(chosenPrefix) ?: intArrayOf())
        Logs.add("Context budget growing[$growing] recent[$recent] selected=${if (useRecent) "recent" else "growing"}")
        return Choice(messages, snapshot, growing, recent, useRecent)
    }

    companion object {
        const val RECENT = 20
        const val MODEL_CONTEXT = 1_048_576
        const val IMAGE_TOKENS = 1024   // Documented maximum per image; dimensions are not always available.
        private const val BOS = 0
        private const val EOS = 1
        private const val USER = 128803
        private const val ASSISTANT = 128804
        private const val START_THINK = 128821
        private const val END_THINK = 128822
        private const val SYSTEM = 200000 // Role and schema markers outside the text vocabulary.
        private const val SCHEMA = 200001
        private val BLOCK_BREAK by lazy { DeepSeekTokenizer.encode("\n\n") }
        private const val FLASH_REASONING = "Reasoning Effort: 75 (range 1-100, the higher the value, the more thorough the reasoning)\n\n"

        // The reference template uses json.dumps(ensure_ascii=False) for each function.
        private fun schemaJson(value: Any): String = when (value) {
            is JSONObject -> value.keys().asSequence().joinToString(", ", "{", "}") { key ->
                "${schemaJson(key)}: ${schemaJson(value.get(key))}"
            }
            is JSONArray -> (0 until value.length()).joinToString(", ", "[", "]") { schemaJson(value.get(it)) }
            is String -> JSONObject.quote(value).replace("\\/", "/")
            else -> value.toString()
        }
        private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
