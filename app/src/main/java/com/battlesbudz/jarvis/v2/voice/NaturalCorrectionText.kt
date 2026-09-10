package com.battlesbudz.jarvis.v2.voice

import java.util.Locale

/** Remove only leading echo. Preserve later corrections, negation and original punctuation. */
object NaturalCorrectionText {
    fun resolve(finalText: String, reference: String): String? {
        val gate = BargeInGate()
        gate.update(true, false, 0, finalText, reference)
        if (gate.update(true, false, 300, finalText, reference) != BargeInGate.Action.CONFIRM) return null
        val tokens = Regex("[\\p{L}\\p{N}']+").findAll(finalText).toList()
        val request = gate.requestText.split(' ')
        for (start in tokens.indices) {
            if (start + request.size > tokens.size) break
            if (request.indices.all { offset -> tokens[start + offset].value.lowercase(Locale.ROOT) == request[offset] }) {
                return finalText.substring(tokens[start].range.first).trim()
            }
        }
        return null
    }
}
