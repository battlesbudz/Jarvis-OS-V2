package com.battlesbudz.jarvis.v2.verification

import android.content.Intent
import android.content.ContentValues
import android.media.AudioManager
import android.os.BatteryManager
import android.os.SystemClock
import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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

    /** Waits for the async reload after a mutation instead of racing a disabled form control. */
    private fun enabled(selector: BySelector): UiObject2 {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            // Scrolling and Compose recomposition can invalidate a prior accessibility node.
            val control = scrollTo(selector)
            try {
                if (control.isEnabled) return control
            } catch (_: StaleObjectException) {
                // No action has been dispatched; retry with a fresh node within the same bound.
            }
            device.waitForIdle()
        }
        throw AssertionError("Control did not become enabled: $selector")
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
