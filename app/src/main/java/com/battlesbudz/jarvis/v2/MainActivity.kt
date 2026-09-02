package com.battlesbudz.jarvis.v2

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import com.battlesbudz.jarvis.v2.ai.ModelCatalog
import com.battlesbudz.jarvis.v2.ai.ModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var modelStore: ModelStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modelStore = ModelStore(applicationContext)
        setContent {
            JarvisApp(
                store = modelStore,
                onRunModelSmokeTest = { runModelSmokeTest(it) },
                onImportModel = { uri, spec, report -> importModel(uri, spec, report) },
                onSend = { prompt, onToken, onComplete ->
                    runConversation(prompt, onToken, onComplete)
                }
            )
        }
    }

    private fun runModelSmokeTest(report: (String) -> Unit) {
        lifecycleScope.launch(Dispatchers.Default) {
            mainHandler.post { report("Loading local models…") }
            var primary: LiteRtLmEngine? = null
            var actions: LiteRtLmEngine? = null
            try {
                check(modelStore.verifyIntegrity(ModelCatalog.gemma4E2b)) {
                    "The Gemma model file changed or failed integrity verification. Re-import it."
                }
                check(modelStore.verifyIntegrity(ModelCatalog.mobileActions270m)) {
                    "The MobileActions model file changed or failed integrity verification. Re-import it."
                }
                primary = LiteRtLmEngine(
                    ModelCatalog.gemma4E2b.id,
                    modelStore.fileFor(ModelCatalog.gemma4E2b).path,
                    cacheDir.path,
                    useGpu = true
                )
                actions = LiteRtLmEngine(
                    ModelCatalog.mobileActions270m.id,
                    modelStore.fileFor(ModelCatalog.mobileActions270m).path,
                    cacheDir.path,
                    useGpu = false
                )
                primary.initialize()
                actions.initialize()
                val primaryProbe = primary.generate(
                    "Reply with exactly GEMMA_PR1_OK and nothing else.",
                    onToken = {}
                )
                check(primaryProbe.text.contains("GEMMA_PR1_OK", ignoreCase = true)) {
                    "The selected Gemma file did not pass its identity probe."
                }
                val actionProbe = actions.generate(
                    "Return exactly the read_battery action name and nothing else.",
                    onToken = {}
                )
                check(actionProbe.text.contains("read_battery", ignoreCase = true)) {
                    "The selected MobileActions file did not pass its identity probe."
                }
                modelStore.markSmokeTestPassed()
                mainHandler.post { report("Both local models initialized successfully.") }
            } catch (error: Throwable) {
                mainHandler.post { report("Local model test failed: ${error.message ?: "unknown error"}") }
            } finally {
                actions?.close()
                primary?.close()
            }
        }
    }

    private fun importModel(
        uri: Uri,
        spec: com.battlesbudz.jarvis.v2.ai.LocalModelSpec,
        report: (String) -> Unit
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = modelStore.importModel(uri, spec)
            withContext(Dispatchers.Main) {
                report(result.fold(
                    { "Model imported successfully." },
                    { error ->
                        val message = error.message ?: "unknown error"
                        "Import failed: $message"
                    }
                ))
            }
        }
    }

    private fun runConversation(
        prompt: String,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit
    ) {
        lifecycleScope.launch(Dispatchers.Default) {
            var engine: LiteRtLmEngine? = null
            try {
                engine = LiteRtLmEngine(
                    ModelCatalog.gemma4E2b.id,
                    modelStore.fileFor(ModelCatalog.gemma4E2b).path,
                    cacheDir.path,
                    useGpu = true
                )
                engine.initialize()
                val generated = engine.generate(prompt) { token ->
                    mainHandler.post { onToken(token) }
                }
                mainHandler.post { onComplete(generated.text) }
            } catch (error: Throwable) {
                mainHandler.post { onComplete("I could not load the local model: ${error.message ?: "unknown error"}") }
            } finally {
                engine?.close()
            }
        }
    }

}

@Composable
private fun JarvisApp(
    store: ModelStore,
    onRunModelSmokeTest: ((String) -> Unit) -> Unit,
    onImportModel: (Uri, com.battlesbudz.jarvis.v2.ai.LocalModelSpec, (String) -> Unit) -> Unit,
    onSend: (String, (String) -> Unit, (String) -> Unit) -> Unit
) {
    var modelsReady by remember { mutableStateOf(store.isReady()) }
    var smokeTestPassed by rememberSaveable { mutableStateOf(store.isReady() && store.smokeTestPassed()) }
    var setupStatus by rememberSaveable { mutableStateOf("") }
    var smokeTestRunning by remember { mutableStateOf(false) }
    var modelImportRunning by remember { mutableStateOf(false) }

    // An import can finish after the previous Activity is destroyed during
    // rotation/fold changes. Keep the replacement screen synchronized with
    // the durable files even when the old callback was cancelled.
    LaunchedEffect(Unit) {
        while (!modelsReady) {
            delay(500)
            modelsReady = store.isReady()
        }
    }

    val importModel: (Uri?, com.battlesbudz.jarvis.v2.ai.LocalModelSpec) -> Unit = { uri, spec ->
        if (uri != null) {
            modelImportRunning = true
            setupStatus = "Importing local model…"
            onImportModel(uri, spec) { result ->
                modelImportRunning = false
                setupStatus = result
                if (result == "Model imported successfully.") modelsReady = store.isReady()
            }
        }
    }
    val gemmaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        importModel(uri, ModelCatalog.gemma4E2b)
    }
    val actionsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        importModel(uri, ModelCatalog.mobileActions270m)
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (modelsReady && smokeTestPassed) {
                JarvisChat(onSend)
            } else {
                ModelSetup(
                    ready = modelsReady,
                    testing = smokeTestRunning,
                    importing = modelImportRunning,
                    status = setupStatus,
                    onPickGemma = { gemmaPicker.launch(arrayOf("*/*")) },
                    onPickActions = { actionsPicker.launch(arrayOf("*/*")) },
                    onTest = {
                        smokeTestRunning = true
                        onRunModelSmokeTest { result ->
                            smokeTestRunning = false
                            setupStatus = result
                            if (result == "Both local models initialized successfully.") {
                                smokeTestPassed = true
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ModelSetup(
    ready: Boolean,
    testing: Boolean,
    importing: Boolean,
    status: String,
    onPickGemma: () -> Unit,
    onPickActions: () -> Unit,
    onTest: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Jarvis setup", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Choose the two local model files. They are stored privately on this phone.",
            modifier = Modifier.padding(top = 12.dp, bottom = 20.dp)
        )
        Button(onClick = onPickGemma, modifier = Modifier.fillMaxWidth(), enabled = !testing && !importing) {
            Text("Choose Gemma 4 E2B")
        }
        Button(
            onClick = onPickActions,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            enabled = !testing && !importing
        ) {
            Text("Choose MobileActions")
        }
        if (status.isNotBlank()) Text(status, modifier = Modifier.padding(top = 20.dp))
        Button(
            onClick = onTest,
            enabled = ready && !testing && !importing,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
        ) {
            Text(if (testing) "Testing local models…" else "Test local models")
        }
    }
}

@Composable
private fun JarvisChat(
    onSend: (String, (String) -> Unit, (String) -> Unit) -> Unit
) {
    var prompt by rememberSaveable { mutableStateOf("") }
    var response by rememberSaveable { mutableStateOf("") }
    // Sending is tied to the current Activity's lifecycle job. Do not restore
    // it across recreation after that job has been cancelled.
    var isSending by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        if (response.isNotBlank()) {
            Text(
                response,
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 20.dp)
            )
        }
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Message Jarvis") },
            maxLines = 4,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                val submitted = prompt
                prompt = ""
                response = ""
                isSending = true
                onSend(submitted, { token -> response = response + token }, { result -> response = result; isSending = false })
            },
            enabled = prompt.isNotBlank() && !isSending,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        ) {
            Text(if (isSending) "Thinking…" else "Send")
        }
    }
}
