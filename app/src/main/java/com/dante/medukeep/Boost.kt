package com.dante.medukeep

import android.app.Activity
import rikka.shizuku.Shizuku

/** Uses Shizuku's shell-level access so Android stops freezing/killing the app. */
object Boost {

    fun apply(act: Activity, say: (String) -> Unit) {
        val p = act.packageName
        Thread {
            val cmds = listOf(
                "cmd deviceidle whitelist +$p",
                "cmd appops set $p RUN_ANY_IN_BACKGROUND allow",
                "cmd appops set $p RUN_IN_BACKGROUND allow",
                "am set-standby-bucket $p active",
                "settings put global settings_enable_monitor_phantom_procs false",
                "device_config put activity_manager max_phantom_processes 2147483647"
            )
            var sent = 0
            cmds.forEach {
                try { sh(it); sent++ } catch (_: Throwable) { }
            }
            act.runOnUiThread { say("Shizuku boost done ($sent/${cmds.size} commands ran)") }
        }.start()
    }

    // Shizuku.newProcess is private in API 13+, but still works via reflection.
    private fun sh(cmd: String): String {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        val proc = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return out
    }
}
