package com.nextui.launcher

import android.util.Log

object DiagnosticsLogger {
    fun log(message: String) {
        Log.d("DiagnosticsLogger", message)
    }
    fun getLog(): String = "Mock Log"
    fun recordError(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
    }
    fun recordPhase(phase: Any, message: String? = null, extra: Any? = null) {
        Log.d("DiagnosticsLogger", "Phase: $phase, Message: $message, Extra: $extra")
    }
}
