package com.battlesbudz.jarvis.v2.memory
import org.junit.Assert.*
import org.junit.Test
class MemorySensitivityPolicyTest {
    @Test fun sensitiveBoundCopiesRemainGenericThroughControllerHistoryDeliveryAndDiagnostics() {
        val prefs=PrivacyPreferences()
        val history=com.battlesbudz.jarvis.v2.chat.ConversationHistory(prefs.preferences,clock={1_000L})
        val calls=com.battlesbudz.jarvis.v2.voice.SharedPreferencesVoiceCallStore(prefs.preferences,key="calls",clock={1_000L})
        val controller=com.battlesbudz.jarvis.v2.voice.VoiceSessionController(calls,nowMs={1_000L})
        val call=controller.beginCall();controller.beginReply(call.id,"reply")
        val raw="Your diagnosis is asthma"
        listOf(raw.take(8),raw).forEach { value ->
            val copy=MemorySensitivityPolicy.durableCopy(value,true)
            history.updateReply(history.current.value.id,"reply",copy,false)
            controller.updateReplyText(call.id,"reply",copy)
        }
        controller.updateReplyText(call.id,"reply",MemorySensitivityPolicy.durableCopy(raw,true),finished=true)
        assertEquals(MemorySensitivityPolicy.PRIVATE_COPY,MemorySensitivityPolicy.publishSensitiveDelivery(controller,call.id,"reply",raw))
        assertEquals(MemorySensitivityPolicy.PRIVATE_COPY,controller.currentTranscript().single().text)
        assertEquals(MemorySensitivityPolicy.PRIVATE_COPY,calls.list().single().transcript.single().delivery!!.spans.single().text)
        val diagnostics=com.battlesbudz.jarvis.v2.diagnostics.DiagnosticRecorder(prefs.preferences,clock={1_000L})
        diagnostics.recordInferencePrompt(raw)
        assertFalse(diagnostics.snapshot().contains("asthma"))
        assertFalse(prefs.durableText().contains("asthma"))
    }
    @Test fun lockRereadChangesPacketAndLivePromptDeliveryFailsClosed() {
        val source=extractionSource("My diagnosis is asthma")
        val record=MemoryAcceptance.records(source,listOf(extractionFact(source)),MemorySnapshot(0,emptyList(),emptyList()),1_000).single()
        var unlocked=true
        val os=MemoryOs(ExtractionLedger(MemorySnapshot(1,listOf(record),emptyList())),{1_000L},{unlocked})
        val visible=os.contextPacket("diagnosis",2_000)
        assertTrue(visible.packet!!.text.contains("asthma"))
        val turn=MemoryTurnContext(visible.packet!!.text,visible.stateToken,"diagnosis",null,0,containsSensitive=true,canDiscloseSensitive={unlocked}){0}
        val fence=MemoryDeliveryFence();val ticket=fence.ticket()
        unlocked=false
        assertTrue(os.read().snapshot!!.memories.isEmpty())
        assertNotEquals(visible.stateToken,os.contextPacket("diagnosis",2_000).stateToken)
        assertEquals("",turn.promptSection())
        var published=false
        assertFalse(fence.publish(ticket,{turn.isCurrent()}){published=true})
        assertFalse(published)
    }
    @Test fun actualComparisonTrialCannotRetainOrExportSensitiveBoundSourceCopies() {
        val comparison=com.battlesbudz.jarvis.v2.voice.comparison.LiveComparison
        val trial=com.battlesbudz.jarvis.v2.voice.comparison.LiveComparison.Trial("private-trial",
            com.battlesbudz.jarvis.v2.voice.comparison.LiveComparison.Request(
                com.battlesbudz.jarvis.v2.voice.comparison.LiveComparison.Path.MOONSHINE,"What is my diagnosis?",1))
        val prompt="Policy\nMemory context: <memory>My diagnosis is asthma</memory>\nCurrent user message: What is my diagnosis?"
        trial.log("prompt audioBytes=0 text=${MemorySensitivityPolicy.comparisonCopy(prompt,true)}")
        listOf("tts", "incremental", "capture", "barge").forEach { channel ->
            trial.log("$channel ${MemorySensitivityPolicy.comparisonCopy("You have asthma",true)}")
        }
        listOf("tts_error", "generation_error", "answer", "final_status", "speech_delivery").forEach { channel ->
            trial.put(channel,MemorySensitivityPolicy.comparisonCopy("You have asthma",true))
        }
        trial.put("llm_setup_ms",12L)
        val previous=comparison.results.value
        try {
            comparison.finish(trial) // The immutable copies stay safe after live disclosure is gone.
            val retained=comparison.results.value.last()
            assertFalse(retained.report().contains("asthma"))
            assertTrue(retained.report().contains(MemorySensitivityPolicy.PRIVATE_COPY))
            assertTrue(retained.report().contains("llm_setup_ms"))
            val bytes=java.io.ByteArrayOutputStream();retained.writeZip(bytes)
            val zip=java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes.toByteArray()))
            zip.use { assertEquals("report.txt",it.nextEntry.name);assertFalse(it.readBytes().toString(Charsets.UTF_8).contains("asthma")) }
        } finally { comparison.results.value=previous }
    }
    @Test fun comparisonScansFullSourceBeforeTruncationAndRetainsTypedNumericMetrics() {
        val prefix="I like apricots. ".repeat(150)
        assertEquals(SourceTextPersistencePolicy.EXCLUDED,MemorySensitivityPolicy.comparisonCopy(prefix+"My password is comet-secret-value",false))
        assertEquals(MemorySensitivityPolicy.PRIVATE_COPY,MemorySensitivityPolicy.comparisonCopy("My diagnosis is asthma",false))
        assertEquals("I like apricots",MemorySensitivityPolicy.comparisonCopy("I like apricots",false))
        val tts=com.battlesbudz.jarvis.v2.voice.TtsSessionMetrics(10,20,30,40,50,1f,60,2,3,20,"hash",4,true,"asthma",
            outputRoute="asthma",sourcePcmSummary="asthma",pcmDelivery="asthma")
        val metrics=MemorySensitivityPolicy.comparisonTtsMetrics(tts)
        assertEquals(30,metrics.getInt("synthesis_ms"));assertEquals(2,metrics.getInt("underruns"))
        assertFalse(metrics.toString().contains("asthma"))
        val asr=com.battlesbudz.jarvis.v2.voice.AsrCaptureMetrics(10,20,30,40,50,60,2,70,"asthma",endpointCue="asthma")
        assertEquals(40,MemorySensitivityPolicy.comparisonAsrMetrics(asr).getInt("decode_ms"))
        assertFalse(MemorySensitivityPolicy.comparisonAsrMetrics(asr).toString().contains("asthma"))
    }
}
