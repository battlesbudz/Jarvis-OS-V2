package com.battlesbudz.jarvis.v2.voice

/**
 * Pure state machine orchestrating video capture for a call. Android-free:
 * the [VideoBinder] does the platform work (CameraX), so this is fully
 * unit-testable.
 *
 * Capture is owned by exactly one call identity: [start] records the call
 * that owns the capture, and only [stopForCall] with that same identity (or
 * [stop]) ends it. A stale farewell for an ended call can never stop a
 * newer call's capture, and capture never survives into passive wake
 * listening — the runtime ends it with the call that owned it.
 *
 * States: IDLE -> ACTIVE -> IDLE, or IDLE -> DENIED -> IDLE.
 * DENIED means the camera permission was refused: the binder is never
 * touched and the service degrades to audio-only.
 */
class CallVisionController(
    private val binder: VideoBinder,
    private val cadence: FrameCadence = FrameCadence(),
    private val hub: VisionFrameHub = VisionFrameHub(),
) {
    enum class State { IDLE, DENIED, ACTIVE }

    interface VideoBinder {
        fun bind()
        fun unbind()
    }

    @Volatile
    var state: State = State.IDLE
        private set

    /** The call that owns the active capture; null when nothing is capturing. */
    @Volatile
    private var captureCallId: String? = null

    fun captureCallId(): String? = captureCallId

    /**
     * Start video capture for [callId]. Returns the resulting state.
     * Idempotent for the owning call; a different call rebinds defensively
     * (beginCall guarantees no overlap, but a late start must never inherit
     * a dead call's capture).
     */
    @Synchronized
    fun start(cameraPermissionGranted: Boolean, callId: String): State {
        require(callId.isNotBlank()) { "Video capture needs a call identity." }
        if (state == State.ACTIVE) {
            if (captureCallId == callId) return state
            stopLocked()
        }
        if (!cameraPermissionGranted) {
            state = State.DENIED
            return state
        }
        binder.bind()
        captureCallId = callId
        state = State.ACTIVE
        return state
    }

    /**
     * Stop video capture, but only when [callId] owns it. A stale farewell
     * for an older call is a no-op: it must not end a newer call's capture.
     */
    @Synchronized
    fun stopForCall(callId: String): State {
        if (state == State.ACTIVE && captureCallId == callId) stopLocked()
        return state
    }

    /** Stop video capture and release everything. Safe to call when idle. */
    @Synchronized
    fun stop(): State {
        stopLocked()
        return state
    }

    private fun stopLocked() {
        if (state == State.ACTIVE) binder.unbind()
        state = State.IDLE
        captureCallId = null
        cadence.reset()
        hub.clear()
    }

    /** Temporarily raise the frame rate; only meaningful while ACTIVE. */
    fun requestBurst(durationMs: Long, fps: Double = FrameCadence.BURST_FPS) {
        if (state == State.ACTIVE) cadence.startBurst(durationMs, fps)
    }

    fun hub(): VisionFrameHub = hub
    fun cadence(): FrameCadence = cadence
}
