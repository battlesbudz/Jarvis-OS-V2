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
}
