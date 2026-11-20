package com.mindtravel.reduction.feature.chat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CardRepositoryTest {

    private fun cards() = CardRepository(Files.createTempDirectory("cards").toFile())

    @Test
    fun severalCardsLiveSideBySide() {
        val cards = cards()
        cards.save("科研想法·检索", "a", purpose = "检索")
        cards.save("科研想法·评测", "b", purpose = "评测")
        cards.save("备忘", "c", purpose = "零碎待办")
        assertEquals(setOf("科研想法·检索", "科研想法·评测", "备忘"), cards.list().map { it.name }.toSet())
        assertEquals("b", cards.read("科研想法·评测"))
        assertEquals("评测", cards.list().first { it.name == "科研想法·评测" }.purpose)
    }

    @Test
    fun promptDirectoryIsOrderedByTypeAndFullNameAcrossBodyWrites() {
        val cards = cards()
        cards.save("B/前言", "草稿")
        cards.save("任务/Z", "已审", "任务")
        cards.setLocked("任务/Z", true)
        cards.writeProgramCard("计划", "旧待办", "待办")
        cards.save("A/后记", "初稿", "笔记")
        cards.save("任务/A", "已审", "任务")
        cards.setLocked("任务/A", true)
        cards.writeProgramCard("sub agent 任务", "旧记录", "任务进度")
        val expected = listOf(
            "- sub agent 任务：任务进度", "- 计划：待办",
            "- 任务/A：任务【用户已审核并锁定的 prompt】", "- 任务/Z：任务【用户已审核并锁定的 prompt】",
            "- A/后记：笔记", "- B/前言：（没有写用途）"
        )
        assertEquals(expected, cards.promptDirectory())

        cards.writeProgramCard("sub agent 任务", "任务已完成，已交回报告", "任务进度")
        cards.writeProgramCard("计划", "新的待办和时间", "待办")
        assertEquals("saved", cards.autosave("B/前言", "", "自动保存后的长正文", false, "", "草稿"))
        assertEquals(expected, cards.promptDirectory())
    }

    @Test
    fun savingReplacesTheWholeCardAndCanChangeItsPurpose() {
        val cards = cards()
        cards.save("任务", "旧", purpose = "当前任务卡")
        cards.save("任务", "新")
        assertEquals("新", cards.read("任务"))
        assertEquals("a save without a purpose keeps it", "当前任务卡", cards.purpose("任务"))
        cards.save("任务", "新", purpose = "改了用途")
        assertEquals("改了用途", cards.purpose("任务"))
    }

    @Test
    fun theDirectoryCarriesPurposesNotText() {
        val cards = cards()
        cards.save("想法", "很长的正文……", purpose = "科研想法")
        val json = cards.directoryJson()
        assertTrue(json, json.contains("科研想法"))
        assertFalse(json.contains("很长的正文"))
    }

    @Test
    fun deletingRemovesOnlyThatCardAndItsPurpose() {
        val cards = cards()
        cards.save("a", "1", purpose = "甲")
        cards.save("b", "2", purpose = "乙")
        assertTrue(cards.delete("a"))
        assertFalse(cards.delete("a"))
        assertNull(cards.read("a"))
        assertEquals("", cards.purpose("a"))
        assertEquals(listOf("b"), cards.list().map { it.name })
    }

    @Test
    fun unusableNamesAreRefused() {
        val cards = cards()
        assertFalse(cards.save("", "x"))
        assertFalse(cards.save("两\n行", "x"))
        assertFalse(cards.save("长".repeat(61), "x"))
        // "/" only says which folder a card is shown in; it never reaches the file system.
        assertTrue(cards.save("../../etc/passwd", "x"))
        assertEquals(listOf("../../etc/passwd"), cards.list().map { it.name })
    }

    @Test
    fun aCardTheProgramWritesCannotBeChangedByAnyoneElse() {
        val cards = cards()
        assertTrue(cards.writeProgramCard("任务记录", "原文一", "程序写入"))
        assertFalse(cards.save("任务记录", "改过的"))
        assertFalse(cards.delete("任务记录"))
        val refused = runCatching { cards.patch("*** Update Card: 任务记录\n+加一行") }.exceptionOrNull()
        assertTrue(refused is CardPatch.Invalid && refused.message!!.contains("由程序写入"))
        assertEquals("原文一", cards.read("任务记录"))

        // The program itself can go on writing it, and it is marked in the directory.
        assertTrue(cards.writeProgramCard("任务记录", "原文二", "程序写入"))
        assertEquals("原文二", cards.read("任务记录"))
        assertTrue(cards.directoryJson().contains("\"locked\":true"))
    }

    @Test
    fun everyWriteAndDeleteIsAnnounced() {
        val cards = cards()
        val changed = ArrayList<String>()
        cards.onChange = { changed += it }
        cards.save("备忘", "- 买电池", "零碎待办")
        cards.patch("*** Update Card: 备忘\n+- 交报告")
        cards.delete("备忘")
        assertEquals(listOf("备忘", "备忘", "备忘"), changed)
    }

    @Test
    fun aNameWithASlashPutsTheCardInAFolder() {
        val cards = cards()
        assertTrue(cards.save(" 产品需求 / 短期 ", "要做的", "近期要动手的"))
        assertEquals("要做的", cards.read("产品需求/短期"))
        assertEquals(listOf("产品需求/短期"), cards.list().map { it.name })
        assertFalse(cards.save("产品需求/", "x"))
        assertFalse(cards.save("/短期", "x"))
    }

    @Test
    fun movingACardKeepsItsTextAndPurpose() {
        val cards = cards()
        cards.save("需求·短期", "- 耳机手势", "近期要动手的")
        cards.save("备忘", "- 买电池", "零碎待办")
        val said = cards.patch("*** Move Card: 需求·短期 -> 产品需求/短期\n*** Update Card: 产品需求/短期\n+- 悬浮卡片")
        assertEquals(2, said.size)
        assertEquals(null, cards.read("需求·短期"))
        assertEquals(listOf("- 耳机手势", "- 悬浮卡片"), cards.read("产品需求/短期")!!.lines().filter { it.isNotBlank() })
        assertEquals("近期要动手的", cards.purpose("产品需求/短期"))

        val taken = runCatching { cards.patch("*** Move Card: 备忘 -> 产品需求/短期") }.exceptionOrNull()
        assertTrue(taken is CardPatch.Invalid)
        assertEquals("- 买电池", cards.read("备忘"))

        assertTrue(cards.rename("备忘", "杂项/备忘"))
        assertEquals("零碎待办", cards.purpose("杂项/备忘"))
        assertFalse(cards.rename("杂项/备忘", "产品需求/短期"))
    }

    @Test
    fun picturesAndSoundAreKeptWhileACardRefersToThem() {
        val dir = Files.createTempDirectory("cards").toFile()
        val cards = CardRepository(dir)
        val picture = cards.saveMedia(byteArrayOf(1, 2, 3), "JPG")!!
        val sound = cards.saveMedia(byteArrayOf(4, 5), "m4a")!!
        assertEquals(null, cards.saveMedia(byteArrayOf(1), "exe"))
        assertEquals(null, cards.saveMedia(ByteArray(0), "png"))
        assertEquals(listOf<Byte>(1, 2, 3), cards.mediaFile(picture)!!.readBytes().toList())
        assertEquals(null, cards.mediaFile("../" + picture))
        assertEquals(null, cards.mediaFile("m000000000000.jpg"))

        // Old enough to be swept, were nothing referring to them.
        java.io.File(dir, "media").listFiles()!!.forEach { it.setLastModified(System.currentTimeMillis() - 7_200_000) }
        cards.save("相册", "![白板](media:$picture)\n\n![口述](media:$sound)")
        assertTrue(cards.mediaFile(picture) != null && cards.mediaFile(sound) != null)

        cards.patch("*** Update Card: 相册\n-![口述](media:$sound)")
        assertTrue(cards.mediaFile(picture) != null)
        assertEquals(null, cards.mediaFile(sound))
        cards.delete("相册")
        assertEquals(null, cards.mediaFile(picture))
    }

    @Test
    fun whatWasJustAddedIsNotSweptBeforeItsCardIsSaved() {
        val cards = cards()
        val fresh = cards.saveMedia(byteArrayOf(9), "png")!!
        cards.save("别的", "没有图")
        assertTrue(cards.mediaFile(fresh) != null)
    }

    @Test
    fun aLockedPromptStaysExactlyAsItWasApproved() {
        val cards = cards()
        cards.save("任务/改登录页", "把登录页的按钮改成蓝色", "任务卡")
        assertFalse(cards.isLocked("任务/改登录页"))
        assertTrue(cards.setLocked("任务/改登录页", true))
        assertTrue(cards.directoryJson().contains("\"prompt\":true"))

        // Not the assistant's patch, not a save from the screen, not a rename, not a delete.
        val refused = runCatching { cards.patch("*** Update Card: 任务/改登录页\n+再把字体放大") }.exceptionOrNull()
        assertTrue(refused is CardPatch.Invalid && refused.message!!.contains("锁定的 prompt"))
        assertTrue(runCatching { cards.patch("*** Move Card: 任务/改登录页 -> 别处") }.exceptionOrNull() is CardPatch.Invalid)
        assertTrue(runCatching { cards.patch("*** Delete Card: 任务/改登录页") }.exceptionOrNull() is CardPatch.Invalid)
        assertFalse(cards.save("任务/改登录页", "改过的"))
        assertFalse(cards.rename("任务/改登录页", "别处"))
        assertFalse(cards.delete("任务/改登录页"))
        assertEquals("把登录页的按钮改成蓝色", cards.read("任务/改登录页"))

        assertTrue(cards.setLocked("任务/改登录页", false))
        assertTrue(cards.save("任务/改登录页", "改过的"))
    }

    @Test
    fun onlyAnExistingOrdinaryCardCanBeLocked() {
        val cards = cards()
        assertFalse(cards.setLocked("没有这张", true))
        cards.writeProgramCard("任务记录", "原文", "程序写入")
        assertFalse(cards.setLocked("任务记录", true))
    }
}
