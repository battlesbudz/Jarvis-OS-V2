package com.battlesbudz.jarvis.v2.ai

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents

/** One native user message keeps the recording and its accompanying text together. */
internal fun audioMessageContents(prompt: String, audioBytes: ByteArray): Contents {
    require(audioBytes.isNotEmpty()) { "The voice recording is empty." }
    return Contents.of(Content.AudioBytes(audioBytes), Content.Text(prompt))
}
