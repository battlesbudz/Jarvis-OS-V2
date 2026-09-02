package com.battlesbudz.jarvis.v2.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileActionValidatorTest {
    private val validator = MobileActionValidator()

    @Test
    fun validatesBatteryAction() {
        assertEquals(
            ActionValidation.Valid(MobileAction.ReadBattery),
            validator.validate(ActionRequest("read_battery"))
        )
    }

    @Test
    fun rejectsUnknownActions() {
        val result = validator.validate(ActionRequest("delete_everything"))
        assertTrue(result is ActionValidation.Rejected)
    }

    @Test
    fun validatesBoundedVolume() {
        assertEquals(
            ActionValidation.Valid(MobileAction.SetVolume(50)),
            validator.validate(ActionRequest("set_volume", mapOf("level" to "50")))
        )
    }

    @Test
    fun rejectsPackageSegmentsStartingWithDigits() {
        val result = validator.validate(
            ActionRequest("open_app", mapOf("package" to "com.1example.app"))
        )
        assertTrue(result is ActionValidation.Rejected)
    }
}