package com.battlesbudz.jarvis.v2.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Runs only on VAD-qualified candidate speech, before final text/tool dispatch. Raw audio is
 * discarded after capture; only bounded embedding centroids persist on the phone. */
class SpeakerPreferenceGuard(context: Context, model: File, private val activation: String,
                             private val learnFromActivation: Boolean, private val log: (String) -> Unit) : AutoCloseable {
    private val prefs = context.getSharedPreferences("speaker_preference_v1", Context.MODE_PRIVATE)
    private val state = load()
    private val extractor = SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(model = model.path, numThreads = 1))
    private var vectors = emptyList<FloatArray>()
    private var seconds = 0f
    fun accept(pcm: ByteArray): Boolean {
        vectors = emptyList(); seconds = pcm.size / 32000f
        // Two separate windows avoid rejecting the owner based on one mixed/short fragment.
        if (pcm.size < 96000) { log("speaker_preference decision=uncertain reason=short_speech learned=${state.preferred != null}"); return true }
        val began = System.nanoTime()
        vectors = listOf(pcm.copyOfRange(0, 48000), pcm.copyOfRange(pcm.size - 48000, pcm.size)).mapNotNull(::embedding)
        val scores = vectors.mapNotNull(state::score)
        val reject = scores.size == 2 && scores.all { it < 0.35f }
        log("speaker_preference decision=${if (reject) "reject" else "accept_or_uncertain"} learned=${state.preferred != null} scores=${scores.joinToString(",")} voicedMs=${pcm.size / 32} computeMs=${(System.nanoTime()-began)/1_000_000}")
        return !reject
    }
    fun accepted(text: String) {
        if (!learnFromActivation || text.trim().split(Regex("\\s+")).size < 3 || seconds !in 3f..12f || vectors.size != 2) return
        if (PreferredSpeaker.cosine(vectors[0], vectors[1]) < 0.65f) { log("speaker_preference training=skipped reason=inconsistent_windows"); return }
        val vector = PreferredSpeaker.normalize(FloatArray(vectors[0].size) { (vectors[0][it] + vectors[1][it]) / 2 })
        state.observe(vector, seconds, activation)
        prefs.edit().putString("centroids", JSONArray().also { a -> state.candidates.forEach { c ->
            a.put(JSONObject().put("v", JSONArray(c.vector.toList())).put("weight", c.weight)
                .put("turns", c.turns).put("activation", c.lastActivation))
        } }.toString()).apply()
        log("speaker_preference training=observed learned=${state.preferred != null} candidates=${state.candidates.size}")
    }
    private fun embedding(pcm: ByteArray): FloatArray? {
        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(pcmFloats(pcm), 16000); stream.inputFinished()
            if (extractor.isReady(stream)) extractor.compute(stream).takeIf { v -> v.size == extractor.dim() && v.all { it.isFinite() } } else null
        } finally { stream.release() }
    }
    private fun load(): PreferredSpeaker = runCatching {
        val a = JSONArray(prefs.getString("centroids", "[]"))
        PreferredSpeaker((0 until minOf(a.length(), 4)).map { i -> val c = a.getJSONObject(i); val v = c.getJSONArray("v")
            require(v.length() in 1..1024)
            PreferredSpeaker.Candidate(FloatArray(v.length()) { v.getDouble(it).toFloat() }, c.getDouble("weight").toFloat(), c.getInt("turns"), c.getString("activation"))
        }.toMutableList())
    }.getOrElse { PreferredSpeaker() }
    override fun close() = extractor.release()
}
