package com.copyparty.zflip5

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * Thin Kotlin wrapper around party_bridge.py (real upstream copyparty).
 */
object CopypartyController {
    private const val TAG = "CopypartyController"

    @Volatile
    private var startedOk: Boolean = false

    fun ensurePython(context: Context) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
    }

    fun start(
        context: Context,
        port: Int,
        sharePath: String,
        readOnly: Boolean,
        password: String,
        bindHost: String = "0.0.0.0"
    ): Boolean {
        ensurePython(context)
        val hist = FilePaths.histDir(context)
        return try {
            val bridge = Python.getInstance().getModule("party_bridge")
            val ok = bridge.callAttr(
                "start",
                port,
                sharePath,
                readOnly,
                password,
                hist,
                bindHost
            ).toBoolean()
            startedOk = ok
            if (!ok) {
                val err = bridge.callAttr("last_error")?.toString()
                Log.e(TAG, "start failed: $err")
            }
            ok
        } catch (e: Exception) {
            Log.e(TAG, "start exception", e)
            startedOk = false
            false
        }
    }

    fun stop(context: Context) {
        try {
            ensurePython(context)
            Python.getInstance().getModule("party_bridge").callAttr("stop")
        } catch (e: Exception) {
            Log.e(TAG, "stop exception", e)
        } finally {
            startedOk = false
        }
    }

    fun isRunning(context: Context): Boolean {
        return try {
            ensurePython(context)
            Python.getInstance().getModule("party_bridge").callAttr("is_running").toBoolean()
        } catch (_: Exception) {
            startedOk
        }
    }

    fun lastError(context: Context): String? {
        return try {
            ensurePython(context)
            Python.getInstance().getModule("party_bridge").callAttr("last_error")?.toString()
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}

object FilePaths {
    fun histDir(context: Context): String {
        val dir = java.io.File(context.filesDir, "copyparty-hist")
        if (!dir.exists()) dir.mkdirs()
        return dir.absolutePath
    }
}
