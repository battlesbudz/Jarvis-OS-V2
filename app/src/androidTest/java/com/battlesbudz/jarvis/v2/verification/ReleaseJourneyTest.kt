package com.battlesbudz.jarvis.v2.verification

import android.content.Intent
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import com.battlesbudz.jarvis.v2.ai.ModelCatalog
import com.battlesbudz.jarvis.v2.ai.ModelStore
import com.battlesbudz.jarvis.v2.chat.ConversationHistory
import com.battlesbudz.jarvis.v2.chat.ShortTermConversationContext
import com.battlesbudz.jarvis.v2.memory.*
import com.battlesbudz.jarvis.v2.ui.ConversationScreen
import com.battlesbudz.jarvis.v2.ui.VoiceCallOverlay
import com.battlesbudz.jarvis.v2.ui.MemoryScreen
import com.battlesbudz.jarvis.v2.ui.JarvisApp
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
import java.util.concurrent.atomic.AtomicReference
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

    private fun hideKeyboardWithoutNavigating() {
        // UiObject2.setText need not open the IME. A blind Back can finish the Activity.
        activity.onActivity { host ->
            host.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .hideSoftInputFromWindow(host.window.decorView.windowToken, 0)
        }
        device.waitForIdle()
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

    /** Waits for an async UI state update and verifies a fresh node remains disabled. */
    private fun waitUntilDisabled(selector: BySelector): UiObject2 {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                val control = device.findObject(selector)
                if (control != null && !control.isEnabled) {
                    device.waitForIdle()
                    SystemClock.sleep(300)
                    val fresh = device.findObject(selector)
                    if (fresh != null && !fresh.isEnabled) {
                        return fresh
                    }
                }
            } catch (_: StaleObjectException) {
                // Re-query after Compose replaces the accessibility node.
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("Control did not become stably disabled: $selector")
    }

    private fun openBrowser() { find(By.res("model_browse")).click(); find(By.res("model_search")) }

    private fun clickEnabled(selector: BySelector) {
        enabled(selector).click()
        device.waitForIdle()
    }

    /** Category chips live in horizontal LazyRows, so vertical page seeking cannot reveal all of them. */
    private fun clickHorizontalChip(strip: BySelector, target: BySelector) {
        repeat(8) {
            device.findObject(target)?.let { chip ->
                if (chip.isEnabled && hasSafeTapBounds(chip)) {
                    chip.click()
                    device.waitForIdle()
                    return
                }
            }
            val row = find(strip).visibleBounds
            device.swipe(row.right - 12, row.centerY(), row.left + 12, row.centerY(), 180)
            device.waitForIdle()
        }
        clickEnabled(target)
    }

    private fun enterText(selector: BySelector, value: String) {
        try {
            enabled(selector).text = value
        } catch (_: StaleObjectException) {
            // Text assignment is an idempotent replacement, so a fresh-node retry is safe.
            enabled(selector).text = value
        }
        // ACTION_SET_TEXT can leave the IME closed. Unconditional Escape dismisses the
        // AlertDialog (retained build-842 evidence). Dismiss only a visible keyboard.
        device.waitForIdle()
        if (device.hasObject(By.pkg(java.util.regex.Pattern.compile(".*inputmethod.*")))) device.pressBack()
        device.waitForIdle()
    }

    private fun searchMemory(query: String, expected: BySelector) {
        enterText(By.res("memory_search_input"), query)
        // Search is live in the wiki.  The adjacent action clears a search; it must not be
        // tapped here or the assertion would inspect the unfiltered page.
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
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun test24_memoryManagerReviewsCorrectsSearchesAndErases() {
        /*
         * This mounts the shipping MemoryScreen on a real, file-backed MemoryOs.  The two
         * ConversationMemory inputs are deliberately finalized fixtures: this proves the
         * production capture boundary and review UI without claiming microphone/ASR/model work.
         */
        val file = File(context.cacheDir, "release-memory-wiki-journey.db").apply { delete() }
        val store = SQLiteMemoryStore(file)
        val memoryOs = MemoryOs(store)
        val bridge = ConversationMemory(memoryOs)
        val sapphire = "I prefer sapphire notebooks for verification"
        val cobalt = "My favorite color is cobalt"
        val atlas = "Project Atlas uses [[Cobalt]]."
        val cobaltNotes = "Cobalt reference notes for verification."
        val indigo = "I prefer indigo notebooks for verification"
        val persistent = "I prefer persistent amber tea for verification"

        fun mountMemoryWiki() {
            activity.onActivity { host -> host.setContent {
                MaterialTheme { Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    MemoryScreen(memoryOs = memoryOs, onBack = {})
                } }
            } }
            assertNotNull(find(By.text("Memory")))
        }
        fun addManual(content: String, category: String, topic: String) {
            clickEnabled(By.res("memory_new"))
            assertNotNull(find(By.text("Add a memory")))
            enterText(By.res("memory_new_content"), content)
            clickHorizontalChip(By.res("memory_category_picker"), By.res("memory_category_$category"))
            enterText(By.res("memory_topic_input"), topic)
            clickEnabled(By.res("memory_propose"))
            assertTrue("Add dialog did not close after its saved proposal", device.wait(
                Until.gone(By.res("memory_new_content")), 15_000
            ))
        }
        fun approveOnlyPending() {
            clickEnabled(By.res("memory_review_tab"))
            assertNotNull(find(By.text("Review (1)")))
            clickEnabled(By.res("memory_approve"))
            assertNotNull(find(By.text("Review (0)")))
        }

        try {
            // Keep the app-level entry and return route in the release journey before mounting
            // the controlled, file-backed capture fixture below.
            clickEnabled(By.res("memory_open"))
            assertNotNull(find(By.text("Memory")))
            clickEnabled(By.res("memory_back"))
            assertNotNull(find(By.res("model_browse")))
            mountMemoryWiki()

            // Add remains a modal. Restricted content must stay visibly rejected in that modal.
            clickEnabled(By.res("memory_new"))
            assertNotNull(find(By.text("Add a memory")))
            enterText(By.res("memory_new_content"), "Bank account number 1234 5678 9012 3456")
            clickEnabled(By.res("memory_propose"))
            assertNotNull(find(By.res("memory_error")))
            clickEnabled(By.text("Cancel"))

            val now = System.currentTimeMillis()
            assertEquals(ConversationMemoryOutcome.PROPOSED, bridge.capture(FinalMemoryInput(
                "release-wiki-text", "release-wiki-thread", null, ConversationMemorySource.TEXT,
                "Remember $sapphire.", now
            )).outcome)
            assertEquals(ConversationMemoryOutcome.PROPOSED, bridge.capture(FinalMemoryInput(
                "release-wiki-voice", "release-wiki-thread", "release-wiki-call", ConversationMemorySource.VOICE,
                "$cobalt.", now + 1
            )).outcome)

            // Pending capture is reviewable but never part of Wiki search until a real Approve tap.
            assertNotNull(find(By.text("Review (2)")))
            searchMemory("sapphire notebooks", By.text("No approved memories match that search."))
            enterText(By.res("memory_search_input"), "")
            clickEnabled(By.res("memory_review_tab"))
            assertNotNull(scrollTo(By.text(sapphire)))
            captureEvidence("memory_wiki_review_pending")
            clickEnabled(By.res("memory_approve"))
            assertNotNull(find(By.text("Review (1)")))
            clickEnabled(By.res("memory_approve"))
            assertNotNull(find(By.text("Review (0)")))

            // Exact wiki search opens the derived topic page; Sources exposes the capture provenance.
            clickEnabled(By.res("memory_wiki_tab"))
            searchMemory("sapphire notebooks", By.text(sapphire))
            clickEnabled(By.text(sapphire))
            assertNotNull(find(By.text("Preferences")))
            clickEnabled(By.res("memory_sources_tab"))
            assertNotNull(scrollTo(By.textStartsWith("Captured from Text conversation")))
            clickEnabled(By.res("memory_article_back"))

            // Explicit category/topic placement can be changed later and survives the store reload.
            addManual(atlas, "projects", "Atlas")
            addManual(cobaltNotes, "knowledge", "Cobalt")
            clickEnabled(By.res("memory_review_tab"))
            assertNotNull(find(By.text("Review (2)")))
            clickEnabled(By.res("memory_approve"))
            assertNotNull(find(By.text("Review (1)")))
            clickEnabled(By.res("memory_approve"))
            assertNotNull(find(By.text("Review (0)")))
            clickEnabled(By.res("memory_wiki_tab"))
            searchMemory("atlas", By.text(atlas))
            clickEnabled(By.text(atlas))
            assertNotNull(find(By.res("memory_article_tab")))
            assertNotNull(find(By.text("Atlas")))
            clickEnabled(By.text(atlas))
            assertNotNull(find(By.text("Memory detail")))
            clickEnabled(By.res("memory_organize"))
            assertNotNull(find(By.text("Organize memory")))
            clickHorizontalChip(By.res("memory_organize_category_picker"), By.res("memory_organize_category_knowledge"))
            enterText(By.res("memory_organize_topic"), "Verified links")
            captureEvidence("memory_wiki_organize_dialog")
            clickEnabled(By.res("memory_assign"))
            assertTrue("Organize dialog did not close after saving placement", device.wait(
                Until.gone(By.res("memory_organize_topic")), 15_000
            ))
            // Assignment moved the record from Atlas to Verified links and refreshed the page index.
            clickEnabled(By.res("memory_detail_back"))
            searchMemory("atlas", By.text(atlas))
            assertNotNull(find(By.text("Verified links")))
            clickEnabled(By.text(atlas))
            assertNotNull(find(By.text("Verified links")))
            assertNotNull(scrollTo(By.text("Linked pages")))
            clickEnabled(By.text("Cobalt"))
            assertNotNull(find(By.text("Cobalt")))
            assertNotNull(scrollTo(By.text("Backlinks")))
            assertNotNull(scrollTo(By.text("Verified links")))
            captureEvidence("memory_wiki_linked_article")
            clickEnabled(By.res("memory_article_back"))

            // Corrections stay out of the index until reviewed, then supersede the old search hit.
            searchMemory("sapphire notebooks", By.text(sapphire))
            clickEnabled(By.text(sapphire))
            assertNotNull(find(By.res("memory_article_tab")))
            clickEnabled(By.text(sapphire))
            clickEnabled(By.res("memory_correct"))
            assertNotNull(find(By.text("Correct memory")))
            enterText(By.res("memory_new_content"), indigo)
            clickEnabled(By.res("memory_propose"))
            assertTrue("Correction dialog did not close after its saved proposal", device.wait(
                Until.gone(By.res("memory_new_content")), 15_000
            ))
            clickEnabled(By.res("memory_article_back"))
            approveOnlyPending()
            clickEnabled(By.res("memory_wiki_tab"))
            searchMemory("sapphire notebooks", By.text("No approved memories match that search."))
            searchMemory("indigo notebooks", By.text(indigo))
            clickEnabled(By.text(indigo))
            assertNotNull(find(By.res("memory_article_tab")))
            clickEnabled(By.text(indigo))
            assertNotNull(find(By.text("This is a correction of an earlier saved fact.")))
            val indigoId = checkNotNull(memoryOs.read().snapshot).memories.first { it.content == indigo }.id
            clickEnabled(By.res("memory_delete"))
            assertNotNull(find(By.text("Erase this memory?")))
            clickEnabled(By.res("memory_delete_cancel"))
            assertNotNull(find(By.text(indigo)))
            clickEnabled(By.res("memory_delete"))
            clickEnabled(By.res("memory_delete_confirm"))
            assertTrue("Deletion confirmation must finish before returning from a removed article", device.wait(
                Until.gone(By.text("Erase this memory?")), 15_000
            ))
            assertTrue("The deleted detail must leave the UI before optional article navigation", device.wait(
                Until.gone(By.res("memory_detail_$indigoId")), 15_000
            ))
            // A deletion can remove the page that was open. Return only when the page remains.
            device.findObject(By.res("memory_article_back"))?.click()
            enabled(By.res("memory_search_input"))
            searchMemory("indigo notebooks", By.text("No approved memories match that search."))
            searchMemory("sapphire notebooks", By.text("No approved memories match that search."))
            enterText(By.res("memory_search_input"), "")

            // Rejected pending records are retained only in History, never in the wiki index.
            addManual("Rejected private note for verification.", "knowledge", "Rejected")
            clickEnabled(By.res("memory_review_tab"))
            clickEnabled(By.res("memory_reject"))
            assertNotNull(find(By.text("Review (0)")))
            clickEnabled(By.res("memory_wiki_tab"))
            searchMemory("rejected private note", By.text("No approved memories match that search."))
            enterText(By.res("memory_search_input"), "")
            clickEnabled(By.res("memory_history_tab"))
            assertNotNull(scrollTo(By.text("Rejected private note for verification.")))
            assertNotNull(scrollTo(By.textStartsWith("Rejected ·")))

            // A fresh approved record survives Activity recreation using the same real store.
            addManual(persistent, "preferences", "Tea")
            approveOnlyPending()
            activity.recreate()
            val reloaded = SQLiteMemoryStore(file).use { checkNotNull(it.read().snapshot) }
            assertTrue("A separately opened store must retain the approved record after recreation",
                reloaded.memories.any { it.content == persistent && it.reviewStatus == MemoryReviewStatus.APPROVED })
            assertTrue("Manual category/topic organization must also survive a separate store reopen",
                reloaded.memories.any { it.content == atlas && it.wikiAssignment == MemoryWikiAssignment(WikiCategory.KNOWLEDGE, "Verified links") })
            mountMemoryWiki()
            clickEnabled(By.res("memory_wiki_tab"))
            searchMemory("persistent amber tea", By.text(persistent))
            captureEvidence("memory_wiki_reloaded_search")
            enterText(By.res("memory_search_input"), "")
            clickEnabled(By.res("memory_erase_all"))
            assertNotNull(find(By.text("Erase all memories?")))
            clickEnabled(By.text("Cancel"))
            searchMemory("persistent amber tea", By.text(persistent))
            enterText(By.res("memory_search_input"), "")
            clickEnabled(By.res("memory_erase_all"))
            clickEnabled(By.res("memory_delete_all_confirm"))
            assertTrue("Erase-all confirmation must finish before checking the ledger", device.wait(
                Until.gone(By.text("Erase all memories?")), 15_000
            ))
            clickEnabled(By.res("memory_history_tab"))
            assertNotNull(find(By.text("No memory history yet.")))
            clickEnabled(By.res("memory_wiki_tab"))
            searchMemory("persistent amber tea", By.text("No approved memories match that search."))

            // History keeps the bulk action even when no approved page exists.
            // Remounting preserves rememberSaveable state; leave the search explicitly first.
            enterText(By.res("memory_search_input"), "")
            val pendingOnly = checkNotNull(memoryOs.propose(MemoryProposal("Pending ledger-only note", MemorySource("release-pending-only", "manual", System.currentTimeMillis()))).memory)
            val rejectedOnly = checkNotNull(memoryOs.propose(MemoryProposal("Rejected ledger-only note", MemorySource("release-rejected-only", "manual", System.currentTimeMillis() + 1))).memory)
            assertEquals(MemoryOutcome.REJECTED, memoryOs.reject(rejectedOnly.id, rejectedOnly.revision).outcome)
            mountMemoryWiki()
            clickEnabled(By.res("memory_history_tab"))
            assertNotNull(scrollTo(By.text(pendingOnly.content)))
            assertNotNull(scrollTo(By.text(rejectedOnly.content)))
            clickEnabled(By.res("memory_erase_all"))
            assertNotNull(find(By.text("Erase all memories?")))
            clickEnabled(By.res("memory_delete_all_confirm"))
            assertTrue("History erase confirmation must finish", device.wait(Until.gone(By.text("Erase all memories?")), 15_000))
            assertNotNull(find(By.text("No memory history yet.")))
        } finally {
            store.close(); file.delete()
        }
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
        val forceChat = androidx.compose.runtime.mutableStateOf(false)
        val forceChatConsumed = AtomicInteger(0)
        val forceChatAcknowledged = java.util.concurrent.CountDownLatch(1)
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
                        forceChatDestination = forceChat.value,
                        onForceChatConsumed = {
                            forceChatConsumed.incrementAndGet()
                            forceChat.value = false
                            forceChatAcknowledged.countDown()
                        },
                        voiceContent = { visible, _, _, _ ->
                            if (visible) VoiceCallOverlay.Bubble(
                                phase = "Listening",
                                status = VoiceSessionUi.status.value.ifBlank { "Voice Call is listening" },
                                level = 0.2f,
                                active = true,
                                microphonePaused = false,
                                canStart = false,
                                stopReplyAvailable = false,
                                onStart = {},
                                onStopReply = {},
                                onToggleMicrophone = {},
                                onEndCall = { ends.incrementAndGet() },
                                transcriptSpeaker = "",
                                transcript = ""
                            )
                        },
                    )
                    }
                }
            } }
            VoiceSessionUi.status.value = "Voice Call is listening — controlled fixture with a deliberately long status that must not hide End call on a narrow screen."
            VoiceSessionUi.armed.value = true
            assertNotNull(find(By.res("voice_call_overlay")))
            device.pressBack()
            device.waitForIdle()
            assertEquals("Back must not end the active call", 0, ends.get())
            assertNotNull("Back keeps the floating call bubble over chat", find(By.res("voice_call_overlay")))
            assertNotNull(find(By.res("voice_call_status")))
            val endBounds = find(By.res("voice_call_end")).visibleBounds
            assertTrue("End call must remain visible beside a long status", endBounds.width() > 0 && endBounds.right <= device.displayWidth)
            assertFalse("Unified chat has no mode tabs", device.hasObject(By.res("voice_tab")))
            assertEquals("Unified chat has no mode tabs", false, device.hasObject(By.res("chat_tab")))
            find(By.res("voice_call_open")).click()
            assertNotNull(find(By.res("voice_call_overlay")))
            device.pressBack()
            device.waitForIdle()
            assertEquals("Back leaves the call bubble open without ending the call", 0, ends.get())
            assertNotNull(find(By.res("voice_call_overlay")))
            activity.onActivity { forceChat.value = true }
            // These views already exist in unified chat; wait for the new request itself.
            assertTrue("Memory return must be consumed before checking the shared call surface",
                forceChatAcknowledged.await(15, java.util.concurrent.TimeUnit.SECONDS))
            assertNotNull(find(By.res("chat_composer")))
            assertTrue(device.wait(Until.hasObject(By.res("voice_call_overlay")), 15_000))
            assertEquals("Returning from Memory preserves the active overlay", 1, forceChatConsumed.get())
            assertEquals(0, ends.get())
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

            // Mount the shipping parent with an isolated, explicitly seeded ready-state store.
            // Its inert callbacks deliberately avoid native model, microphone, and download work.
            val fixtureRoot = File(context.cacheDir, "release-jarvis-app-memory-route").apply { deleteRecursively(); mkdirs() }
            val fixtureContext = object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = File(fixtureRoot, "files").apply { mkdirs() }
                override fun getCacheDir(): File = File(fixtureRoot, "cache").apply { mkdirs() }
                override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("release-jarvis-app-$name", mode)
            }
            val fixtureStore = ModelStore(fixtureContext)
            val fixtureSpec = ModelCatalog.gemma4E2b
            val fixturePrefs = fixtureContext.getSharedPreferences("model_setup", Context.MODE_PRIVATE)
            fixturePrefs.edit().clear().commit()
            val fixtureFile = fixtureStore.fileFor(fixtureSpec).apply { parentFile?.mkdirs(); writeText("ready-state fixture") }
            fixturePrefs.edit()
                .putString("selected_model", fixtureSpec.id)
                .putString("sha256_${fixtureSpec.id}", "ready-state-fixture")
                .putLong("sha256_${fixtureSpec.id}_length", fixtureFile.length())
                .putLong("sha256_${fixtureSpec.id}_modified", fixtureFile.lastModified())
                .putBoolean("sha256_${fixtureSpec.id}_invalid", false)
                .putBoolean("smoke_test_passed_${fixtureSpec.id}", true)
                .commit()
            assertTrue("The isolated fixture must reach JarvisApp's ready branch", fixtureStore.isUsable() && fixtureStore.smokeTestPassed())
            val appHistory = ConversationHistory(fixtureContext.getSharedPreferences("conversation-history", Context.MODE_PRIVATE))
            val combinedTaskFile = File(fixtureRoot, "combined-tasks.json")
            val combinedTaskStore = FileToolTaskStore(combinedTaskFile)
            val combinedLedger = ToolTaskLedger(combinedTaskStore)
            val combinedApprovals = ActionApprovalStore(combinedTaskStore)
            val combinedGroup = combinedLedger.admit(listOf(ActionRequest("set_volume", mapOf("level" to "25"))),
                appHistory.current.value.id, ToolAuthority.EXACT_APPROVAL)
            val combinedChoice = ActionDispatchGate(combinedApprovals, combinedLedger)
                .prepare(checkNotNull(combinedLedger.get(combinedGroup.attemptIds.single())))
            val combinedJournal = MutableStateFlow<ToolTaskJournal?>(combinedLedger.journal())
            val callsBeforeRoute = ends.get()
            activity.onActivity { host -> host.setContent {
                MaterialTheme { Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    JarvisApp(
                        store = fixtureStore, conversationHistory = appHistory, chatBusy = busy, callState = callState,
                        onSendChat = { _, _ -> null }, onSelectConversation = { null }, onSelectModel = { null }, onDeleteModel = { null },
                        voicePlayback = MutableStateFlow(VoicePlaybackFrame()), voiceModelStore = TtsModelStore(fixtureContext),
                        phoneTasks = combinedJournal,
                        onPhoneTaskAction = { id, generation, command ->
                            val attempt = combinedLedger.get(id)?.takeIf { it.generation == generation }
                            if (command == "deny") attempt?.approvalId?.let { combinedApprovals.deny(it) }
                            combinedJournal.value = combinedLedger.journal()
                        },
                        initialVoiceCalls = emptyList(), onRunModelSmokeTest = { done -> done("Ready-state fixture") },
                        onVoiceTurn = { _, _, _, done -> done("Voice is disabled in this route fixture.") },
                        onWakeTest = { _, done -> done() }, onStopWakeTest = {},
                        onEndVoiceCall = { done -> ends.incrementAndGet(); done("") },
                        onResumeVoiceCall = { _, done -> done(null) }, onDeleteVoiceCall = {}, onRefreshVoiceCalls = { emptyList() },
                        onDownloadGemma = { _, _, _, done -> done("Downloads are disabled in this route fixture.") },
                        onCancelModelDownload = {},
                        onImportModel = { _, _, done -> done("Imports are disabled in this route fixture.") },
                        onCopyDiagnostics = {}, onExportSpeechAudio = {},
                    )
                } }
            } }
            VoiceSessionUi.armed.value = true
            assertNotNull(find(By.res("voice_call_overlay")))
            assertNotNull(find(By.res("voice_call_status")))
            clickEnabled(By.res("memory_open"))
            assertNotNull(find(By.text("Memory")))
            clickEnabled(By.res("memory_nav_chat"))
            assertNotNull(find(By.res("chat_composer")))
            assertEquals("The parent Memory-to-Chat route must preserve an active call", callsBeforeRoute, ends.get())
            clickEnabled(By.res("phone_tasks_open"))
            assertNotNull(find(By.text("Set media volume to 25%")))
            assertNotNull(find(By.text("Waiting for your approval")))
            clickEnabled(By.res("task_deny_${combinedChoice.task.id}"))
            assertEquals(ToolTaskState.CANCELLED, combinedLedger.get(combinedChoice.task.id)?.state)
            clickEnabled(By.text("Done"))
            assertNotNull(find(By.res("voice_call_overlay")))
            assertEquals("Task approval UI must not end the active call", callsBeforeRoute, ends.get())
            assertNotNull(find(By.res("voice_call_overlay")))
            clickEnabled(By.res("memory_open"))
            clickEnabled(By.res("memory_nav_voice"))
            assertNotNull(find(By.res("voice_call_overlay")))
            assertNotNull("Voice and Chat now share the same transcript", find(By.res("chat_composer")))
            assertEquals("Memory-to-Voice must preserve the same call", callsBeforeRoute, ends.get())
            VoiceSessionUi.armed.value = false
            fixtureRoot.deleteRecursively()

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

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun test30_phoneCallOverlaysUnifiedChatAndKeepsVoiceNoteMic() {
        val prefs = context.getSharedPreferences("release-call-overlay", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val history = ConversationHistory(prefs)
        repeat(20) { history.updateReply(history.current.value.id, "swipe-$it", "Scrollable message $it", true) }
        val busy = MutableStateFlow(false)
        val ends = AtomicInteger(0)
        val liveCaption = "Live speech appears without minimizing"
        try {
            VoiceSessionUi.armed.value = false
            VoiceSessionUi.status.value = ""
            VoiceSessionUi.phase.value = VoicePhase.IDLE
            VoiceSessionUi.liveTranscript.value = ""
            activity.onActivity { host -> host.setContent {
                val callArmed by VoiceSessionUi.armed.collectAsState()
                MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) { Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    ConversationScreen(history, busy, MutableStateFlow(VoiceSessionState.ACTIVELY_LISTENING),
                        onSend = { _, _ -> null },
                        selectedModel = LocalModelSpec("release-fixture", "release-fixture.bin", recommendedGpu = false),
                        onSelectConversation = { null }, onEndVoice = { done -> ends.incrementAndGet(); done("") },
                        onOpenVoiceCalls = {}, resumedVoice = false,
                        voiceContent = { visible, _, _, _ ->
                            if (visible) VoiceCallOverlay.Bubble(
                                phase = "Listening",
                                status = VoiceSessionUi.status.value.ifBlank { "Voice Call is listening" },
                                transcriptSpeaker = "You",
                                transcript = liveCaption,
                                level = VoiceSessionUi.level.value,
                                active = callArmed,
                                microphonePaused = VoiceSessionUi.paused.value,
                                canStart = !callArmed,
                                stopReplyAvailable = false,
                                onStart = {},
                                onStopReply = {},
                                onToggleMicrophone = {},
                                onEndCall = { ends.incrementAndGet() }
                            )
                        })
                } }
            } }
            enterText(By.res("chat_composer"), "Keep my draft")
            hideKeyboardWithoutNavigating() // Keep the activity alive when no IME was opened.
            device.waitForIdle()
            assertNotNull(find(By.res("chat_voice_input")))
            busy.value = true
            assertFalse(waitUntilDisabled(By.res("voice_call_open")).isEnabled)
            busy.value = false
            enabled(By.res("voice_call_open"))
            find(By.res("voice_call_open")).click()
            assertNotNull("Voice button opens the in-window waveform bubble", find(By.res("voice_call_overlay")))
            assertNotNull(find(By.res("voice_start")))
            assertNotNull("The separate dictation microphone stays in the composer", find(By.res("chat_voice_input")))
            VoiceSessionUi.status.value = "Voice Call is listening · live transcript fixture"
            VoiceSessionUi.phase.value = VoicePhase.LISTENING
            VoiceSessionUi.armed.value = true
            activity.onActivity {
                history.updateReply(history.current.value.id, "streaming-voice", "Transcript is updating while I speak", false)
            }
            VoiceSessionUi.liveTranscript.value = liveCaption
            assertEquals("Live speech appears without minimizing", find(By.res("voice_call_live_transcript")).text)
            assertNotNull(find(By.res("voice_call_status")))
            assertNotNull(find(By.res("voice_call_orb")))
            assertNotNull(find(By.res("voice_call_end")))
            assertNotNull(find(By.res("voice_call_pause")))
            assertFalse("A minimize control is not part of the call overlay", device.hasObject(By.res("voice_call_minimize")))
            val liveTranscript = find(By.text("Transcript is updating while I speak"))
            val overlayBounds = find(By.res("voice_call_overlay")).visibleBounds
            val transcriptBounds = find(By.res("conversation_transcript")).visibleBounds
            assertTrue("Waveform bubble must be visible in the chat window", overlayBounds.width() > 0 && overlayBounds.height() > 0)
            assertTrue("The floating orb must leave most chat width clear", overlayBounds.width() < transcriptBounds.width() / 2)
            assertFalse("Live text must remain outside the floating controls", android.graphics.Rect.intersects(
                find(By.res("voice_call_live_transcript")).visibleBounds, overlayBounds))
            assertTrue("The conversation viewport remains on screen under the bubble", transcriptBounds.width() > 0 && transcriptBounds.height() > 0)
            assertTrue("The live chat transcript remains visible with the bubble", liveTranscript.visibleBounds.width() > 0)
            assertTrue("The existing draft survives opening the bubble", find(By.res("chat_composer")).text.contains("Keep my draft"))
            assertEquals("Keeping the bubble open must not end the call", 0, ends.get())
            find(By.res("voice_call_open")).click()
            assertNotNull(find(By.res("voice_call_overlay")))
            assertEquals(0, ends.get())
            captureEvidence("test30_activeVoiceOrb")
        } finally {
            VoiceSessionUi.armed.value = false
            VoiceSessionUi.status.value = ""
            VoiceSessionUi.phase.value = VoicePhase.IDLE
            VoiceSessionUi.liveTranscript.value = ""
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun test31_voiceInputReviewsTextAndCancelPreservesDraft() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.RECORD_AUDIO)
        val prefs = context.getSharedPreferences("release-chat-dictation", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val history = ConversationHistory(prefs)
        val sends = AtomicInteger(0)
        val sessions = AtomicInteger(0)
        val transcribes = AtomicInteger(0)
        val textOnlyModel = LocalModelSpec("text-only-fixture", "fixture.bin", recommendedGpu = false)
        val audioSent = java.util.concurrent.atomic.AtomicReference<com.battlesbudz.jarvis.v2.chat.ChatAttachment?>()
        val rawPcm = ByteArray(3200) { (it % 127).toByte() }
        val cancellations = AtomicInteger(0)
        VoiceSessionUi.armed.value = false
        fun renderModel(model: LocalModelSpec) {
        activity.onActivity { host -> host.setContent {
            MaterialTheme { Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                ConversationScreen(history, MutableStateFlow(false), MutableStateFlow(VoiceSessionState.PASSIVE_LISTENING),
                    onSend = { text, attachment ->
                        history.appendUser(text, attachment)
                        audioSent.set(attachment); sends.incrementAndGet(); null },
                    selectedModel = model,
                    onSelectConversation = { null }, onEndVoice = {}, onOpenVoiceCalls = {}, resumedVoice = false,
                    dictationFactory = {
                        val number = sessions.incrementAndGet()
                        object : ChatDictation {
                            val result = CompletableDeferred<ByteArray>()
                            override suspend fun record(onStatus: (String) -> Unit): ByteArray {
                                onStatus("Recording controlled speech")
                                try { return result.await() }
                                catch (cancelled: kotlinx.coroutines.CancellationException) { cancellations.incrementAndGet(); throw cancelled }
                            }
                            override fun finish() { result.complete(rawPcm) }
                            override suspend fun transcribe(pcm: ByteArray, onStatus: (String) -> Unit): String {
                                transcribes.incrementAndGet()
                                if (number == 3 || number == 5) error("No speech detected. Try recording again.")
                                return "dictated words"
                            }
                        }
                    }, voiceContent = { _, _, _, _ -> })
            } }
        } }
        }
        renderModel(textOnlyModel)
        assertFalse(device.hasObject(By.text("Attach audio")))
        enterText(By.res("chat_composer"), "Existing draft")
        hideKeyboardWithoutNavigating()
        clickEnabled(By.res("chat_voice_input"))
        assertNotNull(find(By.res("dictation_status")))
        assertFalse("The recording panel replaces the composer, including the call button",
            device.hasObject(By.res("voice_call_open")))
        assertFalse("Text-only model must not receive raw audio", find(By.res("dictation_send")).isEnabled)
        assertFalse("Recording replaces the text-send controls", device.hasObject(By.res("chat_send")))
        clickEnabled(By.res("dictation_stop"))
        enabled(By.res("chat_voice_input"))
        enabled(By.res("voice_call_open"))
        assertEquals("Existing draft dictated words", find(By.res("chat_composer")).text)
        assertEquals("Dictation must not send a message", 0, sends.get())
        clickEnabled(By.res("chat_voice_input"))
        assertNotNull(find(By.res("dictation_status")))
        clickEnabled(By.res("dictation_cancel"))
        enabled(By.res("chat_voice_input"))
        enabled(By.res("voice_call_open"))
        assertEquals(1, cancellations.get())
        assertEquals("Existing draft dictated words", find(By.res("chat_composer")).text)
        clickEnabled(By.res("chat_voice_input"))
        assertNotNull(find(By.res("dictation_status")))
        clickEnabled(By.res("dictation_stop"))
        assertNotNull(find(By.text("No speech detected. Try recording again.")))
        assertEquals("Existing draft dictated words", find(By.res("chat_composer")).text)
        assertEquals(0, sends.get())
        enterText(By.res("chat_composer"), "Reviewed and edited")
        clickEnabled(By.res("chat_send"))
        assertEquals(1, sends.get())
        assertNull(audioSent.get())
        renderModel(textOnlyModel.copy(supportsAudio = true))
        clickEnabled(By.res("chat_voice_input"))
        enabled(By.res("dictation_send"))
        captureEvidence("voice_recording_controls")
        val beforeAudioSend = transcribes.get()
        clickEnabled(By.res("dictation_send"))
        enabled(By.res("chat_voice_input"))
        assertEquals(2, sends.get())
        assertEquals("Send must include a transcript", beforeAudioSend + 1, transcribes.get())
        val saved = history.current.value.messages.last()
        assertEquals("dictated words", saved.text)
        assertTrue(saved.spoken)
        assertNotNull(saved.attachment)
        val reloaded = ConversationHistory(prefs).current.value.messages.last()
        assertEquals(saved.text, reloaded.text)
        assertEquals(saved.attachment, reloaded.attachment)
        assertNotNull(find(By.desc("Play voice message")))
        val attachment = requireNotNull(audioSent.get())
        assertEquals(com.battlesbudz.jarvis.v2.chat.AttachmentKind.AUDIO, attachment.kind)
        val wav = File(requireNotNull(android.net.Uri.parse(attachment.uri).path)).readBytes()
        com.battlesbudz.jarvis.v2.chat.AttachmentPolicy.validateAudio(wav)
        assertArrayEquals("Audio payload must preserve the recording", rawPcm, wav.copyOfRange(44, wav.size))
        captureEvidence("voice_message_with_transcript")
        enterText(By.res("chat_composer"), "Keep my draft")
        hideKeyboardWithoutNavigating()
        clickEnabled(By.res("chat_voice_input"))
        clickEnabled(By.res("dictation_send"))
        assertNotNull(find(By.text("No speech detected. Try recording again.")))
        enabled(By.res("chat_voice_input"))
        assertEquals("Keep my draft", find(By.res("chat_composer")).text)
        assertEquals("Failed Send transcription must not send", 2, sends.get())
        com.battlesbudz.jarvis.v2.chat.ChatMediaStore.discard(context, attachment)
    }

    @Test fun test32_installedModelAndCompletedDownloadSurviveStoreRecreation() = runBlocking {
        val bytes = ByteArray(4097) { (it % 251).toByte() }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val spec = LocalModelSpec(
            id = "verification-storage", fileName = "verification-storage.litertlm",
            expectedSha256 = hash, recommendedGpu = false, downloadBytes = bytes.size.toLong(),
            downloadUrl = "https://127.0.0.1:9/must-not-download.litertlm"
        )
        val store = com.battlesbudz.jarvis.v2.ai.ModelStore(context)
        val file = store.fileFor(spec)
        val preferences = context.getSharedPreferences("model_setup", android.content.Context.MODE_PRIVATE)
        val key = "sha256_${spec.id}"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, spec.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
        }
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        try {
            checkNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
            assertTrue(store.importModel(uri, spec).isSuccess)
            assertTrue(store.hasModel(spec))
            assertTrue(com.battlesbudz.jarvis.v2.ai.ModelStore(context).isUsable(spec))
            // Lose only verification metadata, as can happen on upgrade/restore.
            preferences.edit().remove(key).remove("${key}_length").remove("${key}_modified").commit()
            val restored = com.battlesbudz.jarvis.v2.ai.ModelStore(context)
            assertTrue(restored.hasModel(spec))
            assertFalse(restored.isUsable(spec))
            assertTrue(restored.verifyIntegrity(spec))
            assertTrue(restored.isUsable(spec))
            assertTrue(restored.downloadOrReuse(spec).isSuccess)
            assertArrayEquals(bytes, file.readBytes())
            // Corrupt installed bytes must never be accepted as usable.
            file.writeBytes(ByteArray(bytes.size))
            preferences.edit().remove("${key}_modified").commit()
            assertFalse(restored.verifyIntegrity(spec))
            assertFalse(restored.isUsable(spec))
            // A fully downloaded checkpoint installs without a network request.
            file.delete()
            File(file.parentFile, "${spec.fileName}.part").writeBytes(bytes)
            context.contentResolver.delete(uri, null, null)
            // Simulate the actual voice owner's native lease. Installing another
            // model must neither reject the transfer nor release that lease.
            val selectedId = restored.selectedModel().id
            assertTrue(restored.tryBeginModelOperation())
            try {
                assertTrue(restored.downloadOrReuse(spec).isSuccess)
                assertTrue("Download completion must preserve the voice owner's lock", restored.isModelOperationActive())
                assertFalse(restored.tryBeginModelOperation())
                assertEquals(selectedId, restored.selectedModel().id)
            } finally { restored.endModelOperation() }
            assertFalse(restored.isModelOperationActive())
            assertTrue(restored.downloadOrReuse(spec).isSuccess)
            assertTrue(com.battlesbudz.jarvis.v2.ai.ModelStore(context).isUsable(spec))
            assertArrayEquals(bytes, file.readBytes())
        } finally {
            context.contentResolver.delete(uri, null, null)
            file.delete()
            File(file.parentFile, "${spec.fileName}.part").delete()
            val edit = preferences.edit()
            preferences.all.keys.filter { it.contains(spec.id) }.forEach { edit.remove(it) }
            edit.commit()
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun test33_downloadDoesNotSelectOrDismissCurrentModel() {
        val selectedId = com.battlesbudz.jarvis.v2.ai.ModelStore(context).selectedModel().id
        val requested = AtomicReference<String?>()
        val choices = AtomicInteger(0)
        val dismissals = AtomicInteger(0)
        activity.onActivity { host -> host.setContent {
            MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                com.battlesbudz.jarvis.v2.ui.ModelBrowser(
                    phone = com.battlesbudz.jarvis.v2.ai.PhoneProfile("Release fixture", "Fixture", "Fixture",
                        12_000_000_000L, 8_000_000_000L, 20_000_000_000L, true),
                    selectedId = selectedId, isInstalled = { it.id == selectedId },
                    onSelect = { choices.incrementAndGet(); null },
                    onDismiss = { dismissals.incrementAndGet() },
                    onDownload = { requested.set(it.id) })
            }
        } }
        enterText(By.res("model_search"), "LFM2.5-230M")
        hideKeyboardWithoutNavigating()
        find(By.res("model_family_Liquid · LFM")).click()
        scrollTo(By.res("model_download_LFM2.5-230M")).click()
        assertEquals("LFM2.5-230M", requested.get())
        assertEquals("Download must not choose the requested model", 0, choices.get())
        assertEquals("Download must keep the browser available", 0, dismissals.get())
        assertEquals(selectedId, com.battlesbudz.jarvis.v2.ai.ModelStore(context).selectedModel().id)
        scrollTo(By.res("model_choose_LFM2.5-230M")).click()
        assertEquals("Choosing remains a separate explicit action", 1, choices.get())
        assertEquals(1, dismissals.get())
    }

    @Test fun test34_sqliteMigrationPreservesHistoryAndEraseAcrossReopen() {
        val root = File(context.cacheDir, "release-sqlite-migration").apply { deleteRecursively(); mkdirs() }
        val legacy = File(root, "memory-os.json")
        val database = File(root, "memory-os.db")
        val now = System.currentTimeMillis()
        try {
            val original = MemoryOs(legacy) { now }
            val approved = original.propose(MemoryProposal("I prefer azure mugs", MemorySource("migration-approved", "manual", now))).memory!!
            original.approve(approved.id)
            val replacement = original.propose(MemoryProposal("I prefer jade mugs", MemorySource("migration-correction", "manual", now), correctsMemoryId = approved.id)).memory!!
            original.approve(replacement.id)
            val erasedProposal = MemoryProposal("An erased orchid note", MemorySource("migration-erased", "manual", now))
            val erased = original.propose(erasedProposal).memory!!
            original.delete(erased.id)
            original.propose(MemoryProposal("Pending quartz note", MemorySource("migration-pending", "manual", now)))
            val expected = checkNotNull(original.read().snapshot)
            // Exercise the actual schema-1 migration path too, including optional older fields.
            val json = org.json.JSONObject(legacy.readText()).apply { put("schemaVersion", 1) }
            legacy.writeText(json.toString())
            val oldBytes = legacy.readBytes()
            SQLiteMemoryStore(database, legacy).use { storage ->
                assertEquals(expected, storage.read().snapshot)
                assertFalse("Retire the JSON only after a validated SQLite commit", legacy.exists())
                val os = MemoryOs(storage) { now }
                assertTrue(os.contextPacket("mugs", 900).packet!!.text.contains("jade mugs"))
                assertFalse(os.contextPacket("mugs", 900).packet!!.text.contains("azure mugs"))
                assertEquals(MemoryOutcome.DELETED, os.propose(erasedProposal).outcome)
                assertEquals(MemoryOutcome.DELETED, os.delete(replacement.id).outcome)
            }
            // Simulate a stale pre-migration file surviving a crash: never import it twice.
            legacy.writeBytes(oldBytes)
            SQLiteMemoryStore(database, legacy).use { storage ->
                val os = MemoryOs(storage) { now }
                assertNotNull(os.read().snapshot)
                assertFalse(legacy.exists())
                assertTrue(os.contextPacket("mugs", 900).packet!!.memories.isEmpty())
                assertEquals(MemoryOutcome.DELETED, os.propose(erasedProposal).outcome)
                assertEquals(1, storage.read().snapshot!!.memories.size)
                assertEquals(3, storage.read().snapshot!!.tombstones.size)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun test35_sqliteCorruptMigrationAndFailedEraseDoNotLoseData() {
        val root = File(context.cacheDir, "release-sqlite-failure").apply { deleteRecursively(); mkdirs() }
        val legacy = File(root, "memory-os.json")
        val database = File(root, "memory-os.db")
        val now = System.currentTimeMillis()
        try {
            legacy.writeText("{broken")
            SQLiteMemoryStore(database, legacy).use { storage ->
                assertNotNull(storage.read().error)
                assertNull(storage.read().snapshot)
                assertEquals("{broken", legacy.readText())
            }
            // Failed migration rolls back schema/data too; repair the source and retry safely.
            legacy.delete()
            val proposal = MemoryProposal("I prefer silver spoons", MemorySource("sqlite-failure-original", "manual", now))
            val source = MemoryOs(legacy) { now }
            val record = source.propose(proposal).memory!!
            source.approve(record.id)
            SQLiteMemoryStore(database, legacy).use { storage ->
                val os = MemoryOs(storage) { now }
                val before = checkNotNull(os.read().snapshot)
                android.database.sqlite.SQLiteDatabase.openDatabase(database.path, null, 0).use { db ->
                    db.execSQL("CREATE TRIGGER reject_erasure BEFORE INSERT ON tombstones BEGIN SELECT RAISE(ABORT, 'injected write failure'); END")
                }
                assertEquals(MemoryOutcome.STORAGE_FAILURE, os.delete(record.id).outcome)
                assertEquals("Rows and generation must roll back together", before, os.read().snapshot)
                assertTrue(os.contextPacket("spoons", 900).packet!!.text.contains("silver spoons"))
                android.database.sqlite.SQLiteDatabase.openDatabase(database.path, null, 0).use { db -> db.execSQL("DROP TRIGGER reject_erasure") }
                assertEquals(MemoryOutcome.DELETED, os.delete(record.id).outcome)
            }
            SQLiteMemoryStore(database).use { storage ->
                assertTrue(storage.read().snapshot!!.memories.isEmpty())
                assertEquals(MemoryOutcome.DELETED, MemoryOs(storage) { now }.propose(proposal).outcome)
            }
            // A future DB version must never be reset or treated as an empty ledger.
            android.database.sqlite.SQLiteDatabase.openDatabase(database.path, null, 0).use { it.version = 99 }
            SQLiteMemoryStore(database).use { storage -> assertNotNull(storage.read().error) }
            android.database.sqlite.SQLiteDatabase.openDatabase(database.path, null, 0).use { assertEquals(99, it.version) }
        } finally { root.deleteRecursively() }
    }

    @Test fun test36_sqliteExceedsLegacyByteLimitAndSerializesSeparateWriters() {
        val root = File(context.cacheDir, "release-sqlite-capacity").apply { deleteRecursively(); mkdirs() }
        val database = File(root, "memory-os.db")
        val now = System.currentTimeMillis()
        try {
            SQLiteMemoryStore(database).use { storage ->
                val seed = MemoryOs(storage) { now }.propose(MemoryProposal("Capacity seed", MemorySource("capacity-seed", "manual", now))).memory!!
                // Controlled valid rows isolate the storage ceiling without 450 extraction calls.
                val result = storage.update { before ->
                    val records = (1..450).map { index -> seed.copy(id = java.util.UUID.randomUUID().toString(), content = "Capacity row $index " + "q".repeat(1950), source = seed.source.copy(eventId = java.util.UUID.randomUUID().toString().replace("-", "")), payloadFingerprint = null) }
                    before.copy(generation = before.generation + 1, memories = before.memories + records) to records.size
                }
                assertEquals(450, result.value)
                android.database.sqlite.SQLiteDatabase.openDatabase(database.path, null, 0).use { db ->
                    db.rawQuery("SELECT SUM(length(payload)) FROM memories", null).use { cursor -> cursor.moveToFirst(); assertTrue(cursor.getLong(0) > MemoryStore.MAX_STORE_BYTES) }
                }
            }
            val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
            try {
                val futures = (1..8).map { index -> pool.submit<MemoryOutcome> {
                    SQLiteMemoryStore(database).use { storage -> MemoryOs(storage) { now }.propose(MemoryProposal("Concurrent writer $index", MemorySource("writer-$index", "manual", now))).outcome }
                } }
                futures.forEach { assertEquals(MemoryOutcome.CREATED, it.get(60, java.util.concurrent.TimeUnit.SECONDS)) }
            } finally { pool.shutdownNow() }
            SQLiteMemoryStore(database).use { storage ->
                assertEquals(459, storage.read().snapshot!!.memories.size)
                assertEquals(10L, storage.read().snapshot!!.generation)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun test37_sourceArchiveRetainsExplicitHistoryAndExpiresWithoutFactLoss() {
        val root = File(context.cacheDir, "release-source-archive").apply { deleteRecursively(); mkdirs() }
        val database = File(root, "memory-os.db")
        var now = System.currentTimeMillis()
        val capturedAt = now
        var unlocked = true
        fun input(id: String, text: String) = FinalMemoryInput(id, "archive-conversation", "archive-call", ConversationMemorySource.TEXT, text, capturedAt)
        val removed = input("archive-removed", "I prefer silver spoons")
        try {
            SQLiteMemoryStore(database, canReadSourceText = { unlocked }, archiveClock = { now }).use { storage ->
                val os = MemoryOs(storage) { now }
                val bridge = ConversationMemory(os, storage)
                assertEquals(ConversationMemoryOutcome.IGNORED, bridge.capture(input("archive-episode", "We discussed sapphire notebook delivery yesterday")).outcome)
                assertTrue(os.contextPacket("sapphire", 900).packet!!.memories.isEmpty())
                assertEquals(1, storage.searchExplicitHistory("sapphire").episodes.size)
                assertTrue(storage.searchExplicitHistory("%").episodes.isEmpty())
                val fact = bridge.capture(removed).memory!!
                os.approve(fact.id)
                os.delete(fact.id)
                assertTrue(os.contextPacket("spoons", 900).packet!!.memories.isEmpty())
                assertEquals(1, storage.searchExplicitHistory("spoons").episodes.size)
                bridge.capture(removed)
                assertTrue("Retained source must not recreate an erased fact", os.read().snapshot!!.memories.isEmpty())
                val retained = bridge.capture(input("archive-retained", "I prefer jade mugs")).memory!!
                os.approve(retained.id)
                unlocked = false
                assertEquals(SourceArchiveOutcome.LOCKED, storage.searchExplicitHistory("jade").outcome)
                assertTrue(storage.searchExplicitHistory("jade").episodes.isEmpty())
                unlocked = true
                now = capturedAt + com.battlesbudz.jarvis.v2.memory.MemoryArchivePolicy.RETENTION_MS - 1
                assertEquals(1, storage.searchExplicitHistory("jade").episodes.size)
                now++
                assertTrue(storage.searchExplicitHistory("jade").episodes.isEmpty())
                assertTrue(os.contextPacket("mugs", 900).packet!!.text.contains("jade mugs"))
                assertEquals(SourceArchiveOutcome.EXPIRED, storage.captureSource(removed.copy(capturedAtMs = now)).outcome)
                android.database.sqlite.SQLiteDatabase.openDatabase(database.path, null, 0).use { db ->
                    db.rawQuery("SELECT count(*), sum(text_bytes), count(text) FROM source_events", null).use {
                        assertTrue(it.moveToFirst()); assertEquals(3, it.getInt(0)); assertEquals(0, it.getInt(1)); assertEquals(0, it.getInt(2))
                    }
                }
            }
            SQLiteMemoryStore(database, canReadSourceText = { true }, archiveClock = { now }).use { storage ->
                assertTrue(storage.searchExplicitHistory("sapphire").episodes.isEmpty())
                assertTrue(MemoryOs(storage) { now }.contextPacket("mugs", 900).packet!!.text.contains("jade mugs"))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun test38_sourceArchiveUpgradesV1RejectsSecretsAndRollsBackFailure() {
        val root = File(context.cacheDir, "release-source-archive-failure").apply { deleteRecursively(); mkdirs() }
        val database = File(root, "memory-os.db")
        val now = System.currentTimeMillis()
        fun input(id: String, text: String) = FinalMemoryInput(id, "archive-thread", null, ConversationMemorySource.TEXT, text, now)
        try {
            val before = SQLiteMemoryStore(database).use { storage ->
                val os = MemoryOs(storage) { now }
                val fact = os.propose(MemoryProposal("I prefer copper mugs", MemorySource("archive-v1-fact", "manual", now))).memory!!
                os.approve(fact.id); os.read().snapshot!!
            }
            // Restore the exact v1 table layout to exercise the additive upgrade.
            android.database.sqlite.SQLiteDatabase.openDatabase(database.path, null, 0).use { db -> db.execSQL("DROP TABLE source_events"); db.version = 1 }
            SQLiteMemoryStore(database, canReadSourceText = { true }, archiveClock = { now }).use { storage ->
                assertEquals(before, storage.read().snapshot)
                assertEquals(SourceArchiveOutcome.STORED, storage.captureSource(input("health", "My doctor appointment is Monday")).outcome)
                assertEquals(SourceArchiveOutcome.ALREADY_RECORDED, storage.captureSource(input("health", "My doctor appointment is Monday")).outcome)
                assertEquals(SourceArchiveOutcome.CONFLICT, storage.captureSource(input("health", "My doctor appointment is Tuesday")).outcome)
                assertEquals(SourceArchiveOutcome.STORED, storage.captureSource(input("financial", "My bank balance is $500").copy(source = ConversationMemorySource.VOICE)).outcome)
                assertEquals(ConversationMemorySource.VOICE, storage.searchExplicitHistory("bank balance").episodes.single().source)
                listOf("PIN: 1234", "My password is x", "Card number 4111 1111 1111 1111", "x".repeat(4_000) + " password: hunter22").forEachIndexed { index, text ->
                    assertEquals(SourceArchiveOutcome.EXCLUDED, storage.captureSource(input("secret-$index", text)).outcome)
                }
                assertEquals(SourceArchiveOutcome.IGNORED, storage.captureSource(input("draft", "tea").copy(complete = false)).outcome)
                android.database.sqlite.SQLiteDatabase.openDatabase(database.path, null, 0).use { db ->
                    assertEquals(2, db.version)
                    db.execSQL("CREATE TRIGGER reject_source BEFORE INSERT ON source_events BEGIN SELECT RAISE(ABORT, 'injected archive failure'); END")
                }
                assertEquals(SourceArchiveOutcome.STORAGE_FAILURE, storage.captureSource(input("failed", "We discussed amber notebooks")).outcome)
                assertEquals(before, storage.read().snapshot)
                assertTrue(storage.searchExplicitHistory("amber").episodes.isEmpty())
                assertEquals(1, storage.searchExplicitHistory("Monday").episodes.size)
                android.database.sqlite.SQLiteDatabase.openDatabase(database.path, null, 0).use { db ->
                    db.rawQuery("SELECT count(*) FROM source_events", null).use { assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)) }
                    db.execSQL("DROP TRIGGER reject_source")
                }
                assertEquals(SourceArchiveOutcome.STORED, storage.captureSource(input("failed", "We discussed amber notebooks")).outcome)
            }
            var reads = 0
            SQLiteMemoryStore(database, canReadSourceText = { ++reads == 1 }, archiveClock = { now }).use { storage ->
                assertEquals(SourceArchiveOutcome.LOCKED, storage.searchExplicitHistory("amber").outcome)
            }
            SQLiteMemoryStore(database).use { assertEquals(SourceArchiveOutcome.LOCKED, it.searchExplicitHistory("amber").outcome) }
        } finally { root.deleteRecursively() }
    }

    @Test fun test39_referencePdfExtractionAndPendingAcknowledgmentUseReleaseCode() {
        val bytes = instrumentation.context.assets.open("reference-fixture.pdf").use { it.readBytes() }
        val text = com.battlesbudz.jarvis.v2.ai.ReferencePdfText.read(context, bytes)
        assertTrue(text.contains("Fermented plant juice (FPJ)"))
        assertTrue(text.contains("brown sugar"))
        try {
            com.battlesbudz.jarvis.v2.ai.ReferencePdfText.read(context, "not a PDF".toByteArray())
            fail("Malformed PDF must not become reference evidence")
        } catch (_: java.io.IOException) { }
        val root = File(context.cacheDir, "ack-receipt-${System.nanoTime()}").apply { mkdirs() }
        try {
            val os = MemoryOs(File(root, "memory.json"))
            val receipt = ConversationMemory(os).capture(FinalMemoryInput("ack", "conversation", source = ConversationMemorySource.TEXT,
                text = "Remember I like apricots", capturedAtMs = System.currentTimeMillis()))
            assertEquals(MemoryReviewStatus.PENDING, receipt.memory!!.reviewStatus)
            assertEquals("I've added a pending memory for your review. It isn't approved yet.", MemoryCaptureAcknowledgment.reply(receipt))
            assertTrue(os.contextPacket("what fruit do I like?", 1200).packet!!.memories.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun test40_phoneActionJournalPreservesReceiptsAndFencesUnknownEffects() {
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

    @Test fun test41_taskRecoveryAndExactApprovalPreserveAndroidEffects() {
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
    @Test fun test42_taskPanelShowsExactChoiceAndReconcilesWithoutRetry() {
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
                    "approve" -> JournaledActionPipeline(ledger) { effects.incrementAndGet(); ExecutionResult(true, "25%") }
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

    @Test fun test43_conditionalAndCommaPlansUseRealAndroidWithoutModelCalls() = runBlocking {
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

    @Test fun test44_backgroundAssistantOpensAppAndFinishesOrderedPlanWithoutTap() = runBlocking {
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
