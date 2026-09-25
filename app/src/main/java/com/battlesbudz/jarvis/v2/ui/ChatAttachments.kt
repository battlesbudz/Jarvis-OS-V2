package com.battlesbudz.jarvis.v2.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.chat.*
import kotlinx.coroutines.*

@Composable
internal fun ChatAttachmentPicker(model: LocalModelSpec, enabled: Boolean,
    onBusy: (Boolean) -> Unit, onPrepared: (ChatAttachment) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kind by rememberSaveable { mutableStateOf(AttachmentKind.IMAGE) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val selectedKind = kind
            onBusy(true)
            scope.launch {
                var prepared: ChatAttachment? = null
                var accepted = false
                try {
                    withContext(Dispatchers.IO) { prepared = ChatMediaStore.prepare(context, uri, selectedKind) }
                    onPrepared(requireNotNull(prepared))
                    accepted = true
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { onError(error.message ?: "Could not attach this file.") }
                finally {
                    if (!accepted) prepared?.let { ChatMediaStore.discard(context, it) }
                    onBusy(false)
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        if (model.supportsVision) TextButton(enabled = enabled, onClick = {
            kind = AttachmentKind.IMAGE; picker.launch(arrayOf("image/*"))
        }) { Text("Attach image") }
        if (model.supportsAudio) TextButton(enabled = enabled, onClick = {
            kind = AttachmentKind.AUDIO; picker.launch(arrayOf("audio/wav", "audio/x-wav", "audio/wave"))
        }) { Text("Attach audio") }
    }
}

@Composable
internal fun ChatAttachmentPreview(attachment: ChatAttachment) {
    val thumbnail by produceState<Bitmap?>(null, attachment.uri) {
        if (attachment.kind == AttachmentKind.IMAGE) value = withContext(Dispatchers.IO) {
            runCatching {
                val path = android.net.Uri.parse(attachment.uri).path
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 4 })
            }.getOrNull()
        }
    }
    if (attachment.kind == AttachmentKind.AUDIO) Text("Audio clip attached", style = MaterialTheme.typography.labelMedium)
    else thumbnail?.let {
        Image(it.asImageBitmap(), contentDescription = "Attached image", modifier = Modifier.fillMaxWidth().height(120.dp))
    } ?: Text("Image unavailable", style = MaterialTheme.typography.labelMedium)
}
