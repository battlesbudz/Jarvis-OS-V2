        }
    }

    private fun runConversation(
        prompt: String,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit
    ) {
        lifecycleScope.launch(Dispatchers.Default) {
            var newlyCreatedEngine: LiteRtLmEngine? = null
            try {
                check(modelStore.verifyIntegrity(ModelCatalog.gemma4E2b)) {
                    "The Gemma model file changed or failed integrity verification. Re-import it."
                }
                val engine: LiteRtLmEngine
                if (conversationEngine == null) {
                    val created = LiteRtLmEngine(
                        ModelCatalog.gemma4E2b.id,
                        modelStore.fileFor(ModelCatalog.gemma4E2b).path,
                        cacheDir.path,
                        useGpu = true
                    )
                    newlyCreatedEngine = created
                    created.initialize()
                    conversationEngine = created
                    engine = created
                } else {
                    engine = conversationEngine!!
                }
                val generated = engine.generate(prompt) { token ->
                    mainHandler.post { onToken(token) }
                }
                mainHandler.post { onComplete(generated.text) }
            } catch (error: Throwable) {
                mainHandler.post { onComplete("I could not load the local model: ${error.message ?: "unknown error"}") }
            } finally {
                if (newlyCreatedEngine != null && conversationEngine !== newlyCreatedEngine) {
                    newlyCreatedEngine?.close()
                }
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
                        onRunModelSmokeTest.invoke { result ->
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
