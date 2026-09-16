package com.battlesbudz.jarvis.v2.voice

/** Fixed, diagnostic-only submissions; never persisted as a live-call profile. */
object PaulIsolationCases {
    data class Case(val id: String, val submissions: List<String>, val reset: Boolean) {
        val text: String get() = submissions.joinToString(" ")
    }
    private val opening = "I understand. I can keep up with what you are saying and process your requests."
    private val ending = "I am ready when you are."
    val all = listOf(
        Case("standalone-reset", listOf("I understand."), true),
        Case("opening-one-reset", listOf("$opening $ending"), true),
        Case("opening-grouped-reset", listOf(opening, ending), true),
        Case("opening-grouped-retained", listOf(opening, ending), false),
        Case("paragraph-one-reset", listOf(TtsBenchmarkSamples.all.getValue("paragraph-v1")), true),
        Case("paragraph-grouped-reset", listOf("Good evening, sir. Your next appointment begins in twenty minutes.",
            "There is time for a cup of tea, provided we do not attempt to invent a new kettle first. I will keep the details ready while you finish what you are doing."), true),
        Case("paragraph-grouped-retained", listOf("Good evening, sir. Your next appointment begins in twenty minutes.",
            "There is time for a cup of tea, provided we do not attempt to invent a new kettle first. I will keep the details ready while you finish what you are doing."), false)
    )
}
