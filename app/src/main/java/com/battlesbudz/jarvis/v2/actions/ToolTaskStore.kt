package com.battlesbudz.jarvis.v2.actions

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** Authority consumption and dispatch intent share one durable transaction. */
interface ToolTaskStore {
    fun read(): List<ToolTaskAttempt>
    fun update(change: (List<ToolTaskAttempt>) -> List<ToolTaskAttempt>): List<ToolTaskAttempt>
    // Compatibility for attempt-only fixture stores. Production overrides both journal methods.
    fun readJournal() = ToolTaskJournal(attempts = read())
    fun updateJournal(change: (ToolTaskJournal) -> ToolTaskJournal): ToolTaskJournal {
        var next = ToolTaskJournal()
        update { attempts ->
            next = change(ToolTaskJournal(attempts = attempts))
            if (next.copy(attempts = emptyList()) != ToolTaskJournal()) throw ToolTaskStorageException()
            next.attempts
        }
        return next
    }
}
class ToolTaskStorageException : IllegalStateException("The phone-action journal is unavailable.")

class InMemoryToolTaskStore : ToolTaskStore {
    private var journal = ToolTaskJournal()
    @Synchronized override fun read() = journal.frozen().attempts
    @Synchronized override fun readJournal() = journal.frozen()
    @Synchronized override fun update(change: (List<ToolTaskAttempt>) -> List<ToolTaskAttempt>) =
        updateJournal { it.copy(attempts = change(it.attempts)) }.attempts
    @Synchronized override fun updateJournal(change: (ToolTaskJournal) -> ToolTaskJournal): ToolTaskJournal {
        journal = change(journal.frozen()).frozen()
        return journal.frozen()
    }
}

/** App-private journal; schema 1 attempts migrate on the first successful transaction. */
class FileToolTaskStore(
    private val file: File,
    private val commitWriter: (File, String) -> Unit = ::atomicWrite
) : ToolTaskStore {
    override fun read() = readJournal().attempts
    override fun update(change: (List<ToolTaskAttempt>) -> List<ToolTaskAttempt>) =
        updateJournal { it.copy(attempts = change(it.attempts)) }.attempts
    override fun readJournal(): ToolTaskJournal = synchronized(lockFor(file)) { readLocked().frozen() }
    override fun updateJournal(change: (ToolTaskJournal) -> ToolTaskJournal): ToolTaskJournal = synchronized(lockFor(file)) {
        val before = readLocked()
        val after = retain(change(before.frozen()).frozen())
        if (after != before) {
            validate(after)
            val encoded = encode(after)
            if (encoded.toByteArray(StandardCharsets.UTF_8).size > MAX_BYTES) throw ToolTaskStorageException()
            try { commitWriter(file, encoded) } catch (_: Exception) { throw ToolTaskStorageException() }
        }
        after.frozen()
    }

    private fun readLocked(): ToolTaskJournal = try {
        val parent = file.parentFile
        if (parent != null && parent.exists()) {
            if (!parent.isDirectory) throw ToolTaskStorageException()
            val files = parent.listFiles() ?: throw ToolTaskStorageException()
            files.filter { it.isFile && it.name.startsWith(".${file.name}.") && it.name.endsWith(".tmp") }
                .forEach { if (!it.delete()) throw ToolTaskStorageException() }
        }
        if (!file.exists()) ToolTaskJournal() else {
            require(file.isFile && file.length() <= MAX_BYTES)
            val root = JSONObject(file.readText(StandardCharsets.UTF_8))
            val version = root.getInt("schemaVersion")
            require(version in 1..2 && root.get("schemaVersion") is Int)
            val journal = ToolTaskJournal(
                attempts = root.getJSONArray("attempts").objects { a -> ToolTaskAttempt(
                    id = a.getString("id"), generation = a.strictLong("generation"),
                    state = ToolTaskState.valueOf(a.getString("state")), request = a.getJSONObject("request").request(),
                    createdAtMs = a.strictLong("createdAtMs"), updatedAtMs = a.strictLong("updatedAtMs"),
                    result = a.nullString("result"), resultOutcome = a.nullString("resultOutcome")?.let(ExecutionResult.Outcome::valueOf),
                    groupId = a.nullString("groupId"), stepId = a.optString("stepId", a.getString("id")),
                    provider = a.optString("provider", "native"), schemaVersion = a.optInt("toolSchemaVersion", MobileToolCatalog.VERSION),
                    authority = ToolAuthority.valueOf(a.optString("authority", ToolAuthority.USER_REQUEST.name)),
                    grantId = a.nullString("grantId"), approvalId = a.nullString("approvalId"),
                    actionRevision = if (a.has("actionRevision")) a.strictLong("actionRevision") else 0L,
                    reconciled = a.optBoolean("reconciled")) },
                groups = if (version == 1) emptyList() else root.getJSONArray("groups").objects { g -> ToolTaskGroup(
                    g.getString("id"), g.getString("conversationId"), g.getString("summary"),
                    g.getJSONArray("attemptIds").strings(), g.strictLong("createdAtMs"), g.strictLong("expiresAtMs"), g.getBoolean("cancelled"),
                    if (g.has("resumeAfterRestart")) g.get("resumeAfterRestart").also { require(it is Boolean) } as Boolean else true) },
                approvals = if (version == 1) emptyList() else root.getJSONArray("approvals").objects { a -> ActionApprovalRequest(
                    a.getString("id"), a.getString("taskId"), a.getString("stepId"), a.getString("provider"),
                    a.getJSONObject("action").request(), a.getInt("schemaVersion"), a.strictLong("revision"),
                    a.getString("fingerprint"), a.strictLong("createdAtMs"), a.getBoolean("consumed"),
                    a.nullString("decision")?.let(ApprovalDecision::valueOf)) },
                grants = if (version == 1) emptyList() else root.getJSONArray("grants").objects { g -> ToolActionGrant(
                    g.getString("id"), g.getString("routineId"), g.getString("provider"), g.getInt("schemaVersion"),
                    g.getJSONArray("requests").objects { it.request() }, g.strictLong("createdAtMs"),
                    g.strictLong("expiresAtMs"), g.getBoolean("revoked")) },
                events = if (version == 1) emptyList() else root.getJSONArray("events").objects { e -> ToolTaskEvent(
                    e.getString("attemptId"), e.strictLong("generation"), ToolTaskEventKind.valueOf(e.getString("kind")), e.strictLong("atMs")) },
                activeQuestionId = if (version == 1) null else root.nullString("activeQuestionId"))
            validate(journal)
            journal
        }
    } catch (_: Exception) { throw ToolTaskStorageException() }

    /** Preserve unfinished groups and unresolved unknown effects; keep 256 recent completed attempts. */
    private fun retain(j: ToolTaskJournal): ToolTaskJournal {
        val protectedGroups = j.groups.filter { group -> j.attempts.any {
            it.id in group.attemptIds && (!it.state.isTerminal() || it.state == ToolTaskState.UNKNOWN_OUTCOME && !it.reconciled)
        } }.mapTo(hashSetOf()) { it.id }
        val removable = j.attempts.filter {
            it.state.isTerminal() && (it.state != ToolTaskState.UNKNOWN_OUTCOME || it.reconciled) && it.groupId !in protectedGroups
        }.sortedBy { it.updatedAtMs }
        val drop = hashSetOf<String>()
        var count = j.attempts.size
        for (attempt in removable) {
            if (count <= RETAIN_RECEIPTS) break
            val ids = attempt.groupId?.let { id -> j.groups.find { it.id == id }?.attemptIds } ?: listOf(attempt.id)
            ids.filterNot { it in drop }.forEach { drop += it; count-- }
        }
        val kept = j.attempts.filterNot { it.id in drop }
        val approvalIds = kept.mapNotNullTo(hashSetOf()) { it.approvalId }
        val recent = j.approvals.filter { it.consumed && it.id !in approvalIds }.takeLast(RETAIN_RECEIPTS).mapTo(hashSetOf()) { it.id }
        val approvals = j.approvals.filter { !it.consumed || it.id in approvalIds || it.id in recent }
        val grantIds = kept.mapNotNullTo(hashSetOf()) { it.grantId }
        val latestAt = maxOf(j.attempts.maxOfOrNull { it.updatedAtMs } ?: 0L, j.grants.maxOfOrNull { it.createdAtMs } ?: 0L)
        val removableGrants = j.grants.filter { it.id !in grantIds && (it.revoked || it.expiresAtMs <= latestAt) }
            .sortedBy { it.createdAtMs }.take((j.grants.size - MAX_GRANTS).coerceAtLeast(0)).mapTo(hashSetOf()) { it.id }
        return j.copy(attempts = kept, groups = j.groups.filter { it.attemptIds.any { id -> id !in drop } },
            approvals = approvals, grants = j.grants.filterNot { it.id in removableGrants },
            events = j.events.filterNot { it.attemptId in drop }.takeLast(MAX_EVENTS),
            activeQuestionId = j.activeQuestionId?.takeIf { id -> approvals.any { it.id == id && !it.consumed } })
    }

    private fun validate(j: ToolTaskJournal) = try {
        require(j.attempts.size <= MAX_ATTEMPTS && j.groups.size <= MAX_ATTEMPTS && j.approvals.size <= MAX_APPROVALS &&
            j.grants.size <= MAX_GRANTS && j.events.size <= MAX_EVENTS)
        require(j.attempts.map { it.id }.toSet().size == j.attempts.size && j.groups.map { it.id }.toSet().size == j.groups.size)
        require(j.approvals.map { it.id }.toSet().size == j.approvals.size && j.grants.map { it.id }.toSet().size == j.grants.size)
        j.attempts.forEach { a ->
            require(a.id.isUuid() && a.stepId.isUuid() && a.generation >= 0 && a.actionRevision >= 0)
            require(a.createdAtMs >= 0 && a.updatedAtMs >= a.createdAtMs && a.provider == "native" && a.schemaVersion > 0)
            a.request.validateRequest()
            require(a.result == null || a.result.length <= 2048)
            require(a.groupId == null || j.groups.any { it.id == a.groupId && a.id in it.attemptIds })
            require(a.grantId == null || j.grants.any { it.id == a.grantId })
            require(a.approvalId == null || j.approvals.any { it.id == a.approvalId })
        }
        j.groups.forEach { g ->
            require(g.id.isUuid() && g.summary.length <= 256 && g.conversationId.length in 1..256)
            require(g.createdAtMs >= 0 && g.expiresAtMs >= g.createdAtMs && g.attemptIds.size in 1..3)
            require(g.attemptIds.toSet().size == g.attemptIds.size && g.attemptIds.all { id -> j.attempts.any { it.id == id && it.groupId == g.id } })
        }
        j.approvals.forEach { a ->
            require(a.id.isUuid() && a.taskId.length in 1..256 && a.stepId.length in 1..256 && a.provider == "native")
            require(a.schemaVersion > 0 && a.revision > 0 && a.createdAtMs >= 0)
            a.action.validateRequest()
            require(a.fingerprint == approvalFingerprint(a.taskId, a.stepId, a.provider, a.action, a.schemaVersion, a.revision))
            require(a.consumed == (a.decision != null))
        }
        j.grants.forEach { g ->
            require(g.id.isUuid() && g.routineId.length in 1..256 && g.provider == "native" && g.schemaVersion > 0)
            require(g.createdAtMs >= 0 && g.expiresAtMs > g.createdAtMs && g.requests.size in 1..3)
            g.requests.forEach { it.validateRequest(); require(it.isRoutineEligible()) }
        }
        require(j.activeQuestionId == null || j.approvals.any { it.id == j.activeQuestionId && !it.consumed })
        j.events.forEach { e -> require(e.attemptId.isUuid() && e.generation >= 0 && e.atMs >= 0) }
        Unit
    } catch (_: Exception) { throw ToolTaskStorageException() }

    private fun encode(j: ToolTaskJournal) = JSONObject().put("schemaVersion", 2)
        .put("attempts", JSONArray(j.attempts.map { a -> JSONObject()
            .put("id", a.id).put("generation", a.generation).put("state", a.state.name).put("request", a.request.json())
            .put("createdAtMs", a.createdAtMs).put("updatedAtMs", a.updatedAtMs)
            .put("result", a.result ?: JSONObject.NULL).put("resultOutcome", a.resultOutcome?.name ?: JSONObject.NULL)
            .put("groupId", a.groupId ?: JSONObject.NULL).put("stepId", a.stepId).put("provider", a.provider)
            .put("toolSchemaVersion", a.schemaVersion).put("authority", a.authority.name)
            .put("grantId", a.grantId ?: JSONObject.NULL).put("approvalId", a.approvalId ?: JSONObject.NULL)
            .put("actionRevision", a.actionRevision).put("reconciled", a.reconciled) }))
        .put("groups", JSONArray(j.groups.map { g -> JSONObject().put("id", g.id).put("conversationId", g.conversationId)
            .put("summary", g.summary).put("attemptIds", JSONArray(g.attemptIds)).put("createdAtMs", g.createdAtMs)
            .put("expiresAtMs", g.expiresAtMs).put("cancelled", g.cancelled).put("resumeAfterRestart", g.resumeAfterRestart) }))
        .put("approvals", JSONArray(j.approvals.map { a -> JSONObject().put("id", a.id).put("taskId", a.taskId)
            .put("stepId", a.stepId).put("provider", a.provider).put("action", a.action.json()).put("schemaVersion", a.schemaVersion)
            .put("revision", a.revision).put("fingerprint", a.fingerprint).put("createdAtMs", a.createdAtMs)
            .put("consumed", a.consumed).put("decision", a.decision?.name ?: JSONObject.NULL) }))
        .put("grants", JSONArray(j.grants.map { g -> JSONObject().put("id", g.id).put("routineId", g.routineId)
            .put("provider", g.provider).put("schemaVersion", g.schemaVersion).put("requests", JSONArray(g.requests.map { it.json() }))
            .put("createdAtMs", g.createdAtMs).put("expiresAtMs", g.expiresAtMs).put("revoked", g.revoked) }))
        .put("events", JSONArray(j.events.map { e -> JSONObject().put("attemptId", e.attemptId).put("generation", e.generation)
            .put("kind", e.kind.name).put("atMs", e.atMs) }))
        .put("activeQuestionId", j.activeQuestionId ?: JSONObject.NULL).toString()

    private fun JSONObject.strictLong(key: String): Long = get(key).let { require(it is Int || it is Long); (it as Number).toLong() }
    private fun JSONObject.nullString(key: String): String? = if (isNull(key)) null else getString(key)
    private fun JSONObject.request(): ActionRequest {
        val args = getJSONObject("arguments")
        return ActionRequest(getString("name"), args.keys().asSequence().associateWith { key -> args.get(key).also { require(it is String) } as String })
    }
    private fun ActionRequest.json() = JSONObject().put("name", name).put("arguments", JSONObject(arguments))
    private fun ActionRequest.validateRequest() {
        require(name in setOf("read_battery", "set_volume", "open_app", "media_control"))
        require(arguments.size <= 2 && arguments.all { (k, v) -> k.length <= 64 && v.length <= 512 })
    }
    private fun <T> JSONArray.objects(map: (JSONObject) -> T) = (0 until length()).map { map(getJSONObject(it)) }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun String.isUuid() = UUID.fromString(this).toString() == this
    companion object {
        const val MAX_ATTEMPTS = 512
        const val MAX_BYTES = 1_048_576
        const val MAX_APPROVALS = 512
        const val MAX_GRANTS = 128
        const val MAX_EVENTS = 256
        const val RETAIN_RECEIPTS = 256
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
