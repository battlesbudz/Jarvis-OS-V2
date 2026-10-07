package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class VisionFrameHubTest {
    private class Recording : VisionObserver {
        val frames = mutableListOf<Pair<ByteArray, Long>>()
        override fun onFrame(jpegBytes: ByteArray, timestampMs: Long) {
            frames += jpegBytes to timestampMs
        }
    }

    @Test fun framesAreDroppedWhenNobodyIsRegistered() {
        val hub = VisionFrameHub()
        hub.dispatch(byteArrayOf(1, 2, 3), 1000L)
        assertEquals(1L, hub.droppedCount())
        assertNull(hub.latest())
        assertEquals(0, hub.observerCount())
    }

    @Test fun registeredObserverReceivesFrames() {
        val hub = VisionFrameHub()
        val rec = Recording()
        hub.register(rec)
        hub.dispatch(byteArrayOf(7), 2000L)
        assertEquals(1, rec.frames.size)
        assertArrayEquals(byteArrayOf(7), rec.frames[0].first)
        assertEquals(2000L, rec.frames[0].second)
        assertEquals(0L, hub.droppedCount())
    }

    @Test fun unregisteredObserverStopsReceiving() {
        val hub = VisionFrameHub()
        val rec = Recording()
        hub.register(rec)
        hub.unregister(rec)
        hub.dispatch(byteArrayOf(7), 2000L)
        assertTrue(rec.frames.isEmpty())
        assertEquals(1L, hub.droppedCount())
    }

    @Test fun latestCacheHoldsOnlyTheNewestFrame() {
        val hub = VisionFrameHub()
        hub.register(Recording())
        hub.dispatch(byteArrayOf(1), 1000L)
        hub.dispatch(byteArrayOf(2), 2000L)
        val latest = hub.latest()
        assertNotNull(latest)
        assertArrayEquals(byteArrayOf(2), latest!!.jpegBytes)
        assertEquals(2000L, latest.timestampMs)
    }

    @Test fun clearEmptiesTheCache() {
        val hub = VisionFrameHub()
        hub.register(Recording())
        hub.dispatch(byteArrayOf(1), 1000L)
        hub.clear()
        assertNull(hub.latest())
    }

    @Test fun throwingObserverDoesNotBreakOthers() {
        val hub = VisionFrameHub()
        hub.register(object : VisionObserver {
            override fun onFrame(jpegBytes: ByteArray, timestampMs: Long) {
                throw RuntimeException("boom")
            }
        })
        val rec = Recording()
        hub.register(rec)
        hub.dispatch(byteArrayOf(9), 3000L)
        assertEquals(1, rec.frames.size)
    }

    @Test fun duplicateRegistrationDeliversOnce() {
        val hub = VisionFrameHub()
        val rec = Recording()
        hub.register(rec)
        hub.register(rec)
        hub.dispatch(byteArrayOf(1), 1000L)
        assertEquals(1, rec.frames.size)
        assertEquals(1, hub.observerCount())
    }
}
