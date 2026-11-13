package me.rerere.rikkahub.hostshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellPolicyEngineTest {

    @Test
    fun `read-only diagnostic commands are allowed automatically`() {
        val allowed = listOf(
            "dumpsys battery",
            "dumpsys notification --noredact",
            "dumpsys activity top | head -n 40",
            "logcat -d -t 100 | grep -i AndroidRuntime",
            "getprop ro.build.version.release",
            "pm list packages -3",
            "pm path me.rerere.rikkahub",
            "settings get global adb_enabled",
            "wm size",
            "wm density",
            "ls -la /sdcard/Download && df -h",
            "sqlite3 -readonly /sdcard/test.db \"SELECT count(*) FROM items\"",
            "timeout 10 top -n 1",
            "ping -c 3 1.1.1.1",
            "ip addr show wlan0",
            "sort -u /system/build.prop | uniq -c",
            "screencap -p",
            "env LANG=C getprop ro.product.model",
        )
        for (cmd in allowed) {
            val res = ShellPolicyEngine.evaluate(cmd, asRoot = false)
            assertEquals("Expected ALLOW for: $cmd (reason=${res.reason})", PolicyDecision.ALLOW, res.decision)
        }
    }

    @Test
    fun `state-changing, variable-expanding, redirect or screen-injecting commands require approval`() {
        val askCommands = listOf(
            "pm clear com.example.demo",
            "pm uninstall com.example.demo",
            "settings put system screen_brightness 100",
            "wm size 1080x2400",
            "dumpsys battery set level 100",
            "dumpsys battery unplug",
            "input tap 500 800",
            "am start -n com.example/.MainActivity",
            "am force-stop com.example",
            "rm /sdcard/Download/temp.apk",
            "echo hello > /sdcard/Download/test.txt",
            "echo hello >/dev/null_fake",
            "cat <> /sdcard/Download/newfile.txt",
            "A=-exec; B=rm; find /data/local/tmp -maxdepth 0 \$A \$B -rf /sdcard/target \\;",
            "F=-f; P=/sdcard/victim.txt; logcat -d \$F \$P",
            "dd if=/sdcard/test.bin of=/dev/null bs=1M",
            "sed -i 's/a/b/g' /sdcard/test.txt",
            "sed -n '1,20p' /system/build.prop",
            "sed -n 'w /sdcard/Download/out.txt' /system/build.prop",
            "sed -ne'w /sdcard/Download/out.txt' /system/build.prop",
            "sed 's/foo/bar/w /sdcard/Download/out.txt' /system/build.prop",
            "sed -e '1,5W /sdcard/Download/out.txt' /system/build.prop",
            "sort -o /sdcard/Download/out.txt /system/build.prop",
            "sort -uo /sdcard/Download/out.txt /system/build.prop",
            "sort --output=/sdcard/Download/out.txt /system/build.prop",
            "sort --out=/sdcard/Download/out.txt /system/build.prop",
            "uniq /system/build.prop /sdcard/Download/out.txt",
            "screencap -p /sdcard/Download/cap.png",
            "logcat -d -f /sdcard/Download/log.txt",
            "awk '{ print \$1 }' /system/build.prop",
            "awk 'BEGIN { system(\"id\") }'",
            "awk 'BEGIN { system (\"touch /sdcard/Download/out.txt\") }'",
            "awk '{ print > \"/sdcard/Download/out.txt\" }' /system/build.prop",
            "find /sdcard/Download -name '*.tmp' -delete",
            "sqlite3 /sdcard/test.db 'DELETE FROM items'",
            "sqlite3 -readonly /sdcard/test.db '.output /sdcard/out.txt' 'SELECT 1'",
            "logcat", // missing -d would hang
        )
        for (cmd in askCommands) {
            val res = ShellPolicyEngine.evaluate(cmd, asRoot = false)
            assertEquals("Expected ASK for: $cmd (got ${res.decision}: ${res.reason})", PolicyDecision.ASK, res.decision)
        }
    }

    @Test
    fun `compound command with safe prefix and risky suffix still requires approval or deny`() {
        val askRes = ShellPolicyEngine.evaluate("dumpsys battery && pm clear com.example.app", asRoot = false)
        assertEquals(PolicyDecision.ASK, askRes.decision)
        assertFalse(askRes.canRememberInSession)

        val denyRes = ShellPolicyEngine.evaluate("ls /sdcard; rm -rf /sdcard", asRoot = false)
        assertEquals(PolicyDecision.DENY, denyRes.decision)
    }

    @Test
    fun `root flag always triggers single-use approval unless denied`() {
        val res = ShellPolicyEngine.evaluate("ls -la /data/data/com.example.app", asRoot = true)
        assertEquals(PolicyDecision.ASK, res.decision)
        assertFalse("Root commands must always require explicit per-call approval", res.canRememberInSession)
    }

    @Test
    fun `approval store isolates approvals per conversation and clears cleanly`() {
        val store = ShellApprovalStore()
        val cmd = "settings put system screen_brightness 120"
        store.recordApproval(cmd, asRoot = false, conversationId = "conv-A")

        assertTrue(store.hasApproval(cmd, asRoot = false, conversationId = "conv-A"))
        assertFalse("Approval must never leak to another conversation", store.hasApproval(cmd, asRoot = false, conversationId = "conv-B"))

        store.clearConversation("conv-A")
        assertFalse(store.hasApproval(cmd, asRoot = false, conversationId = "conv-A"))
    }

    @Test
    fun `catastrophic, escalation, quote-obfuscated, globbed and banking commands are hard denied`() {
        val denied = listOf(
            "dd if=/dev/zero of=/dev/block/by-name/boot",
            "mkfs.ext4 /dev/block/sda1",
            "reboot bootloader",
            "toybox toybox reboot",
            "env reboot",
            "svc power reboot",
            "/system/bin/toybox rm -rf /sdcard",
            "busybox rm -rf /",
            "rm -rf /storage/emulated/0/*",
            "find /sdcard -delete",
            "rm -rf /data/adb/modules/zygisk_sui",
            "ls /data/\"adb\"/modules",
            "ls /data/ad*",
            "ls /dev/bl*ck/by-name",
            "su -c 'id'",
            "cat /data/data/com.eg.android.AlipayGphone/shared_prefs/main.xml",
            "cat /data/data/com.eg.android.Alipay*/shared_prefs/main.xml",
            "ls /data/data/com.x8bit.'bitwarden'",
            "pm clear cmb.pb",
            "sqlite3 -readonly /data/data/com.x8bit.bitwarden/databases/vault.db 'SELECT * FROM cipher'",
        )
        for (cmd in denied) {
            val res = ShellPolicyEngine.evaluate(cmd, asRoot = false)
            assertEquals("Expected DENY for: $cmd (got ${res.decision}: ${res.reason})", PolicyDecision.DENY, res.decision)
        }

        assertEquals(
            PolicyDecision.DENY,
            ShellPolicyEngine.evaluate("rm -rf /data/adb/modules", asRoot = true).decision,
        )
        assertEquals(
            PolicyDecision.DENY,
            ShellPolicyEngine.evaluate("ls /data/data/com.eg.android.AlipayGphone", asRoot = true).decision,
        )
    }
}
