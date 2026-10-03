package ai.moonshine.voice

import com.battlesbudz.jarvis.v2.voice.MoonshineUpdateCadence
import org.junit.Assert.*
import org.junit.Test

class MoonshineUpdateCadenceTest {
    @Test fun backlogFeedsEverySampleButCoalescesIntermediateUpdates() {
        val sdk = Transcriber()
        val cadence = MoonshineUpdateCadence(sdk, 0.25)
        repeat(40) {
            cadence.beforeAccept(3200, allowPartial = false)
            assertFalse(due(sdk, 1, 1600))
        }
        cadence.beforeAccept(3200, allowPartial = true)
        assertTrue("Catch-up must request a fresh hypothesis once current", due(sdk, 1, 1600))
        cadence.restore()
        assertTrue(due(sdk, 2, 4000))
    }

    // Exercise the actual pinned SDK's Java cadence, without loading a native model.
    private fun due(sdk: Transcriber, stream: Int, samples: Int): Boolean =
        sdk.isUpdateDue(stream, samples, 16000)

    @Test fun boundedFourSecondProbeDoesNotRequestIntermediateTranscription() {
        val sdk = Transcriber()
        val cadence = MoonshineUpdateCadence(sdk, 0.25)
        cadence.prepareProbe(4000)
        repeat(16) {
            cadence.beforeAccept(8000)
            assertFalse(due(sdk, 1, 4000))
        }
        try { cadence.beforeAccept(2); fail("Must reject audio beyond the bounded interval") }
        catch (_: IllegalStateException) { }
    }

    @Test fun returnedWeightsAndNextStreamRestoreOrdinaryUpdates() {
        val sdk = Transcriber()
        val cadence = MoonshineUpdateCadence(sdk, 0.25)
        cadence.prepareProbe(4000)
        cadence.beforeAccept(32000)
        assertFalse(due(sdk, 1, 16000))
        cadence.restore()
        assertTrue(due(sdk, 2, 4000))
        // Construction also restores cadence if a preceding borrower left it changed.
        sdk.setUpdateInterval(5.0)
        MoonshineUpdateCadence(sdk, 0.25)
        assertTrue(due(sdk, 3, 4000))
    }

    @Test fun cannotSwitchAnAlreadyFedStreamToProbeMode() {
        val cadence = MoonshineUpdateCadence(Transcriber(), 0.25)
        cadence.beforeAccept(3200)
        try { cadence.prepareProbe(4000); fail("Mode must precede audio") }
        catch (_: IllegalStateException) { }
    }
}
