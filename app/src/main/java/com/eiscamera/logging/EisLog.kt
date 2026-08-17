package com.eiscamera.logging

import android.util.Log

/**
 * Structured logging categories per spec section 32: "Every major subsystem
 * should have its own logging category" — this lets `adb logcat -s
 * EIS-CAPABILITY` (etc.) isolate one subsystem's logs on a real device
 * without grepping through everything else.
 *
 * Usage:
 *   EisLog.i(EisLog.Tag.CAPABILITY, "Classified device as LEVEL_1_BASIC")
 *
 * Production/release builds should raise [minLevel] to WARN to avoid the
 * logging overhead of a real-time pipeline (spec section 32: "avoid
 * excessive logging in production mode"). This is not wired to build type
 * yet — that belongs with the first release-build hardening pass, not V0.2.
 */
object EisLog {

    enum class Tag(val label: String) {
        CAMERA("EIS-CAMERA"),
        GYRO("EIS-GYRO"),
        SENSOR("EIS-SENSOR"),
        SYNC("EIS-SYNC"),
        MOTION("EIS-MOTION"),
        GPU("EIS-GPU"),
        ENCODER("EIS-ENCODER"),
        CAPABILITY("EIS-CAPABILITY"),
        PROFILE("EIS-PROFILE"),
        PROCESSING("EIS-PROCESSING"),
        UI("EIS-UI"),
    }

    enum class Level { VERBOSE, DEBUG, INFO, WARN, ERROR }

    /** Minimum level actually emitted. Raise this for release builds. */
    var minLevel: Level = Level.VERBOSE

    fun v(tag: Tag, msg: String) = emit(Level.VERBOSE, tag, msg, null)
    fun d(tag: Tag, msg: String) = emit(Level.DEBUG, tag, msg, null)
    fun i(tag: Tag, msg: String) = emit(Level.INFO, tag, msg, null)
    fun w(tag: Tag, msg: String, t: Throwable? = null) = emit(Level.WARN, tag, msg, t)
    fun e(tag: Tag, msg: String, t: Throwable? = null) = emit(Level.ERROR, tag, msg, t)

    private fun emit(level: Level, tag: Tag, msg: String, t: Throwable?) {
        if (level.ordinal < minLevel.ordinal) return
        when (level) {
            Level.VERBOSE -> Log.v(tag.label, msg)
            Level.DEBUG -> Log.d(tag.label, msg)
            Level.INFO -> Log.i(tag.label, msg)
            Level.WARN -> if (t != null) Log.w(tag.label, msg, t) else Log.w(tag.label, msg)
            Level.ERROR -> if (t != null) Log.e(tag.label, msg, t) else Log.e(tag.label, msg)
        }
    }
}
