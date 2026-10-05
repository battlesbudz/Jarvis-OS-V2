package com.battlesbudz.jarvis.v2.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only operation kinds with a real runtime observation belong here. */
enum class AgentActivityKind { CHECKING_REFERENCES, ERROR }

/** Ephemeral presentation metadata. Never include prompts, URLs or reference contents. */
data class AgentActivitySnapshot(
    val operationId: Long,
    val conversationId: String,
    val kind: AgentActivityKind,
    val label: String,
)

/**
 * Process-owned observation, independent of Compose and request authority. A read owns only its
 * lease: late cleanup from an old read must not dismiss a newer operation's presentation.
 */
internal class AgentActivityMonitor {
    private val lock = Any()
    private var nextOperationId = 0L
    private val mutableState = MutableStateFlow<AgentActivitySnapshot?>(null)
    val state: StateFlow<AgentActivitySnapshot?> = mutableState.asStateFlow()

    fun beginReferences(conversationId: String): Lease = begin(conversationId,
        AgentActivityKind.CHECKING_REFERENCES, "Checking references")

    fun beginFailure(conversationId: String): Lease = begin(conversationId,
        AgentActivityKind.ERROR, "Something went wrong")

    /** A newly admitted turn must not inherit a previous turn's error pose. */
    fun clearFailure() = synchronized(lock) {
        if (mutableState.value?.kind == AgentActivityKind.ERROR) mutableState.value = null
    }

    private fun begin(conversationId: String, kind: AgentActivityKind, label: String): Lease = synchronized(lock) {
        val operationId = ++nextOperationId
        mutableState.value = AgentActivitySnapshot(
            operationId = operationId,
            conversationId = conversationId,
            kind = kind,
            label = label,
        )
        Lease {
            synchronized(lock) {
                if (mutableState.value?.operationId == operationId) mutableState.value = null
            }
        }
    }

    class Lease internal constructor(private val finish: () -> Unit) : AutoCloseable {
        override fun close() = finish()
    }
}
