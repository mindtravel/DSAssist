package com.mindtravel.reduction.feature.chat.model

import com.mindtravel.reduction.core.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptTest {

    private fun reference(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("system-prompt-$name.txt")!!.bufferedReader().readText()

    private fun settings(web: Boolean, calc: Boolean, read: Boolean, write: Boolean, project: String) = AppSettings(
        webSearchEnabled = web, calculatorEnabled = calc, opencodeEnabled = read, opencodeWriteEnabled = write, pcProject = project
    )

    private fun built(): Map<String, String> = mapOf(
        "all" to SystemPrompt.build(
            settings(true, true, true, true, "/home/u/p"), "2026-01-02",
            listOf("- 备忘：零碎待办", "- 想法：（没有写用途）")
        ),
        "readonly" to SystemPrompt.build(settings(false, true, true, false, "/home/u/p"), "2026-01-02", emptyList()),
        "bare" to SystemPrompt.build(settings(false, false, false, false, "/x"), "2026-01-02", emptyList())
    )

    /**
     * The reference texts are the wording as it was last read and approved. A change to the
     * prompt shows up here; set REDUCTION_APPROVE_PROMPTS to the resources folder to approve it,
     * and read the resulting diff.
     */
    @Test
    fun theTextIsExactlyTheApprovedWording() {
        val approveInto = System.getenv("REDUCTION_APPROVE_PROMPTS")
        for ((name, text) in built()) {
            if (approveInto.isNullOrBlank()) {
                assertEquals(reference(name), text)
            } else {
                java.io.File(approveInto, "system-prompt-$name.txt").writeText(text)
            }
        }
    }

    @Test
    fun repliesAreSpokenInFull() {
        val all = built().getValue("all")
        assertFalse(all.contains("【朗读】") || all.contains("【详情】"))
        assertTrue(all.contains("summary") && all.contains("session_status"))
    }

    @Test
    fun toolsThatAreOffAreNotMentioned() {
        val bare = SystemPrompt.build(settings(false, false, false, false, "/x"), "2026-01-02", emptyList())
        assertFalse(bare.contains("web_search") || bare.contains("calculator") || bare.contains("run_agent"))
        assertTrue(bare.contains("patch_cards") && bare.contains("（还没有卡片）"))
    }

    @Test
    fun deviceNoticesAppearOnlyWhenThereAreSome() {
        val plain = built().getValue("bare")
        assertFalse(plain.contains("# 设备"))
        val low = SystemPrompt.build(settings(false, false, false, false, "/x"), "2026-01-02", emptyList(), listOf("手机电量 15%"))
        assertTrue(low.contains("# 设备") && low.contains("- 手机电量 15%"))
        assertEquals(plain, low.replace(Regex("\\n\\n# 设备\\n[^#]*(?=\\n\\n# 安全)"), ""))
    }
}
