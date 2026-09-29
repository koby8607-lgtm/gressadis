package com.r36s.usb0manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Process
import java.util.concurrent.Executors

class PppControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uid = Binder.getCallingUid()
        if (uid != Process.ROOT_UID && uid != Process.SHELL_UID && uid != context.applicationInfo.uid) {
            return
        }

        val action = intent.action ?: return
        if (action != ACTION_CONNECT && action != ACTION_DISCONNECT) {
            return
        }

        val pending = goAsync()
        Executors.newSingleThreadExecutor().execute {
            try {
                val profile = PppManager.active(context)
                val command = when (action) {
                    ACTION_CONNECT -> {
                        val file = PppManager.writeRuntimeProfile(context, profile)
                        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                        val repair = if (prefs.getBoolean("ppp_network_repair", true)) 1 else 0
                        "PPP_NETWORK_REPAIR=$repair ppp-connect ${shellQuote(file.absolutePath)}"
                    }
                    ACTION_DISCONNECT -> "ppp-disconnect"
                    else -> return@execute
                }
                runRoot(context, command)
            } finally {
                pending.finish()
            }
        }
    }

    private fun runRoot(context: Context, command: String): String {
        val helper = HelperManager.install(context).helper
        val shellCommand = shellQuote(helper.absolutePath) + " " + command
        var last = "no su"

        for (su in listOf("/system/xbin/su", "/system/bin/su", "/vendor/bin/su", "su")) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf(su, "-c", shellCommand))
                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()
                process.waitFor()

                if (process.exitValue() == 0 || stdout.isNotBlank()) {
                    return stdout + if (stderr.isNotBlank()) "\n[stderr]\n$stderr" else ""
                }
                last = stderr.ifBlank { "su exit=${process.exitValue()}" }
            } catch (e: Exception) {
                last = e.message ?: "su failed"
            }
        }

        return "ROOT_ERROR=$last"
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    companion object {
        const val ACTION_CONNECT = "de.draisberghof.pppwidget3.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "de.draisberghof.pppwidget3.ACTION_DISCONNECT"
    }
}
