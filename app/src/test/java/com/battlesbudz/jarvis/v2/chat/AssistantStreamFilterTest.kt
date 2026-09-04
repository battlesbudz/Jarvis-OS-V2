package com.battlesbudz.jarvis.v2.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantStreamFilterTest {
    @Test
    fun ordinaryTextIsEmittedImmediately() {
        val emitted = mutableListOf<String>()
        val filter = AssistantStreamFilter(emitted::add)

        filter.accept("Hello")
        filter.accept(" world")

        assertEquals(listOf("Hello", " world"), emitted)
    }

    @Test
    fun toolMarkupIsNeverEmitted() {
        val emitted = mutableListOf<String>()
        val filter = AssistantStreamFilter(emitted::add)

        filter.accept("<|tool_")
        filter.accept("call|>call:MobileActions:open_app{}")

        assertEquals(emptyList<String>(), emitted)
    }
}
