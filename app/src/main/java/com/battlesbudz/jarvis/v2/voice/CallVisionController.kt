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
 * States: IDLE -> ACTIVE -> IDLE, IDLE -> DENIED -> IDLE, or
 * ACTIVE -> DEGRADED -> IDLE.
 * DENIED means the camera permission was refused: the binder is never
 * touched and the service degrades to audio-only. DEGRADED means the
 * permission was granted but the camera itself failed (provider failure,
 * no back camera, bind failure): the binder is released and the call
 * continues audio-only. The owning call identity is retained through
 * DEGRADED so ending the call still cleans up exactly once.
 */
class CallVisionController(
    private val binder: VideoBinder,
    private val cadence: FrameCadence = FrameCadence(),
    private val hub: VisionFrameHub = VisionFrameHub(),
) {
    enum class State { IDLE, DENIED, ACTIVE, DEGRADED }

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

    /** Why the controller degraded, when [state] is DEGRADED; null otherwise. */
    @Volatile
    private var videoError: VideoError? = null

    fun videoError(): VideoError? = videoError

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
        videoError = null
        state = State.ACTIVE
        return state
    }

    /**
     * The camera failed while this call owned the capture (provider failure,
     * no back camera, or bind failure). Release the camera, keep the call
     * identity so [stopForCall] still ends the call exactly once, and report
     * honest audio-only: the call continues, video does not. No-op unless
     * the capture is ACTIVE.
     */
    @Synchronized
    fun degrade(error: VideoError): State {
        if (state == State.ACTIVE) {
            binder.unbind()
            state = State.DEGRADED
            videoError = error
            cadence.reset()
            hub.clear()
        }
        return state
    }

    /**
     * Stop video capture, but only when [callId] owns it. A stale farewell
     * for an older call is a no-op: it must not end a newer call's capture.
     */
    @Synchronized
    fun stopForCall(callId: String): State {
        if ((state == State.ACTIVE || state == State.DEGRADED) && captureCallId == callId) stopLocked()
        return state
    }

    /** Stop video capture and release everything. Safe to call when idle. */
    @Synchronized
    fun stop(): State {
        stopLocked()
        return state
    }

    private fun stopLocked() {
        if (state == State.ACTIVE || state == State.DEGRADED) binder.unbind()
        state = State.IDLE
        captureCallId = null
        videoError = null
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
