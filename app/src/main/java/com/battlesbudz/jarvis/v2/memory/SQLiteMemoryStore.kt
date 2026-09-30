package com.battlesbudz.jarvis.v2.memory

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.json.JSONObject

/**
 * Device-local canonical ledger. Records and non-content erase tombstones are separate rows;
 * mutations and generation changes share one SQLite transaction. Indexes are rebuildable later.
 * A committed migration marker prevents a surviving legacy file from resurrecting erased data.
 */
class SQLiteMemoryStore(private val file: File, private val legacyFile: File? = null) : MemoryPersistence, AutoCloseable {
    private var connection: SQLiteDatabase? = null

    @Synchronized override fun read(): MemoryStore.Read = try {
        val db = database()
        transaction(db) { MemoryStore.Read(snapshot(db)) }
    } catch (e: Exception) {
        MemoryStore.Read(null, "Memory database is unavailable (${e.javaClass.simpleName}).")
    }

    @Synchronized override fun <T> update(block: (MemorySnapshot) -> Pair<MemorySnapshot, T>): MemoryStore.Update<T> = try {
        val db = database()
        transaction(db) {
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
    }

    @Synchronized override fun close() { connection?.close(); connection = null }

    private fun database(): SQLiteDatabase {
        connection?.let { return it }
        require(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "Memory directory unavailable" }
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY)
        try {
            // Avoid a long-lived WAL containing old memory payloads; scrub deleted SQLite cells.
            db.disableWriteAheadLogging()
            db.execSQL("PRAGMA secure_delete=ON")
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
                        db.version = DATABASE_VERSION
                    }
                    DATABASE_VERSION -> snapshot(db) // Missing/malformed schema must fail closed.
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
    }

    private fun <T> transaction(db: SQLiteDatabase, block: () -> T): T {
        db.beginTransaction()
        try { val value = block(); db.setTransactionSuccessful(); return value }
        finally { db.endTransaction() }
    }

    companion object { const val DATABASE_VERSION = 1 }
}
