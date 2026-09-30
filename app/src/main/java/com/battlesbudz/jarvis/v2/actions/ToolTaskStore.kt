package com.battlesbudz.jarvis.v2.actions

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** Commits before returning; a failed read/write must never look like an empty journal. */
interface ToolTaskStore {
    fun read(): List<ToolTaskAttempt>
    fun update(change: (List<ToolTaskAttempt>) -> List<ToolTaskAttempt>): List<ToolTaskAttempt>
}

class ToolTaskStorageException : IllegalStateException("The phone-action journal is unavailable.")

class InMemoryToolTaskStore : ToolTaskStore {
    private var attempts = emptyList<ToolTaskAttempt>()
    @Synchronized override fun read() = copy(attempts)
    @Synchronized override fun update(change: (List<ToolTaskAttempt>) -> List<ToolTaskAttempt>): List<ToolTaskAttempt> {
        attempts = copy(change(copy(attempts)))
        return copy(attempts)
    }
    private fun copy(value: List<ToolTaskAttempt>) = value.map { it.copy(request = it.request.copy(arguments = it.request.arguments.toMap())) }
}

/** App-private bounded JSON journal. Canonical-file locking serializes all in-process instances. */
class FileToolTaskStore(
    private val file: File,
    private val commitWriter: (File, String) -> Unit = ::atomicWrite
) : ToolTaskStore {
    override fun read(): List<ToolTaskAttempt> = synchronized(lockFor(file)) { readLocked() }

    override fun update(change: (List<ToolTaskAttempt>) -> List<ToolTaskAttempt>): List<ToolTaskAttempt> = synchronized(lockFor(file)) {
        val before = readLocked()
        val after = change(before)
        if (after != before) {
            validate(after)
            val encoded = encode(after)
            if (encoded.toByteArray(StandardCharsets.UTF_8).size > MAX_BYTES) throw ToolTaskStorageException()
            try { commitWriter(file, encoded) } catch (_: Exception) { throw ToolTaskStorageException() }
        }
        after
    }

    private fun readLocked(): List<ToolTaskAttempt> {
        try {
            // A temp file is uncommitted intent. Never promote it to a completed dispatch.
            val parent = file.parentFile
            if (parent != null && parent.exists()) {
                if (!parent.isDirectory) throw ToolTaskStorageException()
                val files = parent.listFiles() ?: throw ToolTaskStorageException()
                files.filter { it.isFile && it.name.startsWith(".${file.name}.") && it.name.endsWith(".tmp") }
                    .forEach { if (!it.delete()) throw ToolTaskStorageException() }
            }
            if (!file.exists()) return emptyList()
            if (!file.isFile || file.length() > MAX_BYTES) throw ToolTaskStorageException()
            val root = JSONObject(file.readText(StandardCharsets.UTF_8))
            require(root.get("schemaVersion") == 1)
            val items = root.getJSONArray("attempts")
            require(items.length() <= MAX_ATTEMPTS)
            val attempts = (0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                val action = item.getJSONObject("request")
                val args = action.getJSONObject("arguments")
                val arguments = args.keys().asSequence().associateWith { key -> args.get(key).also { require(it is String) } as String }
                ToolTaskAttempt(
                    id = item.getString("id"), generation = item.strictLong("generation"),
                    state = ToolTaskState.valueOf(item.getString("state")),
                    request = ActionRequest(action.getString("name"), arguments),
                    createdAtMs = item.strictLong("createdAtMs"), updatedAtMs = item.strictLong("updatedAtMs"),
                    result = if (item.isNull("result")) null else item.getString("result"),
                    resultOutcome = if (item.isNull("resultOutcome")) null else ExecutionResult.Outcome.valueOf(item.getString("resultOutcome")))
            }
            validate(attempts)
            return attempts
        } catch (_: Exception) { throw ToolTaskStorageException() }
    }

    private fun validate(attempts: List<ToolTaskAttempt>) {
        try {
            require(attempts.size <= MAX_ATTEMPTS && attempts.map { it.id }.toSet().size == attempts.size)
            attempts.forEach { a ->
                require(UUID.fromString(a.id).toString() == a.id && a.generation >= 0)
                require(a.createdAtMs >= 0 && a.updatedAtMs >= a.createdAtMs)
                // This journal currently supports only the existing phone tools, no arbitrary payloads.
                require(a.request.name in setOf("read_battery", "set_volume", "open_app"))
                require(a.request.arguments.size <= 2 && a.request.arguments.all { (k, v) -> k.length <= 64 && v.length <= 512 })
                require(a.result == null || a.result.length <= 2048)
            }
        } catch (_: Exception) { throw ToolTaskStorageException() }
    }

    private fun encode(attempts: List<ToolTaskAttempt>): String = JSONObject().put("schemaVersion", 1)
        .put("attempts", JSONArray(attempts.map { a -> JSONObject()
            .put("id", a.id).put("generation", a.generation).put("state", a.state.name)
            .put("request", JSONObject().put("name", a.request.name).put("arguments", JSONObject(a.request.arguments)))
            .put("createdAtMs", a.createdAtMs).put("updatedAtMs", a.updatedAtMs)
            .put("result", a.result ?: JSONObject.NULL).put("resultOutcome", a.resultOutcome?.name ?: JSONObject.NULL)
        })).toString()

    private fun JSONObject.strictLong(key: String): Long {
        val value = get(key)
        require(value is Int || value is Long)
        return (value as Number).toLong()
    }

    companion object {
        const val MAX_ATTEMPTS = 512
        const val MAX_BYTES = 1_048_576
        private val locks = ConcurrentHashMap<String, Any>()
        private fun lockFor(file: File) = locks.getOrPut(file.canonicalPath) { Any() }
        private fun atomicWrite(destination: File, contents: String) {
            val parent = destination.parentFile ?: throw ToolTaskStorageException()
            if (!parent.isDirectory && !parent.mkdirs()) throw ToolTaskStorageException()
            val temporary = File(parent, ".${destination.name}.${UUID.randomUUID()}.tmp")
            try {
                FileOutputStream(temporary).use { out -> out.write(contents.toByteArray(StandardCharsets.UTF_8)); out.fd.sync() }
                if (!temporary.renameTo(destination)) throw ToolTaskStorageException()
            } finally { temporary.delete() }
        }
    }
}
