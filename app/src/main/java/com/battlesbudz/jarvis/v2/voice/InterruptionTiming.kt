package com.battlesbudz.jarvis.v2.voice

/** Audio age includes capture backlog, native work and the word confirmation interval. */
object InterruptionTiming {
    const val DECODE_MS = 1600L
    const val START_AGE_MS = 300L
    const val RESULT_AGE_MS = DECODE_MS + START_AGE_MS + 200L
    const val CONFIRM_AGE_MS = RESULT_AGE_MS + 400L
    const val RECOVERY_QUIET_MS = 500L
}
