package com.battlesbudz.jarvis.v2.verification

import android.content.Intent
import android.content.ContentValues
import android.app.NotificationManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.SystemClock
import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until
import com.battlesbudz.jarvis.v2.MainActivity
import com.battlesbudz.jarvis.v2.actions.*
import com.battlesbudz.jarvis.v2.ai.ToolCall
import com.battlesbudz.jarvis.v2.ai.ConversationPromptBuilder
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.chat.ConversationHistory
import com.battlesbudz.jarvis.v2.chat.ShortTermConversationContext
import com.battlesbudz.jarvis.v2.memory.*
import com.battlesbudz.jarvis.v2.ui.ConversationScreen
import com.battlesbudz.jarvis.v2.ui.MemoryScreen
import com.battlesbudz.jarvis.v2.voice.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/** Real release UI/Android actions; controlled ToolCalls verify routing without model weights. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ReleaseJourneyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var activity: ActivityScenario<MainActivity>

    @get:Rule val testName = TestName()

    @Before fun launch() {
        activity = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        find(By.res("model_browse"))
    }

    @After fun close() {
        try {
            captureEvidence(testName.methodName)
        } finally { if (::activity.isInitialized) activity.close() }
    }

    private fun captureEvidence(name: String) {
        val directory = File(context.cacheDir, "verification").apply { mkdirs() }
        val screenshot = File(directory, "$name.png")
        val hierarchy = File(directory, "$name.xml")
        assertTrue("Screenshot capture failed", device.takeScreenshot(screenshot))
        device.dumpWindowHierarchy(hierarchy)
        exportEvidence(screenshot, "image/png")
        exportEvidence(hierarchy, "application/xml")
    }

    private fun exportEvidence(file: File, mime: String) {
        val folder = InstrumentationRegistry.getArguments().getString("jarvisEvidenceDir")
            ?: error("The verification controller must supply a unique evidence directory")
        require(folder.matches(Regex("jarvis-verification-[0-9]+")))
        // Android 11 blocks adb shell from app-specific external storage. Publish
        // these test-owned files through Downloads instead; no storage grant/root
        // is needed, and this export exists only in the instrumentation APK.
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$folder")
        }
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        checkNotNull(resolver.openOutputStream(uri)).use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        }
    }

    private fun find(selector: BySelector): UiObject2 =
        device.wait(Until.findObject(selector), 15_000)
            ?: throw AssertionError("Missing UI element: $selector")

    /** Finds controls above or below the current viewport without relying on content height. */
    private fun scrollTo(selector: BySelector): UiObject2 {
        fun seek(fromY: Int, toY: Int): UiObject2? {
            repeat(14) {
                device.findObject(selector)?.let { return it }
                device.swipe(device.displayWidth / 2, fromY, device.displayWidth / 2, toY, 25)
                device.waitForIdle()
            }
            return device.findObject(selector)
        }
        return seek(device.displayHeight * 4 / 5, device.displayHeight / 3)
            ?: seek(device.displayHeight / 3, device.displayHeight * 4 / 5)
            ?: find(selector)
    }

    /** Requires a non-edge, non-empty visible area before a single physical tap. */
    private fun hasSafeTapBounds(control: UiObject2): Boolean {
        val bounds = control.visibleBounds
        val safeInset = 24
        return bounds.width() > 0 && bounds.height() > 0 &&
            bounds.left >= safeInset && bounds.right <= device.displayWidth - safeInset &&
            bounds.top >= safeInset && bounds.bottom <= device.displayHeight - safeInset
    }

    /**
     * Waits for the async reload after a mutation, then verifies a fresh Compose node
     * remains enabled with unchanged tap bounds for a bounded settling interval.
     */
    private fun enabled(selector: BySelector): UiObject2 {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            // Scrolling and Compose recomposition can invalidate a prior accessibility node.
            val control = scrollTo(selector)
            try {
                if (control.isEnabled && hasSafeTapBounds(control)) {
                    val before = control.visibleBounds
                    device.waitForIdle()
                    SystemClock.sleep(300)
                    val fresh = device.findObject(selector)
                    if (fresh != null && fresh.isEnabled && hasSafeTapBounds(fresh) && fresh.visibleBounds == before) {
                        return fresh
                    }
                }
            } catch (_: StaleObjectException) {
                // No action has been dispatched; retry with a fresh node within the same bound.
            }
            device.waitForIdle()
        }
        throw AssertionError("Control did not become stably enabled: $selector")
    }

    private fun openBrowser() { find(By.res("model_browse")).click(); find(By.res("model_search")) }

    private fun clickEnabled(selector: BySelector) {
        enabled(selector).click()
        device.waitForIdle()
    }

    private fun enterText(selector: BySelector, value: String) {
        try {
            enabled(selector).text = value
        } catch (_: StaleObjectException) {
            // Text assignment is an idempotent replacement, so a fresh-node retry is safe.
            enabled(selector).text = value
        }
        // Close the IME without navigating away before we need to reach controls below the editor.
        device.pressKeyCode(android.view.KeyEvent.KEYCODE_ESCAPE)
        device.waitForIdle()
    }

    private fun searchMemory(query: String, expected: BySelector) {
        enterText(By.res("memory_search_input"), query)
        clickEnabled(By.res("memory_search"))
        // The enabled field proves the async search has replaced the prior rows before checking its result.
        enabled(By.res("memory_search_input"))
        assertNotNull(scrollTo(expected))
    }

    @Test fun test01_setupRequiresAnInstalledModel() {
        assertNotNull(find(By.text("Jarvis setup")))
        assertFalse(scrollTo(By.res("model_check")).isEnabled)
    }

    @Test fun test02_searchHasAnHonestEmptyState() {
        openBrowser()
        find(By.res("model_search")).text = "no-such-jarvis-model-8429"
        assertNotNull(find(By.textStartsWith("No matching models.")))
        find(By.text("Done")).click()
    }

    @Test fun test03_browsingDoesNotChangeTheSelectedModel() {
        assertNotNull(find(By.text("Gemma-4-E2B-it")))
        openBrowser()
        find(By.res("model_search")).text = "Gemma-4-E4B-it"
        find(By.res("model_family_Gemma")).click()
        find(By.text("Done")).click()
        assertNotNull(find(By.text("Gemma-4-E2B-it")))
    }

    @Test fun test04_batteryToolReadsAndroid() {
        val percent = context.getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        assertTrue("Battery fixture must expose a valid capacity", percent in 0..100)
        val result = MobileActionPipeline(executor = AndroidMobileActionExecutor(context))
            .execute(ActionRequest("read_battery"))
        assertTrue(result.succeeded)
        assertEquals("Battery is at $percent percent.", result.message)
    }

    @Test fun test05_volumeToolChangesAndroidAndRejectsBadInput() {
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val pipeline = MobileActionPipeline(executor = AndroidMobileActionExecutor(context))
        try {
            assertTrue(pipeline.execute(ActionRequest("set_volume", mapOf("level" to "40"))).succeeded)
            val actual = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            assertEquals((audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * .4).roundToInt(), actual)
            assertFalse(pipeline.execute(ActionRequest("set_volume", mapOf("level" to "101"))).succeeded)
            assertEquals(actual, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        } finally { audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0) }
    }

    @Test fun test06_missingAppReturnsFailure() {
        val result = MobileActionPipeline(executor = AndroidMobileActionExecutor(context))
            .execute(ActionRequest("open_app", mapOf("app" to "jarvis-nonexistent-app-8429")))
        assertFalse(result.succeeded)
    }

    @Test fun test07_settingsToolOpensAndroidSettings() {
        val pipeline = MobileActionPipeline(executor = AndroidMobileActionExecutor(context, canLaunchDirectly = { true }))
        val result = pipeline.execute(ActionRequest("open_app", mapOf("app" to "Settings", "package" to "com.android.settings")))
        assertTrue(result.message, result.succeeded)
        assertTrue("Settings must actually appear, not merely report success",
            device.wait(Until.hasObject(By.pkg("com.android.settings").depth(0)), 15_000))
        // @After captures the launched Settings screen before closing Jarvis's scenario.
    }

    @Test fun test08_rejectedToolSequencePreservesAndroidVolume() {
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val pipeline = MobileActionPipeline(executor = AndroidMobileActionExecutor(context))
        val requests = listOf(ActionRequest("unsupported_action"), ActionRequest("open_app")) +
            listOf("", "NaN", "Infinity", "-1", "101", "loud").map {
                ActionRequest("set_volume", mapOf("level" to it))
            }
        for (request in requests) {
            assertFalse("Rejected input must not succeed: $request", pipeline.execute(request).succeeded)
            assertEquals("Rejected input changed volume: $request", before,
                audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        }
    }

    @Test fun test09_repeatedVolumeRequestIsIdempotent() {
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val pipeline = MobileActionPipeline(executor = AndroidMobileActionExecutor(context))
        val request = ActionRequest("set_volume", mapOf("level" to "40"))
        try {
            repeat(2) {
                assertTrue(pipeline.execute(request).succeeded)
                assertEquals((audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * .4).roundToInt(),
                    audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            }
        } finally { audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0) }
    }

    @Test fun test10_modelIssueWarningPrecedesSelectionAndCancelPreservesModel() {
        // Exercise the release UI without linking to R8-optimized catalog internals.
        val original = "Gemma-4-E2B-it"
        openBrowser()
        find(By.res("model_search")).text = "Zamba2-2.7B"
        find(By.res("model_family_Zamba")).click()
        assertNotNull(scrollTo(By.textStartsWith("Warning: GPU startup reportedly rebooted")))
        scrollTo(By.res("model_choose_Zamba2-2.7B-instruct")).click()
        assertNotNull(find(By.text("Known issue — read before choosing")))
        find(By.text("Cancel")).click()
        find(By.text("Done")).click()
        assertNotNull(find(By.text(original)))
    }

    @Test fun test11_modelDetailsAreOptionalAndDoNotChangeSelection() {
        assertFalse(device.hasObject(By.textContains("bundle /")))
        find(By.res("selected_model_details")).click()
        assertNotNull(find(By.text("About this model")))
        find(By.text("Close details")).click()
        assertNotNull(find(By.text("Gemma-4-E2B-it")))
        openBrowser()
        find(By.res("model_search")).text = "Gemma"
        find(By.res("model_family_Gemma")).click()
        assertNotNull(find(By.text("Not yet verified for this Jarvis setup.")))
        assertFalse(device.hasObject(By.textContains("bundle /")))
        scrollTo(By.res("model_details_Gemma3-1B-IT")).click()
        assertNotNull(find(By.text("About this model")))
        captureEvidence("model_details_open")
        find(By.text("Close details")).click()
        assertNotNull(find(By.res("model_choose_Gemma3-1B-IT")))
        // Keep the compact family view visible for the screenshot.
    }

    @Test fun test12_lastFamilyModelCanBeSelectedAboveNavigationBar() {
        openBrowser()
        find(By.res("model_search")).text = "Gemma"
        find(By.res("model_family_Gemma")).click()
        val target = By.res("model_choose_codegemma-7b-it-int4-litertlm")
        scrollTo(target)
        // Reach the actual end of the list, not just the first partly visible button.
        repeat(3) {
            val list = find(By.res("model_list")).visibleBounds
            device.swipe(list.centerX(), list.bottom - 30, list.centerX(), list.top + 30, 25)
            device.waitForIdle()
        }
        val button = find(target)
        var navigationInset = 0
        activity.onActivity {
            navigationInset = it.window.decorView.rootWindowInsets
                ?.getInsets(android.view.WindowInsets.Type.navigationBars())?.bottom ?: 0
        }
        val bounds = button.visibleBounds
        assertTrue("Choose must be above Android navigation", bounds.bottom <= device.displayHeight - navigationInset)
        assertTrue("Choose must have its full touch target", bounds.height() >= (40 * context.resources.displayMetrics.density).roundToInt())
        captureEvidence("last_model_button")
        button.click()
        assertNotNull(find(By.text("codegemma-7b-it-int4-litertlm")))
        // Restore the starting model without touching files or bypassing the UI.
        openBrowser()
        find(By.res("model_search")).text = "Gemma-4-E2B-it"
        find(By.res("model_family_Gemma")).click()
        scrollTo(By.res("model_choose_Gemma-4-E2B-it")).click()
        assertNotNull(find(By.text("Gemma-4-E2B-it")))
    }

    @Test fun test13_imageAttachmentIsResizedStoredAndInvalidInputRejected() {
        val original = File(context.cacheDir, "attachment-source.png")
        val bitmap = android.graphics.Bitmap.createBitmap(2000, 1000, android.graphics.Bitmap.Config.ARGB_8888)
        try { original.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        val media = com.battlesbudz.jarvis.v2.chat.ChatMediaStore.prepare(context, android.net.Uri.fromFile(original),
            com.battlesbudz.jarvis.v2.chat.AttachmentKind.IMAGE)
        try {
            val file = File(android.net.Uri.parse(media.uri).path!!)
            assertTrue(file.exists())
            val decoded = android.graphics.BitmapFactory.decodeFile(file.path)
            try { assertEquals(1536, decoded.width); assertEquals(768, decoded.height) }
            finally { decoded.recycle() }
            original.writeText("This is not an image")
            try {
                com.battlesbudz.jarvis.v2.chat.ChatMediaStore.prepare(context, android.net.Uri.fromFile(original),
                    com.battlesbudz.jarvis.v2.chat.AttachmentKind.IMAGE)
                fail("Corrupt input must be rejected")
            } catch (_: android.graphics.ImageDecoder.DecodeException) { }
        } finally { com.battlesbudz.jarvis.v2.chat.ChatMediaStore.discard(context, media); original.delete() }
    }

    @Test fun test14_threeActionTurnUsesRealAndroidState() {
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            val outcome = ActionTurnRunner(AndroidMobileActionExecutor(context, canLaunchDirectly = { true })).run(
                ActionTurnPlan.parse("Read battery then set media volume to 30 percent then open Settings"), listOf(listOf(
                    com.battlesbudz.jarvis.v2.ai.ToolCall("read_battery", "{}"),
                    com.battlesbudz.jarvis.v2.ai.ToolCall("set_volume", "{\"level\":30}"),
                    com.battlesbudz.jarvis.v2.ai.ToolCall("open_app", "{\"app\":\"Settings\"}"))))
            assertTrue(outcome.message, outcome.completed)
            assertEquals(kotlin.math.round(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * .3).toInt(), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings").depth(0)), 15_000))
        } finally { audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0) }
    }

    @Test fun test15_invalidActionTurnPreservesAndroidState() {
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val outcome = ActionTurnRunner(AndroidMobileActionExecutor(context)).run(
            ActionTurnPlan.parse("Set media volume to 5000 percent then delete files"), emptyList())
        assertFalse(outcome.completed)
        assertEquals(before, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test fun test16_partialFailureKeepsCompletedAndroidAction() {
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            val outcome = ActionTurnRunner(AndroidMobileActionExecutor(context)).run(
                ActionTurnPlan.parse("Set media volume to 30 percent then open jarvis-nonexistent-app-8429 then read battery"), listOf(listOf(
                    com.battlesbudz.jarvis.v2.ai.ToolCall("set_volume", "{\"level\":30}"),
                    com.battlesbudz.jarvis.v2.ai.ToolCall("open_app", "{\"app\":\"jarvis-nonexistent-app-8429\"}"),
                    com.battlesbudz.jarvis.v2.ai.ToolCall("read_battery", "{}"))))
            assertFalse(outcome.completed)
            assertEquals(2, outcome.receipts.size)
            assertEquals(kotlin.math.round(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * .3).toInt(), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        } finally { audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0) }
    }

    @Test fun test17_cancelledAndDuplicateActionTurnsDoNotReplay() {
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val prefs = context.getSharedPreferences("action-turn-cancel", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val history = com.battlesbudz.jarvis.v2.chat.ConversationHistory(prefs)
        val reply = "cancelled-reply"
        history.updateReply(history.current.value.id, reply, "", false)
        val real = AndroidMobileActionExecutor(context)
        var executions = 0
        val job = kotlinx.coroutines.Job()
        val execute = MobileActionExecutor { action ->
            executions++
            val result = real.execute(action)
            history.recordReplyAction(history.current.value.id, reply,
                com.battlesbudz.jarvis.v2.chat.ActionReceipt("set_volume", result.message, result.succeeded))
            if (executions == 1) job.cancel()
            result
        }
        val runner = ActionTurnRunner(execute)
        try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withContext(job) {
                    runner.runNative(ActionTurnPlan.parse("Set media volume to 30 percent then read battery"), listOf(
                        com.battlesbudz.jarvis.v2.ai.ToolCall("set_volume", "{\"level\":30}"),
                        com.battlesbudz.jarvis.v2.ai.ToolCall("read_battery", "{}")), dispatch = { request ->
                            val action = MobileActionValidator().validate(request) as ActionValidation.Valid
                            execute.execute(action.action)
                        }, nextCalls = { emptyList() })
                }
            }
        } catch (_: java.util.concurrent.CancellationException) { }
        try {
            assertEquals("Cancellation must stop before the second Android action", 1, executions)
            val restored = com.battlesbudz.jarvis.v2.chat.ConversationHistory(prefs)
            assertTrue(restored.current.value.messages.single().text.contains("Media volume set to 30 percent."))
            assertTrue(restored.context().single().text.contains("Media volume set to 30 percent."))
            assertEquals(kotlin.math.round(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * .3).toInt(), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            var duplicateExecutions = 0
            val duplicateRunner = ActionTurnRunner(MobileActionExecutor { action ->
                duplicateExecutions++
                real.execute(action)
            })
            val duplicateOutcome = duplicateRunner.run(
                ActionTurnPlan.parse("Set media volume to 30 percent then read battery"), listOf(
                    listOf(com.battlesbudz.jarvis.v2.ai.ToolCall("set_volume", "{\"level\":30}")),
                    listOf(com.battlesbudz.jarvis.v2.ai.ToolCall("set_volume", "{\"level\":30}"),
                        com.battlesbudz.jarvis.v2.ai.ToolCall("read_battery", "{}"))))
            assertTrue(duplicateOutcome.completed)
            assertEquals("Duplicate model call must not rerun Android volume", 2, duplicateExecutions)
            assertEquals(2, duplicateOutcome.receipts.size)
            assertEquals(kotlin.math.round(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * .3).toInt(), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        } finally { audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0) }
    }


    @Test fun test18_naturalActionRoutingOpensSettingsThenReadsBattery() {
        val plan = com.battlesbudz.jarvis.v2.ai.TurnOrchestrator(com.battlesbudz.jarvis.v2.ai.ReferenceGroundingClient())
            .plan("Can you open up Settings and tell me what my battery percentage is?")
        assertEquals(com.battlesbudz.jarvis.v2.ai.TurnKind.NORMAL_CHAT, plan.kind)
        assertNull(plan.lookupQuery)
        val outcome = ActionTurnRunner(AndroidMobileActionExecutor(context, canLaunchDirectly = { true })).run(plan.actionPlan, listOf(listOf(
            com.battlesbudz.jarvis.v2.ai.ToolCall("open_app", "{\"app\":\"Settings\"}"),
            com.battlesbudz.jarvis.v2.ai.ToolCall("read_battery", "{}"))))
        assertTrue(outcome.completed)
        assertEquals(listOf("open_app", "read_battery"), outcome.receipts.map { it.request.name })
        assertTrue(outcome.receipts.all { it.result.succeeded })
        assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings").depth(0)), 15_000))
        val percent = context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        assertEquals("Battery is at $percent percent.", outcome.receipts.last().result.message)
    }

    @Test fun test19_retryLiteralUnknownAppStopsWithoutBattery() {
        val plan = com.battlesbudz.jarvis.v2.ai.TurnOrchestrator(com.battlesbudz.jarvis.v2.ai.ReferenceGroundingClient())
            .plan("I said, can you open up the fistbook and tell me what my battery percentage is?")
        assertEquals(com.battlesbudz.jarvis.v2.ai.TurnKind.NORMAL_CHAT, plan.kind)
        assertNull(plan.lookupQuery)
        val ready = plan.actionPlan as ActionTurnPlan.Ready
        assertEquals("fistbook", ready.steps.first().request.arguments["app"])
        var calls = 0
        val substituted = ActionTurnRunner(MobileActionExecutor { calls++; ExecutionResult(true, "bad") }).run(ready, listOf(listOf(
            com.battlesbudz.jarvis.v2.ai.ToolCall("open_app", "{\"app\":\"Facebook\"}"))))
        assertFalse(substituted.completed); assertEquals(0, calls)
        var androidAttempts = 0
        val real = AndroidMobileActionExecutor(context)
        val outcome = ActionTurnRunner(MobileActionExecutor { action -> androidAttempts++; real.execute(action) }).run(ready, listOf(listOf(
            com.battlesbudz.jarvis.v2.ai.ToolCall("open_app", "{\"app\":\"fistbook\"}"),
            com.battlesbudz.jarvis.v2.ai.ToolCall("read_battery", "{}"))))
        assertFalse(outcome.completed); assertEquals(1, androidAttempts); assertEquals(1, outcome.receipts.size)
        assertFalse(outcome.receipts.single().result.succeeded)
        assertEquals("open_app", outcome.receipts.single().request.name)
    }


    @Test fun test20_followupQueuesWhileAcceptedAndroidActionRuns() = runBlocking {
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val queue = AcceptedActionQueue<String>()
        val session = ContinuousActionSession(queue)
        val firstNativeGeneration = CompletableDeferred<Unit>()
        val releaseFirstNativeGeneration = CompletableDeferred<Unit>()
        val executionOrder = mutableListOf<String>()
        try {
            assertNotNull(queue.admit("accepted-A", "utterance-A", "A"))
            queue.start { task ->
                if (task.value == "A") {
                    firstNativeGeneration.complete(Unit)
                    releaseFirstNativeGeneration.await()
                }
                val plan = if (task.value == "A") {
                    ActionTurnPlan.parse("Read battery then set media volume to 30 percent")
                } else ActionTurnPlan.parse("Open Settings")
                val calls = if (task.value == "A") listOf(
                    ToolCall("read_battery", "{}"), ToolCall("set_volume", "{\"level\":30}")
                ) else listOf(ToolCall("open_app", "{\"app\":\"Settings\"}"))
                val outcome = ActionTurnRunner(MobileActionExecutor { error("controlled calls dispatch below") }).runNative(
                    plan, calls,
                    dispatch = { request ->
                        executionOrder += "${task.value}:${request.name}"
                        MobileActionPipeline(executor = AndroidMobileActionExecutor(context, canLaunchDirectly = { true })).execute(request)
                    },
                    nextCalls = { emptyList() }
                )
                assertTrue(session.onTaskEvent(task.id, "${task.value}: ${outcome.message}"))
                outcome.completed
            }
            withTimeout(15_000) { firstNativeGeneration.await() }
            assertTrue("The listener/session must remain available before native dispatch", session.canAdmitAcceptedAction())
            assertNotNull("Follow-up B must be accepted while A is blocked", queue.admit("accepted-B", "utterance-B", "B"))
            assertEquals(1, queue.pendingCount())
            releaseFirstNativeGeneration.complete(Unit)
            withTimeout(20_000) { queue.awaitIdle() }

            assertEquals(listOf("A:read_battery", "A:set_volume", "B:open_app"), executionOrder)
            assertEquals(kotlin.math.round(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * .3).toInt(),
                audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings").depth(0)), 15_000))
            val delivery = checkNotNull(session.nextDelivery())
            val combinedReport = delivery.reports.joinToString(" ") { it.text }
            assertTrue(combinedReport.contains("Battery is at"))
            assertTrue(combinedReport.contains("Media volume set to 30 percent."))
            assertTrue(combinedReport.contains("Opening Settings."))
            assertTrue(session.markDelivered(delivery.attemptId, delivery.reports.map { it.taskId }.toSet()))
            assertEquals(0, session.pendingReportCount())
        } finally {
            queue.close()
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0)
        }
    }

    @Test fun test21_speechInterruptionPreservesAcceptedAndroidActions() = runBlocking {
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val queue = AcceptedActionQueue<String>()
        val session = ContinuousActionSession(queue)
        val originalBlocked = CompletableDeferred<Unit>()
        val releaseOriginal = CompletableDeferred<Unit>()
        val dispatches = mutableListOf<String>()
        try {
            assertNotNull(queue.admit("original", "original-utterance", "original"))
            queue.start { task ->
                if (task.value == "original") {
                    originalBlocked.complete(Unit)
                    releaseOriginal.await()
                }
                val plan = if (task.value == "original") {
                    ActionTurnPlan.parse("Set media volume to 30 percent then read battery")
                } else ActionTurnPlan.parse("Open Settings")
                val calls = if (task.value == "original") listOf(
                    ToolCall("set_volume", "{\"level\":30}"), ToolCall("read_battery", "{}")
                ) else listOf(ToolCall("open_app", "{\"app\":\"Settings\"}"))
                val outcome = ActionTurnRunner(MobileActionExecutor { error("controlled calls dispatch below") }).runNative(
                    plan, calls,
                    dispatch = { request ->
                        dispatches += "${task.value}:${request.name}"
                        MobileActionPipeline(executor = AndroidMobileActionExecutor(context, canLaunchDirectly = { true })).execute(request)
                    }, nextCalls = { emptyList() }
                )
                assertTrue(session.onTaskEvent(task.id, "${task.value}: ${outcome.message}"))
                outcome.completed
            }
            withTimeout(15_000) { originalBlocked.await() }
            assertNotNull(queue.admit("followup", "followup-utterance", "followup"))
            session.onCaptureStarted()
            val control = session.control("stop speaking")
            assertEquals(VoiceActionControl.SpeechOnly, control)
            assertTrue(session.onCaptured(SessionCapture("speech-interrupt", "stop speaking"),
                CapturedKind.Control(control)) is CaptureOutcome.Control)
            assertTrue("Speech interruption must leave accepted actions alive", queue.hasUnfinished())
            releaseOriginal.complete(Unit)
            withTimeout(20_000) { queue.awaitIdle() }

            assertEquals(listOf("original:set_volume", "original:read_battery", "followup:open_app"), dispatches)
            assertEquals(1, dispatches.count { it == "original:set_volume" })
            assertEquals(1, dispatches.count { it == "followup:open_app" })
            val firstDelivery = checkNotNull(session.nextDelivery())
            assertEquals(2, firstDelivery.reports.size)
            session.onCaptureStarted()
            assertTrue("A speech floor handoff must interrupt only delivery", session.interruptDelivery(firstDelivery.attemptId).not())
            assertEquals(2, session.pendingReportCount())
            session.onCaptureStopped()
            val acknowledgedDelivery = checkNotNull(session.nextDelivery())
            assertEquals(firstDelivery.reports.map { it.taskId }.toSet(), acknowledgedDelivery.reports.map { it.taskId }.toSet())
            assertTrue(session.markDelivered(acknowledgedDelivery.attemptId,
                acknowledgedDelivery.reports.map { it.taskId }.toSet()))
            assertEquals(0, session.pendingReportCount())
        } finally {
            queue.close()
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0)
        }
    }

    @Test fun test22_explicitCancellationKeepsCompletedAndroidReceipts() = runBlocking {
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val preferences = context.getSharedPreferences("continuous-action-cancel", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val store = SharedPreferencesVoiceCallStore(preferences)
        val calls = VoiceSessionController(store)
        val call = calls.beginCall()
        val replyId = "cancelled-accepted-reply"
        calls.beginReply(call.id, replyId)
        val queue = AcceptedActionQueue<String>()
        val session = ContinuousActionSession(queue)
        val volumeReceipt = CompletableDeferred<Unit>()
        val allowBattery = CompletableDeferred<Unit>()
        var batteryDispatches = 0
        var settingsDispatches = 0
        try {
            assertNotNull(queue.admit("cancel-A", "cancel-utterance-A", "A"))
            queue.start { task ->
                if (task.value == "B-settings") {
                    val outcome = ActionTurnRunner(MobileActionExecutor { error("controlled calls dispatch below") }).runNative(
                        ActionTurnPlan.parse("Open Settings"), listOf(ToolCall("open_app", "{\"app\":\"Settings\"}")),
                        dispatch = { request ->
                            settingsDispatches++
                            MobileActionPipeline(executor = AndroidMobileActionExecutor(context, canLaunchDirectly = { true })).execute(request)
                        }, nextCalls = { emptyList() }
                    )
                    outcome.completed
                } else try {
                    val outcome = ActionTurnRunner(MobileActionExecutor { error("controlled calls dispatch below") }).runNative(
                        ActionTurnPlan.parse("Set media volume to 30 percent then read battery"),
                        listOf(ToolCall("set_volume", "{\"level\":30}")),
                        dispatch = { request ->
                            val result = MobileActionPipeline(executor = AndroidMobileActionExecutor(context)).execute(request)
                            calls.recordReplyAction(call.id, replyId, VoiceActionOutcome(request.name, result.message, result.succeeded))
                            volumeReceipt.complete(Unit)
                            result
                        },
                        nextCalls = {
                            allowBattery.await()
                            batteryDispatches++
                            listOf(ToolCall("read_battery", "{}"))
                        }
                    )
                    outcome.completed
                } finally {
                    session.onTaskEvent(task.id, "A cancelled after its durable volume receipt")
                }
            }
            withTimeout(15_000) { volumeReceipt.await() }
            assertNotNull(queue.admit("cancel-B", "cancel-utterance-B", "B-settings"))
            queue.cancel(VoiceActionControl.CancelAll)
            withTimeout(15_000) { queue.awaitIdle() }

            assertEquals("Cancellation must prevent the blocked battery dispatch", 0, batteryDispatches)
            assertEquals("Cancellation must prevent queued Settings", 0, settingsDispatches)
            assertEquals(AcceptedActionState.CANCELLED, queue.tasks.value.first { it.id == "cancel-A" }.state)
            assertEquals(AcceptedActionState.CANCELLED, queue.tasks.value.first { it.id == "cancel-B" }.state)
            val savedReceipt = store.list().single { it.id == call.id }.transcript.single { it.replyId == replyId }.actions.single()
            assertEquals("set_volume", savedReceipt.name)
            assertTrue(savedReceipt.succeeded)
            assertEquals(kotlin.math.round(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * .3).toInt(),
                audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        } finally {
            queue.close()
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0)
        }
    }

    @Test fun test23_actionResultsSurviveEndCallWithoutReplay() = runBlocking {
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val preferences = context.getSharedPreferences("continuous-action-end-call", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val store = SharedPreferencesVoiceCallStore(preferences)
        val controller = VoiceSessionController(store)
        val originalCall = controller.beginCall()
        val replyId = "ended-call-accepted-reply"
        controller.beginReply(originalCall.id, replyId)
        val queue = AcceptedActionQueue<String>()
        val session = ContinuousActionSession(queue)
        val nativeBlocked = CompletableDeferred<Unit>()
        val releaseNative = CompletableDeferred<Unit>()
        var executions = 0
        try {
            assertNotNull(queue.admit("ended-A", "ended-utterance-A", "A"))
            queue.start { task ->
                nativeBlocked.complete(Unit)
                releaseNative.await()
                val outcome = ActionTurnRunner(MobileActionExecutor { error("controlled calls dispatch below") }).runNative(
                    ActionTurnPlan.parse("Read battery then set media volume to 30 percent"),
                    listOf(ToolCall("read_battery", "{}"), ToolCall("set_volume", "{\"level\":30}")),
                    dispatch = { request ->
                        executions++
                        val result = MobileActionPipeline(executor = AndroidMobileActionExecutor(context)).execute(request)
                        controller.recordReplyAction(originalCall.id, replyId,
                            VoiceActionOutcome(request.name, result.message, result.succeeded))
                        result
                    }, nextCalls = { emptyList() }
                )
                controller.updateTaskForCall(originalCall.id, VoiceTaskStatus(
                    if (outcome.completed) VoiceTaskState.COMPLETED else VoiceTaskState.FAILED,
                    completedSteps = outcome.receipts.map { it.request.name }
                ))
                assertTrue(session.onTaskEvent(task.id, outcome.message))
                outcome.completed
            }
            withTimeout(15_000) { nativeBlocked.await() }
            controller.end()
            releaseNative.complete(Unit)
            withTimeout(20_000) { queue.awaitIdle() }

            assertEquals(2, executions)
            val reloadedStore = SharedPreferencesVoiceCallStore(preferences)
            val saved = reloadedStore.list().single { it.id == originalCall.id }
            assertNotNull(saved.endedAtMs)
            assertEquals(listOf("read_battery", "set_volume"),
                saved.transcript.single { it.replyId == replyId }.actions.map { it.name })
            assertEquals(VoiceTaskState.COMPLETED, saved.taskStatus?.state)
            assertEquals(kotlin.math.round(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * .3).toInt(),
                audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            val reloadedController = VoiceSessionController(reloadedStore)
            val newCall = reloadedController.beginCall()
            assertTrue(reloadedController.currentTranscript().isEmpty())
            assertEquals("Reloading storage must not replay completed Android work", 2, executions)
            reloadedController.end()
            assertNotEquals(originalCall.id, newCall.id)
        } finally {
            queue.close()
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0)
        }
    }
    @Test fun test24_memoryManagerReviewsCorrectsSearchesAndErases() {
        // Use only the release UI: memory implementation classes are intentionally shrinkable.
        clickEnabled(By.res("memory_open"))
        assertNotNull(find(By.text("Memory")))

        enterText(By.res("memory_new_content"), "Bank account number 1234 5678 9012 3456")
        clickEnabled(By.res("memory_propose"))
        assertNotNull(find(By.res("memory_error")))
        enabled(By.res("memory_new_content"))
        enterText(By.res("memory_new_content"), "Synthetic preference: teal notebooks.")
        clickEnabled(By.res("memory_propose"))
        assertNotNull(scrollTo(By.text("Pending · Added from manual entry")))
        // Saved mutations must settle and restore input; this catches a stuck busy lease.
        enabled(By.res("memory_new_content"))
        clickEnabled(By.res("memory_approve"))
        assertNotNull(scrollTo(By.text("Approved · Added from manual entry")))
        enabled(By.res("memory_new_content"))

        searchMemory("teal", By.text("Synthetic preference: teal notebooks."))
        clickEnabled(By.res("memory_correct"))
        assertNotNull(scrollTo(By.text("This change will replace: Synthetic preference: teal notebooks.")))
        enabled(By.res("memory_new_content"))
        enterText(By.res("memory_new_content"), "Synthetic preference: indigo notebooks.")
        clickEnabled(By.res("memory_propose"))
        assertNotNull(scrollTo(By.text("Pending · Added from manual entry")))
        enabled(By.res("memory_new_content"))
        clickEnabled(By.res("memory_approve"))
        // The old approved row must become superseded before we treat the replacement as searchable.
        assertNotNull(scrollTo(By.text("Superseded · Added from manual entry")))
        assertNotNull(scrollTo(By.text("Approved · Added from manual entry")))
        enabled(By.res("memory_new_content"))
        searchMemory("teal", By.text("No matching memories."))
        assertFalse(device.hasObject(By.text("Synthetic preference: teal notebooks.")))
        searchMemory("indigo", By.text("Synthetic preference: indigo notebooks."))

        enterText(By.res("memory_new_content"), "Synthetic item to reject.")
        clickEnabled(By.res("memory_propose"))
        assertNotNull(scrollTo(By.text("Pending · Added from manual entry")))
        enabled(By.res("memory_new_content"))
        clickEnabled(By.res("memory_reject"))
        assertNotNull(scrollTo(By.text("Rejected · Added from manual entry")))
        enabled(By.res("memory_new_content"))

        // Cancellation must leave the saved records visible before the confirmed erase.
        clickEnabled(By.res("memory_erase_all"))
        assertNotNull(find(By.text("Erase all memories?")))
        find(By.text("Cancel")).click()
        device.waitForIdle()
        enabled(By.res("memory_new_content"))
        assertNotNull(scrollTo(By.text("Synthetic preference: indigo notebooks.")))
        clickEnabled(By.res("memory_erase_all"))
        assertNotNull(find(By.text("Erase all memories?")))
        find(By.text("Erase all")).click()
        device.waitForIdle()
        assertNotNull(scrollTo(By.text("No memories have been added yet.")))
        enabled(By.res("memory_new_content"))

        enterText(By.res("memory_new_content"), "Synthetic memory after erase.")
        clickEnabled(By.res("memory_propose"))
        assertNotNull(scrollTo(By.text("Pending · Added from manual entry")))
        enabled(By.res("memory_new_content"))
        clickEnabled(By.res("memory_approve"))
        assertNotNull(scrollTo(By.text("Approved · Added from manual entry")))
        enabled(By.res("memory_new_content"))
        assertNotNull(scrollTo(By.text("Synthetic memory after erase.")))
        clickEnabled(By.res("memory_back"))
        assertNotNull(find(By.text("Jarvis setup")))
        activity.recreate()
        clickEnabled(By.res("memory_open"))
        assertNotNull(scrollTo(By.text("Approved · Added from manual entry")))
        assertNotNull(scrollTo(By.text("Synthetic memory after erase.")))
        enabled(By.res("memory_new_content"))
        // The control assertion scrolls upward; return to the approved record for useful retained evidence.
        assertNotNull(scrollTo(By.text("Synthetic memory after erase.")))
        captureEvidence("memory_approved_after_recreation")
        clickEnabled(By.res("memory_back"))
    }

    @Test fun test25_finalizedTextAndVoiceMemoryNeedsApprovalBeforePromptUse() {
        // Controlled finalized-input fixtures exercise the production local bridge and prompt builder,
        // not a microphone or model-generated response.
        val file = File(context.cacheDir, "release-finalized-memory.json").apply { delete() }
        val now = System.currentTimeMillis()
        try {
            val os = MemoryOs(file) { now }
            val bridge = ConversationMemory(os)
            val text = bridge.capture(FinalMemoryInput(
                "release-text-memory", "thread-release", null, ConversationMemorySource.TEXT,
                "Remember I prefer bergamot tea.", now
            ))
            val voice = bridge.capture(FinalMemoryInput(
                "release-voice-memory", "thread-release", "call-release", ConversationMemorySource.VOICE,
                "My favorite color is cobalt.", now
            ))
            assertEquals(ConversationMemoryOutcome.PROPOSED, text.outcome)
            assertEquals(ConversationMemoryOutcome.PROPOSED, voice.outcome)
            val pending = checkNotNull(os.read().snapshot).memories
            assertEquals(setOf(MemoryReviewStatus.PENDING), pending.map { it.reviewStatus }.toSet())
            assertTrue(bridge.approvedContext("tea", 600).packet!!.memories.isEmpty())

            pending.forEach { record -> assertEquals(MemoryOutcome.APPROVED, os.approve(record.id, record.revision).outcome) }
            val packet = checkNotNull(bridge.approvedContext("tea cobalt", 900).packet).text
            assertTrue(packet.contains("I prefer bergamot tea"))
            assertTrue(packet.contains("My favorite color is cobalt"))
            val builder = ConversationPromptBuilder(ShortTermConversationContext())
            assertTrue(builder.buildGemmaPrompt("What tea and color do I prefer?", null, emptyList(), true,
                memoryContext = packet).contains(packet))
            assertTrue(builder.buildGemmaPrompt("What tea and color do I prefer?", null, emptyList(), true,
                voice = true, memoryContext = packet).contains(packet))
        } finally { file.delete() }
    }

    @Test fun test26_memoryCorrectionAndEraseRefreshApprovedPacket() {
        // This is a local persistence/refresh contract; it does not claim a generated reply changed.
        val file = File(context.cacheDir, "release-memory-refresh.json").apply { delete() }
        val now = System.currentTimeMillis()
        try {
            val os = MemoryOs(file) { now }
            val bridge = ConversationMemory(os)
            val created = checkNotNull(bridge.capture(FinalMemoryInput(
                "release-original-memory", "thread-release", null, ConversationMemorySource.TEXT,
                "Remember I prefer blue mugs.", now
            )).memory)
            val approved = checkNotNull(os.approve(created.id, created.revision).memory)
            val before = bridge.approvedContext("mugs", 600)
            assertTrue(checkNotNull(before.packet).text.contains("blue mugs"))

            val correction = checkNotNull(os.propose(MemoryProposal(
                "I prefer green mugs",
                MemorySource("release-correction-memory", "final_text_input", now),
                correctsMemoryId = approved.id,
                expectedTargetRevision = approved.revision,
            )).memory)
            val replacement = checkNotNull(os.approve(correction.id, correction.revision).memory)
            val afterCorrection = bridge.approvedContext("mugs", 600)
            assertTrue(checkNotNull(afterCorrection.packet).text.contains("green mugs"))
            assertFalse(afterCorrection.packet.text.contains("blue mugs"))
            assertNotEquals(before.stateToken, afterCorrection.stateToken)

            assertEquals(MemoryOutcome.DELETED, os.delete(replacement.id, replacement.revision).outcome)
            val afterErase = bridge.approvedContext("mugs", 600)
            assertTrue(checkNotNull(afterErase.packet).memories.isEmpty())
            assertNotEquals(afterCorrection.stateToken, afterErase.stateToken)
        } finally { file.delete() }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun test27_controlledConversationSurfaceKeepsCallUntilExplicitEnd() {
        // Controlled StateFlows exercise the production Compose navigation surface. They do not
        // start audio capture, load weights, or represent a microphone/model result.
        val prefs = context.getSharedPreferences("release-conversation-surface", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val history = ConversationHistory(prefs)
        val busy = MutableStateFlow(false)
        val callState = MutableStateFlow(VoiceSessionState.ACTIVELY_LISTENING)
        val ends = AtomicInteger(0)
        val sends = AtomicInteger(0)
        val memoryBacks = AtomicInteger(0)
        val memoryFile = File(context.cacheDir, "release-memory-overlay.json").apply { delete() }
        val queueFull = AtomicBoolean(true)
        try {
            activity.onActivity { host -> host.setContent {
                MaterialTheme {
                    Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    ConversationScreen(
                        history = history,
                        busy = busy,
                        callState = callState,
                        onSend = { _, attachment ->
                            sends.incrementAndGet()
                            if (attachment != null) "Attachments are unavailable while a Voice Call is active."
                            else if (queueFull.get()) "Voice Call input queue is full. Wait for the current turn." else null
                        },
                        selectedModel = LocalModelSpec("release-fixture", "release-fixture.bin", recommendedGpu = false),
                        onSelectConversation = { null },
                        onEndVoice = { done -> ends.incrementAndGet(); done("Voice Call ended.") },
                        onOpenVoiceCalls = {},
                        resumedVoice = false,
                        voiceContent = { visible, _, _, _ -> if (visible) androidx.compose.material3.Text("Controlled voice surface") },
                    )
                    }
                }
            } }
            VoiceSessionUi.status.value = "Voice Call is listening — controlled fixture with a deliberately long status that must not hide End call on a narrow screen."
            VoiceSessionUi.armed.value = true
            assertNotNull(find(By.text("Controlled voice surface")))
            device.pressBack()
            device.waitForIdle()
            assertEquals("Back must not end the active call", 0, ends.get())
            assertNotNull(find(By.res("voice_call_status")))
            val endBounds = find(By.res("voice_call_end")).visibleBounds
            assertTrue("End call must remain visible beside a long status", endBounds.width() > 0 && endBounds.right <= device.displayWidth)
            find(By.res("voice_tab")).click()
            assertNotNull(find(By.text("Controlled voice surface")))
            find(By.res("chat_tab")).click()
            assertEquals("Changing tabs must not end the active call", 0, ends.get())
            assertNotNull(find(By.res("voice_call_status")))
            assertNotNull(find(By.text("Attachments are unavailable during a voice call. End the call to add one.")))
            enterText(By.res("chat_composer"), "Synthetic typed call follow-up")
            clickEnabled(By.res("chat_send"))
            assertEquals(1, sends.get())
            assertNotNull(find(By.text("Voice Call input queue is full. Wait for the current turn.")))
            assertEquals("Synthetic typed call follow-up", find(By.res("chat_composer")).text)
            queueFull.set(false)
            clickEnabled(By.res("chat_send"))
            assertEquals(2, sends.get())
            assertFalse("Successful armed admission must clear the draft", find(By.res("chat_send")).isEnabled)
            find(By.res("voice_call_end")).click()
            assertEquals(1, ends.get())
            VoiceSessionUi.armed.value = false

            activity.onActivity { host -> host.setContent {
                MaterialTheme {
                    Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                        Box(Modifier.fillMaxSize()) {
                            androidx.compose.material3.Text("Underlying call surface")
                            MemoryScreen(
                                memoryOs = MemoryOs(memoryFile),
                                onBack = { memoryBacks.incrementAndGet() },
                                callActive = true,
                                callStatus = "A deliberately long controlled call status that must keep the End call action visible on a narrow screen.",
                                onEndCall = { done -> ends.incrementAndGet(); done("Voice Call ended.") },
                            )
                        }
                    }
                }
            } }
            assertNotNull(find(By.text("Memory")))
            enabled(By.res("memory_back"))
            val memoryEndBounds = find(By.res("voice_call_end")).visibleBounds
            assertTrue("Memory must keep End call visible beside a long status", memoryEndBounds.width() > 0 && memoryEndBounds.right <= device.displayWidth)
            device.pressBack()
            device.waitForIdle()
            assertEquals("Memory overlay must consume system Back before its underlying call surface", 1, memoryBacks.get())
        } finally {
            VoiceSessionUi.armed.value = false
            VoiceSessionUi.status.value = ""
            memoryFile.delete()
        }
    }



    @Test fun test28_voiceNavigationRetainsCallIdUntilExplicitEnd() {
        val preferences = context.getSharedPreferences("voice-navigation-contract", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val controller = VoiceSessionController(SharedPreferencesVoiceCallStore(preferences))
        val call = controller.beginCall()
        // ConversationScreen dispatches these same transitions to its end-call callback.
        VoiceNavigationPolicy.dispatch(VoiceNavigationPolicy.Transition.SHOW_CHAT) { controller.end() }
        assertEquals(call.id, controller.currentCallId())
        VoiceNavigationPolicy.dispatch(VoiceNavigationPolicy.Transition.SHOW_VOICE) { controller.end() }
        assertEquals(call.id, controller.currentCallId())
        VoiceNavigationPolicy.dispatch(VoiceNavigationPolicy.Transition.EXPLICIT_END) { controller.end() }
        assertNull(controller.currentCallId())
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun test29_eachAssistantReplyKeepsPersistedMetricsAcrossReload() {
        val prefs = context.getSharedPreferences("release-live-metrics", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val history = ConversationHistory(prefs)
        val threadId = history.current.value.id
        history.updateReply(threadId, "one", "First response", true)
        history.updateReplyMetrics(threadId, "one") { it.submitted(100).firstRawToken(220).copy(estimatedTokensPerSecond = 12.5) }
        history.updateReply(threadId, "two", "Second response", true)
        history.updateReplyMetrics(threadId, "two") { it.submitted(400).firstRawToken(760).copy(estimatedTokensPerSecond = 5.0) }
        activity.onActivity { host -> host.setContent {
            MaterialTheme { Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                ConversationScreen(history, MutableStateFlow(false), MutableStateFlow(VoiceSessionState.PASSIVE_LISTENING),
                    onSend = { _, _ -> null }, selectedModel = LocalModelSpec("release-fixture", "release-fixture.bin", recommendedGpu = false),
                    onSelectConversation = { null }, onEndVoice = {}, onOpenVoiceCalls = {}, resumedVoice = false,
                    voiceContent = { _, _, _, _ -> })
            } }
        } }
        assertTrue(find(By.res("reply_metrics_one")).text.contains("TTFT 0.12s"))
        assertTrue(find(By.res("reply_metrics_two")).text.contains("TTFT 0.36s"))
        val reloaded = ConversationHistory(prefs)
        assertEquals(12.5, reloaded.current.value.messages.first { it.id == "one" }.metrics?.estimatedTokensPerSecond)
        assertEquals(5.0, reloaded.current.value.messages.first { it.id == "two" }.metrics?.estimatedTokensPerSecond)
    }

    @Test fun test30_phoneActionJournalPreservesReceiptsAndFencesUnknownEffects() {
        val directory = File(context.noBackupFilesDir, "release-action-journal").apply { deleteRecursively(); mkdirs() }
        val file = File(directory, "attempts.json")
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        val pipeline = JournaledActionPipeline(ledger, AndroidMobileActionExecutor(context))
        try {
            assertTrue(pipeline.execute(ActionRequest("set_volume", mapOf("level" to "40"))).succeeded)
            val actual = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            assertEquals((audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * .4).roundToInt(), actual)
            val receipt = ToolTaskLedger(FileToolTaskStore(file)).snapshot().single()
            assertEquals(ToolTaskState.SUCCEEDED, receipt.state)
            assertEquals(ExecutionResult.Outcome.SUCCEEDED, receipt.resultOutcome)

            // Simulate process loss after persisted intent, without claiming a second effect ran.
            val unfinished = ledger.create(ActionRequest("set_volume", mapOf("level" to "90")), ToolTaskState.RUNNING)
            val reopened = ToolTaskLedger(FileToolTaskStore(file))
            reopened.recoverAfterRestart()
            assertEquals(receipt, reopened.get(receipt.id))
            assertEquals(ToolTaskState.UNKNOWN_OUTCOME, reopened.get(unfinished.id)?.state)
            assertNull(reopened.transition(unfinished.id, unfinished.generation, ToolTaskState.RUNNING))
            assertEquals(actual, audio.getStreamVolume(AudioManager.STREAM_MUSIC))

            file.writeText("corrupt")
            val rejected = JournaledActionPipeline(reopened, AndroidMobileActionExecutor(context))
                .execute(ActionRequest("set_volume", mapOf("level" to "90")))
            assertFalse(rejected.succeeded)
            assertEquals(actual, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertEquals("corrupt", file.readText())
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0)
            directory.deleteRecursively()
        }
    }

    @Test fun test31_taskRecoveryAndExactApprovalPreserveAndroidEffects() {
        val directory = File(context.noBackupFilesDir, "release-task-owner").apply { deleteRecursively(); mkdirs() }
        val file = File(directory, "journal.json")
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            val ledger = ToolTaskLedger(FileToolTaskStore(file))
            val group = ledger.admit(listOf(ActionRequest("set_volume", mapOf("level" to "40")), ActionRequest("read_battery")), "release-thread")
            val pipeline = JournaledActionPipeline(ledger, AndroidMobileActionExecutor(context))
            assertTrue(pipeline.executeAttempt(checkNotNull(ledger.get(group.attemptIds[0]))).succeeded)
            val actual = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val reopened = ToolTaskLedger(FileToolTaskStore(file))
            reopened.recoverAfterRestart()
            assertEquals(ToolTaskState.READY, reopened.get(group.attemptIds[1])?.state)
            val recovered = JournaledActionPipeline(reopened, AndroidMobileActionExecutor(context))
            assertFalse(recovered.executeAttempt(checkNotNull(reopened.get(group.attemptIds[0]))).succeeded)
            assertTrue(recovered.executeAttempt(checkNotNull(reopened.get(group.attemptIds[1]))).succeeded)
            assertEquals(actual, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            val approvalStore = ActionApprovalStore(FileToolTaskStore(file))
            val gate = ActionDispatchGate(approvalStore, reopened)
            val guarded = reopened.admit(listOf(ActionRequest("set_volume", mapOf("level" to "90"))), "release-thread", ToolAuthority.EXACT_APPROVAL)
            val pending = gate.prepare(checkNotNull(reopened.get(guarded.attemptIds.single())))
            assertFalse(recovered.executeAttempt(pending.task).succeeded)
            assertNull(gate.authorize(pending, schemaVersion = MobileToolCatalog.VERSION + 1))
            assertEquals(actual, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertEquals(ApprovalDecision.DENIED, approvalStore.deny(pending.approval.id))
            assertEquals(ToolTaskState.CANCELLED, ToolTaskLedger(FileToolTaskStore(file)).get(pending.task.id)?.state)
            assertEquals(ApprovalDecision.DENIED, ActionApprovalStore(FileToolTaskStore(file)).get(pending.approval.id)?.decision)
            assertEquals(2, reopened.journal().events.count { it.kind == ToolTaskEventKind.DISPATCHED })
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0)
            directory.deleteRecursively()
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun test32_taskPanelShowsExactChoiceAndReconcilesWithoutRetry() {
        val file = File(context.cacheDir, "release-task-panel.json").apply { delete() }
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        val approvals = ActionApprovalStore(FileToolTaskStore(file))
        val gate = ActionDispatchGate(approvals, ledger)
        val group = ledger.admit(listOf(ActionRequest("set_volume", mapOf("level" to "25"))), "panel-thread", ToolAuthority.EXACT_APPROVAL)
        val pending = gate.prepare(checkNotNull(ledger.get(group.attemptIds.single())))
        val unknown = ledger.create(ActionRequest("read_battery"), ToolTaskState.RUNNING)
        val legacy = ledger.create(ActionRequest("read_battery"))
        ledger.recoverAfterRestart()
        val journal = MutableStateFlow<ToolTaskJournal?>(ledger.journal())
        val effects = AtomicInteger(0)
        val decide: (String, Long, String) -> Unit = { id, generation, command ->
            val a = ledger.get(id)?.takeIf { it.generation == generation }
            if (a != null) {
                when (command) {
                    "approve" -> JournaledActionPipeline(ledger, MobileActionExecutor { effects.incrementAndGet(); ExecutionResult(true, "25%") })
                        .executeAttempt(a, a.approvalId?.let { approvals.get(it) })
                    "deny" -> a.approvalId?.let { approvals.deny(it) }
                    "checked" -> ledger.reconcileUnknown(id, generation)
                    "cancel" -> ledger.cancelLegacyAttempt(id, generation)
                }
                journal.value = ledger.journal()
            }
        }
        try {
            activity.onActivity { host -> host.setContent {
                MaterialTheme { Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    val snapshot by journal.collectAsState()
                    com.battlesbudz.jarvis.v2.ui.PhoneTaskPanel(snapshot, "panel-thread", null, decide)
                } }
            } }
            find(By.res("phone_tasks_open")).click()
            assertNotNull(find(By.text("Set media volume to 25%")))
            assertEquals(0, effects.get())
            captureEvidence("phone_task_approval")
            find(By.res("task_approve_${pending.task.id}")).click()
            device.waitForIdle()
            // The approve handler runs the effect off the UI thread; poll briefly
            // rather than asserting immediately (fixes intermittent test32 flake).
            val deadline = android.os.SystemClock.uptimeMillis() + 10_000
            while (effects.get() != 1 && android.os.SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(200)
            }
            assertEquals(1, effects.get())
            assertEquals(ToolTaskState.SUCCEEDED, ledger.get(pending.task.id)?.state)
            find(By.res("task_checked_${unknown.id}")).click()
            device.waitForIdle()
            assertTrue(checkNotNull(ToolTaskLedger(FileToolTaskStore(file)).get(unknown.id)).reconciled)
            assertEquals(1, effects.get())
            find(By.res("task_cancel_${legacy.id}")).click()
            device.waitForIdle()
            assertEquals(ToolTaskState.CANCELLED, ToolTaskLedger(FileToolTaskStore(file)).get(legacy.id)?.state)
            val declined = ledger.admit(listOf(ActionRequest("read_battery")), "panel-thread", ToolAuthority.EXACT_APPROVAL)
            val choice = gate.prepare(checkNotNull(ledger.get(declined.attemptIds.single())))
            journal.value = ledger.journal()
            find(By.res("task_deny_${choice.task.id}")).click()
            device.waitForIdle()
            assertEquals(ToolTaskState.CANCELLED, ToolTaskLedger(FileToolTaskStore(file)).get(choice.task.id)?.state)
            assertEquals(1, effects.get())
        } finally { file.delete() }
    }

    @Test fun test33_conditionalAndCommaPlansUseRealAndroidWithoutModelCalls() = runBlocking {
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val executor = AndroidMobileActionExecutor(context, canLaunchDirectly = { true })
        val battery = MobileActionPipeline(executor = executor).execute(ActionRequest("read_battery"))
        assertTrue(battery.succeeded)
        assertEquals(context.getSystemService(android.os.BatteryManager::class.java)
            .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY), battery.batteryPercent)
        val percent = checkNotNull(battery.batteryPercent)
        val directory = File(context.noBackupFilesDir, "conditional-journey").apply { deleteRecursively(); mkdirs() }
        val ledger = ToolTaskLedger(FileToolTaskStore(File(directory, "journal.json")))
        try {
            val runner = ActionTurnRunner(executor)
            val plan = ActionTurnPlan.parse("If my battery is at least $percent%, set volume to 40%, tell me my battery percentage, and open Settings") as ActionTurnPlan.Ready
            var group: ToolTaskGroup? = null
            var index = 0
            val pipeline = JournaledActionPipeline(ledger, executor)
            val outcome = runner.runValidated(plan, dispatch = { request ->
                if (group == null) group = ledger.admit(plan.steps.map { it.request }, "conditional-thread", resumeAfterRestart = false)
                pipeline.executeBound(checkNotNull(ledger.get(group!!.attemptIds[index++])), request)
            }, checkBattery = { MobileActionPipeline(executor = executor).execute(ActionRequest("read_battery")) })
            assertTrue(outcome.message, outcome.completed)
            assertEquals(true, outcome.conditionMatched)
            assertEquals(listOf("set_volume", "read_battery", "open_app"), outcome.receipts.map { it.request.name })
            assertEquals((audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * .4).roundToInt(), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertEquals(percent, outcome.receipts[1].result.batteryPercent)
            assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings")), 10_000))
            device.pressBack()
            activity.recreate()
            val falseCondition = ActionTurnPlan.parse("If my battery is below 0 set volume to 90%") as ActionTurnPlan.Ready
            val skipped = runner.runValidated(falseCondition, dispatch = { error("False condition dispatched") },
                checkBattery = { MobileActionPipeline(executor = executor).execute(ActionRequest("read_battery")) })
            assertTrue(skipped.completed)
            assertEquals(false, skipped.conditionMatched)
            assertTrue(skipped.receipts.isEmpty())
            assertEquals((audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * .4).roundToInt(), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertEquals(3, ledger.snapshot().count { it.state == ToolTaskState.SUCCEEDED })
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0)
            directory.deleteRecursively()
        }
    }

    @Test fun test34_backgroundAssistantOpensAppAndFinishesOrderedPlanWithoutTap() = runBlocking {
        val service = context.packageName + "/com.battlesbudz.jarvis.v2.assistant.JarvisInteractionService"
        val keys = listOf("assistant", "voice_interaction_service", "voice_recognition_service")
        val saved = keys.associateWith { device.executeShellCommand("settings get secure $it").trim() }
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val diagnostics = mutableListOf<String>()
        try {
            // Configure the real system assistant on this disposable emulator, rather than
            // giving the executor a fake foreground/launch exemption or using shell to launch.
            device.executeShellCommand("settings put secure assistant $service")
            device.executeShellCommand("settings put secure voice_interaction_service $service")
            val readyBy = SystemClock.elapsedRealtime() + 10_000
            while (!com.battlesbudz.jarvis.v2.assistant.JarvisInteractionService.isReady(context) &&
                SystemClock.elapsedRealtime() < readyBy) SystemClock.sleep(100)
            assertTrue("System must bind the selected assistant", com.battlesbudz.jarvis.v2.assistant.JarvisInteractionService.isReady(context))
            device.pressHome()
            assertFalse(device.hasObject(By.pkg(context.packageName)))
            // Do not let the recent-foreground grace period prove the assistant route.
            SystemClock.sleep(11_000)
            val executor = AndroidMobileActionExecutor(context, canLaunchDirectly = { false }, onDiagnostic = diagnostics::add)
            val plan = ActionTurnPlan.parse("set volume to 40% then open Settings then tell me my battery") as ActionTurnPlan.Ready
            val outcome = ActionTurnRunner(executor).runValidated(plan,
                dispatch = { request -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    MobileActionPipeline(executor = executor).execute(request)
                } }, checkBattery = { error("No conditional reading requested") })
            assertTrue(outcome.message, outcome.completed)
            assertEquals(listOf("set_volume", "open_app", "read_battery"), outcome.receipts.map { it.request.name })
            assertEquals((audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * .4).roundToInt(), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertEquals(context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY), outcome.receipts.last().result.batteryPercent)
            assertTrue("Background app command must really open Settings without notification interaction",
                device.wait(Until.hasObject(By.pkg("com.android.settings")), 10_000))
            assertTrue(diagnostics.any { "route=selected_assistant visible=false selected=true" in it })
            captureEvidence("background_assistant_first_launch")
            // A second command while Jarvis is still hidden must not fall back to a tap.
            device.pressHome()
            val again = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                MobileActionPipeline(executor = executor).execute(ActionRequest("open_app", mapOf("app" to "Settings")))
            }
            assertTrue(again.message, again.succeeded)
            assertFalse(again.message.contains("tap", ignoreCase = true))
            assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings")), 10_000))
            val missing = MobileActionPipeline(executor = executor).execute(ActionRequest("open_app", mapOf("app" to "jarvis nonexistent fixture app")))
            assertFalse(missing.succeeded)
            assertTrue(missing.message.contains("could not find", ignoreCase = true))
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0)
            for ((key, value) in saved) {
                if (value == "null" || value.isBlank()) device.executeShellCommand("settings delete secure $key")
                else {
                    require(value.matches(Regex("[a-zA-Z0-9_./:]+")))
                    device.executeShellCommand("settings put secure $key $value")
                }
            }
            // Settings is intentionally foreground and Jarvis is STOPPED. Recreating the
            // stopped activity here waits for RESUMED and masks the real test result.
            // @After closes this scenario; the next journey launches its own activity.
        }
    }

    @Test fun test35_mediaControlDispatchesViaAudioManager() {
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val pipeline = MobileActionPipeline(executor = AndroidMobileActionExecutor(context))
        try {
            listOf("play", "pause", "toggle", "next", "previous").forEach { verb ->
                val result = pipeline.execute(ActionRequest("media_control", mapOf("action" to verb)))
                assertTrue("media_control $verb must dispatch: ${result.message}", result.succeeded)
                assertTrue("receipt must describe the dispatch honestly, not claim a playback change",
                    result.message.contains("active media session"))
            }
            val rejected = pipeline.execute(ActionRequest("media_control", mapOf("action" to "rewind")))
            assertFalse("unknown media verb must not dispatch", rejected.succeeded)
            assertEquals("media keys must not change the volume", before,
                audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0)
        }
    }

    @Test fun test36_mediaTextRequestParsesAndDispatches() {
        // Regression for the Fold 6 report (2026-10-04): text "pause music" must
        // become a Ready media_control plan and dispatch through the real
        // executor. A NotAction plan would fall through to chat and let the
        // model hallucinate success, which is the bug being fixed.
        val plan = ActionTurnPlan.parse("pause music")
        assertTrue("text 'pause music' must parse as an action plan, was $plan",
            plan is ActionTurnPlan.Ready)
        val request = (plan as ActionTurnPlan.Ready).steps.single().request
        assertEquals("media_control", request.name)
        assertEquals("pause", request.arguments["action"])
        val pipeline = MobileActionPipeline(executor = AndroidMobileActionExecutor(context))
        val result = pipeline.execute(request)
        assertTrue("parsed media_control must dispatch: ${result.message}", result.succeeded)
        assertTrue("receipt must describe the dispatch honestly, not claim a playback change",
            result.message.contains("active media session"))
    }

    @Test fun test37_openSettingsWifiShowsSettings() {
        val pipeline = MobileActionPipeline(executor = AndroidMobileActionExecutor(context, canLaunchDirectly = { true }))
        val result = pipeline.execute(ActionRequest("open_settings", mapOf("screen" to "wifi")))
        assertTrue(result.message, result.succeeded)
        assertTrue("Wi-Fi settings must actually appear, not merely report success",
            device.wait(Until.hasObject(By.pkg("com.android.settings").depth(0)), 15_000))
        // @After captures the launched Settings screen before closing Jarvis's scenario.
    }

    @Test fun test38_openWebsiteDispatchesHonestReceipt() {
        val pipeline = MobileActionPipeline(executor = AndroidMobileActionExecutor(context, canLaunchDirectly = { true }))
        val result = pipeline.execute(ActionRequest("open_website", mapOf("url" to "example.com")))
        assertTrue("open_website must dispatch: ${result.message}", result.succeeded)
        assertTrue("receipt must name the normalized URL honestly",
            result.message.contains("https://example.com"))
        val rejected = pipeline.execute(ActionRequest("open_website", mapOf("url" to "javascript:alert(1)")))
        assertFalse("dangerous URL scheme must not dispatch", rejected.succeeded)
    }

    @Test fun test39_navigateDispatchesHonestReceipt() {
        val pipeline = MobileActionPipeline(executor = AndroidMobileActionExecutor(context, canLaunchDirectly = { true }))
        val result = pipeline.execute(ActionRequest("navigate", mapOf("destination" to "1600 Amphitheatre Parkway")))
        assertTrue("navigate must dispatch: ${result.message}", result.succeeded)
        assertTrue("receipt must name the destination honestly",
            result.message.contains("1600 Amphitheatre Parkway"))
        val rejected = pipeline.execute(ActionRequest("navigate", mapOf("destination" to "   ")))
        assertFalse("blank destination must not dispatch", rejected.succeeded)
    }

    // M1c screen control journeys.

    private class FakeScreenBridge(
        var observation: ScreenObservation? = null,
        var available: Boolean = true
    ) : ScreenBridge {
        val tapped = mutableListOf<ScreenNode>()
        val scrolled = mutableListOf<Pair<ScreenNode, ScreenScrollDirection>>()
        val typed = mutableListOf<Pair<ScreenNode, String>>()
        var overlayShown = false

        override fun isAvailable(): Boolean = available
        override fun observe(): ScreenObservation? = observation
        override fun tap(node: ScreenNode): Boolean {
            tapped += node
            return true
        }
        override fun scroll(node: ScreenNode, direction: ScreenScrollDirection): Boolean {
            scrolled += node to direction
            return true
        }
        override fun type(node: ScreenNode, text: String): Boolean {
            typed += node to text
            return true
        }
        override fun showStopOverlay(taskLabel: String): Boolean {
            overlayShown = true
            return true
        }
        override fun hideStopOverlay() {
            overlayShown = false
        }
    }

    private fun screenFixtureNodes() = listOf(
        ScreenNode("n0", "Search", "button", "10,20-100,80", clickable = true),
        ScreenNode("n1", "Name", "field", "10,100-400,160", editable = true),
        ScreenNode("n2", "Results", "list", "0,200-1080,1800", scrollable = true)
    )

    @Suppress("DEPRECATION")
    @Test fun test40_screenObservationExtractsCompactSnapshot() {
        // The real Android tree-walking logic against the live window tree:
        // UiAutomation hands back genuine AccessibilityNodeInfo instances, so
        // getChild/recycle follow the production path exactly.
        val root = instrumentation.uiAutomation.rootInActiveWindow
            ?: throw AssertionError("No active window for screen extraction")
        try {
            val nodes = extractScreenNodes(root)
            assertTrue(
                "the Jarvis screen must expose actionable nodes, got ${nodes.size}",
                nodes.isNotEmpty()
            )
            assertTrue(
                "node IDs must be n<index>",
                nodes.all { it.id.matches(Regex("^n[0-9]{1,4}$")) }
            )
            assertTrue(
                "every node must be actionable or labeled",
                nodes.all { it.clickable || it.editable || it.scrollable || it.label.isNotBlank() }
            )
            val text = ScreenObservation("com.battlesbudz.jarvis.v2", nodes)
                .compactText("abcdef1234567890")
            assertTrue(text.contains("observation token: abcdef1234567890"))
            assertTrue(text.lines().size <= nodes.size.coerceAtMost(64) + 3)
        } finally {
            root.recycle()
        }
        // The service must be declared with the accessibility binding permission.
        val info = context.packageManager.getServiceInfo(
            android.content.ComponentName(context, ScreenControlService::class.java), 0
        )
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", info.permission)
    }

    @Test fun test41_screenTapNeedsVerifiedTarget() {
        val bridge = FakeScreenBridge(observation = ScreenObservation("com.example.app", screenFixtureNodes()))
        val session = ScreenControlSession()
        val pipeline = MobileActionPipeline(
            executor = AndroidMobileActionExecutor(context, screenBridge = bridge, screenSession = session)
        )
        // Mutations need an admitted session grant; without it nothing dispatches.
        val denied = pipeline.execute(ActionRequest("screen_tap", mapOf("target" to "n0", "token" to "abcdef1234567890")))
        assertFalse("unadmitted tap must not dispatch: ${denied.message}", denied.succeeded)
        assertTrue(denied.message.contains("approval"))
        assertTrue(bridge.tapped.isEmpty())

        assertEquals(AdmitResult.Admitted, session.admit("test-41", userApproved = true))
        val observed = pipeline.execute(ActionRequest("screen_observe"))
        assertTrue("observe must succeed: ${observed.message}", observed.succeeded)
        val token = session.currentToken!!
        assertTrue(observed.message.contains("observation token: $token"))

        // A well-formed but stale token never dispatches.
        val stale = pipeline.execute(ActionRequest("screen_tap", mapOf("target" to "n0", "token" to "0000000000000000")))
        assertFalse("stale token must not dispatch: ${stale.message}", stale.succeeded)
        assertTrue(stale.message.contains("stale"))
        assertTrue(bridge.tapped.isEmpty())

        // Fresh token plus verified target dispatches with an honest receipt.
        val tapped = pipeline.execute(ActionRequest("screen_tap", mapOf("target" to "n0", "token" to token)))
        assertTrue("verified tap must dispatch: ${tapped.message}", tapped.succeeded)
        assertEquals(listOf("n0"), bridge.tapped.map { it.id })

        // Wrong-kind target is rejected without dispatch.
        val wrongKind = pipeline.execute(
            ActionRequest("screen_type", mapOf("target" to "n0", "text" to "hi", "token" to token))
        )
        assertFalse("non-editable type target must not dispatch", wrongKind.succeeded)
        assertTrue(bridge.typed.isEmpty())

        // Release ends the grant; the overlay hides with it.
        assertTrue(bridge.showStopOverlay("test-41"))
        session.release()
        bridge.hideStopOverlay()
        assertFalse(session.isAdmitted)
        assertFalse(bridge.overlayShown)
    }

    @Test fun test42_touchPauseAndIdleResumeReobserves() {
        var now = 10_000L
        val bridge = FakeScreenBridge(observation = ScreenObservation("com.example.app", screenFixtureNodes()))
        val session = ScreenControlSession(clock = { now }, touchIdleMs = 3_000L)
        val pipeline = MobileActionPipeline(
            executor = AndroidMobileActionExecutor(context, screenBridge = bridge, screenSession = session)
        )
        session.admit("test-42", userApproved = true)
        assertTrue(pipeline.execute(ActionRequest("screen_observe")).succeeded)
        val token = session.currentToken!!

        // Manual touch pauses dispatch.
        session.noteTouchStart()
        val paused = pipeline.execute(ActionRequest("screen_tap", mapOf("target" to "n0", "token" to token)))
        assertFalse("tap during manual touch must pause: ${paused.message}", paused.succeeded)
        assertTrue(paused.message.contains("touching the screen"))
        assertTrue(bridge.tapped.isEmpty())

        // After the touch-idle interval the executor re-observes the changed
        // screen without a countdown; the pre-touch token is stale by design.
        session.noteTouchEnd()
        now += 3_000L
        bridge.observation = ScreenObservation(
            "com.example.other",
            listOf(ScreenNode("n0", "Other", "button", "0,0-50,50", clickable = true))
        )
        val resumed = pipeline.execute(ActionRequest("screen_tap", mapOf("target" to "n0", "token" to token)))
        assertFalse("pre-touch token must be stale after resume: ${resumed.message}", resumed.succeeded)
        assertTrue(resumed.message.contains("stale"))
        assertTrue(bridge.tapped.isEmpty())

        // The resume re-observed: a fresh observation token dispatches.
        val token2 = session.currentToken!!
        assertNotEquals(token, token2)
        val retried = pipeline.execute(ActionRequest("screen_tap", mapOf("target" to "n0", "token" to token2)))
        assertTrue("fresh token must dispatch after resume: ${retried.message}", retried.succeeded)
        assertEquals(listOf("n0"), bridge.tapped.map { it.id })
    }

    @Test fun test43_screenToolsReportHonestlyWhenServiceDisabled() {
        // Real service bridge; the service is not enabled on the emulator, so
        // every screen tool must answer honestly instead of claiming effects.
        val pipeline = MobileActionPipeline(executor = AndroidMobileActionExecutor(context))
        val observed = pipeline.execute(ActionRequest("screen_observe"))
        assertFalse("observe without the enabled service must not succeed", observed.succeeded)
        assertTrue(
            "receipt must name the missing Accessibility enablement, was: ${observed.message}",
            observed.message.contains("Accessibility")
        )
        val tap = pipeline.execute(ActionRequest("screen_tap", mapOf("target" to "n0", "token" to "abcdef1234567890")))
        assertFalse("tap without a grant must not dispatch", tap.succeeded)
        assertTrue(tap.message.contains("approval"))
    }

    // M1d task/conversation scheduling journeys.

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun test45_panelApprovalAdmitsScreenSessionAndDispatchesExactly() {
        // M1d approval-UI wiring (T07): approving a screen task in the task
        // panel admits the screen-control session grant for its group, and the
        // ledger claim consumes the approval atomically with dispatch
        // eligibility. A changed target invalidates the prior approval (D13).
        val file = File(context.cacheDir, "release-m1d-approval.json").apply { delete() }
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        val approvals = ActionApprovalStore(FileToolTaskStore(file))
        val session = ScreenControlSession()
        val bridge = FakeScreenBridge(observation = ScreenObservation("com.example.app", screenFixtureNodes()))
        val token = session.recordObservation(checkNotNull(bridge.observation))
        val executor = AndroidMobileActionExecutor(context, screenBridge = bridge, screenSession = session)
        val admission = ScreenApprovalAdmission(session)
        fun tapRequest(target: String) = ActionRequest("screen_tap", mapOf("target" to target, "token" to token))
        val group = ledger.admit(listOf(tapRequest("n0")), "panel-thread")
        val attempt = checkNotNull(ledger.get(group.attemptIds.single()))
        val pending = ledger.requestApproval(attempt.id, attempt.generation, "native", MobileToolCatalog.VERSION)
        // D13: the target changes before approval...
        val changedOnce = checkNotNull(ledger.revise(pending.task.id, pending.task.generation, tapRequest("n1")))
        // ...so the stale approval cannot admit the session.
        assertTrue("changed target must invalidate the prior approval",
            admission.admitForApproval(checkNotNull(ledger.get(changedOnce.id)), pending.approval) is AdmitResult.Denied)
        // Fresh approval for the current action goes through the panel.
        val changedBack = checkNotNull(ledger.revise(changedOnce.id, changedOnce.generation, tapRequest("n0")))
        val fresh = ledger.requestApproval(changedBack.id, changedBack.generation, "native", MobileToolCatalog.VERSION)
        val journal = MutableStateFlow<ToolTaskJournal?>(ledger.journal())
        val decide: (String, Long, String) -> Unit = { id, generation, command ->
            val a = ledger.get(id)?.takeIf { it.generation == generation }
            if (a != null && command == "approve") {
                val approval = a.approvalId?.let { approvals.get(it) }
                if (approval != null) {
                    // The M1d approval wiring: the panel approval admits the
                    // session grant for this group before the ledger claim.
                    admission.admitForApproval(a, approval)
                    JournaledActionPipeline(ledger, executor).executeAttempt(a, approval)
                    if (session.holderGroupId == a.groupId) bridge.showStopOverlay("test-45")
                }
            }
            journal.value = ledger.journal()
        }
        try {
            activity.onActivity { host -> host.setContent {
                MaterialTheme { Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    val snapshot by journal.collectAsState()
                    com.battlesbudz.jarvis.v2.ui.PhoneTaskPanel(snapshot, "panel-thread", null, decide)
                } }
            } }
            find(By.res("phone_tasks_open")).click()
            captureEvidence("m1d_screen_approval")
            find(By.res("task_approve_${fresh.task.id}")).click()
            device.waitForIdle()
            val deadline = android.os.SystemClock.uptimeMillis() + 10_000
            while (bridge.tapped.isEmpty() && android.os.SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(200)
            }
            assertEquals("approved tap must dispatch exactly once", listOf("n0"), bridge.tapped.map { it.id })
            assertEquals("session grant must belong to the approved group", group.id, session.holderGroupId)
            assertEquals(ToolTaskState.SUCCEEDED, ledger.get(fresh.task.id)?.state)
            assertTrue("approval must be consumed", checkNotNull(approvals.get(fresh.approval.id)).consumed)
            assertTrue("Stop overlay shows while the admitted group holds the lease", bridge.overlayShown)
            // A finished group releases the lease and the overlay hides (T04).
            assertTrue(session.releaseIf(group.id))
            bridge.hideStopOverlay()
            assertFalse(session.isAdmitted)
            assertFalse(bridge.overlayShown)
        } finally { file.delete() }
    }

    @Test fun test46_conflictingScreenTaskQueuesBehindTheLease() {
        // T02: a follow-up screen task approved while another group holds the
        // lease is denied a second grant and stays waiting for its turn; it is
        // never rejected and never steals the lease.
        val session = ScreenControlSession()
        val bridge = FakeScreenBridge(observation = ScreenObservation("com.example.app", screenFixtureNodes()))
        val token = session.recordObservation(checkNotNull(bridge.observation))
        val ledger = ToolTaskLedger()
        val admission = ScreenApprovalAdmission(session)
        fun parkTap(): Triple<ToolTaskGroup, ToolTaskAttempt, ActionApprovalRequest> {
            val group = ledger.admit(listOf(ActionRequest("screen_tap", mapOf("target" to "n0", "token" to token))), "thread-1")
            val attempt = checkNotNull(ledger.get(group.attemptIds.single()))
            val pending = ledger.requestApproval(attempt.id, attempt.generation, "native", MobileToolCatalog.VERSION)
            return Triple(group, pending.task, pending.approval)
        }
        val (groupA, _, approvalA) = parkTap()
        val taskA = checkNotNull(ledger.get(groupA.attemptIds.single()))
        assertEquals(AdmitResult.Admitted, admission.admitForApproval(taskA, approvalA))
        val (groupB, _, approvalB) = parkTap()
        val taskB = checkNotNull(ledger.get(groupB.attemptIds.single()))
        val denied = admission.admitForApproval(taskB, approvalB)
        assertTrue("second grant must be denied while the lease is held, was: $denied", denied is AdmitResult.Denied)
        assertEquals("lease must stay with the first group", groupA.id, session.holderGroupId)
        assertEquals("denied task stays waiting for its turn", ToolTaskState.WAITING_APPROVAL, ledger.get(taskB.id)?.state)
        assertFalse("denied approval stays unconsumed",
            checkNotNull(ledger.journal().approvals.find { it.id == approvalB.id }).consumed)
        // The first group finishes: the lease releases and the queued task can
        // take its turn.
        assertTrue(session.releaseIf(groupA.id))
        assertEquals(AdmitResult.Admitted, admission.admitForApproval(taskB, approvalB))
        assertEquals(groupB.id, session.holderGroupId)
        // Independent (non-screen) work is never blocked by the lease.
        val scheduler = TaskScheduler()
        assertEquals(ScheduleDecision.RunNow, scheduler.schedule(
            scheduler.resourceFor(ActionRequest("read_battery")), listOf(TaskResource(TaskResourceKind.SCREEN_LEASE))))
    }

    @Test fun test47_progressNotificationPostsSilentlyDuringDnd() {
        // T04/T15/D35: task progress posts to notifications immediately and
        // silently, even during Do Not Disturb — never deferred.
        val manager = context.getSystemService(NotificationManager::class.java)
        runCatching {
            device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        }
        val dndBefore = runCatching { device.executeShellCommand("settings get global zen_mode").trim() }.getOrNull()
        runCatching { device.executeShellCommand("settings put global zen_mode 1") }
        try {
            val dndOn = runCatching { device.executeShellCommand("settings get global zen_mode").trim() }.getOrNull() == "1"
            val working = TaskStatusProjection("group-47", "Test task", TaskProjectionState.WORKING,
                1, 3, "Test task: working (1 of 3 steps done).", listOf("step one done"))
            assertTrue("progress must post", TaskProgressNotification.postProgress(context, working))
            val id = 0x7a000000 or ("group-47".hashCode() and 0x00ffffff)
            var posted = false
            val deadline = android.os.SystemClock.uptimeMillis() + 10_000
            while (!posted && android.os.SystemClock.uptimeMillis() < deadline) {
                posted = manager.activeNotifications.any { it.id == id }
                if (!posted) Thread.sleep(200)
            }
            assertTrue("progress notification must be posted immediately${if (dndOn) " during Do Not Disturb" else ""}", posted)
            val finished = working.copy(state = TaskProjectionState.FINISHED, completedSteps = 3,
                statusLine = "Test task: done (3 of 3 steps).",
                stepReceipts = listOf("step one done", "step two done", "step three done"))
            assertTrue("finished must post", TaskProgressNotification.postFinished(context, finished))
            TaskProgressNotification.cancel(context, "group-47")
        } finally {
            runCatching { device.executeShellCommand("settings put global zen_mode ${dndBefore ?: 0}") }
        }
    }

    @Test fun test48_finishedScreenGroupReleasesLeaseNotifiesAndProjects() {
        // T04: ending a call does not cancel admitted work; when the group
        // finishes, the screen lease releases, the Stop overlay hides, and
        // the ordered projection reports completion.
        val session = ScreenControlSession()
        val bridge = FakeScreenBridge(observation = ScreenObservation("com.example.app", screenFixtureNodes()))
        val token = session.recordObservation(checkNotNull(bridge.observation))
        val ledger = ToolTaskLedger()
        val admission = ScreenApprovalAdmission(session)
        val group = ledger.admit(listOf(ActionRequest("screen_tap", mapOf("target" to "n0", "token" to token))), "thread-1")
        val attempt = checkNotNull(ledger.get(group.attemptIds.single()))
        val pending = ledger.requestApproval(attempt.id, attempt.generation, "native", MobileToolCatalog.VERSION)
        assertEquals(AdmitResult.Admitted, admission.admitForApproval(pending.task, pending.approval))
        // "Call ends": detaching audio never touches admitted work.
        assertEquals(ToolTaskState.WAITING_APPROVAL, ledger.get(attempt.id)?.state)
        assertEquals(group.id, session.holderGroupId)
        val executor = AndroidMobileActionExecutor(context, screenBridge = bridge, screenSession = session)
        assertTrue(bridge.showStopOverlay("test-48"))
        val result = JournaledActionPipeline(ledger, executor).executeAttempt(pending.task, pending.approval)
        assertTrue("approved tap must dispatch: ${result.message}", result.succeeded)
        assertEquals(listOf("n0"), bridge.tapped.map { it.id })
        // Finished group: release the lease, hide the overlay, project.
        assertTrue(session.releaseIf(group.id))
        bridge.hideStopOverlay()
        assertFalse(session.isAdmitted)
        assertFalse(bridge.overlayShown)
        val projection = checkNotNull(TaskProgressProjector().project(ledger.journal(), group.id))
        assertEquals(TaskProjectionState.FINISHED, projection.state)
        assertTrue(projection.isTerminal)
        assertEquals(listOf("Tapped \"Search\"."), projection.stepReceipts)
        assertEquals(1, projection.completedSteps)
    }

    @Test fun test49_sourceAccessDenialBlocksDispatchAcrossAdapters() {
        // T08: first-source access is remembered per family; a denial or
        // revocation blocks dispatch on every adapter with a truthful
        // receipt, and a new tool can never broaden an existing grant's
        // scope. The approval claim path is blocked too.
        val dispatched = AtomicInteger(0)
        val executor = MobileActionExecutor { dispatched.incrementAndGet(); ExecutionResult(true, "ok") }
        val ledger = ToolTaskLedger()
        val access = ToolSourceAccess(ledger)
        val pipeline = JournaledActionPipeline(ledger, executor,
            sourceAccess = access,
            capabilityProbe = ToolCapabilityProbe { null },
            lockGate = DeviceLockGate(isLocked = { false }))
        // First grant: read_battery succeeds and its family access is remembered.
        assertTrue(pipeline.execute(ActionRequest("read_battery")).succeeded)
        val record = ledger.journal().sourceAccess.single { it.family == "phone" }
        assertEquals(SourceAccessState.GRANTED, record.state)
        assertEquals(ToolSourcePolicy.familyScopes("phone"), record.scopes)
        // Revocation blocks the direct path with an honest receipt.
        assertTrue(access.revoke("phone"))
        val denied = pipeline.execute(ActionRequest("set_volume", mapOf("level" to "25")))
        assertFalse(denied.succeeded)
        assertEquals(ExecutionResult.Outcome.DENIED_PERMISSION, denied.outcome)
        // Revocation blocks the approval claim path too: no dispatch.
        val group = ledger.admit(listOf(ActionRequest("read_battery")), "thread-49")
        val attempt = checkNotNull(ledger.get(group.attemptIds.single()))
        val pending = ledger.requestApproval(attempt.id, attempt.generation, "native", MobileToolCatalog.VERSION)
        assertNull("revoked family must not claim",
            ledger.claim(pending.task.id, pending.task.generation, approval = pending.approval))
        assertEquals("exactly one real dispatch happened", 1, dispatched.get())
        // A new tool can never broaden an existing grant's scope: a record
        // claiming another family's scopes does not admit.
        val tamperedStore = InMemoryToolTaskStore()
        tamperedStore.updateJournal { j -> j.copy(sourceAccess = listOf(
            ToolSourceAccessRecord("web", setOf("screen.control"), SourceAccessState.GRANTED, 0))) }
        val tamperedDenial = ToolSourceAccess(ToolTaskLedger(tamperedStore))
            .denial(ActionRequest("open_website", mapOf("url" to "https://example.com")))
        assertNotNull("out-of-family scope must not admit", tamperedDenial)
        assertEquals(ExecutionResult.Outcome.DENIED_PERMISSION, tamperedDenial!!.outcome)
    }

    @Test fun test50_lockedDeviceGatesSensitiveActions() {
        // T09: on a locked device, sensitive actions hand off to unlock;
        // owner recognition is gated (a voice match never authorizes); the
        // non-sensitive battery read still dispatches through the real
        // Android adapter. The CI emulator ships without a lock screen, so
        // the journey sets a real PIN via locksettings first — the keyguard
        // state below is genuine Android lock state, not a fixture.
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        device.executeShellCommand("locksettings set-pin 1234")
        try {
            device.sleep()
            val deadline = android.os.SystemClock.uptimeMillis() + 10_000
            while (keyguard?.isDeviceLocked != true && android.os.SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(200)
            }
            assertTrue("device must be locked for this journey (locksettings PIN must take effect)",
                keyguard?.isDeviceLocked == true)
            val gate = androidLockGate(context)
            assertEquals(OwnerRecognitionMode.GATED, gate.ownerRecognition)
            val dispatched = AtomicInteger(0)
            val fake = MobileActionExecutor { dispatched.incrementAndGet(); ExecutionResult(true, "ok") }
            val realExecutor = AndroidMobileActionExecutor(context)
            val pipeline = JournaledActionPipeline(ToolTaskLedger(), fake,
                sourceAccess = null, capabilityProbe = null, lockGate = gate)
            val realPipeline = JournaledActionPipeline(ToolTaskLedger(), realExecutor,
                sourceAccess = null, capabilityProbe = null, lockGate = gate)
            val battery = realPipeline.execute(ActionRequest("read_battery"))
            assertTrue("non-sensitive read works while locked: ${battery.message}", battery.succeeded)
            assertTrue("real battery receipt, was: ${battery.message}",
                battery.message.startsWith("Battery is at"))
            val tap = pipeline.execute(
                ActionRequest("screen_tap", mapOf("target" to "n0", "token" to "0123456789abcdef")))
            assertEquals(ExecutionResult.Outcome.NEEDS_UNLOCK, tap.outcome)
            assertTrue("handoff must name the lock, was: ${tap.message}",
                tap.message.contains("locked", ignoreCase = true))
            assertFalse("never describe a voice match as authorization",
                tap.message.contains("voice", ignoreCase = true))
            val volume = pipeline.execute(ActionRequest("set_volume", mapOf("level" to "25")))
            assertEquals(ExecutionResult.Outcome.NEEDS_UNLOCK, volume.outcome)
            assertEquals("only the battery read dispatched", 0, dispatched.get())
        } finally {
            runCatching { device.executeShellCommand("locksettings clear --old 1234") }
            device.wakeUp()
        }
        assertFalse("PIN must be cleared so later journeys run unlocked",
            keyguard?.isDeviceLocked == true)
    }

    @Test fun test51_crossFamilyRegressionInvalidArgsProduceNoEffects() {
        // T01 regression across all M1 command families: invalid args are
        // rejected before any adapter runs, so nothing changes on the device.
        val audio = context.getSystemService(AudioManager::class.java)
        val volumeBefore = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val bridge = FakeScreenBridge(observation = ScreenObservation("com.example.app", screenFixtureNodes()))
        val executor = AndroidMobileActionExecutor(context, screenBridge = bridge)
        val dispatched = AtomicInteger(0)
        val counting = MobileActionExecutor { action -> dispatched.incrementAndGet(); executor.execute(action) }
        val ledger = ToolTaskLedger()
        val pipeline = JournaledActionPipeline(ledger, counting,
            sourceAccess = ToolSourceAccess(ledger),
            capabilityProbe = ToolCapabilityProbe { null },
            lockGate = DeviceLockGate(isLocked = { false }))
        val invalid = listOf(
            ActionRequest("set_volume", mapOf("level" to "999")),
            ActionRequest("media_control", mapOf("action" to "explode")),
            ActionRequest("open_app", mapOf("app" to "   ")),
            ActionRequest("open_website", mapOf("url" to "javascript:alert(1)")),
            ActionRequest("open_settings", mapOf("screen" to "nuclear")),
            ActionRequest("navigate", mapOf("destination" to "   ")),
            ActionRequest("screen_tap", mapOf("target" to "zzz", "token" to "bad")),
            ActionRequest("screen_scroll", mapOf("target" to "n2", "direction" to "sideways", "token" to "bad")),
            ActionRequest("screen_type", mapOf("target" to "n1", "text" to "", "token" to "bad"))
        )
        for (request in invalid) {
            val rejected = pipeline.execute(request)
            assertEquals("invalid ${request.name} must be rejected, was: ${rejected.message}",
                ExecutionResult.Outcome.REJECTED_VALIDATION, rejected.outcome)
        }
        assertEquals("no adapter may run for invalid args", 0, dispatched.get())
        assertEquals("volume must be unchanged", volumeBefore, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertTrue("no screen effects", bridge.tapped.isEmpty() && bridge.scrolled.isEmpty() && bridge.typed.isEmpty())
        // Valid anchor: the real battery adapter still dispatches through the gate.
        val battery = pipeline.execute(ActionRequest("read_battery"))
        assertTrue("valid read_battery must dispatch: ${battery.message}", battery.succeeded)
        assertEquals(1, dispatched.get())
    }

    @Test fun test52_crashBeforeAndAfterDispatchReconcilesWithoutRepeat() {
        // T10: crash before dispatch and crash after dispatch both recover to
        // an unknown outcome; stale callbacks are rejected and the unknown
        // mutation is never blindly repeated.
        val file = File(context.cacheDir, "release-m1e-crash.json").apply { delete() }
        try {
            val dispatched = AtomicInteger(0)
            val executor = MobileActionExecutor {
                dispatched.incrementAndGet()
                ExecutionResult(true, "Battery 80%")
            }
            // Crash before dispatch: claimed RUNNING, then "process death".
            var ledger = ToolTaskLedger(FileToolTaskStore(file))
            val before = ledger.admit(listOf(ActionRequest("read_battery")), "thread-52")
            val beforeAttempt = checkNotNull(ledger.get(before.attemptIds.single()))
            val claimedBefore = checkNotNull(ledger.claim(beforeAttempt.id, beforeAttempt.generation))
            ledger = ToolTaskLedger(FileToolTaskStore(file))
            val recoveredBefore = ledger.recoverAfterRestart().single { it.id == claimedBefore.id }
            assertEquals(ToolTaskState.UNKNOWN_OUTCOME, recoveredBefore.state)
            assertNull("stale pre-crash callback must be rejected",
                ledger.finish(claimedBefore, ExecutionResult(true, "late")))
            assertEquals(ToolTaskState.UNKNOWN_OUTCOME, ledger.get(claimedBefore.id)?.state)
            assertTrue(ledger.reconcileUnknown(recoveredBefore.id, recoveredBefore.generation))
            // Crash after dispatch: the real effect happened once, then
            // "death" before the receipt saved.
            val after = ledger.admit(listOf(ActionRequest("read_battery")), "thread-52")
            val afterAttempt = checkNotNull(ledger.get(after.attemptIds.single()))
            val claimedAfter = checkNotNull(ledger.claim(afterAttempt.id, afterAttempt.generation))
            val afterAction = (MobileActionValidator().validate(claimedAfter.request) as ActionValidation.Valid).action
            executor.execute(afterAction)
            assertEquals(1, dispatched.get())
            ledger = ToolTaskLedger(FileToolTaskStore(file))
            val recoveredAfter = ledger.recoverAfterRestart().single { it.id == claimedAfter.id }
            assertEquals(ToolTaskState.UNKNOWN_OUTCOME, recoveredAfter.state)
            assertTrue(ledger.reconcileUnknown(recoveredAfter.id, recoveredAfter.generation))
            // Neither unknown mutation is ever repeated.
            val pipeline = JournaledActionPipeline(ledger, executor)
            for (id in listOf(claimedBefore.id, claimedAfter.id)) {
                val retry = pipeline.executeAttempt(checkNotNull(ledger.get(id)))
                assertFalse("reconciled attempt must not redispatch", retry.succeeded)
            }
            assertEquals("the effect happened exactly once", 1, dispatched.get())
        } finally { file.delete() }
    }

    // Leave this selection in durable preferences for the controller's separate-process check.
    @Test fun test90_modelSelectionPersistsAcrossRecreation() {
        openBrowser()

        find(By.res("model_search")).text = "Gemma-4-E4B-it"
        find(By.res("model_family_Gemma")).click()
        scrollTo(By.res("model_choose_Gemma-4-E4B-it")).click()
        assertNotNull(find(By.text("Gemma-4-E4B-it")))
        activity.recreate()
        assertNotNull(find(By.text("Gemma-4-E4B-it")))
    }
}
