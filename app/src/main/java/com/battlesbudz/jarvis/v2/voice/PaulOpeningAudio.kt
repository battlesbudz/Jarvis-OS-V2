package com.battlesbudz.jarvis.v2.voice

import android.content.res.AssetManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Fixed neutral Paul cues. It is never regenerated on the phone. */
internal object PaulOpeningAudio {
    const val ASSET = "voice/paul-one-moment-v2.wav"
    const val SHA256 = "5ac7821f968ae2a8d7c021dfc38ff4279709b0095d03c9d8d25e96cd46cffa35"
    const val RECOVERY_ASSET = "voice/paul-bear-with-me-v2.wav"
    const val RECOVERY_SHA256 = "82dc70ac006647be63a6879207b846082ebe18bb9fd6a56e0e6d85878bc552b3"
    fun loadRecovery(assets: AssetManager): ShortArray = decode(assets.open(RECOVERY_ASSET).use { it.readBytes() }, RECOVERY_SHA256)
    fun load(assets: AssetManager): ShortArray = decode(assets.open(ASSET).use { it.readBytes() })
    fun decode(wav: ByteArray, expectedHash: String = SHA256): ShortArray {
        check(MessageDigest.getInstance("SHA-256").digest(wav).joinToString("") { "%02x".format(it) } == expectedHash)
        val data = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        check(data.getInt(24) == 24000 && data.getShort(22).toInt() == 1 && data.getShort(34).toInt() == 16)
        check(data.getInt(40) == wav.size - 44)
        return ShortArray((wav.size - 44) / 2) { data.getShort(44 + it * 2) }
    }
}
