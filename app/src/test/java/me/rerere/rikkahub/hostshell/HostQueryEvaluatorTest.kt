package me.rerere.rikkahub.hostshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostQueryEvaluatorTest {

    @Test
    fun `pure read-only host diagnostic queries are allowed and safely single-quoted`() {
        val queries = listOf(
            "getprop ro.build.version.release" to "'getprop' 'ro.build.version.release'",
            "dumpsys battery" to "'dumpsys' 'battery'",
            "pm list packages -3" to "'pm' 'list' 'packages' '-3'",
            "settings get global adb_enabled" to "'settings' 'get' 'global' 'adb_enabled'",
            "logcat -d -t 50" to "'logcat' '-d' '-t' '50'",
            "wm size" to "'wm' 'size'",
            "screencap -p" to "'screencap' '-p'",
            "uiautomator dump /proc/self/fd/1" to "'uiautomator' 'dump' '/proc/self/fd/1'",
            "ps -A" to "'ps' '-A'",
            "df -h /data" to "'df' '-h' '/data'",
        )
        for ((raw, expectedSafe) in queries) {
            val res = HostQueryEvaluator.evaluate(raw)
            assertTrue("Expected Allowed for `$raw`, got $res", res is HostQueryEvaluator.Result.Allowed)
            assertEquals(expectedSafe, (res as HostQueryEvaluator.Result.Allowed).safeQuotedCommand)
        }
    }

    @Test
    fun `metacharacters, pipes, redirects, variable expansions and mutating commands are rejected`() {
        val rejected = listOf(
            "dumpsys battery | grep level",
            "getprop; rm -rf /sdcard/a",
            "echo hi > /sdcard/a.txt",
            "screencap -p /sdcard/Download/cap.png",
            "uiautomator dump /sdcard/ui.xml",
            "logcat -d -f /sdcard/log.txt",
            "logcat -c",
            "logcat", // missing -d
            "dumpsys battery set level 100",
            "dumpsys notification --noredact",
            "wm size 1080x2400",
            "settings put system screen_brightness 50",
            "pm clear com.example.app",
            "pm dump com.x8bit.bitwarden",
            "pm dump com.eg.android.AlipayGphone",
            "F=-f logcat -d \$F /sdcard/pwn",
            "su -c id",
            "rm /sdcard/test.txt",
        )
        for (raw in rejected) {
            val res = HostQueryEvaluator.evaluate(raw)
            assertTrue("Expected Rejected for `$raw`, got $res", res is HostQueryEvaluator.Result.Rejected)
        }
    }
}
