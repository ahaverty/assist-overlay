package com.ahaverty.assist

import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceChunkerTest {
    private fun chunk(vararg deltas: String): List<String> {
        val c = SentenceChunker()
        return deltas.flatMap { c.add(it) } + listOfNotNull(c.flush())
    }

    @Test
    fun statusLineThenAnswer() {
        assertEquals(
            listOf("Looking at the camera…", "The back garden is empty and the gate is shut.", "![Back garden](ha:camera.back_garden)"),
            chunk("Looking at the camera…\n\n", "\n\nThe back garden is empty and the gate is shut.\n\n![Back garden](ha:camera.back_garden)"),
        )
    }

    @Test
    fun leadingBlankLines() {
        assertEquals(listOf("Hello there."), chunk("\n\n\n\nHello there."))
    }

    @Test
    fun sentencesSplitOnceLongEnough() {
        assertEquals(
            listOf("It is cloudy and sixteen degrees outside.", "Wind is strong."),
            chunk("It is cloudy and sixteen degrees outside. Wind is strong."),
        )
    }
}
