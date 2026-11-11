package me.rerere.rikkahub.phoneuse

import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method
import kotlinx.coroutines.delay
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val TAG = "ShizukuFastInput"
private const val INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH = 2

/**
 * 基于 Sui / Shizuku Binder 直连 `IInputManager.injectInputEvent` 的毫秒级输入注入器。
 *
 * 相比传统 `su -c "input tap x y"` 每次需要 fork `app_process` 冷启动 ART 虚拟机（~200ms），
 * 直接通过常驻 Binder 事务把 [MotionEvent] / [KeyEvent] 投递进系统 `InputManagerService`，
 * 单次点击/按键耗时仅 ~1-2ms，且不在 Magisk 超级用户日志刷屏。
 * 若个别魔改 ROM 修改了 `IInputManager` 签名，调用方会自动无缝回退到 `input` 命令行。
 */
object ShizukuFastInput {

    @Volatile
    private var cachedService: Any? = null

    @Volatile
    private var cachedInjectMethod: Method? = null

    @Volatile
    private var cachedBinder: IBinder? = null

    private fun ensureService(): Pair<Any, Method>? {
        if (!Shizuku.pingBinder()) return null
        synchronized(this) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { HiddenApiBypass.addHiddenApiExemptions("Landroid/hardware/input/") }
            }
            val rawBinder = SystemServiceHelper.getSystemService("input") ?: return null
            if (cachedService != null && cachedInjectMethod != null && cachedBinder === rawBinder && rawBinder.isBinderAlive) {
                return cachedService!! to cachedInjectMethod!!
            }
            return runCatching {
                val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
                val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
                val service = asInterface.invoke(null, ShizukuBinderWrapper(rawBinder)) ?: return null
                val method = service.javaClass.methods.firstOrNull {
                    it.name == "injectInputEvent" && it.parameterTypes.size == 2 &&
                        InputEvent::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                        it.parameterTypes[1] == Int::class.javaPrimitiveType
                } ?: return null
                method.isAccessible = true
                cachedBinder = rawBinder
                cachedService = service
                cachedInjectMethod = method
                service to method
            }.onFailure {
                Log.w(TAG, "Failed to bind IInputManager via ShizukuBinderWrapper", it)
            }.getOrNull()
        }
    }

    private fun injectEvent(event: InputEvent): Boolean {
        val (service, method) = ensureService() ?: return false
        return runCatching {
            method.invoke(service, event, INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH) as? Boolean ?: false
        }.onFailure {
            Log.w(TAG, "injectInputEvent failed, clearing cache for fallback", it)
            cachedService = null
            cachedInjectMethod = null
        }.getOrDefault(false)
    }

    fun injectTap(x: Float, y: Float): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val down = obtainTouchEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 1.0f)
        val okDown = try {
            injectEvent(down)
        } finally {
            down.recycle()
        }
        if (!okDown) return false

        val upTime = SystemClock.uptimeMillis().coerceAtLeast(downTime + 12L)
        val up = obtainTouchEvent(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0.0f)
        return try {
            injectEvent(up)
        } finally {
            up.recycle()
        }
    }

    suspend fun injectGesture(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long,
    ): Boolean {
        val isQuickTap = hypot(toX - fromX, toY - fromY) < 12f && durationMs <= 100L
        if (isQuickTap) {
            return injectTap(fromX, fromY)
        }

        val totalMs = durationMs.coerceAtLeast(50L)
        val downTime = SystemClock.uptimeMillis()
        val down = obtainTouchEvent(downTime, downTime, MotionEvent.ACTION_DOWN, fromX, fromY, 1.0f)
        val okDown = try {
            injectEvent(down)
        } finally {
            down.recycle()
        }
        if (!okDown) return false

        // 按 ~16ms (60Hz) 帧步进平滑插值注入 MOVE 事件，兼容长按（原地微停留）与滑动手势
        val steps = (totalMs / 16L).toInt().coerceIn(2, 120)
        val stepDelay = (totalMs / steps).coerceAtLeast(4L)
        for (i in 1 until steps) {
            delay(stepDelay)
            val fraction = i.toFloat() / steps.toFloat()
            val cx = fromX + (toX - fromX) * fraction
            val cy = fromY + (toY - fromY) * fraction
            val now = SystemClock.uptimeMillis()
            val move = obtainTouchEvent(downTime, now, MotionEvent.ACTION_MOVE, cx, cy, 1.0f)
            try {
                if (!injectEvent(move)) return false
            } finally {
                move.recycle()
            }
        }
        val remaining = (downTime + totalMs) - SystemClock.uptimeMillis()
        if (remaining > 0) delay(remaining)

        val upTime = SystemClock.uptimeMillis()
        val up = obtainTouchEvent(downTime, upTime, MotionEvent.ACTION_UP, toX, toY, 0.0f)
        return try {
            injectEvent(up)
        } finally {
            up.recycle()
        }
    }

    fun injectKeyCode(keyCode: Int, metaState: Int = 0): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(
            now,
            now,
            KeyEvent.ACTION_DOWN,
            keyCode,
            0,
            metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            0,
            InputDevice.SOURCE_KEYBOARD,
        )
        if (!injectEvent(down)) return false

        val up = KeyEvent(
            now,
            SystemClock.uptimeMillis().coerceAtLeast(now + 8L),
            KeyEvent.ACTION_UP,
            keyCode,
            0,
            metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            0,
            InputDevice.SOURCE_KEYBOARD,
        )
        return injectEvent(up)
    }

    fun injectCtrlA(): Boolean {
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        return injectKeyCode(KeyEvent.KEYCODE_A, metaState = meta)
    }

    private fun obtainTouchEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
        pressure: Float,
    ): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x.roundToInt().toFloat()
            this.y = y.roundToInt().toFloat()
            this.pressure = pressure
            this.size = 1f
        })
        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            props,
            coords,
            0,
            0,
            1.0f,
            1.0f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
    }
}
