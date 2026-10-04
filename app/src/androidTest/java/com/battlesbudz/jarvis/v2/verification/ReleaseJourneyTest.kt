package com.battlesbudz.jarvis.v2.verification

import android.content.Intent
import android.content.Context
import android.content.ContextWrapper
import android.content.ContentValues
import android.app.ActivityManager
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
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until
import com.battlesbudz.jarvis.v2.MainActivity
import com.battlesbudz.jarvis.v2.JarvisRuntime
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
import com.battlesbudz.jarvis.v2.ui.PipelineBenchmarkScreen
import com.battlesbudz.jarvis.v2.diagnostics.*
import com.battlesbudz.jarvis.v2.voice.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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

    /** Modal actions must settle without page-seeking gestures that can dismiss the dialog. */
    private fun clickModalAction(selector: BySelector) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        var ready: UiObject2? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                val control = device.findObject(selector)
                if (control != null && control.isEnabled && hasSafeTapBounds(control)) {
                    val bounds = control.visibleBounds
                    device.waitForIdle((deadline - SystemClock.uptimeMillis()).coerceAtLeast(1))
                    SystemClock.sleep(minOf(300, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)))
                    val fresh = device.findObject(selector)
                    if (SystemClock.uptimeMillis() < deadline && fresh != null && fresh.isEnabled &&
                        hasSafeTapBounds(fresh) && fresh.visibleBounds == bounds) {
                        ready = fresh
                        break
                    }
                }
            } catch (_: StaleObjectException) {
                // No tap has been dispatched; await a fresh node in the same modal.
            }
            SystemClock.sleep(minOf(100, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)))
        }
        val control = ready ?: throw AssertionError("Modal action did not become stably enabled: $selector")
        assertTrue("Modal action must settle within its deadline", SystemClock.uptimeMillis() < deadline)
        // The tap is outside the retry loop: a mutation must never be replayed.
        control.click()
        device.waitForIdle()
    }

    private fun recordModelGeometry(message: String) {
        android.util.Log.i("JarvisVerification", message)
        // Keep diagnostics in instrumentation.txt even if later platform logs
        // rotate. This non-reserved status key carries no test completion fields.
        instrumentation.sendStatus(1, android.os.Bundle().apply { putString("jarvisModelChooseGeometry", message) })
    }

    /** Keep explicit settlement waits, without repeating implicit idle waits for every getter. */
    private inline fun <T> observeNavigation(block: () -> T): T {
        if (android.os.Build.VERSION.SDK_INT < 34) return block()
        val configuration = Configurator.getInstance()
        val savedIdleTimeout = configuration.getWaitForIdleTimeout()
        configuration.setWaitForIdleTimeout(0)
        return try { block() } finally { configuration.setWaitForIdleTimeout(savedIdleTimeout) }
    }

    private fun clearNavigationCache() {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            assertTrue("Navigation discovery requires a fresh accessibility cache", instrumentation.uiAutomation.clearCache())
        }
    }

    private fun findNavigationObject(selector: BySelector): UiObject2? {
        clearNavigationCache()
        return device.findObject(selector)
    }

    /** A matching node may be clipped; scroll inside the list until its full 48 dp target is visible. */
    private fun fullyVisibleModelChoose(selector: BySelector, navigationInset: Int): UiObject2 =
        observeNavigation { revealModelChoose(selector, navigationInset) }

    private fun revealModelChoose(selector: BySelector, navigationInset: Int): UiObject2 {
        val minimum = (48 * context.resources.displayMetrics.density).roundToInt()
        val deadline = SystemClock.uptimeMillis() + 15_000
        var swipes = 0
        var stationary = 0
        var lastBounds = "missing"
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                val list = findNavigationObject(By.res("model_list"))
                    ?: throw AssertionError("Model list disappeared while revealing Choose")
                val viewport = list.visibleBounds
                val bottom = minOf(viewport.bottom, device.displayHeight - navigationInset)
                fun isFullyVisible(control: UiObject2, currentViewport: android.graphics.Rect = viewport,
                    currentBottom: Int = bottom): Boolean {
                    clearNavigationCache()
                    val bounds = control.visibleBounds
                    lastBounds = bounds.toString()
                    return control.isEnabled && control.isClickable &&
                        bounds.width() >= minimum && bounds.height() >= minimum &&
                        currentViewport.contains(bounds) && bounds.bottom <= currentBottom
                }
                val control = findNavigationObject(selector)
                if (control != null && isFullyVisible(control)) {
                    val before = control.visibleBounds
                    device.waitForIdle((deadline - SystemClock.uptimeMillis()).coerceAtLeast(1))
                    SystemClock.sleep(minOf(150, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)))
                    var freshViewport = viewport
                    var freshBottom = bottom
                    if (android.os.Build.VERSION.SDK_INT >= 34) {
                        freshViewport = findNavigationObject(By.res("model_list"))?.visibleBounds
                            ?: throw AssertionError("Model list disappeared while settling Choose")
                        var freshNavigationInset = navigationInset
                        activity.onActivity {
                            freshNavigationInset = it.window.decorView.rootWindowInsets
                                ?.getInsets(android.view.WindowInsets.Type.navigationBars())?.bottom ?: navigationInset
                        }
                        freshBottom = minOf(freshViewport.bottom, device.displayHeight - freshNavigationInset)
                    }
                    val fresh = findNavigationObject(selector)
                    if (SystemClock.uptimeMillis() < deadline && fresh != null &&
                        isFullyVisible(fresh, freshViewport, freshBottom) && fresh.visibleBounds == before) {
                        recordModelGeometry("model_navigation ready selector=$selector target=${fresh.visibleBounds} viewport=$freshViewport minimumPx=$minimum navigationBottom=$freshBottom gestures=$swipes")
                        if (SystemClock.uptimeMillis() < deadline) return fresh
                    }
                }
                if (SystemClock.uptimeMillis() >= deadline || swipes >= 14 || stationary >= 2) break
                val before = benchmarkViewportSignature(list)
                val scrollDown = control == null || control.visibleBounds.centerY() >= viewport.centerY()
                val targetBefore = control?.visibleBounds?.toString() ?: "missing"
                assertTrue("Model list must provide a usable gesture viewport", viewport.width() > 96 && viewport.height() > 96)
                // The generic unfolded device has a hinge at the exact center X.
                // Keep gestures in the left quarter of the actual list, and both
                // vertical ends away from navigation and button edges.
                val swipeX = viewport.left + viewport.width() / 4
                // UiAutomator synchronously injects every step. A longer stroke
                // with fewer steps keeps navigation within its existing deadline.
                val highY = viewport.top + viewport.height() * 15 / 100
                val lowY = viewport.top + viewport.height() * 85 / 100
                if (SystemClock.uptimeMillis() >= deadline) break
                assertTrue("Model list swipe must dispatch", device.swipe(swipeX,
                    if (scrollDown) lowY else highY, swipeX, if (scrollDown) highY else lowY, 12))
                swipes++
                device.waitForIdle((deadline - SystemClock.uptimeMillis()).coerceAtLeast(1))
                SystemClock.sleep(minOf(150, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)))
                val freshList = findNavigationObject(By.res("model_list"))
                    ?: throw AssertionError("Model list disappeared after scrolling")
                val after = benchmarkViewportSignature(freshList)
                stationary = if (after == before) stationary + 1 else 0
                val targetAfter = findNavigationObject(selector)?.visibleBounds?.toString() ?: "missing"
                recordModelGeometry("model_navigation gesture=$swipes x=$swipeX direction=${if (scrollDown) "DOWN" else "UP"} viewportBefore=$viewport viewportAfter=${freshList.visibleBounds} targetBefore=$targetBefore targetAfter=$targetAfter moved=${after != before} stationary=$stationary minimumPx=$minimum navigationBottom=$bottom remainingMs=${(deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)}")
            } catch (_: StaleObjectException) {
                // Reacquire the list and target after Compose scrolling/recomposition.
            }
        }
        throw AssertionError("Choose did not expose its full 48 dp target above navigation: $selector bounds=$lastBounds swipes=$swipes")
    }

    /** Category chips live in horizontal LazyRows, so vertical page seeking cannot reveal all of them. */
    private fun clickHorizontalChip(strip: BySelector, target: BySelector) {
        repeat(8) {
            val row = find(strip).visibleBounds
            device.findObject(target)?.let { chip ->
                if (chip.isChecked) return
                // API 30 can expose a chip's unclipped bounds beyond its LazyRow.
                // Reveal the whole chip, then observe selection before continuing.
                if (chip.isEnabled && hasSafeTapBounds(chip) && row.contains(chip.visibleBounds)) {
                    chip.click()
                    device.waitForIdle()
                    if (device.wait(Until.hasObject(By.copy(target).checked(true)), 2_000)) return
                }
            }
            device.swipe(row.right - 12, row.centerY(), row.left + 12, row.centerY(), 180)
            device.waitForIdle()
        }
        throw AssertionError("Category chip did not become selected: $target")
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
        val selectedBefore = ModelStore(context).selectedModel().id
        assertEquals("Gemma-4-E2B-it", selectedBefore)
        assertFalse(device.hasObject(By.textContains("bundle /")))
        find(By.res("selected_model_details")).click()
        assertNotNull(find(By.text("About this model")))
        find(By.text("Close details")).click()
        assertNotNull(find(By.text("Gemma-4-E2B-it")))
        openBrowser()
        enterText(By.res("model_search"), "Gemma")
        clickEnabled(By.res("model_family_Gemma"))
        assertNotNull(find(By.res("model_list")))
        assertNotNull(find(By.text("Not yet verified for this Jarvis setup.")))
        assertFalse(device.hasObject(By.textContains("bundle /")))
        scrollTo(By.res("model_details_Gemma3-1B-IT")).click()
        assertNotNull(find(By.text("About this model")))
        captureEvidence("model_details_open")
        find(By.text("Close details")).click()
        assertNotNull(find(By.res("model_choose_Gemma3-1B-IT")))
        captureEvidence("model_details_returned_to_family")
        clickEnabled(By.text("Done"))
        assertTrue("Details browsing must close without choosing a model", device.wait(
            Until.gone(By.res("model_search")), 15_000
        ))
        assertNotNull(find(By.res("selected_model_details")))
        assertNotNull(find(By.text(selectedBefore)))
        assertEquals("Details must not change the durable model selection", selectedBefore, ModelStore(context).selectedModel().id)
    }

    @Test fun test12_lastFamilyModelCanBeSelectedAboveNavigationBar() {
        openBrowser()
        enterText(By.res("model_search"), "Gemma")
        clickEnabled(By.res("model_family_Gemma"))
        assertNotNull(find(By.res("model_list")))
        val target = By.res("model_choose_codegemma-7b-it-int4-litertlm")
        var navigationInset = 0
        activity.onActivity {
            val insets = it.window.decorView.rootWindowInsets
            navigationInset = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets?.getInsets(android.view.WindowInsets.Type.navigationBars())?.bottom ?: 0
            } else insets?.systemWindowInsetBottom ?: 0
        }
        val button = fullyVisibleModelChoose(target, navigationInset)
        val bounds = button.visibleBounds
        assertTrue("Choose must be above Android navigation", bounds.bottom <= device.displayHeight - navigationInset)
        assertTrue("Choose must have its full 48 dp touch target", bounds.height() >= (48 * context.resources.displayMetrics.density).roundToInt())
        captureEvidence("last_model_button")
        button.click()
        assertTrue("Choosing the last model must dismiss the browser", device.wait(
            Until.gone(By.res("model_search")), 15_000
        ))
        assertNotNull(find(By.res("selected_model_details")))
        assertNotNull(find(By.text("codegemma-7b-it-int4-litertlm")))
        assertEquals("The last model must be selected in durable preferences", "codegemma-7b-it-int4-litertlm", ModelStore(context).selectedModel().id)
        // Restore the starting model without touching files or bypassing the UI.
        openBrowser()
        enterText(By.res("model_search"), "Gemma-4-E2B-it")
        clickEnabled(By.res("model_family_Gemma"))
        assertNotNull(find(By.res("model_list")))
        scrollTo(By.res("model_choose_Gemma-4-E2B-it")).click()
        assertTrue("Restoring the starting model must dismiss the browser", device.wait(
            Until.gone(By.res("model_search")), 15_000
        ))
        assertNotNull(find(By.res("selected_model_details")))
        assertNotNull(find(By.text("Gemma-4-E2B-it")))
        assertEquals("The starting model must be restored in durable preferences", "Gemma-4-E2B-it", ModelStore(context).selectedModel().id)
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
            assertTrue("Cancelled erase dialog must close before reopening", device.wait(
                Until.gone(By.text("Erase this memory?")), 15_000
            ))
            assertNotNull(find(By.text(indigo)))
            clickEnabled(By.res("memory_delete"))
            // Await the modal before a scroll-capable helper can dispatch a gesture.
            assertNotNull(find(By.res("memory_delete_confirm")))
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
            clickModalAction(By.text("Cancel"))
            assertTrue("Cancelled erase-all dialog must close before reopening", device.wait(
                Until.gone(By.text("Erase all memories?")), 15_000
            ))
            searchMemory("persistent amber tea", By.text(persistent))
            enterText(By.res("memory_search_input"), "")
            clickEnabled(By.res("memory_erase_all"))
            assertNotNull(find(By.text("Erase all memories?")))
            clickModalAction(By.res("memory_delete_all_confirm"))
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
            clickModalAction(By.res("memory_delete_all_confirm"))
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
            clickEnabled(By.res("task_approve_${pending.task.id}"))
            assertTrue("Approved choice must finish before checking its effect", device.wait(
                Until.gone(By.res("task_approve_${pending.task.id}")), 15_000
            ))
            assertEquals(1, effects.get())
            assertEquals(ToolTaskState.SUCCEEDED, ledger.get(pending.task.id)?.state)
            clickEnabled(By.res("task_checked_${unknown.id}"))
            assertTrue("Reconciliation must finish before reopening the ledger", device.wait(
                Until.gone(By.res("task_checked_${unknown.id}")), 15_000
            ))
            assertTrue(checkNotNull(ToolTaskLedger(FileToolTaskStore(file)).get(unknown.id)).reconciled)
            assertEquals(1, effects.get())
            clickEnabled(By.res("task_cancel_${legacy.id}"))
            assertTrue("Cancellation must finish before reopening the ledger", device.wait(
                Until.gone(By.res("task_cancel_${legacy.id}")), 15_000
            ))
            assertEquals(ToolTaskState.CANCELLED, ToolTaskLedger(FileToolTaskStore(file)).get(legacy.id)?.state)
            val declined = ledger.admit(listOf(ActionRequest("read_battery")), "panel-thread", ToolAuthority.EXACT_APPROVAL)
            val choice = gate.prepare(checkNotNull(ledger.get(declined.attemptIds.single())))
            journal.value = ledger.journal()
            clickEnabled(By.res("task_deny_${choice.task.id}"))
            assertTrue("Decline must finish before reopening the ledger", device.wait(
                Until.gone(By.res("task_deny_${choice.task.id}")), 15_000
            ))
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

    /** Benchmark-only navigation: Compose may export descendants wholly outside a LazyColumn. */
    private fun benchmarkScrollAncestor(control: UiObject2): UiObject2? {
        clearNavigationCache()
        var ancestor = control.parent
        repeat(40) {
            val current = ancestor ?: return null
            if (current.isScrollable) return current
            ancestor = current.parent
        }
        throw AssertionError("Benchmark control has an unexpectedly deep ancestor chain")
    }

    private fun benchmarkHasSafeBounds(control: UiObject2): Boolean {
        clearNavigationCache()
        val bounds = control.visibleBounds
        val viewport = android.graphics.Rect(24, 24, device.displayWidth - 24, device.displayHeight - 24)
        var ancestor = control.parent
        var depth = 0
        while (ancestor != null && depth++ < 40) {
            val current = ancestor
            if (current.isScrollable && !viewport.intersect(current.visibleBounds)) viewport.setEmpty()
            ancestor = current.parent
        }
        if (ancestor != null) return false
        // Requiring vertical room also rejects a partially clipped descendant whose
        // accessibility rectangle was truncated exactly at the viewport edge.
        return bounds.width() > 0 && bounds.height() > 0 && viewport.contains(bounds) &&
            bounds.top >= viewport.top + 12 && bounds.bottom <= viewport.bottom - 12 &&
            bounds.centerX() >= viewport.left + 24 && bounds.centerX() <= viewport.right - 24 &&
            bounds.centerY() >= viewport.top + 24 && bounds.centerY() <= viewport.bottom - 24
    }

    /** Observe actual visible content; Compose need not emit a UiAutomator scroll event. */
    private fun benchmarkViewportSignature(list: UiObject2): String {
        clearNavigationCache()
        val viewport = list.visibleBounds
        val rows = mutableListOf<String>()
        fun visit(node: UiObject2, depth: Int) {
            if (depth > 12 || rows.size >= 256) return
            val bounds = node.visibleBounds
            if (bounds.width() <= 0 || bounds.height() <= 0 ||
                !android.graphics.Rect.intersects(viewport, bounds)) return
            rows.add("${node.resourceName}|${node.text}|$bounds")
            node.children.forEach { visit(it, depth + 1) }
        }
        list.children.forEach { visit(it, 0) }
        return rows.joinToString("\n")
    }

    private fun benchmarkScrollList(): UiObject2? {
        val screen = findNavigationObject(By.res("pipeline_benchmark_screen")) ?: return null
        clearNavigationCache()
        return screen.findObject(By.scrollable(true))
    }

    private fun benchmarkFindVisible(selector: BySelector, towardTop: Boolean, inDialog: Boolean,
        deadline: Long, swipes: AtomicInteger): UiObject2 {
        var direction = if (towardTop) Direction.UP else Direction.DOWN
        var reversedAtEdge = false
        var unchangedGestures = 0
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                val control = findNavigationObject(selector)
                if (control != null && benchmarkHasSafeBounds(control)) {
                    if (android.os.Build.VERSION.SDK_INT < 34 || SystemClock.uptimeMillis() < deadline) return control
                    break
                }
                if (inDialog) {
                    SystemClock.sleep(100)
                    continue
                }
                val list = if (control != null) benchmarkScrollAncestor(control)
                    else benchmarkScrollList()
                if (list == null) {
                    SystemClock.sleep(100)
                    continue
                }
                if (swipes.incrementAndGet() > 14) break
                val viewport = list.visibleBounds
                check(viewport.width() > 0 && viewport.height() > 96) { "Benchmark scroll viewport is unavailable" }
                val bounds = control?.visibleBounds?.takeIf { it.width() > 0 && it.height() > 0 }
                if (bounds != null) direction = if (bounds.centerY() < viewport.centerY()) Direction.UP else Direction.DOWN
                // Dispatch physical gestures as the older release journeys do. The
                // UiObject2.scroll result conflates a missing accessibility event with
                // an actual edge, so determine progress from fresh visible content.
                val before = benchmarkViewportSignature(list)
                // Avoid the unfolded hinge and the system clipboard preview at
                // the lower left, preserving overlap and the same safe-tap checks.
                val swipeX = viewport.left + viewport.width() * 3 / 4
                val lowY = viewport.top + viewport.height() * 85 / 100
                val highY = viewport.top + viewport.height() * 15 / 100
                val fromY = if (direction == Direction.DOWN) lowY else highY
                // Build 937 rediscovered the reference at an edge, then a full
                // stroke flung it past the opposite edge. Approach an observed
                // target with a short, slower gesture; empty exported bounds
                // provide no direction. Discovery still uses the full stroke.
                val distance = if (bounds != null) kotlin.math.abs(bounds.centerY() - viewport.centerY())
                    .coerceIn(24, viewport.height() / 4) else lowY - highY
                val steps = if (bounds != null) 80 else 12
                val toY = if (direction == Direction.DOWN) fromY - distance else fromY + distance
                val scrollStarted = SystemClock.uptimeMillis()
                if (android.os.Build.VERSION.SDK_INT >= 34 && SystemClock.uptimeMillis() >= deadline) break
                check(device.swipe(swipeX, fromY, swipeX, toY, steps)) {
                    "Benchmark swipe dispatch failed"
                }
                device.waitForIdle((deadline - SystemClock.uptimeMillis()).coerceAtLeast(1))
                if (SystemClock.uptimeMillis() >= deadline) break
                SystemClock.sleep(150)
                val fresh = findNavigationObject(selector)
                if (fresh != null && benchmarkHasSafeBounds(fresh)) {
                    if (android.os.Build.VERSION.SDK_INT < 34 || SystemClock.uptimeMillis() < deadline) return fresh
                    break
                }
                val freshList = benchmarkScrollList()
                val after = freshList?.let { benchmarkViewportSignature(it) }
                val moved = after != null && after != before
                unchangedGestures = if (moved) 0 else unchangedGestures + 1
                android.util.Log.i("JarvisVerification", "benchmark_navigation selector=$selector direction=$direction targetBefore=$bounds targetAfterFound=${fresh != null} distance=$distance steps=$steps moved=$moved unchangedGestures=$unchangedGestures gestures=${swipes.get()} gestureMs=${SystemClock.uptimeMillis() - scrollStarted} remainingMs=${deadline - SystemClock.uptimeMillis()}")
                // Require two observed stationary gestures before reversing once.
                if (after != null && unchangedGestures >= 2) {
                    if (reversedAtEdge) break
                    direction = if (direction == Direction.UP) Direction.DOWN else Direction.UP
                    reversedAtEdge = true
                    unchangedGestures = 0
                }
            } catch (_: StaleObjectException) {
                // Re-query after scrolling or recomposition, before dispatching any tap.
            }
        }
        throw AssertionError("Benchmark control did not become fully visible: $selector")
    }

    private fun benchmarkScrollTo(selector: BySelector, towardTop: Boolean = false): UiObject2 = observeNavigation {
        benchmarkFindVisible(selector, towardTop, false, SystemClock.uptimeMillis() + 15_000, AtomicInteger())
    }

    private fun benchmarkClickEnabled(selector: BySelector, towardTop: Boolean = false, inDialog: Boolean = false) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        val swipes = AtomicInteger()
        while (SystemClock.uptimeMillis() < deadline) {
            val ready = observeNavigation {
                var ready: UiObject2? = null
                try {
                    val control = benchmarkFindVisible(selector, towardTop, inDialog, deadline, swipes)
                    if (control.isEnabled) {
                        val before = control.visibleBounds
                        device.waitForIdle((deadline - SystemClock.uptimeMillis()).coerceAtLeast(1))
                        SystemClock.sleep(300)
                        val fresh = findNavigationObject(selector)
                        if (SystemClock.uptimeMillis() < deadline && fresh != null && fresh.isEnabled &&
                            benchmarkHasSafeBounds(fresh) && fresh.visibleBounds == before) ready = fresh
                    } else SystemClock.sleep(100)
                } catch (_: StaleObjectException) {
                    // Re-query stale nodes only before dispatching the physical tap.
                }
                ready
            }
            // The observation scope has restored the original idle timeout before any tap.
            if (android.os.Build.VERSION.SDK_INT >= 34 && SystemClock.uptimeMillis() >= deadline) break
            ready?.let {
                it.click() // Exactly one tap; a dispatch error is never retried.
                device.waitForIdle()
                return
            }
        }
        throw AssertionError("Benchmark control did not become stably enabled inside its viewport: $selector")
    }

    /** Controlled observations exercise the release dashboard; they are not model/audio performance evidence. */
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun test45_pipelineBenchmarksScoreOriginalAsrPersistAndExportRedactedEvidence() = runBlocking {
        val directory = File(context.cacheDir, "release-pipeline-benchmarks").apply { deleteRecursively(); mkdirs() }
        val fixtureContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        val store = AndroidPipelineBenchmarkStore(fixtureContext)
        val completedId = "completed-benchmark-fixture"
        val cancelledId = "cancelled-benchmark-fixture"
        val originalAsr = "alpha beta wrong delta"
        val reference = "alpha beta gamma delta"
        fun render(value: AndroidPipelineBenchmarkStore) {
            activity.onActivity { host -> host.setContent {
                // A restored store represents a new process: do not reuse the prior
                // screen's remembered selection, status or reference-dialog state.
                androidx.compose.runtime.key(value) {
                    MaterialTheme { Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                        PipelineBenchmarkScreen(value, onClose = {})
                    } }
                }
            } }
            device.waitForIdle()
            find(By.res("pipeline_benchmark_screen"))
        }
        try {
            store.clear()
            val provenance = store.captureProvenance(
                models = mapOf("asr" to PipelineBenchmarkModel("controlled-whisper-fixture", runtime = "fixture")),
                configuration = mapOf("evidence" to "controlled_release_fixture_not_model_or_device_performance"))
            store.append(PipelineBenchmarkTurn(
                turnId = cancelledId, channel = "voice", capturedAtEpochMs = System.currentTimeMillis() - 1,
                provenance = provenance, outcome = PipelineBenchmarkOutcome.CANCELLED,
                clock = "controlled_fixture_monotonic", stageOffsetsMs = mapOf("turn_started" to 0, "turn_finished" to 500),
                failureCode = "fixture_cancelled"))
            store.append(PipelineBenchmarkTurn(
                turnId = completedId, channel = "voice", capturedAtEpochMs = System.currentTimeMillis(),
                provenance = provenance, outcome = PipelineBenchmarkOutcome.COMPLETE,
                clock = "controlled_fixture_monotonic",
                stageOffsetsMs = mapOf("turn_started" to 0, "microphone_ready" to 20, "recognition_finalized" to 200,
                    "reply_dispatched" to 210, "first_reply_text" to 350, "turn_finished" to 900),
                asr = PipelineBenchmarkAsr("controlled-whisper-fixture", inputAudioMs = 400, decodeWorkMs = 200,
                    transcriptCharacters = originalAsr.length),
                submissions = listOf(PipelineBenchmarkSubmission(
                    "fixture-submission", PipelineBenchmarkPurpose.ANSWER, PipelineBenchmarkOutcome.COMPLETE,
                    firstTokenMs = 100, totalGenerationMs = 300, estimatedOutputTokens = 3,
                    tokenTelemetrySource = "unavailable_fixture_no_native_token_ids"))), hypothesis = originalAsr)
            store.flush()
            render(store)
            benchmarkClickEnabled(By.res("pipeline_benchmark_sample_$completedId"))
            benchmarkClickEnabled(By.res("benchmark_environment_NOISY"))
            benchmarkClickEnabled(By.res("pipeline_benchmark_reference"))
            assertTrue(find(By.text("Original ASR: $originalAsr")).text.contains(originalAsr))
            assertFalse(find(By.res("pipeline_benchmark_reference_score")).isEnabled)
            find(By.res("pipeline_benchmark_reference_text")).text = reference
            hideKeyboardWithoutNavigating()
            assertFalse("Typing a reference is insufficient without explicit verification",
                find(By.res("pipeline_benchmark_reference_score")).isEnabled)
            benchmarkClickEnabled(By.res("pipeline_benchmark_reference_verify"), inDialog = true)
            benchmarkClickEnabled(By.res("pipeline_benchmark_reference_score"), inDialog = true)
            assertTrue(benchmarkScrollTo(By.res("pipeline_benchmark_status"), towardTop = true).text.contains("WER 25.0%"))
            val scored = store.samples.value.single { it.turnId == completedId }
            val score = requireNotNull(scored.accuracy)
            assertEquals(4, score.referenceWords)
            assertEquals(1, score.wordSubstitutions)
            assertEquals(0.25, score.wer!!, 0.000001)
            assertNull("Verified reference words must not become retained content", score.reference)
            assertNull("Original ASR content must not become retained content", score.hypothesis)

            // A completed pipeline can still have an incorrect result: human review is independent.
            benchmarkClickEnabled(By.res("benchmark_quality_task_FAIL"))
            benchmarkClickEnabled(By.res("benchmark_quality_intent_PASS"))
            benchmarkClickEnabled(By.res("benchmark_quality_factuality_FAIL"))
            assertFalse(benchmarkScrollTo(By.res("pipeline_benchmark_quality_save")).isEnabled)
            benchmarkClickEnabled(By.res("pipeline_benchmark_quality_verify"))
            benchmarkClickEnabled(By.res("pipeline_benchmark_quality_save"))
            withTimeout(5_000) { store.samples.first { turns -> turns.single { it.turnId == completedId }.quality != null } }
            val quality = store.samples.value.single { it.turnId == completedId }.quality!!
            assertEquals(PipelineBenchmarkVerdict.FAIL, quality.taskVerdict)
            assertEquals(PipelineBenchmarkVerdict.PASS, quality.intentVerdict)
            assertEquals(PipelineBenchmarkVerdict.FAIL, quality.factualityVerdict)
            assertEquals(PipelineBenchmarkOutcome.COMPLETE, store.samples.value.single { it.turnId == completedId }.outcome)
            assertEquals(PipelineBenchmarkEnvironment.NOISY, store.samples.value.single { it.turnId == completedId }.environment)
            assertNotNull(benchmarkScrollTo(By.text("tts_load_ms: unavailable")))
            captureEvidence("pipeline_benchmark_verified_reference_and_review")

            benchmarkClickEnabled(By.res("pipeline_benchmark_copy_json"), towardTop = true)
            assertEquals("Redacted JSON report copied.", benchmarkScrollTo(By.res("pipeline_benchmark_status"), towardTop = true).text)
            val clipboard = AtomicReference<String>()
            activity.onActivity { host ->
                clipboard.set(host.getSystemService(android.content.ClipboardManager::class.java)
                    .primaryClip?.getItemAt(0)?.coerceToText(host)?.toString().orEmpty())
            }
            val export = org.json.JSONObject(clipboard.get())
            assertFalse(export.getJSONObject("privacy").getBoolean("textIncluded"))
            assertFalse(clipboard.get().contains(originalAsr))
            assertFalse(clipboard.get().contains(reference))
            assertEquals(2, export.getJSONObject("allAttempts").getInt("turnCount"))
            assertEquals(1, export.getJSONObject("allAttempts").getJSONObject("outcomes").getInt("CANCELLED"))
            assertEquals(1, export.getJSONObject("completedTurns").getInt("turnCount"))
            val exportedCompleted = (0 until export.getJSONArray("turns").length())
                .map { export.getJSONArray("turns").getJSONObject(it) }.single { it.getString("turnId") == completedId }
            assertTrue("Missing playback measurements remain JSON null", exportedCompleted.isNull("tts"))
            assertTrue("Token estimates do not invent native counts", exportedCompleted.getJSONArray("submissions")
                .getJSONObject(0).isNull("exactOutputTokens"))
            assertEquals(0.25, export.getJSONObject("allAttempts").getDouble("corpusWer"), 0.000001)

            store.flush()
            val restored = AndroidPipelineBenchmarkStore(fixtureContext)
            val persisted = restored.samples.value.single { it.turnId == completedId }
            assertEquals(scored.accuracy, persisted.accuracy)
            assertEquals(quality, persisted.quality)
            assertEquals(PipelineBenchmarkEnvironment.NOISY, persisted.environment)
            assertNull(persisted.metrics()["endpoint_to_first_answer_playback_ms"])
            assertNull("Original transcript is process-only and is not restored from disk", restored.hypothesis(completedId))
            assertEquals(PipelineBenchmarkOutcome.CANCELLED, restored.samples.value.single { it.turnId == cancelledId }.outcome)
            render(restored)
            benchmarkClickEnabled(By.res("pipeline_benchmark_sample_$completedId"))
            assertFalse(benchmarkScrollTo(By.res("pipeline_benchmark_reference")).isEnabled)
            captureEvidence("pipeline_benchmark_restored_redacted_scores")
            benchmarkClickEnabled(By.res("pipeline_benchmark_reset"), towardTop = true)
            benchmarkClickEnabled(By.res("pipeline_benchmark_reset_confirm"), inDialog = true)
            assertNotNull(benchmarkScrollTo(By.text("No pipeline measurements yet. Complete a text or voice turn, then return here.")))
            restored.flush()
            assertTrue(AndroidPipelineBenchmarkStore(fixtureContext).samples.value.isEmpty())
        } finally {
            store.clear()
            directory.deleteRecursively()
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun test46_replyMetricsExportOnlyTheirConversationAcrossReload() = runBlocking {
        val directory = File(context.cacheDir, "release-conversation-benchmarks").apply { deleteRecursively(); mkdirs() }
        val fixtureContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        val prefs = context.getSharedPreferences("release-benchmark-conversation", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val history = ConversationHistory(prefs)
        val thread = history.current.value.id
        val reply = "conversation-benchmark-reply"
        history.syncCall(VoiceCallRecord("benchmark-call", System.currentTimeMillis(), conversationId = thread,
            transcript = listOf(TranscriptEntry("Jarvis", "Measured reply", replyId = reply,
                metrics = ReplyMetrics().submitted(100).firstRawToken(200)))))
        val row = history.current.value.messages.single()
        assertEquals(reply, ConversationHistory(prefs).current.value.messages.single().sourceReplyId)
        val store = AndroidPipelineBenchmarkStore(fixtureContext)
        try {
            val provenance = store.captureProvenance(configuration = mapOf("reply_id" to reply, "evidence" to "controlled_fixture"))
            for (conversation in listOf(thread, "other-conversation")) store.append(PipelineBenchmarkTurn(
                turnId = "attempt-$conversation", channel = "text", capturedAtEpochMs = System.currentTimeMillis(),
                provenance = provenance, outcome = PipelineBenchmarkOutcome.COMPLETE,
                clock = "controlled_fixture", stageOffsetsMs = mapOf("turn_started" to 0, "turn_finished" to 200),
                conversationId = conversation))
            store.flush()
            val restored = AndroidPipelineBenchmarkStore(fixtureContext)
            assertEquals(1, restored.report(conversationId = thread).turns.size)
            activity.onActivity { host -> host.setContent {
                MaterialTheme { Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    ConversationScreen(history, MutableStateFlow(false), MutableStateFlow(VoiceSessionState.PASSIVE_LISTENING),
                        onSend = { _, _ -> null }, selectedModel = LocalModelSpec("release-fixture", "fixture.bin", recommendedGpu = false),
                        onSelectConversation = { null }, onEndVoice = {}, onOpenVoiceCalls = {}, resumedVoice = false,
                        pipelineBenchmarkStore = restored, voiceContent = { _, _, _, _ -> })
                } }
            } }
            find(By.res("conversation_metrics_copy")).click()
            find(By.text("Conversation metrics copied."))
            val copiedConversation = AtomicReference<String>()
            activity.onActivity { host -> copiedConversation.set(host.getSystemService(android.content.ClipboardManager::class.java)
                .primaryClip?.getItemAt(0)?.coerceToText(host)?.toString().orEmpty()) }
            val directReport = org.json.JSONObject(copiedConversation.get())
            assertEquals(1, directReport.getJSONArray("turns").length())
            assertEquals(thread, directReport.getJSONArray("turns").getJSONObject(0).getString("conversationId"))
            assertEquals(4, directReport.getJSONArray("replyMetrics").getJSONObject(0).getInt("estimatedOutputTokens"))
            assertFalse(copiedConversation.get().contains("other-conversation"))
            assertFalse(copiedConversation.get().contains("Measured reply"))
            find(By.res("conversation_metrics_open")).click()
            find(By.res("pipeline_benchmark_screen"))
            benchmarkClickEnabled(By.res("pipeline_benchmark_close"), towardTop = true)
            find(By.res("reply_metrics_${row.id}")).click()
            find(By.res("pipeline_benchmark_screen"))
            benchmarkClickEnabled(By.res("pipeline_benchmark_copy_json"), towardTop = true)
            find(By.res("pipeline_benchmark_status"))
            val clipboard = AtomicReference<String>()
            activity.onActivity { host -> clipboard.set(host.getSystemService(android.content.ClipboardManager::class.java)
                .primaryClip?.getItemAt(0)?.coerceToText(host)?.toString().orEmpty()) }
            val report = org.json.JSONObject(clipboard.get())
            assertEquals(1, report.getJSONArray("turns").length())
            assertEquals(thread, report.getJSONArray("turns").getJSONObject(0).getString("conversationId"))
            assertFalse(clipboard.get().contains("other-conversation"))
            assertFalse(clipboard.get().contains("Measured reply"))
            captureEvidence("conversation_scoped_benchmark_export")
            benchmarkClickEnabled(By.res("pipeline_benchmark_close"), towardTop = true)
            assertNotNull(find(By.res("chat_composer")))
        } finally { store.clear(); directory.deleteRecursively(); prefs.edit().clear().commit() }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun test47_audioInputChoiceAndDisplayCaptionTogglePersistWithoutLoadingModels() {
        val originalMode = VoiceInputMode.selected(context)
        val originalCaptions = VoiceInputMode.captions(context)
        fun render() {
            activity.onActivity { host -> host.setContent {
                MaterialTheme { Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    androidx.compose.foundation.lazy.LazyColumn {
                        item { com.battlesbudz.jarvis.v2.ui.VoiceInputSettings(enabled = true, onBusy = {}) }
                    }
                } }
            } }
            device.waitForIdle()
        }
        try {
            VoiceInputMode.select(context, VoiceInputMode.TRANSCRIBED_TEXT)
            VoiceInputMode.captions(context, true)
            render()
            find(By.text("Use Gemma audio understanding")).click()
            assertEquals(VoiceInputMode.GEMMA_AUDIO, VoiceInputMode.selected(context))
            find(By.res("gemma_whisper_captions")).click()
            assertFalse(VoiceInputMode.captions(context))
            render()
            assertFalse(find(By.res("gemma_whisper_captions")).isChecked)
            assertNotNull(find(By.textContains("Whisper captions are display-only")))
            captureEvidence("gemma_audio_display_caption_settings")
            find(By.text("Use Speech recognition")).click()
            assertEquals(VoiceInputMode.TRANSCRIBED_TEXT, VoiceInputMode.selected(context))
        } finally {
            VoiceInputMode.select(context, originalMode)
            VoiceInputMode.captions(context, originalCaptions)
        }
    }

    @Suppress("DEPRECATION")
    @Test fun test48_spokenCallEndRetainsWakeSessionAndExplicitStopDisarms() = runBlocking {
        val runtime = JarvisRuntime.get(context)
        val controller = runtime.voiceSessionController
        val originalTurn = runtime.voiceTurnJob
        val originalRecoveryAttempts = runtime.audioRecoveryAttempts
        val originalWakeCue = runtime.returnToWakeCuePending.get()
        val originalReport = runtime.sessionReport
        val originalPhase = VoiceSessionUi.phase.value
        val originalStatus = VoiceSessionUi.status.value
        val originalTranscript = VoiceSessionUi.liveTranscript.value
        val originalLevel = VoiceSessionUi.level.value
        val originalUiArmed = VoiceSessionUi.armed.value
        val originalPaused = VoiceSessionUi.paused.value
        val originalServiceStop = VoiceCallService.stopRequested.value
        val callIds = mutableListOf<String>()
        val finishingTurn = Job()
        val passiveTurn = Job()
        fun callService() = context.getSystemService(ActivityManager::class.java).getRunningServices(100)
            .singleOrNull { it.service.className == VoiceCallService::class.java.name }
        fun awaitService(message: String, condition: () -> Boolean) {
            val until = SystemClock.uptimeMillis() + 15_000
            while (SystemClock.uptimeMillis() < until && !condition()) SystemClock.sleep(100)
            assertTrue(message, condition())
        }
        assertFalse("The controlled runtime journey must start with an idle wake session", runtime.voiceSessionArmed)
        assertNull("The controlled runtime journey must not replace another call", controller.currentCallId())
        assertTrue("The previous runtime turn must already be complete", originalTurn?.isCompleted != false)
        assertNull("The controlled runtime journey must own its foreground service", callService())
        try {
            runtime.sessionReport = {}
            // An owned, incomplete turn keeps sendChat at its real queue boundary without
            // loading ASR, wake-word, Gemma or Piper models in this lifecycle fixture.
            runtime.voiceTurnJob = finishingTurn
            runtime.arm()
            activity.onActivity { host ->
                host.startForegroundService(Intent(host, VoiceCallService::class.java).setAction("release-verification-hold"))
            }
            awaitService("The armed runtime must retain foreground microphone eligibility", { callService()?.foreground == true })
            val oldCall = controller.beginCall().also { callIds += it.id }
            controller.appendTranscript("You", "Stop listening")
            assertNull(runtime.sendChat("This queued draft belongs only to the ending call."))

            // Exercise the same runtime callback used by a final spoken farewell. Native
            // turn cleanup owns rearm; returning to wake mode must not cancel that owner.
            runtime.returnToWakeListening(oldCall.id)
            assertTrue("A spoken farewell must keep the user-armed wake session", runtime.voiceSessionArmed)
            assertTrue(VoiceSessionUi.armed.value)
            assertFalse(VoiceSessionUi.paused.value)
            assertEquals(VoiceSessionState.PASSIVE_LISTENING, controller.state.value)
            assertNull(controller.currentCallId())
            assertTrue("The exact turn must survive until its own cleanup finishes", finishingTurn.isActive)
            assertFalse(finishingTurn.isCancelled)
            assertTrue("Returning to wake mode must queue its ready cue after cleanup", runtime.returnToWakeCuePending.get())
            assertTrue("A farewell must retain the actual foreground service", callService()?.foreground == true)
            assertFalse("A farewell must not request service termination", VoiceCallService.stopRequested.value)
            val saved = SharedPreferencesVoiceCallStore(context.getSharedPreferences("voice_calls", Context.MODE_PRIVATE))
                .list().single { it.id == oldCall.id }
            assertNotNull("The ending call must be durable before another call begins", saved.endedAtMs)
            assertTrue(saved.transcript.any { it.role == "You" && it.text == "Stop listening" })
            assertEquals("The ending call must terminalize its queued typed input once", 1,
                saved.transcript.count { it.text == "Cancelled before processing typed message: This queued draft belongs only to the ending call." })
            assertEquals("Voice Call is waiting for its wake word; keep this draft until the call is listening.",
                runtime.sendChat("A passive-session draft must wait for the next wake word."))

            finishingTurn.complete()
            withTimeout(5_000) { finishingTurn.join() }
            runtime.voiceTurnJob = passiveTurn
            val newCall = controller.beginCall().also { callIds += it.id }
            assertNotEquals("A new wake conversation must get a fresh call identity", oldCall.id, newCall.id)
            assertTrue("Ended-call drafts must not leak into the new call", controller.currentTranscript().isEmpty())
            runtime.returnToWakeListening(oldCall.id)
            assertEquals("A stale farewell must not end a newer call", newCall.id, controller.currentCallId())
            assertEquals(VoiceSessionState.ACTIVELY_LISTENING, controller.state.value)
            assertTrue(passiveTurn.isActive)
            runtime.returnToWakeListening(newCall.id)
            assertNull(controller.currentCallId())
            assertEquals(VoiceSessionState.PASSIVE_LISTENING, controller.state.value)
            assertTrue(runtime.voiceSessionArmed)
            assertTrue(callService()?.foreground == true)

            runtime.endVoiceCall {}
            assertFalse("Explicit End Call must stop the whole session even while passive", runtime.voiceSessionArmed)
            assertFalse(VoiceSessionUi.armed.value)
            assertFalse(runtime.returnToWakeCuePending.get())
            assertTrue("Explicit End Call must cancel the passive turn", passiveTurn.isCancelled)
            awaitService("Explicit End Call must release its foreground service", {
                callService() == null && VoiceCallService.stopRequested.value
            })
            assertTrue(VoiceCallService.stopRequested.value)
        } finally {
            try {
                runtime.endVoiceCall {}
                context.stopService(Intent(context, VoiceCallService::class.java))
                finishingTurn.cancel()
                passiveTurn.cancel()
                withTimeout(5_000) { finishingTurn.join(); passiveTurn.join() }
                awaitService("The runtime fixture must release its service owner", {
                    callService() == null && VoiceCallService.stopRequested.value
                })
                instrumentation.waitForIdleSync()
            } finally {
                controller.currentCallId()?.takeIf { it in callIds }?.let { controller.end() }
                callIds.forEach(runtime.voiceCallStore::delete)
                runtime.voiceTurnJob = originalTurn
                runtime.audioRecoveryAttempts = originalRecoveryAttempts
                runtime.returnToWakeCuePending.set(originalWakeCue)
                runtime.sessionReport = originalReport
                VoiceSessionUi.phase.value = originalPhase
                VoiceSessionUi.status.value = originalStatus
                VoiceSessionUi.liveTranscript.value = originalTranscript
                VoiceSessionUi.level.value = originalLevel
                VoiceSessionUi.armed.value = originalUiArmed
                VoiceSessionUi.paused.value = originalPaused
                VoiceCallService.stopRequested.value = originalServiceStop
            }
        }
    }

    // Leave this selection in durable preferences for the controller's separate-process check.
    @Test fun test90_modelSelectionPersistsAcrossRecreation() {
        openBrowser()
        enterText(By.res("model_search"), "Gemma-4-E4B-it")
        clickEnabled(By.res("model_family_Gemma"))
        assertNotNull(find(By.res("model_list")))
        scrollTo(By.res("model_choose_Gemma-4-E4B-it")).click()
        assertNotNull(find(By.text("Gemma-4-E4B-it")))
        activity.recreate()
        assertNotNull(find(By.text("Gemma-4-E4B-it")))
    }
}
