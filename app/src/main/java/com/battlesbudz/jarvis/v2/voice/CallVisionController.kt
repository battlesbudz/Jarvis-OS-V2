package com.battlesbudz.jarvis.v2.voice

/**
 * Pure state machine orchestrating video capture for a call. Android-free:
 * the [VideoBinder] does the platform work (CameraX), so this is fully
 * unit-testable.
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

    /** Start video capture. Returns the resulting state. Idempotent. */
    @Synchronized
    fun start(cameraPermissionGranted: Boolean): State {
        if (state == State.ACTIVE) return state
        if (!cameraPermissionGranted) {
            state = State.DENIED
            return state
        }
        binder.bind()
        state = State.ACTIVE
        return state
    }

    /** Stop video capture and release everything. Safe to call when idle. */
    @Synchronized
    fun stop(): State {
        if (state == State.ACTIVE) binder.unbind()
        state = State.IDLE
        cadence.reset()
        hub.clear()
        return state
    }

    /** Temporarily raise the frame rate; only meaningful while ACTIVE. */
    fun requestBurst(durationMs: Long, fps: Double = FrameCadence.BURST_FPS) {
        if (state == State.ACTIVE) cadence.startBurst(durationMs, fps)
    }

    fun hub(): VisionFrameHub = hub
    fun cadence(): FrameCadence = cadence
}
