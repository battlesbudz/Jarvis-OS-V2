package com.battlesbudz.jarvis.v2.voice

/**
 * Thread-safe fan-out for captured frames plus a latest-frame cache.
 *
 * Fail-closed by design: if no observer is registered, frames are dropped
 * on the floor — never buffered, so a consumer that never arrives cannot
 * grow memory without bound. The cache holds exactly one frame (the most
 * recent dispatched one) for poll-style consumers.
 */
class VisionFrameHub {
    private val observers = LinkedHashSet<VisionObserver>()

    @Volatile
    private var latest: VisionFrame? = null
    private var droppedForNoObserver = 0L

    @Synchronized
    fun register(observer: VisionObserver) {
        observers += observer
    }

    @Synchronized
    fun unregister(observer: VisionObserver) {
        observers -= observer
    }

    @Synchronized
    fun observerCount(): Int = observers.size

    /**
     * Deliver a frame to all registered observers. Drops the frame (and does
     * not cache it) when nobody is registered. One misbehaving observer can
     * never break delivery to the rest.
     */
    fun dispatch(jpegBytes: ByteArray, timestampMs: Long) {
        val targets: List<VisionObserver>
        synchronized(this) {
            if (observers.isEmpty()) {
                droppedForNoObserver++
                return
            }
            targets = observers.toList()
            latest = VisionFrame(jpegBytes, timestampMs)
        }
        for (observer in targets) {
            try {
                observer.onFrame(jpegBytes, timestampMs)
            } catch (_: Exception) {
                // A throwing observer must not kill delivery to the others.
            }
        }
    }

    /** The most recently dispatched frame, or null if none (or cleared). */
    fun latest(): VisionFrame? = latest

    @Synchronized
    fun clear() {
        latest = null
    }

    @Synchronized
    fun droppedCount(): Long = droppedForNoObserver
}
