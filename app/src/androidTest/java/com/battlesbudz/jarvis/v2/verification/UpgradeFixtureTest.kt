package com.battlesbudz.jarvis.v2.verification

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Runs in the previous published APK. Deliberately uses only framework/JSON APIs:
 * writing with candidate storage adapters would not establish an old-data upgrade.
 * The fixture records the published schema-2 formats used by build 907 onward.
 */
@RunWith(AndroidJUnit4::class)
class UpgradeFixtureTest {
    @Test fun testSeedPreviousReleaseData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val now = System.currentTimeMillis()
        val message = JSONObject().put("id", "upgrade-user").put("role", "You")
            .put("text", "Upgrade fixture: keep my conversation").put("contextText", "Upgrade fixture: keep my conversation")
            .put("complete", true).put("spoken", false).put("sourceTimestampMs", now).put("actions", JSONArray())
        val reply = JSONObject().put("id", "upgrade-reply").put("role", "Jarvis")
            .put("text", "Upgrade fixture reply").put("contextText", "Upgrade fixture reply")
            .put("complete", true).put("spoken", false).put("sourceTimestampMs", now).put("actions", JSONArray())
        assertTrue(context.getSharedPreferences("conversations", Context.MODE_PRIVATE).edit()
            .putString("active", "upgrade-thread").putString("threads", JSONArray().put(JSONObject()
                .put("id", "upgrade-thread").put("messages", JSONArray().put(message).put(reply))).toString()).commit())
        assertTrue(context.getSharedPreferences("model_setup", Context.MODE_PRIVATE).edit()
            .putString("selected_model", "Gemma-4-E2B-it").commit())
        assertTrue(context.getSharedPreferences("voice_input", Context.MODE_PRIVATE).edit()
            .putString("input_mode", "gemma_audio").putBoolean("gemma_whisper_captions", false).commit())
        // Synthetic inert model bytes: persistence coverage, never purported model inference.
        val model = File(context.filesDir, "models/upgrade-fixture.litertlm")
        check(model.parentFile!!.isDirectory || model.parentFile!!.mkdirs())
        FileOutputStream(model).use { it.write(ByteArray(4097) { n -> (n % 251).toByte() }); it.fd.sync() }
        val modelHash = java.security.MessageDigest.getInstance("SHA-256").digest(model.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertTrue(context.getSharedPreferences("model_setup", Context.MODE_PRIVATE).edit()
            .putString("sha256_upgrade-fixture", modelHash).putLong("sha256_upgrade-fixture_length", model.length())
            .putLong("sha256_upgrade-fixture_modified", model.lastModified()).commit())
        val memoryFile = File(context.noBackupFilesDir, "memory-os.db")
        check(!memoryFile.exists()) { "Previous APK fixture must start with empty disposable data" }
        SQLiteDatabase.openOrCreateDatabase(memoryFile, null).use { db ->
            db.beginTransaction()
            try {
                db.execSQL("CREATE TABLE memory_meta (singleton INTEGER PRIMARY KEY CHECK(singleton=1), generation INTEGER NOT NULL CHECK(generation>=0), legacy_imported INTEGER NOT NULL CHECK(legacy_imported=1))")
                db.execSQL("CREATE TABLE memories (id TEXT PRIMARY KEY NOT NULL, event_id TEXT NOT NULL UNIQUE, review_status TEXT NOT NULL, expires_at_ms INTEGER, payload TEXT NOT NULL)")
                db.execSQL("CREATE INDEX memories_active ON memories(review_status, expires_at_ms)")
                db.execSQL("CREATE TABLE tombstones (event_id TEXT PRIMARY KEY NOT NULL, fingerprint TEXT NOT NULL, erased_at_ms INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE source_events (event_key TEXT PRIMARY KEY NOT NULL, conversation_key TEXT NOT NULL, call_key TEXT, source TEXT NOT NULL CHECK(source IN ('TEXT','VOICE')), captured_at_ms INTEGER NOT NULL CHECK(captured_at_ms>0), expires_at_ms INTEGER NOT NULL CHECK(expires_at_ms>captured_at_ms), text TEXT, text_bytes INTEGER NOT NULL CHECK(text_bytes>=0), fingerprint TEXT NOT NULL)")
                db.execSQL("CREATE INDEX source_events_expiry ON source_events(expires_at_ms)")
                val payload = JSONObject().put("id", "12345678-1234-4123-8123-123456789012")
                    .put("content", "Upgrade fixture prefers apricots").put("category", "PREFERENCE")
                    .put("tier", "LONG_TERM").put("type", "SEMANTIC").put("confidence", 90)
                    .put("reviewStatus", "APPROVED").put("createdAtMs", now).put("updatedAtMs", now).put("revision", 1)
                    .put("source", JSONObject().put("eventId", "a".repeat(32)).put("eventSource", "text")
                        .put("createdAtMs", now).put("sensitivity", "NORMAL").put("provenance", JSONArray()))
                db.execSQL("INSERT INTO memory_meta VALUES(1, 2, 1)")
                db.execSQL("INSERT INTO memories VALUES(?, ?, 'APPROVED', NULL, ?)", arrayOf("12345678-1234-4123-8123-123456789012", "a".repeat(32), payload.toString()))
                db.execSQL("INSERT INTO tombstones VALUES(?, ?, ?)", arrayOf<Any>("b".repeat(32), "c".repeat(64), now))
                val text = "Upgrade fixture source text"
                db.execSQL("INSERT INTO source_events VALUES(?, ?, NULL, 'TEXT', ?, ?, ?, ?, ?)",
                    arrayOf<Any>("d".repeat(32), "f".repeat(32), now, now + 86_400_000L, text, text.toByteArray().size, "e".repeat(64)))
                db.version = 2
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        val attempt = JSONObject().put("id", "22345678-1234-4123-8123-123456789012")
            .put("generation", 2).put("state", "SUCCEEDED").put("createdAtMs", now).put("updatedAtMs", now)
            .put("request", JSONObject().put("name", "read_battery").put("arguments", JSONObject()))
            .put("result", "Battery level is 73%.").put("resultOutcome", "SUCCEEDED")
        val journal = JSONObject().put("schemaVersion", 2).put("attempts", JSONArray().put(attempt))
            .put("groups", JSONArray()).put("approvals", JSONArray()).put("grants", JSONArray()).put("events", JSONArray())
        val journalFile = File(context.noBackupFilesDir, "phone-action-attempts.json")
        FileOutputStream(journalFile).use { it.write(journal.toString().toByteArray()); it.fd.sync() }
        assertTrue(context.getSharedPreferences("release_upgrade_fixture", Context.MODE_PRIVATE).edit()
            .putLong("seeded_at", now).putInt("installed_uid", android.os.Process.myUid()).commit())
        assertEquals(4097L, model.length())
        ReleasePhaseEvidence.capture("testSeedPreviousReleaseData")
    }
}
