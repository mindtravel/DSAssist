package com.mindtravel.reduction.feature.chat.model.tools

import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.core.host.ToolApprovalHost
import com.mindtravel.reduction.core.stringOrNull
import com.mindtravel.reduction.model.chat.ToolParam
import com.mindtravel.reduction.model.chat.ToolSpec
import com.mindtravel.reduction.model.chat.ChatImage
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one way to dispatch a sub agent. A run happens in the background on the computer:
 * "start" hands over a task and returns at once, so the reply to the user is not held up;
 * "status" reports how far the runs have got, and what a finished one came to; "stop" ends one. Runs are numbered by [runs]; the assistant never has
 * to carry an identifier or a folder from one call to the next.
 */
class RunAgentTool(
    private val harness: AgentHarness,
    private val runs: AgentRuns,
    private val project: String,
    private val agentsRoot: String,
    private val allowWrite: Boolean,
    private val approvalHost: ToolApprovalHost,
    /** How long "start" lingers: only long enough to notice a run that dies at once. */
    private val waitMillis: Long = 2_500,
    private val pollMillis: Long = 500
) : ChatTool {

    override val spec = ToolSpec(
        name = "run_agent",
        description = "派发 sub agent 并查询它们的进度。sub agent 在电脑上后台运行，能力不设限，但只能写它所属的文件夹。" +
            "start 交出任务卡后立刻返回任务编号，不等它做完；" +
            "status 报告还没结束的任务各做到哪了，做完的给出结果；stop 结束一个任务；record 取出一个任务存着的全过程，只在用户明确要看原文时用。任务编号由程序分配。",
        parameters = listOf(
            ToolParam("action", "start：派发新任务；status：查询进度；stop：结束任务；record：取出一个任务的任务卡原文和结果全文", choices = listOf("start", "status", "stop", "record")),
            ToolParam("task", "start 时必填。任务卡：目标、约束、验收标准，以及希望交回什么", required = false),
            ToolParam(
                "folder",
                "start 时可选。sub agent 所属的文件夹：project 表示项目代码目录；其它值是它自己的独立文件夹名，比如 research。默认 research",
                required = false
            ),
            ToolParam(
                "access",
                "start 时可选。read：只读，用于分析代码和独立验收；write：可以修改文件、落盘保存。默认 read",
                required = false,
                choices = listOf("read", "write")
            ),
            ToolParam("run", "record 时必填；status 和 stop 时可选：任务编号，比如 2。不填时，status 报告所有没结束的任务，stop 结束唯一在跑的那个", required = false)
        )
    )

    override fun describe(argumentsJson: String): String {
        val args = JSONObject(argumentsJson)
        return when (args.stringOrNull("action")?.trim()) {
            "start" -> "派发：" + args.stringOrNull("task").orEmpty().trim().lineSequence().firstOrNull().orEmpty()
            "status" -> "查询 sub agent 进度"
            "stop" -> "结束 sub agent 运行"
            else -> spec.name
        }
    }

    /** Handing a task to a sub agent is the costly call: the user hears that it happened. */
    override fun announces(argumentsJson: String): Boolean =
        JSONObject(argumentsJson).stringOrNull("action")?.trim() == "start"

    override fun execute(argumentsJson: String): String {
        val args = JSONObject(argumentsJson)
        if (!harness.isConfigured) return "派发失败：请先配置电脑 SSH"
        return when (args.stringOrNull("action")?.trim()) {
            "start" -> {
                val folderName = args.stringOrNull("folder")?.trim().orEmpty().ifBlank { DEFAULT_FOLDER }
                val folder = folderPath(folderName) ?: return "文件夹名无效：$folderName（只能用字母、数字、点、横线、下划线）"
                start(args, folderName, folder)
            }
            "status" -> status(args)
            "stop" -> stop(args)
            "record" -> record(args)
            else -> "未知的 action，只能是 start、status、stop 或 record"
        }
    }

    /**
     * Looks at every run not yet known to be over and returns the report of each one that
     * has ended, with its number. A run is reported this way once.
     */
    fun endedRuns(): List<Ended> {
        if (!harness.isConfigured) return emptyList()
        // Pictures of a run the assistant asked about itself: its answer then was words, the pictures follow now.
        val held = runs.takePictures().map { (number, report, images) -> Ended(number, report, images) }
        return held + runs.unfinished().mapNotNull { run ->
            val progress = harness.progress(run.folder, run.launcherId)
            if (progress.state == AgentHarness.State.RUNNING) null else Ended(run.number, report(run, progress, attached = true), progress.images)
        }
    }

    /** A run that has ended: its number, its report in words, and the pictures it handed back with it. */
    data class Ended(val number: Int, val report: String, val images: List<ChatImage> = emptyList())

    /** The run a call is about: the one it names, or null when it names none. A wrong number is an error. */
    private fun named(args: JSONObject): Result<AgentRuns.Run?> {
        val raw = args.stringOrNull("run")?.trim().orEmpty().removePrefix("任务").trim()
        if (raw.isEmpty()) return Result.success(null)
        val run = raw.toIntOrNull()?.let(runs::find)
        return if (run != null) Result.success(run) else Result.failure(IllegalArgumentException("没有任务 $raw。${known()}"))
    }

    /** The whole of one run as it is kept: the task as handed over and what came back, word for word. */
    private fun record(args: JSONObject): String {
        val run = named(args).getOrElse { return it.message.orEmpty() } ?: return "record 要填任务编号 run"
        return buildString {
            appendLine("任务 ${run.number} · ${run.outcome.ifEmpty { "进行中" }} · 文件夹 ${run.folderName}")
            appendLine()
            appendLine("【任务卡原文】")
            appendLine(run.task)
            appendLine()
            appendLine("【交回的结果原文】")
            appendLine(run.result.ifBlank { if (run.finished) "（没有交回任何内容）" else "（还在做）" })
            if (run.diff.isNotBlank()) {
                appendLine()
                appendLine("【改动的文件】")
                appendLine(run.diff)
            }
        }.trim()
    }

    private fun known(): String {
        val open = runs.unfinished()
        return if (open.isEmpty()) "现在没有在跑的任务。" else "在跑的任务：" + open.joinToString("；") { "任务 ${it.number}（${it.title}）" }
    }

    private fun status(args: JSONObject): String {
        val one = named(args).getOrElse { return it.message.orEmpty() }
        val asked = if (one != null) listOf(one) else runs.unfinished().ifEmpty { listOfNotNull(runs.latest()) }
        if (asked.isEmpty()) return "还没有派发过任务。"
        return asked.joinToString("\n\n") { run -> report(run, harness.progress(run.folder, run.launcherId)) }
    }

    private fun stop(args: JSONObject): String {
        val one = named(args).getOrElse { return it.message.orEmpty() }
        val open = runs.unfinished()
        val run = one ?: open.singleOrNull()
            ?: return if (open.isEmpty()) "现在没有在跑的任务。" else "有 ${open.size} 个任务在跑，请说明结束哪一个。${known()}"
        harness.stop(run.folder, run.launcherId)
        runs.end(run, AgentRuns.STOPPED)
        return "已结束任务 ${run.number}"
    }

    private fun start(args: JSONObject, folderName: String, folder: String): String {
        val task = args.stringOrNull("task")?.trim().orEmpty()
        if (task.isBlank()) return "派发失败：task 为空"
        val write = args.stringOrNull("access")?.trim() == "write"
        if (write && !allowWrite) return "派发失败：设置里没有允许 sub agent 修改文件"
        val onProject = folderName == PROJECT
        // Changing the project is the one thing the phone is asked about first.
        if (write && onProject && !approve(task)) return "已取消：用户拒绝执行"

        val brief = if (write) {
            "你是执行任务的 sub agent。当前目录是你唯一能写的文件夹。" +
                "交回结果时分两块：依据（只放原文、出处、链接、文件路径、实际 diff、命令输出）和分析（只放推断和判断）。" +
                "你只做执行、预筛和整理，不替人下结论。$PICTURES\n\n$task"
        } else {
            "你是只读的 sub agent，禁止修改任何文件。" +
                "交回结果时分两块：依据（只放原文、出处、文件路径和行号、命令输出）和分析（只放推断和判断）。" +
                "如果任务是验收：你不是执行者，逐条对照任务卡；第一行只写 PASS 或 FAIL，随后列直接证据，最后指出最值得人亲自看的一个判决点。$PICTURES\n\n$task"
        }

        val id = try {
            harness.start(if (write) AgentHarness.Agent.BUILD else AgentHarness.Agent.PLAN, folder, brief)
        } catch (t: IllegalStateException) {
            return "派发失败：${t.message}"
        }
        val run = runs.add(id, folderName, folder, task, write = write, changesProject = write && onProject)
        Logs.add("Agent run ${run.number} started id=$id folder=$folderName write=$write")

        val deadline = System.currentTimeMillis() + waitMillis
        var progress = harness.progress(folder, id)
        while (progress.state == AgentHarness.State.RUNNING && System.currentTimeMillis() < deadline) {
            Thread.sleep(pollMillis)
            progress = harness.progress(folder, id)
        }
        if (progress.state != AgentHarness.State.RUNNING) return report(run, progress)
        return "【已派发】任务 ${run.number}：${run.title}\n它在电脑上后台运行。现在就回复用户，不要等它；之后用 action=status 查进度和结果。"
    }

    /**
     * What a run has come to, in words. [attached] says the pictures travel with these words,
     * as they do when the watch delivers them; a tool's answer cannot carry pictures, so
     * there they are held back and follow in a message of their own.
     */
    private fun report(run: AgentRuns.Run, progress: AgentHarness.Progress, attached: Boolean = false): String = buildString {
        val wasOpen = !run.finished
        val name = "任务 ${run.number}"
        when (progress.state) {
            AgentHarness.State.RUNNING -> {
                appendLine("【进行中】$name：${run.title}")
                appendLine("已执行 ${progress.steps.size} 步：")
                progress.steps.forEach { appendLine("- $it") }
                if (progress.said.isNotBlank()) {
                    appendLine("目前说到：")
                    appendLine(progress.said)
                }
            }
            AgentHarness.State.DONE -> {
                val diff = if (!run.changesProject) "" else {
                    runCatching { harness.changes(run.folder) }.getOrDefault("").trim()
                }
                val said = (progress.errors.map { "错误：$it" } + listOf(progress.said).filter { it.isNotBlank() }).joinToString("\n")
                runs.end(run, if (progress.succeeded) AgentRuns.DONE else AgentRuns.FAILED, said, diff)
                appendLine(if (progress.succeeded) "【完成】$name" else "【失败】$name，退出码 ${progress.exitCode}")
                progress.errors.forEach { appendLine("错误：$it") }
                if (progress.said.isNotBlank()) appendLine(progress.said)
                if (progress.said.isBlank() && progress.errors.isEmpty()) appendLine("sub agent 没有留下任何输出。")
                if (diff.isNotBlank()) {
                    appendLine()
                    appendLine("git diff --stat:")
                    appendLine(diff)
                }
                if (progress.images.isNotEmpty()) {
                    appendLine()
                    val names = progress.images.joinToString("、") { it.name }
                    if (attached) {
                        appendLine("附图 ${progress.images.size} 张，图片本身就在这条消息里：$names")
                    } else {
                        appendLine("附图 ${progress.images.size} 张（$names）：这次查询的回答里带不了图，图片随后会单独发给你，那时再看图说话。")
                        if (wasOpen) {
                            runs.holdPictures(
                                run.number,
                                "任务 ${run.number} 的附图 ${progress.images.size} 张，图片本身就在这条消息里：$names",
                                progress.images
                            )
                        }
                    }
                }
                if (progress.skippedImages.isNotEmpty()) {
                    appendLine("没有带回来的图（太多或太大）：" + progress.skippedImages.joinToString("、"))
                }
            }
            AgentHarness.State.LOST -> {
                runs.end(run, AgentRuns.LOST, progress.errors.joinToString("\n") { "错误：$it" })
                appendLine("【找不到】$name 已经不在电脑上，可能意外中断了。")
                progress.errors.forEach { appendLine("错误：$it") }
            }
        }
    }.trim()

    private fun folderPath(name: String): String? = when {
        name == PROJECT -> project
        FOLDER_NAME.matches(name) -> "$agentsRoot/$name"
        else -> null
    }

    private fun approve(task: String): Boolean {
        val result = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        approvalHost.requestToolApproval("sub agent 修改项目代码", "项目：$project\n\n$task") { allowed ->
            result.set(allowed)
            latch.countDown()
        }
        return runCatching { latch.await(5, TimeUnit.MINUTES) && result.get() }.getOrDefault(false)
    }

    private companion object {
        const val PROJECT = "project"
        const val DEFAULT_FOLDER = "research"

        /** How a sub agent hands back pictures to be looked at. */
        const val PICTURES = "需要让对方亲眼看的图（图表、截图、生成的图片），把图片文件放进 ${AgentHarness.RESULT_FOLDER}/，" +
            "png、jpg、webp 或 gif，文件名只用字母、数字、点、横线、下划线，最多 ${AgentHarness.MAX_IMAGES} 张、每张不超过 3 MB；" +
            "对方收到的是图片本身。这个文件夹任何时候都可写。"
        val FOLDER_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$")
    }
}
