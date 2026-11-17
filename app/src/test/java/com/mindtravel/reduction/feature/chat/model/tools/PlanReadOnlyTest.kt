package com.mindtravel.reduction.feature.chat.model.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Runs the real harness with a stand-in for bwrap and reads the sandbox arguments it
 * would have used. A plan task must hand the folder to bwrap read-only for every engine,
 * not only for Claude Code; opencode's own plan mode is a policy, not a kernel wall.
 */
class PlanReadOnlyTest {

    private lateinit var home: File
    private lateinit var script: File
    private lateinit var folder: File

    @Before
    fun setUp() {
        assumeTrue("needs a POSIX shell", File("/bin/sh").isFile)
        assumeTrue("needs setsid", available("setsid"))
        assumeTrue("needs timeout", available("timeout"))

        val harness = File("src/main/assets/harness/run-agent.sh")
        assumeTrue("harness script not found at ${harness.absolutePath}", harness.isFile)
        script = harness

        home = Files.createTempDirectory("reduction-plan-test").toFile().canonicalFile
        folder = File(home, "research").apply { mkdirs() }

        // Both are looked up by the harness: bwrap through PATH, opencode under $HOME.
        val bin = File(home, "bin").apply { mkdirs() }
        executable(File(bin, "bwrap"), """
            #!/bin/sh
            printf '%s\n' "${'$'}@" > "${'$'}HOME/bwrap-args"
            exit 0
        """.trimIndent())
        executable(File(home, ".opencode/bin/opencode"), """
            #!/bin/sh
            exit 0
        """.trimIndent())
    }

    @After
    fun tearDown() {
        if (::home.isInitialized) home.deleteRecursively()
    }

    @Test
    fun planIsReadOnlyForOpencodeToo() {
        val args = start("plan")
        assertEquals("--ro-bind", flagFor(args, folder))
    }

    @Test
    fun buildStillWritesItsFolder() {
        val args = start("build")
        assertEquals("--bind", flagFor(args, folder))
    }

    /** Starts the harness, waits for bwrap to be invoked, and returns the arguments it saw. */
    private fun start(agent: String): List<String> {
        val process = ProcessBuilder("sh", "-s", "--", "start", agent, folder.absolutePath, "查资料")
            .redirectErrorStream(true)
            .apply {
                environment().apply {
                    put("HOME", home.absolutePath)
                    put("PATH", listOf(File(home, "bin").absolutePath, "/usr/local/bin", "/usr/bin", "/bin").joinToString(":"))
                    put("AGENT_KEY", "sk-test")
                    put("AGENT_MODEL", "deepseek/deepseek-flash")
                    put("AGENT_ENGINE", "opencode")
                    put("AGENT_TIMEOUT", "5")
                }
            }
            .start()
        process.outputStream.use { it.write(script.readBytes()) }
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("harness did not finish: $output", process.waitFor(10, TimeUnit.SECONDS))

        val seen = File(home, "bwrap-args")
        val deadline = System.currentTimeMillis() + 5_000
        while (!seen.isFile && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("bwrap was not started; harness said: $output", seen.isFile)
        return seen.readLines()
    }

    /** The flag that binds [path]: bwrap takes flag source destination. */
    private fun flagFor(args: List<String>, path: File): String? =
        args.windowed(3).firstOrNull { it[1] == path.absolutePath && it[2] == path.absolutePath }?.get(0)

    private fun executable(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text + "\n")
        check(file.setExecutable(true))
    }

    private fun available(command: String): Boolean = runCatching {
        ProcessBuilder("sh", "-c", "command -v $command").start().waitFor() == 0
    }.getOrDefault(false)
}
