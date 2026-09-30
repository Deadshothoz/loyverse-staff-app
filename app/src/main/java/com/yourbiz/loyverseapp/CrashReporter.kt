package com.yourbiz.loyverseapp

import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter

/**
 * If the app crashes, saves the error so it can be shown (and copied) the
 * next time the app opens - no USB cable or developer tools needed.
 */
object CrashReporter {

    private const val PREFS = "crash_report"
    private const val KEY = "last_crash"
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                // commit() rather than apply(): the app is about to die, so
                // the save has to finish before we hand the crash on.
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY, trace.take(6000)).commit()
            } catch (ignored: Exception) {
                // Never let the crash reporter itself get in the way.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Returns the last saved crash (if any) and clears it. */
    fun takeLastCrash(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val crash = prefs.getString(KEY, null) ?: return null
        prefs.edit().remove(KEY).apply()
        return crash
    }
}
