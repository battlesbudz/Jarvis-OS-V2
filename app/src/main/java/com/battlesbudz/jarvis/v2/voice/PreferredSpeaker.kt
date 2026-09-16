package com.battlesbudz.jarvis.v2.voice

import kotlin.math.sqrt

/** A preference, not identity authentication. Each activation has a capped vote; room audio
 * outside completed, explicitly activated turns never trains this model. */
class PreferredSpeaker(val candidates: MutableList<Candidate> = mutableListOf()) {
    data class Candidate(var vector: FloatArray, var weight: Float, var turns: Int, var lastActivation: String)
    val preferred: Candidate? get() {
        val ranked = candidates.sortedByDescending { it.weight }
        val best = ranked.firstOrNull() ?: return null
        return best.takeIf { it.turns >= 3 && it.weight >= 6f && it.weight > (ranked.getOrNull(1)?.weight ?: 0f) * 2f }
    }
    fun score(vector: FloatArray): Float? = preferred?.let { cosine(it.vector, vector) }
    fun observe(vector: FloatArray, seconds: Float, activation: String) {
        if (seconds < 1.5f || vector.isEmpty() || vector.any { !it.isFinite() } || cosine(vector, vector) < 0.9f) return
        val best = candidates.maxByOrNull { cosine(it.vector, vector) }
        val match = best?.takeIf { cosine(it.vector, vector) >= 0.65f }
        // Once learned, unrelated voices cannot replace the preference in the background.
        if (preferred != null && (match !== preferred)) return
        if (match == null) {
            if (candidates.size < 4) candidates.add(Candidate(normalize(vector), seconds.coerceAtMost(4f), 1, activation))
            return
        }
        if (match.lastActivation == activation) return
        val weight = seconds.coerceAtMost(4f)
        val oldWeight = match.weight.coerceAtMost(20f)
        match.vector = normalize(FloatArray(vector.size) { (match.vector[it] * oldWeight + vector[it] * weight) / (oldWeight + weight) })
        match.weight = (match.weight + weight).coerceAtMost(40f)
        match.turns++; match.lastActivation = activation
    }
    companion object {
        fun cosine(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size || a.isEmpty()) return -1f
            var dot = 0.0; var aa = 0.0; var bb = 0.0
            for (i in a.indices) { dot += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i] }
            return if (aa <= 0 || bb <= 0) -1f else (dot / sqrt(aa * bb)).toFloat()
        }
        fun normalize(a: FloatArray): FloatArray {
            val length = sqrt(a.sumOf { it.toDouble() * it }).toFloat()
            return if (length > 0) FloatArray(a.size) { a[it] / length } else a.copyOf()
        }
    }
}
