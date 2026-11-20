package com.mindtravel.reduction.feature.chat.model

import com.mindtravel.reduction.core.ApiException
import com.mindtravel.reduction.feature.chat.data.ContextCheckpoint
import com.mindtravel.reduction.model.chat.ChatImage
import com.mindtravel.reduction.model.chat.Role
import com.mindtravel.reduction.model.chat.ToolSpec
import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContextBudgetTest {
    @get:Rule val dir = TemporaryFolder()
    private val budget = ContextBudget()
    private fun lines(last: Int) = (0..last).map {
        ContextLine("id-$it", if (it % 2 == 0) Role.USER else Role.ASSISTANT, "第 $it 条消息。", it.toLong())
    }
    private fun plan(history: List<ContextLine>, prev: ContextBudget.Snapshot? = null, system: String = "固定系统指令",
        key: String = "test", model: String = "deepseek-flash", tools: List<ToolSpec> = emptyList()) =
        budget.choose(prev, system, history, model, key, tools, {})

    @Test fun coldStartPricesAllMissesAndUsesTheCheaperOfTwoInputs() {
        val small = plan(lines(6))
        assertFalse(small.usedRecent)
        assertEquals(small.growing, small.recent)
        val many = lines(40)
        val cold = plan(many)
        assertTrue(cold.usedRecent)
        assertEquals(0, cold.recent.hit)
        assertEquals(50L * cold.recent.total, cold.recent.cost)
        assertEquals(many.takeLast(20).map { it.content }, cold.messages.drop(1).map { it.content })
    }

    @Test fun plainAndInterruptedTurnsMatchPublishedV4ProTextFraming() {
        // Counts from the official encode_messages(..., "thinking") and tokenizer.json.
        val normal = lines(2)
        assertEquals(14, plan(normal.take(1), model = "deepseek-v4-pro").growing.total)
        assertEquals(32, plan(normal, model = "deepseek-v4-pro").growing.total)
        val interrupted = normal.map { it.copy(role = Role.USER) }
        assertEquals(28, plan(interrupted, model = "deepseek-v4-pro").growing.total)
    }

    @Test fun anIntactPrefixKeepsGrowingPastFortyAndOneHundred() {
        val history = lines(110)
        var choice = plan(history.take(1))
        for (last in 2..110 step 2) choice = plan(history.take(last + 1), choice.snapshot)
        assertFalse(choice.usedRecent)
        assertEquals(112, choice.messages.size)
        assertTrue(choice.growing.total > choice.recent.total)
        assertTrue(choice.growing.hit > 0 && choice.growing.cost < choice.recent.cost)
        assertEquals(choice.growing.hit + 50L * choice.growing.miss, choice.growing.cost)
    }

    @Test fun anExpensiveCachedOpeningIsDroppedAndGrowthRestartsAtTheChosenBoundary() {
        val history = lines(22).toMutableList()
        history[0] = history[0].copy(content = "所有历史".repeat(8000))
        val previous = plan(history.take(19))
        val prune = plan(history.take(21), previous.snapshot)
        assertTrue(prune.growing.hit > prune.recent.hit)
        assertTrue(prune.recent.cost < prune.growing.cost)
        assertTrue(prune.usedRecent)
        assertEquals(history[1].id, prune.snapshot.firstId)
        val next = plan(history, prune.snapshot)
        assertFalse(next.usedRecent)
        assertEquals(history.drop(1).map { it.content }, next.messages.drop(1).map { it.content })
        assertTrue(next.growing.hit >= prune.snapshot.tokens.size)
    }

    @Test fun onlyCompleteOrPreviouslyObservedSharedPrefixesGetCredit() {
        val first = plan(lines(2), system = "不变的开头：苹果。")
        val second = plan(lines(4), first.snapshot, system = "不变的开头：香蕉。")
        assertEquals(0, second.growing.hit)
        val common = second.snapshot.sharedEnds.single()
        assertTrue(common > 1)
        val third = plan(lines(6), second.snapshot, system = "不变的开头：葡萄。")
        assertEquals(common, third.growing.hit)
        assertTrue(third.growing.miss > 0)
        val longer = plan(lines(8), third.snapshot, system = "不变的开头：葡萄。")
        val changedAgain = plan(lines(10), longer.snapshot, system = "不变的开头：梨。")
        assertEquals(common, changedAgain.growing.hit)
    }

    @Test fun keyModelOrToolSetChangeCannotReuseOldHits() {
        val tool = ToolSpec("a", "example", "x", "arg")
        val previous = plan(lines(4), tools = listOf(tool))
        assertTrue(plan(lines(6), previous.snapshot, tools = listOf(tool)).growing.hit > previous.snapshot.tokens.size)
        assertEquals(0, plan(lines(6), previous.snapshot).growing.hit)
        assertEquals(0, plan(lines(6), previous.snapshot, key = "another", tools = listOf(tool)).growing.hit)
        assertEquals(0, plan(lines(6), previous.snapshot, model = "deepseek-v4-pro", tools = listOf(tool)).growing.hit)
    }

    @Test fun imageIdentityAffectsReuseAndOldPicturesLeaveTheWindow() {
        val history = lines(22).toMutableList()
        history[0] = history[0].copy(images = listOf(ChatImage("photo", "image/png", "AA==")))
        val before = plan(history.take(1))
        val same = plan(history.take(3), before.snapshot)
        assertTrue(same.growing.hit >= ContextBudget.IMAGE_TOKENS)
        history[0] = history[0].copy(images = listOf(ChatImage("photo", "image/png", "AQ==")))
        assertEquals(0, plan(history.take(3), before.snapshot).growing.hit)
        assertTrue(plan(history, before.snapshot).messages.all { it.images.isEmpty() })
    }

    @Test fun checkpointSurvivesRestartButNotClearOrAnIncompleteFile() {
        val file = File(dir.root, "previous")
        val previous = plan(lines(20), plan(lines(18)).snapshot).snapshot
        ContextCheckpoint(file).save(previous, 12)
        val loaded = ContextCheckpoint(file).load(12)!!
        assertEquals(previous.scope, loaded.scope)
        assertArrayEquals(previous.tokens, loaded.tokens)
        assertEquals(previous.firstId, loaded.firstId)
        assertArrayEquals(previous.sharedEnds, loaded.sharedEnds)
        assertTrue(plan(lines(22), loaded).growing.hit > 0)
        assertNull(ContextCheckpoint(file).load(13))
        file.writeBytes(file.readBytes().copyOf(80))
        assertNull(ContextCheckpoint(file).load(12))
        ContextCheckpoint(file).clear()
        assertFalse(file.exists())
    }

    @Test fun modelCapacityAndCancellationAreCheckedBeforeTheChatRequest() {
        val history = lines(30)
        val prev = plan(history.take(19)).snapshot
        val costs = plan(history, prev)
        val small = ContextBudget(costs.recent.total + 1)
        assertTrue(small.choose(prev, "固定系统指令", history, "deepseek-flash", "test", emptyList(), {}).usedRecent)
        assertThrows(ApiException::class.java) {
            ContextBudget(2).choose(prev, "系统", history, "deepseek-flash", "test", emptyList(), {})
        }
        assertThrows(CancellationException::class.java) {
            budget.choose(prev, "系统", history, "deepseek-flash", "test", emptyList(), { throw CancellationException("cancelled") })
        }
    }
}
