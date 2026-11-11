package me.rerere.rikkahub.phoneuse

import android.view.MotionEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuFastInputTest {

    @Test
    fun `cancelling gesture mid-swipe always emits ACTION_CANCEL`() = runBlocking {
        val actions = mutableListOf<Int>()
        var clock = 1000L
        val midSwipeReached = CompletableDeferred<Unit>()

        val job = launch {
            ShizukuFastInput.runGestureSequence(
                fromX = 100f,
                fromY = 200f,
                toX = 100f,
                toY = 800f,
                durationMs = 300L,
                nowMs = { clock },
                sleepMs = { ms ->
                    clock += ms
                    if (actions.count { it == MotionEvent.ACTION_MOVE } >= 2) {
                        midSwipeReached.complete(Unit)
                        throw CancellationException("User stopped generation")
                    }
                },
                sendTouch = { _, _, action, _, _, _ ->
                    actions += action
                    true
                },
            )
        }

        midSwipeReached.await()
        job.join()

        assertEquals(MotionEvent.ACTION_DOWN, actions.first())
        assertTrue(actions.contains(MotionEvent.ACTION_MOVE))
        assertEquals(
            "Cancelled gesture must terminate with ACTION_CANCEL, got $actions",
            MotionEvent.ACTION_CANCEL,
            actions.last(),
        )
        assertFalse(actions.contains(MotionEvent.ACTION_UP))
    }

    @Test
    fun `move injection failure terminates sequence with ACTION_CANCEL`() = runBlocking {
        val actions = mutableListOf<Int>()
        var clock = 500L

        val ok = ShizukuFastInput.runGestureSequence(
            fromX = 50f,
            fromY = 50f,
            toX = 400f,
            toY = 400f,
            durationMs = 200L,
            nowMs = { clock },
            sleepMs = { clock += it },
            sendTouch = { _, _, action, _, _, _ ->
                actions += action
                // 模拟第一帧 MOVE 注入失败
                action != MotionEvent.ACTION_MOVE
            },
        )

        assertFalse(ok)
        assertEquals(
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL),
            actions,
        )
    }

    @Test
    fun `normal tap and swipe complete cleanly with ACTION_UP and no ACTION_CANCEL`() = runBlocking {
        val tapActions = mutableListOf<Int>()
        var clock = 100L
        val tapOk = ShizukuFastInput.runTapSequence(
            nowMs = { clock++ },
            sendTouch = { _, _, action, _, _, _ ->
                tapActions += action
                true
            },
            x = 300f,
            y = 400f,
        )
        assertTrue(tapOk)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), tapActions)

        val swipeActions = mutableListOf<Int>()
        val swipeOk = ShizukuFastInput.runGestureSequence(
            fromX = 100f,
            fromY = 800f,
            toX = 100f,
            toY = 200f,
            durationMs = 120L,
            nowMs = { clock },
            sleepMs = { clock += it },
            sendTouch = { _, _, action, _, _, _ ->
                swipeActions += action
                true
            },
        )
        assertTrue(swipeOk)
        assertEquals(MotionEvent.ACTION_DOWN, swipeActions.first())
        assertEquals(MotionEvent.ACTION_UP, swipeActions.last())
        assertFalse(swipeActions.contains(MotionEvent.ACTION_CANCEL))
    }
}
