package com.battlesbudz.jarvis.v2.memory

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.json.JSONObject
import kotlin.concurrent.withLock

/**
 * Device-local canonical ledger. Records and non-content erase tombstones are separate rows;
 * mutations and generation changes share one SQLite transaction. Indexes are rebuildable later.
 * A committed migration marker prevents a surviving legacy file from resurrecting erased data.
 */
class SQLiteMemoryStore(
    private val file: File,
    private val legacyFile: File? = null,
    private val canReadSourceText: () -> Boolean = { false },
    private val archiveClock: () -> Long = { System.currentTimeMillis() },
) : MemoryPersistence, MemorySourceArchive, MemoryExtractionJobs, AutoCloseable {
    private var connection: SQLiteDatabase? = null
    // Android configures journal mode while opening, before a transaction can serialize writers.
    // All instances for one canonical path therefore share the same in-process boundary.
    private val databaseLock = lockFor(file)

    override fun read(): MemoryStore.Read = databaseLock.withLock { try {
        val db = database()
        transaction(db) { purgeSourceText(db, archiveClock()); MemoryStore.Read(snapshot(db)) }
    } catch (e: Exception) {
        MemoryStore.Read(null, "Memory database is unavailable (${e.javaClass.simpleName}).")
    } }

    override fun <T> update(block: (MemorySnapshot) -> Pair<MemorySnapshot, T>): MemoryStore.Update<T> = databaseLock.withLock { try {
        val db = database()
        transaction(db) {
            purgeSourceText(db, archiveClock())
            val before = snapshot(db)
            val (after, value) = block(before)
            if (after != before) {
                require(after.generation == Math.addExact(before.generation, 1)) { "Invalid memory generation" }
                require(MemorySnapshotCodec.validate(after) == null) { "Invalid memory snapshot" }
                persist(db, before, after)
            }
            MemoryStore.Update(value = value)
        }
    } catch (e: Exception) {
        // Never include SQL/payload text in UI errors or claim a failed transaction succeeded.
        MemoryStore.Update(error = "Memory database update failed (${e.javaClass.simpleName}).")
    } }

    override fun close() { databaseLock.withLock { connection?.close(); connection = null } }

    override fun captureSource(input: FinalMemoryInput): SourceArchiveCapture {
        return databaseLock.withLock { try {
            val prepared = MemoryArchivePolicy.prepare(input, archiveClock())
            prepared.rejection?.let { return SourceArchiveCapture(it) }
            var episode = checkNotNull(prepared.episode)
            val db = database()
            transaction(db) {
                purgeSourceText(db, archiveClock())
                // Exact copied source text retains its oldest capture even under a new event ID.
                // Migration intentionally does not enqueue or backfill existing source rows.
                val textKey = MemoryPolicy.sourceKey(episode.text)
                val oldest = db.rawQuery("SELECT captured_at_ms FROM extraction_source_clocks WHERE text_key=?", arrayOf(textKey)).use {
                    if (it.moveToFirst()) it.getLong(0) else episode.capturedAtMs
                }
                if (episode.capturedAtMs < oldest) return@transaction SourceArchiveCapture(SourceArchiveOutcome.CONFLICT)
                if (!SourceTextPersistencePolicy.eligible(oldest, archiveClock())) return@transaction SourceArchiveCapture(SourceArchiveOutcome.EXPIRED)
                episode = episode.copy(capturedAtMs = minOf(oldest, episode.capturedAtMs), expiresAtMs = minOf(oldest, episode.capturedAtMs) + MemoryArchivePolicy.RETENTION_MS)
                val previous = db.rawQuery("SELECT fingerprint, expires_at_ms FROM source_events WHERE event_key=?", arrayOf(episode.eventKey)).use {
                    if (it.moveToFirst()) it.getString(0) to it.getLong(1) else null
                }
                if (previous != null) return@transaction SourceArchiveCapture(when {
                    previous.second <= archiveClock() -> SourceArchiveOutcome.EXPIRED
                    previous.first == episode.fingerprint -> SourceArchiveOutcome.ALREADY_RECORDED
                    else -> SourceArchiveOutcome.CONFLICT
                })
                val capacity = db.rawQuery("SELECT count(*), coalesce(sum(text_bytes),0) FROM source_events", null).use {
                    check(it.moveToFirst()); it.getLong(0) to it.getLong(1)
                }
                val bytes = episode.text.toByteArray(Charsets.UTF_8).size
                if (capacity.first >= MemoryArchivePolicy.MAX_EVENTS || capacity.second + bytes > MemoryArchivePolicy.MAX_TEXT_BYTES) return@transaction SourceArchiveCapture(SourceArchiveOutcome.FULL)
                if (episode.expiresAtMs <= archiveClock()) return@transaction SourceArchiveCapture(SourceArchiveOutcome.EXPIRED)
                db.insertOrThrow("source_events", null, ContentValues().apply {
                    put("event_key", episode.eventKey); put("conversation_key", episode.conversationKey); put("call_key", episode.callKey)
                    put("source", episode.source.name); put("captured_at_ms", episode.capturedAtMs); put("expires_at_ms", episode.expiresAtMs)
                    put("text", episode.text); put("text_bytes", bytes); put("fingerprint", episode.fingerprint)
                })
                db.execSQL("INSERT OR IGNORE INTO extraction_source_clocks(text_key,captured_at_ms) VALUES(?,?)", arrayOf(textKey, episode.capturedAtMs))
                db.execSQL("INSERT INTO extraction_jobs(event_key,state,attempts,lease,lease_until_ms) VALUES(?,'PENDING',0,NULL,0)", arrayOf(episode.eventKey))
                SourceArchiveCapture(SourceArchiveOutcome.STORED)
            }
        } catch (_: Exception) { SourceArchiveCapture(SourceArchiveOutcome.STORAGE_FAILURE) } }
    }

    override fun searchExplicitHistory(query: String, limit: Int): SourceArchiveSearch {
        return databaseLock.withLock { try {
            if (query.isBlank() || query.length > MemoryArchivePolicy.MAX_QUERY_CHARS || limit !in 1..50) return SourceArchiveSearch(SourceArchiveOutcome.INVALID)
            if (!canReadSourceText()) return SourceArchiveSearch(SourceArchiveOutcome.LOCKED)
            val db = database()
            transaction(db) {
                val now = archiveClock()
                purgeSourceText(db, now)
                val rows = db.rawQuery("SELECT event_key, conversation_key, call_key, source, captured_at_ms, expires_at_ms, text, fingerprint FROM source_events WHERE text IS NOT NULL AND expires_at_ms>? AND instr(lower(text),lower(?))>0 ORDER BY captured_at_ms DESC, event_key LIMIT ?", arrayOf(now.toString(), query.trim(), limit.toString())).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(SourceEpisode(cursor.getString(0), cursor.getString(1), if (cursor.isNull(2)) null else cursor.getString(2), ConversationMemorySource.valueOf(cursor.getString(3)), cursor.getLong(4), cursor.getLong(5), cursor.getString(6), cursor.getString(7)))
                        }
                    }
                }
                // Locking/expiry while the query runs must not disclose a now-ineligible result.
                if (!canReadSourceText()) SourceArchiveSearch(SourceArchiveOutcome.LOCKED)
                else {
                    val completedAtMs = archiveClock()
                    SourceArchiveSearch(null, rows.filter { it.expiresAtMs > completedAtMs })
                }
            }
        } catch (_: Exception) { SourceArchiveSearch(SourceArchiveOutcome.STORAGE_FAILURE) } }
    }

    override fun purgeExpiredSources(): Boolean = databaseLock.withLock { try {
        val db = database()
        transaction(db) { purgeSourceText(db, archiveClock()) }
        true
    } catch (_: Exception) { false } }

    private fun purgeSourceText(db: SQLiteDatabase, nowMs: Long) {
        require(nowMs > 0)
        // Retain only opaque event metadata after expiry so retries cannot restart retention.
        db.execSQL("UPDATE source_events SET text=NULL, text_bytes=0 WHERE expires_at_ms<=? AND text IS NOT NULL", arrayOf(nowMs))
    }

    private fun createSourceArchive(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE source_events (event_key TEXT PRIMARY KEY NOT NULL, conversation_key TEXT NOT NULL, call_key TEXT, source TEXT NOT NULL CHECK(source IN ('TEXT','VOICE')), captured_at_ms INTEGER NOT NULL CHECK(captured_at_ms>0), expires_at_ms INTEGER NOT NULL CHECK(expires_at_ms>captured_at_ms), text TEXT, text_bytes INTEGER NOT NULL CHECK(text_bytes>=0), fingerprint TEXT NOT NULL)")
        db.execSQL("CREATE INDEX source_events_expiry ON source_events(expires_at_ms)")
    }

    private fun createExtractionJobs(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE memory_extraction_meta(singleton INTEGER PRIMARY KEY CHECK(singleton=1),version INTEGER NOT NULL)")
        db.execSQL("INSERT INTO memory_extraction_meta VALUES(1,1)")
        db.execSQL("CREATE TABLE extraction_jobs(event_key TEXT PRIMARY KEY NOT NULL,state TEXT NOT NULL,attempts INTEGER NOT NULL,lease TEXT,lease_until_ms INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE extraction_source_clocks(text_key TEXT PRIMARY KEY NOT NULL,captured_at_ms INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE memory_index_jobs(generation INTEGER PRIMARY KEY NOT NULL,state TEXT NOT NULL)")
        // Establish only privacy lineage metadata for retained legacy sources; never backfill jobs.
        db.rawQuery("SELECT text,captured_at_ms FROM source_events WHERE text IS NOT NULL",null).use { cursor ->
            while(cursor.moveToNext()) {
                val textKey=MemoryPolicy.sourceKey(cursor.getString(0));val captured=cursor.getLong(1)
                db.execSQL("INSERT OR IGNORE INTO extraction_source_clocks(text_key,captured_at_ms) VALUES(?,?)",arrayOf(textKey,captured))
                db.execSQL("UPDATE extraction_source_clocks SET captured_at_ms=min(captured_at_ms,?) WHERE text_key=?",arrayOf(captured,textKey))
            }
        }
    }
    private fun ensureExtractionJobs(db: SQLiteDatabase) {
        val hasMeta=db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='memory_extraction_meta'",null).use { it.moveToFirst() }
        if(!hasMeta) {
            val stray=db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('extraction_jobs','extraction_source_clocks','memory_index_jobs')",null).use { it.moveToFirst() }
            require(!stray) { "Incomplete extraction extension" };createExtractionJobs(db)
        } else {
            db.rawQuery("SELECT version FROM memory_extraction_meta WHERE singleton=1",null).use { require(it.moveToFirst() && it.getInt(0)==1) { "Unsupported extraction extension" } }
            db.rawQuery("SELECT event_key,state,attempts,lease,lease_until_ms FROM extraction_jobs LIMIT 0",null).use { }
            db.rawQuery("SELECT text_key,captured_at_ms FROM extraction_source_clocks LIMIT 0",null).use { }
            db.rawQuery("SELECT generation,state FROM memory_index_jobs LIMIT 0",null).use { }
        }
    }

    override fun claimExtraction(): MemoryExtractionJob? = databaseLock.withLock {
        try { transaction(database()) {
            val db = database(); val now = archiveClock(); purgeSourceText(db, now)
            db.execSQL("UPDATE extraction_jobs SET state='PENDING',lease=NULL WHERE state='RUNNING' AND lease_until_ms<=?", arrayOf(now))
            db.execSQL("UPDATE extraction_jobs SET state='FAILED' WHERE attempts>=5 AND state='PENDING'")
            val barrier = MemorySuppression.barrier(snapshot(db))
            db.execSQL("UPDATE extraction_jobs SET state='SUPPRESSED',lease=NULL WHERE event_key IN (SELECT event_key FROM source_events WHERE captured_at_ms<=?) AND state IN ('PENDING','RUNNING')", arrayOf(barrier))
            db.execSQL("UPDATE extraction_jobs SET state='EXPIRED',lease=NULL WHERE event_key IN (SELECT event_key FROM source_events WHERE text IS NULL OR expires_at_ms<=?) AND state IN ('PENDING','RUNNING')", arrayOf(now))
            val source = db.rawQuery("SELECT s.event_key,s.conversation_key,s.call_key,s.source,s.captured_at_ms,s.expires_at_ms,s.text,s.fingerprint,j.attempts FROM extraction_jobs j JOIN source_events s ON s.event_key=j.event_key WHERE j.state='PENDING' ORDER BY s.captured_at_ms,s.event_key LIMIT 1", null).use { cursor ->
                if (!cursor.moveToFirst()) null else SourceEpisode(cursor.getString(0),cursor.getString(1),if(cursor.isNull(2))null else cursor.getString(2),ConversationMemorySource.valueOf(cursor.getString(3)),cursor.getLong(4),cursor.getLong(5),cursor.getString(6),cursor.getString(7)) to cursor.getInt(8)
            } ?: return@transaction null
            if (!MemoryEligibility.eligibleSource(source.first, now)) return@transaction null
            val lease = java.util.UUID.randomUUID().toString()
            db.execSQL("UPDATE extraction_jobs SET state='RUNNING',attempts=attempts+1,lease=?,lease_until_ms=? WHERE event_key=?", arrayOf(lease,Math.addExact(now,120_000),source.first.eventKey))
            MemoryExtractionJob(source.first.eventKey,lease,source.first,source.second+1)
        } } catch (_: Exception) { null }
    }

    override fun finishExtraction(job: MemoryExtractionJob, facts: List<ExtractedMemory>): Boolean = databaseLock.withLock {
        try { transaction(database()) {
            val db=database();val now=archiveClock();purgeSourceText(db,now)
            if (!validLease(db,job,now)) return@transaction false
            val sourceMatches=db.rawQuery("SELECT text,captured_at_ms,expires_at_ms,fingerprint,conversation_key,call_key,source FROM source_events WHERE event_key=?",arrayOf(job.eventKey)).use {
                it.moveToFirst() && !it.isNull(0) && job.eventKey==job.source.eventKey && it.getString(0)==job.source.text &&
                    it.getLong(1)==job.source.capturedAtMs && it.getLong(2)==job.source.expiresAtMs && it.getString(3)==job.source.fingerprint &&
                    it.getString(4)==job.source.conversationKey && (if(it.isNull(5))null else it.getString(5))==job.source.callKey && it.getString(6)==job.source.source.name
            }
            if(!sourceMatches) return@transaction false
            val before=snapshot(db)
            if (!MemoryEligibility.eligibleSource(job.source,now)) {
                db.execSQL("UPDATE extraction_jobs SET state='EXPIRED',lease=NULL WHERE event_key=?",arrayOf(job.eventKey));return@transaction false
            }
            val suppressed=MemorySuppression.suppressed(job.source,before)
            val added=if(suppressed) emptyList() else MemoryAcceptance.records(job.source,facts,before,now)
            require(before.memories.size+added.size<=MemoryPolicy.MAX_MEMORIES && before.memories.size+before.tombstones.size+added.size<=MemoryPolicy.MAX_TOMBSTONES)
            if(added.isNotEmpty()) {
                val after=before.copy(generation=before.generation+1,memories=before.memories+added)
                require(MemorySnapshotCodec.validate(after)==null);persist(db,before,after)
            }
            db.execSQL("UPDATE extraction_jobs SET state=?,lease=NULL WHERE event_key=?",arrayOf(if(suppressed)"SUPPRESSED" else "COMPLETE",job.eventKey))
            true
        } } catch (_: Exception) { false }
    }

    override fun deferExtraction(job: MemoryExtractionJob, failed: Boolean): Boolean = databaseLock.withLock {
        try { val db=database(); transaction(db) {
            db.execSQL("UPDATE extraction_jobs SET state=CASE WHEN attempts>=5 THEN 'FAILED' ELSE 'PENDING' END,lease=NULL,lease_until_ms=0,attempts=CASE WHEN ?=0 THEN max(attempts-1,0) ELSE attempts END WHERE event_key=? AND state='RUNNING' AND lease=?",arrayOf(if(failed)1 else 0,job.eventKey,job.lease));true
        } } catch (_:Exception){false}
    }

    private fun validLease(db: SQLiteDatabase, job: MemoryExtractionJob, now: Long): Boolean =
        db.rawQuery("SELECT lease,lease_until_ms,state FROM extraction_jobs WHERE event_key=?",arrayOf(job.eventKey)).use {
            it.moveToFirst() && it.getString(0)==job.lease && it.getLong(1)>now && it.getString(2)=="RUNNING"
        }

    override fun extractionJobStates(): Map<String,String> = databaseLock.withLock {
        try { database().rawQuery("SELECT event_key,state FROM extraction_jobs",null).use { cursor ->
            buildMap { while(cursor.moveToNext())put(cursor.getString(0),cursor.getString(1)) }
        } } catch (_:Exception){emptyMap()}
    }
    override fun indexJobStates(): Map<Long,String> = databaseLock.withLock {
        try { database().rawQuery("SELECT generation,state FROM memory_index_jobs",null).use { cursor ->
            buildMap { while(cursor.moveToNext())put(cursor.getLong(0),cursor.getString(1)) }
        } } catch (_:Exception){emptyMap()}
    }
    override fun extractionGeneration(): Long = read().snapshot?.generation ?: -1

    private fun database(): SQLiteDatabase {
        connection?.let { return it }
        require(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "Memory directory unavailable" }
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY)
        try {
            // Avoid a long-lived WAL containing old memory payloads; scrub deleted SQLite cells.
            db.disableWriteAheadLogging()
            // This pragma returns a row even when assigning it. Android execSQL rejects
            // statements with results; use a query and verify the requested setting.
            db.rawQuery("PRAGMA secure_delete=ON", null).use {
                require(it.moveToFirst() && it.getInt(0) == 1) { "Memory secure-delete unavailable" }
            }
            db.execSQL("PRAGMA synchronous=FULL")
            transaction(db) {
                when (db.version) {
                    0 -> {
                        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN ('android_metadata', 'sqlite_sequence')", null).use {
                            require(!it.moveToFirst()) { "Unrecognized memory database" }
                        }
                        db.execSQL("CREATE TABLE memory_meta (singleton INTEGER PRIMARY KEY CHECK(singleton=1), generation INTEGER NOT NULL CHECK(generation>=0), legacy_imported INTEGER NOT NULL CHECK(legacy_imported=1))")
                        db.execSQL("CREATE TABLE memories (id TEXT PRIMARY KEY NOT NULL, event_id TEXT NOT NULL UNIQUE, review_status TEXT NOT NULL, expires_at_ms INTEGER, payload TEXT NOT NULL)")
                        db.execSQL("CREATE INDEX memories_active ON memories(review_status, expires_at_ms)")
                        db.execSQL("CREATE TABLE tombstones (event_id TEXT PRIMARY KEY NOT NULL, fingerprint TEXT NOT NULL, erased_at_ms INTEGER NOT NULL)")
                        val imported = if (legacyFile?.exists() == true) {
                            MemoryStore(legacyFile).read().snapshot ?: error("Legacy memory migration failed")
                        } else MemorySnapshot(0, emptyList(), emptyList())
                        db.execSQL("INSERT INTO memory_meta VALUES(1, 0, 1)")
                        persist(db, MemorySnapshot(0, emptyList(), emptyList()), imported)
                        require(snapshot(db) == imported) { "Memory migration validation failed" }
                        createSourceArchive(db)
                        createExtractionJobs(db)
                        db.version = DATABASE_VERSION
                    }
                    1 -> {
                        snapshot(db)
                        createSourceArchive(db)
                        createExtractionJobs(db)
                        db.version = DATABASE_VERSION
                    }
                    DATABASE_VERSION -> {
                        snapshot(db) // Missing/malformed schema must fail closed.
                        db.rawQuery("SELECT event_key, conversation_key, call_key, source, captured_at_ms, expires_at_ms, text, text_bytes, fingerprint FROM source_events LIMIT 0", null).use { }
                        ensureExtractionJobs(db)
                    }
                    else -> error("Unsupported memory database version")
                }
            }
            // Only retire the old copy after a validated, durable commit. Retry cleanup after a crash
            // or failure; the marker above means this file will never be imported a second time.
            legacyFile?.let { legacy ->
                MemoryStore.deleteOwnedTemps(legacy)
                require(!legacy.exists() || legacy.delete()) { "Legacy memory cleanup failed" }
            }
            connection = db
            return db
        } catch (e: Exception) { db.close(); throw e }
    }

    private fun snapshot(db: SQLiteDatabase): MemorySnapshot {
        val generation = db.rawQuery("SELECT generation, legacy_imported FROM memory_meta WHERE singleton=1", null).use {
            require(it.moveToFirst() && it.getInt(1) == 1) { "Missing memory metadata" }
            it.getLong(0)
        }
        val memories = db.rawQuery("SELECT id, event_id, review_status, expires_at_ms, payload FROM memories ORDER BY rowid", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val record = MemorySnapshotCodec.decodeMemory(JSONObject(cursor.getString(4)))
                    require(record.id == cursor.getString(0) && record.source.eventId == cursor.getString(1) && record.reviewStatus.name == cursor.getString(2)) { "Memory columns disagree" }
                    require(record.expiresAtMs == if (cursor.isNull(3)) null else cursor.getLong(3)) { "Memory expiry disagrees" }
                    add(record)
                }
            }
        }
        val tombstones = db.rawQuery("SELECT event_id, fingerprint, erased_at_ms FROM tombstones ORDER BY rowid", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(MemoryTombstone(cursor.getString(0), cursor.getString(1), cursor.getLong(2))) }
        }
        return MemorySnapshot(generation, memories, tombstones).also {
            require(MemorySnapshotCodec.validate(it) == null) { "Invalid persisted memory" }
        }
    }

    private fun persist(db: SQLiteDatabase, before: MemorySnapshot, after: MemorySnapshot) {
        val previous = before.memories.associateBy { it.id }
        val current = after.memories.associateBy { it.id }
        previous.keys.filterNot { it in current }.forEach { db.delete("memories", "id=?", arrayOf(it)) }
        after.memories.filter { previous[it.id] != it }.forEach { record ->
            val values = ContentValues().apply {
                put("id", record.id); put("event_id", record.source.eventId); put("review_status", record.reviewStatus.name)
                put("expires_at_ms", record.expiresAtMs); put("payload", MemorySnapshotCodec.encodeMemory(record).toString())
            }
            if (record.id in previous) require(db.update("memories", values, "id=?", arrayOf(record.id)) == 1)
            else db.insertOrThrow("memories", null, values)
        }
        val previousTombstones = before.tombstones.associateBy { it.eventId }
        val currentTombstones = after.tombstones.associateBy { it.eventId }
        previousTombstones.keys.filterNot { it in currentTombstones }.forEach { db.delete("tombstones", "event_id=?", arrayOf(it)) }
        after.tombstones.filter { previousTombstones[it.eventId] != it }.forEach { tombstone ->
            val values = ContentValues().apply { put("event_id", tombstone.eventId); put("fingerprint", tombstone.payloadFingerprint); put("erased_at_ms", tombstone.erasedAtMs) }
            if (tombstone.eventId in previousTombstones) require(db.update("tombstones", values, "event_id=?", arrayOf(tombstone.eventId)) == 1)
            else db.insertOrThrow("tombstones", null, values)
        }
        db.execSQL("UPDATE memory_meta SET generation=? WHERE singleton=1", arrayOf(after.generation))
        // Durable invalidation only: lexical recall reads canonical rows; no fake vector index.
        // Initial migration creates this table after persist, so enqueue only when it exists.
        val hasIndexJobs=db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='memory_index_jobs'",null).use { it.moveToFirst() }
        if(hasIndexJobs) {
            db.execSQL("UPDATE memory_index_jobs SET state='SUPERSEDED' WHERE state='PENDING'")
            db.execSQL("INSERT OR IGNORE INTO memory_index_jobs(generation,state) VALUES(?,'PENDING')",arrayOf(after.generation))
            db.execSQL("DELETE FROM memory_index_jobs WHERE state='SUPERSEDED' AND generation<?",arrayOf(maxOf(0L,after.generation-2_000)))
        }
    }

    private fun <T> transaction(db: SQLiteDatabase, block: () -> T): T {
        db.beginTransaction()
        try { val value = block(); db.setTransactionSuccessful(); return value }
        finally { db.endTransaction() }
    }

    companion object {
        const val DATABASE_VERSION = 2
        private val pathLocks = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock>()
        private fun lockFor(file: File): java.util.concurrent.locks.ReentrantLock {
            val path = try { file.canonicalPath } catch (_: Exception) { file.absoluteFile.normalize().path }
            return pathLocks.computeIfAbsent(path) { java.util.concurrent.locks.ReentrantLock(true) }
        }
    }
}
