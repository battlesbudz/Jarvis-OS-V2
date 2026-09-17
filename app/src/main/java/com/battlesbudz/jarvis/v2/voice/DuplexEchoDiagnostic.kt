package com.battlesbudz.jarvis.v2.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat as AndroidFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.io.File

/** Phase D1: unchanged production media/recognition route, isolated from Gemma and actions. */
object DuplexEchoDiagnostic {
    const val REFERENCE_TEXT = "The sky appears blue because sunlight is scattered by molecules in the atmosphere. " +
        "Blue light scatters more strongly than red light, so it reaches our eyes from across the sky."
    const val USER_TEXT = "No. Yes. I. Stop. Hey Jarvis."
    enum class Scenario(val id: String, val label: String, val instruction: String) {
        JARVIS_ONLY("jarvis_only", "Jarvis alone", "Stay completely silent while Jarvis speaks."),
        USER_ONLY("user_only", "Your voice alone", "Say slowly: $USER_TEXT"),
        DOUBLE_TALK("double_talk", "Both voices", "While Jarvis speaks, say slowly: $USER_TEXT")
    }

    suspend fun run(context: Context, scenario: Scenario, status: (String) -> Unit): DuplexAudioEvidence.Result =
        withContext(Dispatchers.Default) {
            val events = java.util.Collections.synchronizedList(mutableListOf<String>())
            fun log(value: String) { synchronized(events) { if (events.size < 350) events.add("atNs=${System.nanoTime()} $value") } }
            val manager = context.getSystemService(AudioManager::class.java)
            val engine = AsrEngine.selected(context)
            status("Preparing the echo test…")
            val asrDirectory = engine.prepare(context, status)
            val reference = if (scenario != Scenario.USER_ONLY) synthesize(context, status) else byteArrayOf()
            currentCoroutineContext().ensureActive()
            val evidence = DuplexAudioEvidence()
            val input = AndroidAudioInput(this, audioManager = manager, echoCancellation = true,
                noiseSuppression = true, evidence = evidence, log = ::log)
            log("route_policy=production source=VOICE_RECOGNITION usage=MEDIA audioMode=${manager.mode} " +
                "volume=${manager.getStreamVolume(AudioManager.STREAM_MUSIC)}/${manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}")
            var played = 0L
            try {
                withTimeout(16_000) {
                    input.start()
                    val reader = launch { input.chunks().collect { /* Drain the same bounded capture queue used by calls. */ } }
                    try {
                        status(scenario.instruction)
                        if (reference.isNotEmpty()) {
                            // start() already retained 300 ms. Add 700 ms before playback and one second after it.
                            delay(700)
                            played = playReference(reference, evidence, ::log)
                            delay(1000)
                        } else delay(9700)
                    } finally { reader.cancelAndJoin() }
                }
            } finally { withContext(NonCancellable) { input.stop() } }
            currentCoroutineContext().ensureActive()
            val mic = evidence.pcm()
            check(mic.size >= 9 * 32000) { "Capture ended early; repeat the test." }
            status("Analyzing the captured audio…")
            val decoder = RecognitionAudioEvidence()
            val transcript = decode(engine, asrDirectory, mic, decoder, ::log)
            MicroInterruptionKeywords(context.assets).use { keywords ->
                for (offset in mic.indices step 3200) {
                    currentCoroutineContext().ensureActive()
                    val hit = keywords.accept(mic.copyOfRange(offset, minOf(offset + 3200, mic.size)))
                    log("keyword_replay audioMs=${minOf(offset + 3200, mic.size) / 32} hit=$hit ${keywords.diagnosticSummary}")
                }
            }
            val signal = Pcm16Signal.measure(mic)
            val report = """
                Jarvis Phase D1 acoustic evidence
                build=${com.battlesbudz.jarvis.v2.BuildConfig.VERSION_NAME} commit=${com.battlesbudz.jarvis.v2.BuildConfig.SOURCE_COMMIT}
                scenario=${scenario.id} declaredByUser=true device=${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT}
                engine=${engine.id} model=${engine.modelVersion}
                captureRate=16000 channels=1 format=PCM16_LE microphoneMs=${mic.size / 32} rms=${signal.rms} peak=${signal.peak}
                referenceRate=22050 referenceFrames=${reference.size / 2} playedFrames=$played referenceText=$REFERENCE_TEXT referenceTrimmedToMs=8000
                expectedUser=${if (scenario == Scenario.JARVIS_ONLY) "silence" else USER_TEXT}
                transcript=$transcript
                decoderMode=${decoder.mode} decoderMs=${decoder.snapshot().size / 32} decoderTruncated=${decoder.truncated}
                evidenceTruncated=${evidence.truncated} clock=monotonic captureReadTimes=delivery_not_acoustic_onset hardwareTimes=when_available
                microphoneScope=AudioRecord_after_platform_effects preHardwarePcm=unavailable
                referenceScope=PCM_submitted_to_AudioTrack renderedPositions=timeline.csv acousticOutput=not_measured
                playbackPolicy=fixed_8s_stream speed=1.0 generation=completed_before_capture liveCallLoad=not_reproduced
                recognitionScope=offline_full_clip_after_capture noVadFiltering=true liveBargeScheduler=false speakerVerification=false
                keywordScope=offline_100ms_replay readiness_and_hits_are_observations_not_interruptions
                gemma=false tools=false automaticInterruption=false routeChanged=false
                AEC enabled/control state is not proof of effective cancellation. No ERLE or before/after claim is possible without pre-effect PCM.
            """.trimIndent() + "\n" + synchronized(events) { events.joinToString("\n") }
            DuplexAudioEvidence.Result(scenario.id, mic, reference, 22050, decoder.snapshot(), evidence.csv(), report)
        }

    private suspend fun synthesize(context: Context, status: (String) -> Unit): ByteArray {
        val engine = TtsEngine.PIPER_NORTHERN
        val directory = TtsModelStore(context).ensureReady(engine, status)
        currentCoroutineContext().ensureActive()
        val tts = OfflineTts(config = sherpaTtsConfig(engine, directory.absolutePath, 4, piperWholePassage = true))
        try {
            val generated = tts.generateWithConfig(REFERENCE_TEXT, GenerationConfig(silenceScale = 1f, sid = engine.speaker))
            currentCoroutineContext().ensureActive()
            check(generated.sampleRate == 22050 && generated.samples.size >= 8 * 22050) { "Unexpected Piper diagnostic audio format or length." }
            return ByteArray(8 * 22050 * 2).also { pcm ->
                for (i in 0 until pcm.size / 2) {
                    check(generated.samples[i].isFinite())
                    val value = (generated.samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
                    pcm[2 * i] = value.toByte(); pcm[2 * i + 1] = (value shr 8).toByte()
                }
            }
        } finally { tts.release() }
    }

    private suspend fun decode(engine: AsrEngine, directory: File, pcm: ByteArray,
                               evidence: RecognitionAudioEvidence, log: (String) -> Unit): String {
        val transcriber = engine.create(directory, live = false, audioEvidence = evidence, log = log)
        try {
            transcriber.prepareForBoundedProbe(pcm.size / 32L)
            for (offset in pcm.indices step 3200) {
                currentCoroutineContext().ensureActive()
                transcriber.accept(pcm.copyOfRange(offset, minOf(offset + 3200, pcm.size)), allowPartial = false)
            }
            currentCoroutineContext().ensureActive()
            return transcriber.finish()
        } finally { transcriber.close() }
    }

    private suspend fun playReference(pcm: ByteArray, evidence: DuplexAudioEvidence, log: (String) -> Unit): Long = coroutineScope {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AndroidFormat.Builder().setSampleRate(22050).setChannelMask(AndroidFormat.CHANNEL_OUT_MONO)
                .setEncoding(AndroidFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(88200).build()
        try {
            check(track.state == AudioTrack.STATE_INITIALIZED)
            track.setStartThresholdInFramesIfSupported()
            track.play()
            val timestamp = AudioTimestamp()
            val writer = launch(Dispatchers.IO) {
                var offset = 0
                while (offset < pcm.size) {
                    ensureActive()
                    val count = track.write(pcm, offset, minOf(4410, pcm.size - offset), AudioTrack.WRITE_NON_BLOCKING)
                    check(count >= 0) { "Diagnostic playback failed ($count)." }
                    offset += count
                    if (count == 0) delay(10)
                }
            }
            try {
                withTimeout(11_000) {
                    var previousRoute = ""
                    while (true) {
                        ensureActive()
                        val head = track.playbackHeadPosition.toLong() and 0xffffffffL
                        val at = System.nanoTime()
                        val valid = runCatching { track.getTimestamp(timestamp) }.getOrDefault(false)
                        evidence.playback(at, head, timestamp.framePosition.takeIf { valid }, timestamp.nanoTime.takeIf { valid })
                        val route = "type=${track.routedDevice?.type} id=${track.routedDevice?.id} rate=${track.sampleRate}"
                        if (route != previousRoute) { log("playback_route $route"); previousRoute = route }
                        if (head >= pcm.size / 2) { writer.join(); log("playback_finished frames=$head underruns=${track.underrunCount}"); break }
                        delay(40)
                    }
                }
            } finally { writer.cancelAndJoin() }
            track.playbackHeadPosition.toLong() and 0xffffffffL
        } finally { runCatching { track.stop() }; track.release() }
    }

    private fun AudioTrack.setStartThresholdInFramesIfSupported() {
        if (android.os.Build.VERSION.SDK_INT >= 31) setStartThresholdInFrames(2205)
    }
}
