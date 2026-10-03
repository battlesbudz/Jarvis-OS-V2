package com.battlesbudz.jarvis.v2.verification

import android.app.Activity
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.battlesbudz.jarvis.v2.MainActivity
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.chat.ConversationHistory
import com.battlesbudz.jarvis.v2.ui.ConversationScreen
import com.battlesbudz.jarvis.v2.ui.VoiceCallOverlay
import com.battlesbudz.jarvis.v2.voice.VoiceSessionState
import com.battlesbudz.jarvis.v2.voice.VoiceSessionUi
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import org.json.JSONObject

/** Shipping Compose surfaces and their real accessibility tree, with controlled call callbacks.
 * This audits named controls/semantics/touch sizes, not TalkBack speech or contrast pixels.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class ReleaseLayoutAccessibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val application = context.applicationContext as Application
    private var activity: ActivityScenario<MainActivity>? = null
    private lateinit var history: ConversationHistory
    private val callState = MutableStateFlow(VoiceSessionState.PASSIVE_LISTENING)
    private val paused = MutableStateFlow(false)
    private val sends = AtomicInteger()
    private val ends = AtomicInteger()
    private val opensMemory = AtomicInteger()
    private var narrow = false
    private lateinit var originalFont: String

    @get:Rule val testName = TestName()

    // MainActivity is recreated on posture/configuration changes. Install the same
    // fixture at each real activity creation, before its first frame. Compose itself
    // restores the draft through the Activity's real saved-state registry.
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityPostCreated(host: Activity, state: Bundle?) {
            if (host !is MainActivity) return
            host.setContent {
                MaterialTheme {
                    Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                        Box(Modifier.fillMaxSize()) {
                        Box(if (narrow) Modifier.widthIn(max = 320.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                            ConversationScreen(
                                history = history, busy = MutableStateFlow(false), callState = callState,
                                onSend = { text, _ -> history.appendUser(text); sends.incrementAndGet(); null },
                                selectedModel = LocalModelSpec("layout-fixture", "fixture.bin", recommendedGpu = false),
                                onSelectConversation = { null },
                                onEndVoice = { done -> endCall(); done("Voice Call ended.") },
                                onOpenVoiceCalls = {}, resumedVoice = false,
                                onOpenMemory = { opensMemory.incrementAndGet() },
                                voiceContent = { visible, _, _, startRequest ->
                                    LaunchedEffect(startRequest) {
                                        if (startRequest > 0 && callState.value == VoiceSessionState.PASSIVE_LISTENING) {
                                            callState.value = VoiceSessionState.ACTIVELY_LISTENING
                                            VoiceSessionUi.armed.value = true
                                        }
                                    }
                                    val microphonePaused by paused.collectAsState()
                                    if (visible) VoiceCallOverlay.Bubble(
                                        phase = "Listening", status = "Listening to a controlled layout fixture",
                                        level = .2f, active = true, microphonePaused = microphonePaused,
                                        canStart = false, stopReplyAvailable = false,
                                        onStart = {}, onStopReply = {}, onToggleMicrophone = { paused.value = !paused.value },
                                        onEndCall = { endCall() }, transcriptSpeaker = "", transcript = "")
                                })
                        }
                        }
                    }
                }
            }
        }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    @Before fun prepare() {
        originalFont = device.executeShellCommand("settings get system font_scale").trim()
        VoiceSessionUi.armed.value = false
        VoiceSessionUi.liveTranscript.value = ""
        val prefs = context.getSharedPreferences("release-layout-fixture", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().clear().commit())
        history = ConversationHistory(prefs)
        history.appendUser("A saved conversation must remain available while the layout changes.")
        application.registerActivityLifecycleCallbacks(callbacks)
    }

    @After fun close() {
        try { capture(testName.methodName) } finally {
            activity?.close()
            application.unregisterActivityLifecycleCallbacks(callbacks)
            VoiceSessionUi.armed.value = false
            VoiceSessionUi.liveTranscript.value = ""
            callState.value = VoiceSessionState.PASSIVE_LISTENING
            device.unfreezeRotation()
            if (originalFont == "null") device.executeShellCommand("settings delete system font_scale")
            else device.executeShellCommand("settings put system font_scale $originalFont")
        }
    }

    private fun launch() {
        activity = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        find(By.res("chat_composer"))
    }

    private fun endCall() {
        VoiceSessionUi.armed.value = false
        callState.value = VoiceSessionState.PASSIVE_LISTENING
        ends.incrementAndGet()
    }

    private fun find(selector: BySelector): UiObject2 = device.wait(Until.findObject(selector), 15_000)
        ?: throw AssertionError("Missing accessible control $selector")

    private fun hideKeyboard() {
        activity!!.onActivity { host ->
            host.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .hideSoftInputFromWindow(host.window.decorView.windowToken, 0)
        }
        device.waitForIdle()
    }

    private fun enterDraft(text: String) {
        find(By.res("chat_composer")).text = text
        assertTrue("Accessibility text entry must reach the composer", device.wait(
            Until.hasObject(By.res("chat_composer").text(text)), 5_000))
        assertTrue("A nonempty draft must enable Send", device.wait(
            Until.hasObject(By.res("chat_send").enabled(true)), 5_000))
        hideKeyboard()
    }

    private fun assertCallbackCount(label: String, counter: AtomicInteger, expected: Int) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (counter.get() < expected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25)
        assertEquals("$label callback count", expected, counter.get())
    }

    private fun assertAction(tag: String, description: String? = null): UiObject2 {
        val control = find(By.res(tag))
        assertTrue("$tag is disabled", control.isEnabled)
        assertTrue("$tag is not exposed as an actionable accessibility node", control.isClickable)
        val label = accessibleName(control)
        assertTrue("$tag has no accessible name", label.isNotBlank())
        if (description != null) assertEquals("$tag's spoken name", description, label)
        val bounds = control.visibleBounds
        val minimum = (48 * context.resources.displayMetrics.density).toInt() - 1
        assertTrue("$tag touch target is smaller than 48 dp: $bounds", bounds.width() >= minimum && bounds.height() >= minimum)
        assertTrue("$tag is clipped offscreen: $bounds", bounds.left >= 0 && bounds.top >= 0 &&
            bounds.right <= device.displayWidth && bounds.bottom <= device.displayHeight)
        return control
    }

    // Material buttons expose their text/icon label as noninteractive children
    // on some Android versions. Audit that action's own subtree, rather than
    // mistaking an empty container label for an unnamed control. Never borrow a
    // label from a separate clickable action or a sibling elsewhere on screen.
    private fun accessibleName(control: UiObject2): String {
        val nodes = actionLabelNodes(control)
        val descriptions = nodes.mapNotNull { it.contentDescription }.filter { it.isNotBlank() }
            .distinct().joinToString(" ")
        if (descriptions.isNotBlank()) return descriptions
        return nodes.mapNotNull { it.text }.filter { it.isNotBlank() }.distinct().joinToString(" ")
    }

    private fun actionLabelNodes(control: UiObject2): List<UiObject2> = listOf(control) +
        control.children.filter { !it.isClickable }.flatMap { actionLabelNodes(it) }

    private fun assertCallActions() {
        assertAction("voice_call_pause", if (paused.value) "Resume microphone" else "Pause microphone")
        assertAction("voice_call_end", "End call")
        val status = find(By.res("voice_call_status"))
        assertEquals("Long status must remain available to accessibility", "Listening to a controlled layout fixture", status.contentDescription)
    }

    @Test fun test01_accessibleChatAndCallControls() {
        launch()
        assertAction("memory_open").click()
        assertCallbackCount("Open Memory", opensMemory, 1)
        assertAction("chat_voice_input", "Record voice message")
        enterDraft("Accessibility action reached the real send callback")
        assertAction("chat_send", "Send message").click()
        assertCallbackCount("Send", sends, 1)
        assertTrue(history.current.value.messages.any { it.text == "Accessibility action reached the real send callback" })
        assertAction("voice_call_open", "Start voice call").click()
        assertCallActions()
        assertAction("voice_call_pause", "Pause microphone").click()
        assertTrue(device.wait(Until.hasObject(By.desc("Resume microphone")), 5_000))
        assertAction("voice_call_pause", "Resume microphone").click()
        assertTrue(device.wait(Until.hasObject(By.desc("Pause microphone")), 5_000))
        assertAction("voice_call_end", "End call").click()
        assertTrue(device.wait(Until.gone(By.res("voice_call_end")), 5_000))
        assertCallbackCount("End call", ends, 1)
    }

    @Test fun test02_largeFontSmallScreenKeepsComposerAndCallActions() {
        narrow = true
        device.executeShellCommand("settings put system font_scale 2.0")
        launch()
        activity!!.onActivity { host -> assertTrue("The system must apply 200% font scaling", host.resources.configuration.fontScale >= 1.95f) }
        assertAction("memory_open").click()
        assertCallbackCount("Open Memory", opensMemory, 1)
        enterDraft("Large font input remains usable")
        assertAction("chat_send", "Send message").click()
        assertCallbackCount("Send", sends, 1)
        assertAction("voice_call_open", "Start voice call").click()
        assertCallActions()
        assertAction("voice_call_end", "End call").click()
        assertTrue(device.wait(Until.gone(By.res("voice_call_end")), 5_000))
        assertCallbackCount("End call", ends, 1)
    }

    /** The host sends actual emulator fold/unfold console commands on the fold profile.
     * Other profiles test a real rotation here and report that narrower coverage.
     */
    @Test fun test03_foldAndUnfoldPreserveActiveCall() {
        launch()
        val threadId = history.current.value.id
        assertAction("voice_call_open", "Start voice call").click()
        assertCallActions()
        val draft = "Draft retained across Android configuration changes"
        enterDraft(draft)
        val initial = device.displayWidth to device.displayHeight
        val foldable = InstrumentationRegistry.getArguments().getString("jarvisFoldable") == "true"
        if (foldable) {
            requestPosture("fold")
            awaitDifferentDimensions(initial)
            assertContinuity(threadId, draft)
            capture("${testName.methodName}-folded")
            val folded = device.displayWidth to device.displayHeight
            requestPosture("unfold")
            awaitDifferentDimensions(folded)
            assertContinuity(threadId, draft)
            capture("${testName.methodName}-unfolded")
        } else {
            device.setOrientationLeft()
            awaitDifferentDimensions(initial)
            assertContinuity(threadId, draft)
            capture("${testName.methodName}-rotated")
            val rotated = device.displayWidth to device.displayHeight
            device.setOrientationNatural()
            awaitDifferentDimensions(rotated)
            assertContinuity(threadId, draft)
        }
        assertAction("voice_call_end", "End call").click()
        assertTrue(device.wait(Until.gone(By.res("voice_call_end")), 5_000))
        assertCallbackCount("End call", ends, 1)
    }

    private fun assertContinuity(threadId: String, draft: String) {
        assertEquals("Configuration change must retain the same conversation", threadId, history.current.value.id)
        assertEquals("Configuration change must retain the unsent draft", draft, find(By.res("chat_composer")).text)
        assertTrue("Configuration change must leave the controlled call armed", VoiceSessionUi.armed.value)
        assertEquals(VoiceSessionState.ACTIVELY_LISTENING, callState.value)
        assertEquals("A configuration change must not end the call", 0, ends.get())
        assertCallActions()
    }

    @Test fun test04_nativeLibrariesLoadAtExpectedPageSize() {
        launch()
        val actualPageSize = Os.sysconf(OsConstants._SC_PAGESIZE)
        val expectedPageSize = InstrumentationRegistry.getArguments().getString("jarvisExpectedPageSize")?.toLongOrNull()
            ?: error("The controller must provide the expected emulator page size")
        assertEquals("The configured system image must provide its claimed page size", expectedPageSize, actualPageSize)
        val names = ZipFile(context.applicationInfo.sourceDir).use { apk ->
            apk.entries().asSequence().map { it.name }
                .filter { it.startsWith("lib/arm64-v8a/") && it.endsWith(".so") }
                .map { it.substringAfterLast('/').removePrefix("lib").removeSuffix(".so") }.toList()
        }
        assertTrue("Shipping native runtimes must be present", names.containsAll(listOf(
            "c++_shared", "onnxruntime", "moonshine", "moonshine-jni", "sherpa-onnx-jni", "microwakeword", "litertlm_jni")))
        assertEquals("Native entries must be unique", names.size, names.toSet().size)
        val priority = listOf("c++_shared", "onnxruntime", "ms_ort_1232", "moonshine", "moonshine-jni")
        val loaded = mutableListOf<String>()
        try {
            for (name in names.sortedWith(compareBy<String> { priority.indexOf(it).let { position -> if (position < 0) priority.size else position } }.thenBy { it })) {
                try { System.loadLibrary(name) } catch (error: UnsatisfiedLinkError) {
                    throw AssertionError("Shipping lib$name.so failed to load with PAGE_SIZE=$actualPageSize: ${error.message}", error)
                }
                loaded += name
            }
        } finally {
            val report = JSONObject().put("page_size", actualPageSize).put("expected_page_size", expectedPageSize)
                .put("shipping_libraries", org.json.JSONArray(names)).put("loaded_libraries", org.json.JSONArray(loaded))
                .put("passed", loaded.size == names.size)
                .put("coverage", "Native library loading only; no speech/model inference")
            val file = File(context.cacheDir, "${testName.methodName}-native.json").apply { writeText(report.toString(2)) }
            export(file, "application/json")
        }
        assertEquals("Every shipped native library must actually load", names.toSet(), loaded.toSet())
    }

    private fun requestPosture(posture: String) {
        instrumentation.sendStatus(1, Bundle().apply { putString("jarvisFold", posture) })
    }

    private fun awaitDifferentDimensions(before: Pair<Int, Int>) {
        val deadline = SystemClock.uptimeMillis() + 45_000
        while (SystemClock.uptimeMillis() < deadline) {
            if ((device.displayWidth to device.displayHeight) != before) {
                device.waitForIdle()
                find(By.res("chat_composer"))
                return
            }
            SystemClock.sleep(100)
        }
        fail("Requested posture/rotation did not change real display dimensions from $before")
    }

    private fun capture(name: String) {
        val directory = File(context.cacheDir, "verification-layout").apply { mkdirs() }
        for (extension in listOf("png", "xml")) {
            val file = File(directory, "$name.$extension")
            if (extension == "png") assertTrue(device.takeScreenshot(file)) else device.dumpWindowHierarchy(file)
            export(file, if (extension == "png") "image/png" else "application/xml")
        }
    }

    private fun export(file: File, mime: String) {
        val folder = InstrumentationRegistry.getArguments().getString("jarvisEvidenceDir")
            ?: error("Verification controller must supply a unique evidence directory")
        require(folder.matches(Regex("jarvis-verification-[0-9]+")))
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$folder")
            }
            val resolver = context.contentResolver
            val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            checkNotNull(resolver.openOutputStream(uri)).use { output -> file.inputStream().use { it.copyTo(output) } }
    }
}
