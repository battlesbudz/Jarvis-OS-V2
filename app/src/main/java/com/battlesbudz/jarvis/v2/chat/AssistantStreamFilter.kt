package com.battlesbudz.jarvis.v2.chat

/**
 * Streams ordinary assistant text immediately while holding only the uncertain
 * protocol prefix long enough to detect leaked model control syntax.
 */
class AssistantStreamFilter(
    private val emit: (String) -> Unit
) {
    private val pending = StringBuilder()
    private var decided = false

    fun accept(chunk: String) {
        if (chunk.isEmpty()) return
        if (decided) {
            emit(chunk)
            return
        }

        pending.append(chunk)
        val candidate = pending.toString()
        val trimmed = candidate.trimStart()
        val isControl = trimmed.contains("tool_call>") ||
            trimmed.contains("start_function_call") ||
            trimmed.contains("call:MobileActions:")
        if (isControl) {
            decided = true
            pending.clear()
            return
        }

        // Normal prose is emitted as soon as its first token is available.
        // A leading '<' is held briefly because it may begin model markup.
        if (!trimmed.startsWith("<") || pending.length >= 48) {
            decided = true
            emit(pending.toString())
            pending.clear()
        }
    }
}
