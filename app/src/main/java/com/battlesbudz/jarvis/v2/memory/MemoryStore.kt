package com.battlesbudz.jarvis.v2.memory

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** Versioned JSON file store. It is intentionally independent from Android; pass noBackupFilesDir from the UI adapter. */
class MemoryStore(
    private val file: File,
    private val commitWriter: (File, String) -> Unit = ::atomicWrite,
) {
    data class Read(val snapshot: MemorySnapshot?, val error: String? = null)
    data class Update<T>(val value: T? = null, val error: String? = null)

    fun read(): Read = synchronized(lockFor(file)) { readLocked() }

    /** Reloads disk while holding a per-canonical-file process lock, then durably commits before returning [value]. */
    fun <T> update(block: (MemorySnapshot) -> Pair<MemorySnapshot, T>): Update<T> = synchronized(lockFor(file)) {
        val read = readLocked()
        val before = read.snapshot ?: return@synchronized Update(error = read.error ?: "Memory store is unavailable.")
        val (after, value) = try { block(before) } catch (e: Exception) { return@synchronized Update(error = e.message ?: "Memory update failed.") }
        val validity = validate(after)
        if (validity != null) return@synchronized Update(error = validity)
        val encoded = encode(after)
        if (encoded.toByteArray(StandardCharsets.UTF_8).size > MAX_STORE_BYTES) return@synchronized Update(error = "Memory store capacity exceeded.")
        return@synchronized try {
            commitWriter(file, encoded)
            Update(value = value)
        } catch (e: Exception) {
            Update(error = "Memory store write failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun readLocked(): Read {
        if (!file.exists()) return Read(MemorySnapshot(0, emptyList(), emptyList()))
        return try {
            if (!file.isFile || file.length() > MAX_STORE_BYTES) return Read(null, "Memory store is unreadable or exceeds its size limit.")
            val raw = file.readText(StandardCharsets.UTF_8)
            if (raw.length > MAX_STORE_BYTES) return Read(null, "Memory store exceeds its size limit.")
            decode(raw)
        } catch (e: Exception) { Read(null, "Memory store is corrupt: ${e.message ?: e.javaClass.simpleName}") }
    }

    private fun decode(raw: String): Read {
        val root = JSONObject(raw)
        if (root.optInt("schemaVersion", -1) != SCHEMA_VERSION) return Read(null, "Memory store schema is not supported.")
        if (!root.has("generation") || !root.has("memories") || !root.has("tombstones")) return Read(null, "Memory store is incomplete.")
        val generation = root.getLong("generation").also { require(it >= 0) }
        val memories = root.getJSONArray("memories").map { decodeMemory(it as JSONObject) }
        val tombstones = root.getJSONArray("tombstones").map { o ->
            val j = o as JSONObject
            MemoryTombstone(j.requiredString("eventId", MemoryPolicy.MAX_EVENT_ID_CHARS), j.requiredString("payloadFingerprint", 64), j.getLong("erasedAtMs"))
        }
        val snapshot = MemorySnapshot(generation, memories, tombstones)
        return validate(snapshot)?.let { Read(null, it) } ?: Read(snapshot)
    }

    private fun decodeMemory(j: JSONObject): MemoryRecord {
        val sourceJson = j.getJSONObject("source")
        val provenance = sourceJson.getJSONArray("provenance").map { item ->
            val p = item as JSONObject
            MemoryProvenance(p.requiredString("kind", 96), p.requiredString("id", 160), p.optString("label").takeIf { it.isNotBlank() }, p.optBoolean("restricted", false))
        }
        return MemoryRecord(
            id = j.requiredString("id", 80), content = j.requiredString("content", MemoryPolicy.MAX_CONTENT_CHARS),
            category = enumValue(j.requiredString("category", 32)), tier = enumValue(j.requiredString("tier", 32)), type = enumValue(j.requiredString("type", 32)),
            confidence = j.getInt("confidence"), source = MemorySource(sourceJson.requiredString("eventId", MemoryPolicy.MAX_EVENT_ID_CHARS), sourceJson.requiredString("eventSource", MemoryPolicy.MAX_EVENT_SOURCE_CHARS), sourceJson.getLong("createdAtMs"), enumValue(sourceJson.requiredString("sensitivity", 32)), provenance),
            reviewStatus = enumValue(j.requiredString("reviewStatus", 32)), createdAtMs = j.getLong("createdAtMs"), updatedAtMs = j.getLong("updatedAtMs"), revision = j.getLong("revision"),
            expiresAtMs = j.optLongOrNull("expiresAtMs"), correctsMemoryId = j.optString("correctsMemoryId").takeIf { it.isNotBlank() },
        )
    }

    private fun encode(snapshot: MemorySnapshot): String = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION); put("generation", snapshot.generation)
        put("memories", JSONArray(snapshot.memories.map(::encodeMemory))); put("tombstones", JSONArray(snapshot.tombstones.map { t -> JSONObject().put("eventId", t.eventId).put("payloadFingerprint", t.payloadFingerprint).put("erasedAtMs", t.erasedAtMs) }))
    }.toString()

    private fun encodeMemory(m: MemoryRecord): JSONObject = JSONObject().apply {
        put("id", m.id); put("content", m.content); put("category", m.category.name); put("tier", m.tier.name); put("type", m.type.name); put("confidence", m.confidence); put("reviewStatus", m.reviewStatus.name)
        put("createdAtMs", m.createdAtMs); put("updatedAtMs", m.updatedAtMs); put("revision", m.revision); put("expiresAtMs", m.expiresAtMs); put("correctsMemoryId", m.correctsMemoryId)
        put("source", JSONObject().put("eventId", m.source.eventId).put("eventSource", m.source.eventSource).put("createdAtMs", m.source.createdAtMs).put("sensitivity", m.source.sensitivity.name).put("provenance", JSONArray(m.source.provenance.map { p -> JSONObject().put("kind", p.kind).put("id", p.id).put("label", p.label).put("restricted", p.restricted) })))
    }

    private fun validate(snapshot: MemorySnapshot): String? {
        if (snapshot.memories.size > MemoryPolicy.MAX_MEMORIES || snapshot.tombstones.size > MemoryPolicy.MAX_TOMBSTONES) return "Memory store capacity exceeded."
        if (snapshot.memories.map { it.id }.toSet().size != snapshot.memories.size) return "Memory store has duplicate memory ids."
        if ((snapshot.memories.map { it.source.eventId } + snapshot.tombstones.map { it.eventId }).toSet().size != snapshot.memories.size + snapshot.tombstones.size) return "Memory store has duplicate source events."
        for (m in snapshot.memories) {
            val d = MemoryPolicy.assess(MemoryProposal(m.content, m.source, m.category, m.tier, m.type, m.confidence, m.expiresAtMs, m.correctsMemoryId), Long.MAX_VALUE / 2)
            if (!d.allowed || m.revision < 1 || m.createdAtMs <= 0 || m.updatedAtMs < m.createdAtMs) return "Memory store contains invalid records."
        }
        return null
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String): T = enumValues<T>().firstOrNull { it.name == value } ?: throw IllegalArgumentException("Unknown enum")
    private fun JSONObject.requiredString(key: String, max: Int): String = getString(key).also { require(it.isNotBlank() && it.length <= max) }
    private fun JSONObject.optLongOrNull(key: String): Long? = if (isNull(key) || !has(key)) null else getLong(key)
    private fun <T> JSONArray.map(block: (Any) -> T): List<T> = (0 until length()).map { block(get(it)) }

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_STORE_BYTES = 1_048_576L
        private val locks = ConcurrentHashMap<String, Any>()
        private fun lockFor(file: File): Any = locks.getOrPut(file.absoluteFile.normalize().path) { Any() }
        private fun atomicWrite(destination: File, contents: String) {
            destination.parentFile?.mkdirs()
            val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
            try {
                FileOutputStream(temporary).use { out -> out.write(contents.toByteArray(StandardCharsets.UTF_8)); out.fd.sync() }
                if (!temporary.renameTo(destination)) throw IllegalStateException("atomic rename failed")
            } finally { temporary.delete() }
        }
    }
}
