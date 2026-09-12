package com.battlesbudz.jarvis.v2.voice

import java.security.MessageDigest

/** Exact float-bit parity independent of callback boundaries, without retaining a second utterance. */
internal class CallbackPcmParity {
    private val callback = MessageDigest.getInstance("SHA-256")
    private var frames = 0L
    private var finished = false

    fun append(samples: FloatArray) {
        check(!finished)
        update(callback, samples)
        frames += samples.size
    }

    data class Result(val matches: Boolean, val callbackHash: String, val returnedHash: String, val frames: Long)

    fun finish(returned: FloatArray): Result {
        check(!finished); finished = true
        val returnedDigest = MessageDigest.getInstance("SHA-256")
        update(returnedDigest, returned)
        val a = callback.digest().toHex(); val b = returnedDigest.digest().toHex()
        return Result(frames == returned.size.toLong() && a == b, a, b, frames)
    }

    private fun update(digest: MessageDigest, samples: FloatArray) {
        val bytes = ByteArray(4096)
        var used = 0
        for (sample in samples) {
            val bits = sample.toRawBits()
            for (shift in 0..24 step 8) bytes[used++] = (bits ushr shift).toByte()
            if (used == bytes.size) { digest.update(bytes); used = 0 }
        }
        if (used > 0) digest.update(bytes, 0, used)
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
