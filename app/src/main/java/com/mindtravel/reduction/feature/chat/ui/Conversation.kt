package com.mindtravel.reduction.feature.chat.ui

import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.core.events.EventSink
import com.mindtravel.reduction.core.settings.SettingsGateway
import com.mindtravel.reduction.feature.chat.data.CardRepository
import com.mindtravel.reduction.feature.chat.data.HistoryRepository
import com.mindtravel.reduction.feature.chat.model.ContextBudget
import com.mindtravel.reduction.feature.chat.model.ContextLine
import com.mindtravel.reduction.feature.chat.model.SystemPrompt
import com.mindtravel.reduction.model.chat.ChatImage
import com.mindtravel.reduction.model.chat.Role
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * The conversation with the assistant: what has been said, and the one way to say
 * something more. It needs no screen — a turn spoken with the display off goes through
 * [submit] exactly as a typed one does. A UI, when there is one, is told of each new
 * exchange and can read the history.
 */
class Conversation(
    private val settingsRepository: SettingsGateway,
    private val history: HistoryRepository,
    private val cards: CardRepository,
    private val chat: ChatController,
    private val emitter: EventSink,
    /** Today's date as yyyy-MM-dd. */
    private val today: () -> String,
    /** Things about the user's devices worth a sentence in this reply, such as a battery running low. */
    private val deviceNotices: () -> List<String> = { emptyList() },
    /** To-dos without a date: said once a day, when the user first speaks. */
    private val todoNotices: () -> List<String> = { emptyList() },
    /** Where the assistant is running, as it should describe itself. */
    private val place: String = "安卓手机"
) {
    private val lines = ArrayList<ContextLine>()
    private val budget = ContextBudget()
    private var previous = history.checkpoint.load(history.clearedAt)
    private var generation = 0L

    /** Told whenever something was said here, by the user or the assistant, or the conversation was cleared. */
    @Volatile
    var onChange: () -> Unit = {}

    init {
        runCatching {
            val saved = JSONArray(history.load())
            var unnamed = false
            val now = System.currentTimeMillis()
            for (i in 0 until saved.length()) {
                val item = saved.optJSONObject(i) ?: continue
                val role = when (item.optString("role")) {
                    "user" -> Role.USER
                    "assistant" -> Role.ASSISTANT
                    else -> continue
                }
                val content = if (role == Role.ASSISTANT) said(item.optString("content")) else item.optString("content")
                if (content.isBlank()) continue
                val id = item.optString("id")
                if (id.isEmpty() || !item.has("at")) unnamed = true
                // A line written before lines had names: named now, and dated in the order it stands in.
                lines += ContextLine(id.ifEmpty { UUID.randomUUID().toString() }, role, content, item.optLong("at", now - (saved.length() - i) * 1000L))
            }
            if (unnamed) persist()
        }.onFailure { Logs.add("History unreadable: ${it.message}") }
    }

    /**
     * Says [raw] to the assistant and starts its reply. Returns false, having told the UI
     * why, when it cannot be sent.
     */
    @Synchronized
    fun submit(raw: String): Boolean = submit(raw, raw)

    /**
     * As [submit], for a message that does not come from the user's mouth, such as the
     * report of a finished sub agent: the assistant is given [raw], the screen shows [shown].
     */
    @Synchronized
    fun submit(raw: String, shown: String): Boolean = submit(raw, shown, emptyList())

    /**
     * As above, with pictures for the assistant to look at. They go to the model with the
     * message, and again while they are among the recent pictures. The record on disk keeps the words.
     */
    @Synchronized
    fun submit(raw: String, shown: String, images: List<ChatImage>): Boolean {
        val text = raw.trim()
        if (text.isEmpty()) return false
        val settings = settingsRepository.load()
        if (settings.apiKey.isBlank()) {
            emitter.emit("onError", "", "请先填写 DeepSeek API Key")
            return false
        }

        lines += ContextLine(UUID.randomUUID().toString(), Role.USER, text, System.currentTimeMillis(), images)
        save()
        val id = UUID.randomUUID().toString()
        emitter.emit("onReplyBegin", id, shown.trim().ifEmpty { text })

        val system = SystemPrompt.build(
            settings, today(), cards.promptDirectory(),
            runCatching(deviceNotices).getOrDefault(emptyList()),
            runCatching(todoNotices).getOrDefault(emptyList()), place
        )
        val turn = ++generation
        val reference = previous
        val entries = lines.toList()
        chat.send(id, settings,
            prepare = { tools, checkActive -> budget.choose(reference, system, entries, settings.model, settings.apiKey, tools, checkActive) },
            onAccepted = { checkpoint -> accept(turn, checkpoint) },
            onCompleted = { reply -> remember(turn, reply) }
        )
        return true
    }

    /**
     * What the user typed together with pictures picked, photographed or pasted. [picturesJson]
     * is a list of {name, mediaType, base64}. The words kept say that pictures came along.
     */
    @Synchronized
    fun submitWithPictures(raw: String, picturesJson: String): Boolean {
        val images = ChatImage.listFromJson(picturesJson)
        if (images.isEmpty()) return submit(raw)
        val said = raw.trim().ifEmpty { "请看图。" }
        // Each picture is kept as something a card can hold, under an id the program gives it. The
        // assistant is told the ids, so that it can put a picture into a card where it belongs; one
        // that goes into no card is cleared away with the rest that nothing refers to.
        val ids = images.map { image ->
            val kind = if (image.mediaType == "image/jpeg") "jpg" else image.mediaType.substringAfter('/')
            runCatching { cards.saveMedia(java.util.Base64.getDecoder().decode(image.base64), kind) }.getOrNull()
        }
        val note = if (ids.all { it != null }) {
            "［随这条消息发来 ${images.size} 张图片，按顺序的附件编号：" + ids.joinToString("、") +
                "。要把哪张放进卡片，就在卡片里写一行 ![说明](media:编号)］"
        } else {
            "［随这条消息发来 ${images.size} 张图片］"
        }
        return submit("$said\n$note", said, images)
    }

    /** The recent transcript used by the UI and device sync. The active model window may be longer. */
    @Synchronized
    fun historyJson(): String = encodeLines(lines.takeLast(KEPT_LINES))

    private fun encodeLines(entries: List<ContextLine>): String = JSONArray().apply {
        for (line in entries) {
            put(
                JSONObject().put("role", if (line.role == Role.USER) "user" else "assistant").put("content", line.content)
                    .put("id", line.id).put("at", line.at)
            )
        }
    }.toString()

    @Synchronized
    fun clear() {
        resetContext()
        lines.clear()
        history.clear()
        history.clearedAt = System.currentTimeMillis()
        runCatching { onChange() }
    }

    // ── sharing the conversation with another device ────────────────────────

    /** The conversation as another device needs it to merge with its own. */
    @Synchronized
    fun export(): JSONObject = JSONObject().put("clearedAt", history.clearedAt).put("lines", JSONArray(historyJson()))

    /**
     * Takes in another device's conversation: what was said there joins what was said
     * here, in the order it was said, and what was cleared there is cleared here. Returns
     * whether anything changed.
     */
    @Synchronized
    fun merge(remote: JSONObject): Boolean {
        val before = lines.map { it.id }
        val cleared = maxOf(history.clearedAt, remote.optLong("clearedAt"))
        if (cleared > history.clearedAt) {
            resetContext()
            history.clearedAt = cleared
        }
        lines.removeAll { it.at <= cleared }
        val known = lines.mapTo(HashSet()) { it.id }
        val theirs = remote.optJSONArray("lines") ?: JSONArray()
        for (i in 0 until theirs.length()) {
            val item = theirs.optJSONObject(i) ?: continue
            val id = item.optString("id")
            val at = item.optLong("at")
            val role = when (item.optString("role")) {
                "user" -> Role.USER
                "assistant" -> Role.ASSISTANT
                else -> continue
            }
            val content = item.optString("content")
            if (id.isEmpty() || content.isBlank() || at <= cleared || !known.add(id)) continue
            lines += ContextLine(id, role, content, at)
        }
        lines.sortBy { it.at }
        trimHistory()
        if (lines.map { it.id } == before) return false
        persist()
        emitter.emit("onHistoryChanged", "")
        return true
    }

    @Synchronized
    private fun remember(turn: Long, reply: String) {
        if (turn != generation || reply.isBlank()) return
        lines += ContextLine(UUID.randomUUID().toString(), Role.ASSISTANT, reply, System.currentTimeMillis())
        save()
    }

    @Synchronized
    private fun accept(turn: Long, checkpoint: ContextBudget.Snapshot) {
        if (turn != generation) return
        previous = checkpoint
        history.checkpoint.save(checkpoint, history.clearedAt)
        if (trimHistory()) persist()
    }

    private fun resetContext() {
        generation++
        previous = null
        history.checkpoint.clear()
        chat.cancel()
    }

    /** Retain enough local history for both the last 100 visible messages and the active window. */
    private fun trimHistory(): Boolean {
        // A device merge can shift several pictures past the visible window at once.
        for (i in 0 until lines.size - ContextBudget.RECENT) {
            if (lines[i].images.isNotEmpty()) lines[i] = lines[i].copy(images = emptyList())
        }
        val excess = lines.size - KEPT_LINES
        if (excess <= 0) return false
        val anchor = previous?.firstId
        val start = if (anchor == null) -1 else lines.indexOfFirst { it.id == anchor }
        val drop = if (start < 0) excess else minOf(start, excess)
        if (drop == 0) return false
        lines.subList(0, drop).clear()
        return true
    }

    private fun persist() { history.save(encodeLines(lines)) }

    private fun save() {
        trimHistory()
        persist()
        runCatching { onChange() }
    }

    private companion object {
        /**
         * A stored reply may be written in two tagged sections, the second one never read
         * aloud. Only what was said is kept, so the assistant sees one way of replying.
         */
        fun said(reply: String): String = reply.substringBefore("【详情】").replace("【朗读】", "").trim()

        const val KEPT_LINES = 100
    }
}
