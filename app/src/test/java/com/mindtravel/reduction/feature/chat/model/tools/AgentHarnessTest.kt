package com.mindtravel.reduction.feature.chat.model.tools

import com.mindtravel.reduction.model.chat.Role
import com.mindtravel.reduction.model.chat.ChatMessage
import com.mindtravel.reduction.model.chat.ChatImage
import com.mindtravel.reduction.core.host.ToolApprovalHost
import com.mindtravel.reduction.hardware.pc.PcGateway
import com.mindtravel.reduction.hardware.pc.PcInfo
import com.mindtravel.reduction.hardware.pc.PcProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AgentHarnessTest {

    private fun harness(script: String) =
        AgentHarness(LocalShell(), PcProfile("host", 22, "user"), "sk-test_KEY-1", "deepseek-flash") { script }

    /**
     * A stand-in launcher: "start" records its arguments and answers RUN t1; "status"
     * prints whatever is in the status file; "stop" records that it was asked.
     */
    private class FakeLauncher {
        val dir: File = Files.createTempDirectory("harness-test").toFile()
        val script = """
            d='${dir.absolutePath}'
            case "${'$'}1" in
              start) printf '%s\n' "${'$'}2" "${'$'}3" "${'$'}4" > "${'$'}d/started"; echo "RUN t1" ;;
              status) cat "${'$'}d/status" ;;
              stop) echo "${'$'}3" > "${'$'}d/stopped"; echo STOPPED ;;
              changes) echo "${'$'}2" > "${'$'}d/compared"; echo " main.kt | 2 +-" ;;
            esac
        """.trimIndent() + "\n"

        fun status(text: String) = File(dir, "status").writeText(text)
        fun started(): List<String> = File(dir, "started").takeIf { it.isFile }?.readLines().orEmpty()
        fun stopped(): String = File(dir, "stopped").takeIf { it.isFile }?.readText()?.trim().orEmpty()
        fun compared(): String = File(dir, "compared").takeIf { it.isFile }?.readText()?.trim().orEmpty()
    }

    /** A shell that remembers every command line it was given. */
    private class RecordingShell(private val shell: LocalShell = LocalShell()) : PcGateway by shell {
        val commands = ArrayList<String>()
        override fun exec(profile: PcProfile, command: String): String = shell.exec(profile, command).also { commands += command }
        override fun send(profile: PcProfile, command: String, input: ByteArray): String =
            shell.send(profile, command, input).also { commands += command }
    }

    private class Approval(private val answer: Boolean) : ToolApprovalHost {
        var asked = 0
        override fun requestToolApproval(title: String, message: String, callback: (Boolean) -> Unit) {
            asked++
            callback(answer)
        }
    }

    /** One registry for the whole test, as in the app: it outlives each tool. */
    private val runs = AgentRuns()

    private fun tool(launcher: FakeLauncher, allowWrite: Boolean = true, approval: Approval = Approval(true)) =
        RunAgentTool(
            harness = harness(launcher.script),
            runs = runs,
            project = "/home/u/project",
            agentsRoot = "/home/u/agents",
            allowWrite = allowWrite,
            approvalHost = approval,
            waitMillis = 300,
            pollMillis = 20
        )

    private fun args(vararg pairs: Pair<String, String>) = JSONObject(mapOf(*pairs)).toString()

    private val textEvent = """{"type":"text","part":{"type":"text","text":"做完了。"}}"""
    private val toolEvent =
        """{"type":"tool","part":{"type":"tool","tool":"bash","callID":"c1","state":{"status":"completed","input":{"command":"ls -la"}}}}"""

    @Test
    fun argumentsReachTheLauncherIntact() {
        val received = File(Files.createTempDirectory("harness-args").toFile(), "args")
        val record = "printf '%s|%s|%s|%s' \"\$1\" \"\$2\" \"\$3\" \"\$4\" > '${received.absolutePath}'; echo 'RUN t9'\n"
        val prompt = "改一下 `main`：把 \$HOME 换成 'x'，\n第二行 \"引号\"；rm -rf /"

        val id = harness(record).start(AgentHarness.Agent.BUILD, "/home/someone/My Project", prompt)

        assertEquals("t9", id)
        assertEquals("start|build|/home/someone/My Project|$prompt", received.readText())
    }

    @Test
    fun aRunStartsOnThePhonesKeyAndModel() {
        val received = File(Files.createTempDirectory("harness-env").toFile(), "env")
        val record = "printf '%s|%s' \"\$AGENT_KEY\" \"\$AGENT_MODEL\" > '${received.absolutePath}'; echo 'RUN t1'\n"
        harness(record).start(AgentHarness.Agent.PLAN, "/home/u/p", "问")
        assertEquals("sk-test_KEY-1|deepseek/deepseek-flash", received.readText())
    }

    @Test
    fun aRunThatCannotStartReportsTheLaunchersMessage() {
        val launcher = "printf '%s\\n' 'harness：电脑上没有 bwrap，无法隔离运行'\n"
        val said = RunAgentTool(harness(launcher), AgentRuns(), "/home/u/p", "/home/u/a", true, Approval(true))
            .execute(args("action" to "start", "task" to "查资料"))
        assertEquals("派发失败：harness：电脑上没有 bwrap，无法隔离运行", said)
    }

    @Test
    fun progressIsReadFromTheEvents() {
        val progress = AgentHarness.parse("STATE running\n$toolEvent\n$textEvent\n")
        assertEquals(AgentHarness.State.RUNNING, progress.state)
        assertEquals(listOf("bash: ls -la（completed）"), progress.steps)
        assertEquals("做完了。", progress.said)
    }

    @Test
    fun aModelErrorIsReportedNotSwallowed() {
        val error = """{"type":"error","error":{"name":"APIError","data":{"message":"Insufficient Balance (request_id: x)","statusCode":402}}}"""
        val progress = AgentHarness.parse("STATE done 1\n$error\n")
        assertFalse(progress.succeeded)
        assertEquals(listOf("Insufficient Balance (request_id: x)"), progress.errors)
    }

    @Test
    fun aFinishedTaskReturnsWhatTheAgentSaid() {
        val launcher = FakeLauncher()
        launcher.status("STATE done 0\n$toolEvent\n$textEvent\n")
        val said = tool(launcher).execute(args("action" to "start", "task" to "查资料"))
        assertEquals("【完成】任务 1\n做完了。", said)
        assertEquals(listOf("plan", "/home/u/agents/research"), launcher.started().take(2))
    }

    @Test
    fun startReturnsAtOnceAndTheResultComesWithStatus() {
        val launcher = FakeLauncher()
        launcher.status("STATE running\n$toolEvent\n")
        val startedAt = System.currentTimeMillis()
        val said = tool(launcher).execute(args("action" to "start", "task" to "大任务", "folder" to "notes", "access" to "write"))
        assertTrue("start held the reply for ${System.currentTimeMillis() - startedAt}ms", System.currentTimeMillis() - startedAt < 2_000)
        assertTrue(said, said.startsWith("【已派发】任务 1：大任务"))
        assertEquals(listOf("build", "/home/u/agents/notes"), launcher.started().take(2))

        val during = tool(launcher).execute(args("action" to "status"))
        assertTrue(during, during.startsWith("【进行中】任务 1：大任务") && during.contains("- bash: ls -la（completed）"))

        // Asking later needs neither the number nor the folder: the app knows both.
        launcher.status("STATE done 0\n$toolEvent\n$textEvent\n")
        assertEquals("【完成】任务 1\n做完了。", tool(launcher).execute(args("action" to "status")))
        assertEquals("现在没有在跑的任务。", tool(launcher).execute(args("action" to "stop")))
    }

    @Test
    fun theDefaultWaitIsTooShortToHoldUpAReply() {
        val launcher = FakeLauncher()
        launcher.status("STATE running\n")
        val tool = RunAgentTool(harness(launcher.script), AgentRuns(), "/home/u/p", "/home/u/a", true, Approval(true))
        val startedAt = System.currentTimeMillis()
        tool.execute(args("action" to "start", "task" to "大任务"))
        assertTrue(System.currentTimeMillis() - startedAt < 5_000)
    }

    @Test
    fun changingTheProjectAsksOnThePhoneFirst() {
        val launcher = FakeLauncher()
        launcher.status("STATE done 0\n$textEvent\n")
        val refused = Approval(false)
        val said = tool(launcher, approval = refused)
            .execute(args("action" to "start", "task" to "改代码", "folder" to "project", "access" to "write"))
        assertEquals("已取消：用户拒绝执行", said)
        assertEquals(1, refused.asked)
        assertEquals("nothing was started", emptyList<String>(), launcher.started())

        val reading = Approval(false)
        tool(launcher, approval = reading).execute(args("action" to "start", "task" to "看代码", "folder" to "project"))
        assertEquals("reading the project needs no approval", 0, reading.asked)
        assertEquals(listOf("plan", "/home/u/project"), launcher.started().take(2))
    }

    @Test
    fun theKeyIsNeverOnTheCommandLine() {
        val received = File(Files.createTempDirectory("harness-key").toFile(), "env")
        val record = "printf '%s' \"\$AGENT_KEY\" > '${received.absolutePath}'; echo 'RUN t1'\n"
        val shell = RecordingShell()
        val harness = AgentHarness(shell, PcProfile("host", 22, "user"), "sk-test_KEY-1", "deepseek-flash") { record }

        harness.start(AgentHarness.Agent.BUILD, "/home/u/p", "问")

        assertEquals("the launcher still gets the key", "sk-test_KEY-1", received.readText())
        assertEquals(1, shell.commands.size)
        // Neither in plain text nor inside the encoded script: the process list shows both.
        val encodedKey = java.util.Base64.getEncoder().encodeToString("sk-test_KEY-1".toByteArray())
        shell.commands.forEach { command ->
            assertFalse(command, command.contains("sk-test_KEY-1"))
            assertFalse(command, command.contains(encodedKey.take(12)))
        }
    }

    @Test
    fun theProjectsChangesAreComparedByTheLauncherNotByGitOnTheComputer() {
        val launcher = FakeLauncher()
        launcher.status("STATE done 0\n$textEvent\n")
        val shell = RecordingShell()
        val tool = RunAgentTool(
            AgentHarness(shell, PcProfile("host", 22, "user"), "sk-test_KEY-1", "deepseek-flash") { launcher.script },
            runs, "/home/u/project", "/home/u/agents", true, Approval(true), 300, 20
        )

        val said = tool.execute(args("action" to "start", "task" to "改代码", "folder" to "project", "access" to "write"))

        assertTrue(said, said.contains("git diff --stat:\nmain.kt | 2 +-"))
        assertEquals("/home/u/project", launcher.compared())
        shell.commands.forEach { assertFalse("git ran outside the launcher: $it", it.contains("git -C")) }
    }

    @Test
    fun writingIsRefusedWhenTheSettingIsOff() {
        val launcher = FakeLauncher()
        val said = tool(launcher, allowWrite = false)
            .execute(args("action" to "start", "task" to "存资料", "access" to "write"))
        assertEquals("派发失败：设置里没有允许 sub agent 修改文件", said)
        assertEquals(emptyList<String>(), launcher.started())
    }

    @Test
    fun aFolderNameCannotEscapeTheAgentsRoot() {
        val launcher = FakeLauncher()
        for (name in listOf("../secret", "a/b", "/etc", "..", ".hidden")) {
            val said = tool(launcher).execute(args("action" to "start", "task" to "x", "folder" to name))
            assertTrue("$name -> $said", said.startsWith("文件夹名无效"))
        }
        assertEquals(emptyList<String>(), launcher.started())
    }

    @Test
    fun stopEndsTheOnlyRunWithoutBeingToldWhich() {
        val launcher = FakeLauncher()
        launcher.status("STATE running\n")
        tool(launcher).execute(args("action" to "start", "task" to "大任务"))
        assertEquals("已结束任务 1", tool(launcher).execute(args("action" to "stop")))
        assertEquals("t1", launcher.stopped())
    }

    @Test
    fun withSeveralRunsTheNumberSaysWhich() {
        val launcher = FakeLauncher()
        launcher.status("STATE running\n")
        tool(launcher).execute(args("action" to "start", "task" to "查甲"))
        tool(launcher).execute(args("action" to "start", "task" to "查乙", "folder" to "notes"))

        val all = tool(launcher).execute(args("action" to "status"))
        assertTrue(all, all.contains("任务 1：查甲") && all.contains("任务 2：查乙"))
        val one = tool(launcher).execute(args("action" to "status", "run" to "任务 2"))
        assertTrue(one, one.contains("任务 2：查乙") && !one.contains("查甲"))

        val unclear = tool(launcher).execute(args("action" to "stop"))
        assertTrue(unclear, unclear.startsWith("有 2 个任务在跑，请说明结束哪一个。"))
        assertEquals("已结束任务 2", tool(launcher).execute(args("action" to "stop", "run" to "2")))
        val wrong = tool(launcher).execute(args("action" to "status", "run" to "9"))
        assertTrue(wrong, wrong.startsWith("没有任务 9。在跑的任务：任务 1（查甲）"))
    }

    @Test
    fun aRunThatHasEndedIsReportedOnceWithoutBeingAskedFor() {
        val launcher = FakeLauncher()
        launcher.status("STATE running\n")
        tool(launcher).execute(args("action" to "start", "task" to "查甲"))
        assertEquals(emptyList<RunAgentTool.Ended>(), tool(launcher).endedRuns())

        launcher.status("STATE done 0\n$textEvent\n")
        assertEquals(listOf(RunAgentTool.Ended(1, "【完成】任务 1\n做完了。")), tool(launcher).endedRuns())
        assertEquals(emptyList<RunAgentTool.Ended>(), tool(launcher).endedRuns())
    }

    @Test
    fun runNumbersAndRecordsSurviveARestart() {
        val file = File(Files.createTempDirectory("runs").toFile(), "runs.json")
        val first = AgentRuns(file)
        first.add("t1", "research", "/a/research", "查甲\n第二行")
        first.end(first.add("t2", "notes", "/a/notes", "查乙", write = true), AgentRuns.DONE, "乙的结果")

        val again = AgentRuns(file)
        assertEquals(listOf(1), again.unfinished().map { it.number })
        assertEquals("查甲", again.find(1)!!.title)
        assertEquals("查甲\n第二行", again.find(1)!!.task)
        assertEquals(listOf("/a/notes", "完成", "乙的结果"), again.find(2)!!.let { listOf(it.folder, it.outcome, it.result) })
        assertEquals(3, again.add("t3", "research", "/a/research", "查丙").number)
    }

    @Test
    fun theFirstEndingRecordedIsTheOneThatStays() {
        val runs = AgentRuns()
        val run = runs.add("t1", "research", "/a/research", "查甲")
        runs.end(run, AgentRuns.DONE, "结果")
        runs.end(run, AgentRuns.LOST, "别的")
        assertEquals(listOf("完成", "结果"), listOf(run.outcome, run.result))
    }

    @Test
    fun theLedgerKeepsOneLineOfTheGoalAndOneOfTheResultAndTheWholeStaysOnRecord() {
        var clock = 1_700_000_000_000L
        val runs = AgentRuns(now = { clock })
        val launcher = FakeLauncher()
        launcher.status("STATE running\n")
        val tool = RunAgentTool(harness(launcher.script), runs, "/home/u/project", "/home/u/agents", true, Approval(true), 300, 20)
        tool.execute(args("action" to "start", "task" to "## 查甲的出处\n## 约束：只读\n交回原文和链接", "folder" to "notes"))

        val during = AgentLedger.render(runs.all())
        assertTrue(during, during.contains("## 任务 1 · 进行中"))
        assertTrue(during, during.contains("- 目标：查甲的出处\n- 结果：（还在做）"))
        // The rest of the task is not on the card.
        assertFalse(during, during.contains("约束") || during.contains("交回原文"))

        clock += 200_000
        launcher.status("STATE done 0\n$textEvent\n")
        tool.execute(args("action" to "status"))
        val after = AgentLedger.render(runs.all())
        assertTrue(after, after.contains("## 任务 1 · 完成") && after.contains("用时 3 分钟") && after.contains("- 结果：做完了。"))
        assertFalse(after, after.contains("（还在做）"))

        // The whole of it is still there for the asking.
        val record = tool.execute(args("action" to "record", "run" to "1"))
        assertTrue(record, record.contains("## 约束：只读") && record.contains("交回原文和链接") && record.contains("做完了。"))
        assertTrue(tool.execute(args("action" to "record")).contains("要填任务编号"))
    }

    @Test
    fun aLineOfTheLedgerIsTheFirstThatSaysSomethingCutToLength() {
        assertEquals("找到三篇", AgentLedger.line("## 依据\n\n- 找到三篇\n- 第二条", 120))
        assertEquals("错误：超时", AgentLedger.line("错误：超时\n别的", 120))
        assertEquals("一二三四…", AgentLedger.line("一二三四五六七", 5))
        assertEquals("", AgentLedger.line("  \n---\n", 120))
        assertEquals("FAIL", AgentLedger.line("**FAIL**\n理由如下", 120))
    }

    @Test
    fun picturesLeftBesideTheResultComeBackAsPicturesAndTheWordsAreAsBefore() {
        val plain = "STATE done 0\n$textEvent\n\n=====RESULT FILE=====\n结果文件"
        val before = AgentHarness.parse(plain)
        val after = AgentHarness.parse(
            plain + "\n=====IMAGE chart.png image/png=====\nQUJD\nREVG\n=====IMAGE SKIPPED huge.png=====\n" +
                "\n=====IMAGE photo.jpg image/jpeg=====\nR0hJ"
        )
        // What came back in words is untouched by the pictures after it.
        assertEquals(listOf(before.said, before.resultFile, before.content), listOf(after.said, after.resultFile, after.content))
        assertEquals("结果文件", after.resultFile)
        assertTrue(before.images.isEmpty() && before.skippedImages.isEmpty())
        assertEquals(
            listOf(ChatImage("chart.png", "image/png", "QUJDREVG"), ChatImage("photo.jpg", "image/jpeg", "R0hJ")),
            after.images
        )
        assertEquals(listOf("huge.png"), after.skippedImages)
    }

    @Test
    fun aFinishedRunHandsItsPicturesOnWithItsReport() {
        val runs = AgentRuns()
        val launcher = FakeLauncher()
        launcher.status("STATE running\n")
        val tool = RunAgentTool(harness(launcher.script), runs, "/home/u/project", "/home/u/agents", true, Approval(true), 300, 20)
        tool.execute(args("action" to "start", "task" to "画一张图", "folder" to "notes"))
        launcher.status("STATE done 0\n$textEvent\n\n=====IMAGE chart.png image/png=====\nQUJD")
        val ended = tool.endedRuns().single()
        assertEquals(listOf(ChatImage("chart.png", "image/png", "QUJD")), ended.images)
        assertTrue(ended.report, ended.report.startsWith("【完成】任务 1\n做完了。") && ended.report.contains("附图 1 张") && ended.report.contains("chart.png"))
        // The record keeps the words; the picture is not stored in it.
        assertFalse(runs.find(1)!!.result.contains("QUJD"))
    }

    @Test
    fun picturesOfARunTheAssistantAskedAboutFollowInAMessageOfTheirOwn() {
        val runs = AgentRuns()
        val launcher = FakeLauncher()
        launcher.status("STATE running\n")
        val tool = RunAgentTool(harness(launcher.script), runs, "/home/u/project", "/home/u/agents", true, Approval(true), 300, 20)
        tool.execute(args("action" to "start", "task" to "画一张图", "folder" to "notes"))
        launcher.status("STATE done 0\n$textEvent\n\n=====IMAGE chart.png image/png=====\nQUJD")
        // A tool's answer is words: it says the picture is on its way, and does not claim to hold it.
        val answer = tool.execute(args("action" to "status"))
        assertTrue(answer, answer.contains("chart.png") && answer.contains("随后会单独发给你") && !answer.contains("就在这条消息里"))
        val ended = tool.endedRuns().single()
        assertEquals(listOf(ChatImage("chart.png", "image/png", "QUJD")), ended.images)
        assertTrue(ended.report, ended.report.contains("任务 1 的附图 1 张"))
        // Handed over once.
        assertTrue(tool.endedRuns().isEmpty())
    }

    @Test
    fun picturesFromThePageAreReadAndWhatIsNotAPictureIsLeftOut() {
        val read = ChatImage.listFromJson(
            """[{"name":"照片","mediaType":"image/jpeg","base64":"QUJD"},{"name":"x","mediaType":"text/html","base64":"QUJD"},
               {"mediaType":"image/png","base64":"REVG"},{"name":"空","mediaType":"image/png","base64":""}]"""
        )
        assertEquals(listOf(ChatImage("照片", "image/jpeg", "QUJD"), ChatImage("图片 3", "image/png", "REVG")), read)
        assertTrue(ChatImage.listFromJson("不是 JSON").isEmpty())
        val many = "[" + (1..9).joinToString(",") { """{"name":"p$it","mediaType":"image/png","base64":"QUJD"}""" } + "]"
        assertEquals(ChatImage.MAX_PER_MESSAGE, ChatImage.listFromJson(many).size)
    }

    @Test
    fun aMessageWithPicturesIsSentAsTextAndPicturesAndOneWithoutIsSentAsBefore() {
        val plain = ChatMessage(Role.USER, "看看").toJson()
        assertEquals("看看", plain.get("content"))
        val parts = ChatMessage(Role.USER, "看看", images = listOf(ChatImage("a.png", "image/png", "QUJD"))).toJson().getJSONArray("content")
        assertEquals(2, parts.length())
        assertEquals("看看", parts.getJSONObject(0).getString("text"))
        assertEquals("image_url", parts.getJSONObject(1).getString("type"))
        assertEquals("data:image/png;base64,QUJD", parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
    }

    @Test
    fun theLedgerShowsTheNewestRunFirst() {
        val runs = AgentRuns()
        runs.add("t1", "research", "/a/research", "查甲")
        runs.add("t2", "research", "/a/research", "查乙")
        val card = AgentLedger.render(runs.all())
        assertTrue(card.indexOf("## 任务 2") in 1 until card.indexOf("## 任务 1"))
    }
}
