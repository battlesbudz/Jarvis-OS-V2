package com.battlesbudz.jarvis.v2.voice

import android.content.res.AssetManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Fixed context-generated Paul interjection. It is never regenerated on the phone. */
internal object PaulOpeningAudio {
    const val ASSET = "voice/paul-umm-v1.wav"
    const val SHA256 = "9fcc714ad8ef2188bd706a0fe50156442efe7368a58567ba0f5c15ae4929c7fe"
    fun load(assets: AssetManager): ShortArray = decode(assets.open(ASSET).use { it.readBytes() })
    fun decode(wav: ByteArray): ShortArray {
        check(MessageDigest.getInstance("SHA-256").digest(wav).joinToString("") { "%02x".format(it) } == SHA256)
        val data = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        check(data.getInt(24) == 24000 && data.getShort(22).toInt() == 1 && data.getShort(34).toInt() == 16)
        check(data.getInt(40) == wav.size - 44)
        return ShortArray((wav.size - 44) / 2) { data.getShort(44 + it * 2) }
    }
}
