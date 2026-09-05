        ) {
            Text(if (isSending) "Thinking…" else "Send")
        }
    }
}

private fun openTranscriptImageStream(
    context: android.content.Context,
    uri: Uri
): InputStream? {
    // Transcript rendering is independent of the raw bytes sent to Gemma.
    return runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
        ?: runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.createInputStream()
        }.getOrNull()
}

@Composable
fun JarvisApp(
    store: ModelStore,
    initialMessages: List<ChatEntry>,
    onRunModelSmokeTest: ((String) -> Unit) -> Unit,
    onImportModel: (Uri, com.battlesbudz.jarvis.v2.ai.LocalModelSpec, (String) -> Unit) -> Unit,
    onCopyDiagnostics: (List<ChatEntry>) -> Unit,
    onMessagesChanged: (List<ChatEntry>) -> Unit,
    onSendingChanged: (Boolean) -> Unit,
    onSend: (String, Uri?, List<ChatEntry>, (String) -> Unit, (String) -> Unit) -> Unit
) {
    var modelsReady by remember { mutableStateOf(store.isUsable()) }
    var smokeTestPassed by rememberSaveable { mutableStateOf(store.isUsable() && store.smokeTestPassed()) }
    var setupStatus by rememberSaveable { mutableStateOf("") }
