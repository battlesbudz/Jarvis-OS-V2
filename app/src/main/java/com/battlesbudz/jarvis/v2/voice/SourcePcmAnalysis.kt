package com.battlesbudz.jarvis.v2.voice

import kotlin.math.log10
import kotlin.math.sqrt

/** Constant-memory PCM16 measurements. Energy is not intelligibility or speaker identity. */
internal class SourcePcmAnalysis(private val rate: Int, private val startFrame: Long,
                                 private val emit: (String) -> Unit) {
    private val windowFrames = (rate / 100).coerceAtLeast(1)
    private var frames = 0L
    private var windowCount = 0
    private var windowEnergy = 0.0
    private var activeEnergy = 0.0
    private var activeFrames = 0L
    private var silentFrames = 0L
    private var silenceStart: Long? = null
    private var intervals = 0
    private var clipped = 0L
    private var finished = false

    init { require(rate > 0 && startFrame >= 0) }

    fun append(pcm: ShortArray) {
        check(!finished)
        for (sample in pcm) {
            val value = sample.toDouble() / 32768.0
            windowEnergy += value * value
            if (kotlin.math.abs(sample.toInt()) >= 32767) clipped++
            frames++; windowCount++
            if (windowCount == windowFrames) flushWindow()
        }
    }

    private fun flushWindow() {
        if (windowCount == 0) return
        val from = startFrame + frames - windowCount
        if (sqrt(windowEnergy / windowCount) < 0.001) {
            if (silenceStart == null) silenceStart = from
            silentFrames += windowCount
        } else {
            closeSilence(from)
            activeEnergy += windowEnergy
            activeFrames += windowCount
        }
        windowCount = 0; windowEnergy = 0.0
    }

    private fun closeSilence(end: Long) {
        silenceStart?.let { start ->
            intervals++
            if (intervals <= 128) emit("event=near_silence startFrame=$start endFrame=$end durationMs=${(end - start) * 1000 / rate}")
        }
        silenceStart = null
    }

    fun finish(complete: Boolean) {
        if (finished) return
        flushWindow(); closeSilence(startFrame + frames); finished = true
        val db = if (activeFrames == 0L) "unavailable" else (10 * log10(activeEnergy / activeFrames)).toString()
        emit("event=source_summary startFrame=$startFrame endFrame=${startFrame + frames} sampleRate=$rate " +
            "windowFrames=$windowFrames rmsThresholdFS=0.001 normalization=pcm16_div_32768 " +
            "activeWindowRmsDbfs=$db activeWindowFrames=$activeFrames nearSilentFrames=$silentFrames " +
            "nearSilentIntervals=$intervals omittedIntervals=${(intervals - 128).coerceAtLeast(0)} " +
            "railPcm16Samples=$clipped railThresholdAbs=32767 complete=$complete finalPartialWindow=included " +
            "scope=accepted_callback_pcm excludes=playback_gaps,fillers " +
            "firstIntelligibleWordMs=unavailable qualityCause=not_determined")
    }
}
