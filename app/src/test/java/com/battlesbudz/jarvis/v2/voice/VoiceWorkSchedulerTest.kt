package com.battlesbudz.jarvis.v2.voice
import org.junit.Assert.*
import org.junit.Test
class VoiceWorkSchedulerTest {
 @Test fun resourcePressureAndRestartChurnSuppressOnlyOptionalWork() {
  val scheduler = VoiceWorkScheduler()
  assertFalse(scheduler.admit(0, 500, 0, 0))
  assertFalse(scheduler.admit(0, 0, 200, 0))
  assertFalse(scheduler.admit(0, 0, 0, 2))
  assertTrue(scheduler.admit(0, 0, 0, 0))
  assertFalse(scheduler.admit(100, 0, 0, 0))
  assertTrue(scheduler.admit(2000, 0, 0, 0))
 }
 @Test fun poorReuseAndSlowCancellationHaveRenewableCooldowns() {
  val scheduler = VoiceWorkScheduler()
  repeat(3) { scheduler.outcome(it * 2000L, false, 0) }
  assertFalse(scheduler.admit(6000, 0, 0, 0))
  assertTrue(scheduler.admit(19000, 0, 0, 0))
  scheduler.outcome(20000, true, 900)
  assertFalse(scheduler.admit(49999, 0, 0, 0))
  assertTrue(scheduler.admit(50000, 0, 0, 0))
 }
}
