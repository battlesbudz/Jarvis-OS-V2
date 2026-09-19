package com.battlesbudz.jarvis.v2.conversation

import java.util.concurrent.atomic.AtomicInteger

/** Process-wide conversation admission shared by foreground service and model setup. */
internal object ConversationWork {
    val activeJobs = AtomicInteger(0)
}
