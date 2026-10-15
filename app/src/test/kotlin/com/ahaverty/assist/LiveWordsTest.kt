package com.ahaverty.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveWordsTest {
    @Test
    fun mergeDropsRepeatedWords() {
        assertEquals("turn on the kitchen lights please", LiveWords.merge("turn on the kitchen lights", "the kitchen lights please"))
        assertEquals("Turn on the kitchen lights. please", LiveWords.merge("Turn on the kitchen lights.", "kitchen lights please"))
        assertEquals("set a timer for ten minutes", LiveWords.merge("set a timer", "for ten minutes"))
    }

    @Test
    fun mergeReplacesAClippedLastWord() {
        // From a replay: the draft was cut just inside "I'm", and Google's tail started a few words back.
        assertEquals(
            "overwriting the entire paragraph as if I'm just starting",
            LiveWords.merge("overwriting the entire paragraph as if I", "paragraph as if I'm just starting"),
        )
    }

    @Test
    fun mergeTakesACumulativeFinal() {
        assertEquals("what's the weather like tomorrow", LiveWords.merge("what's the weather", "what's the weather like tomorrow"))
    }

    @Test
    fun afterFindsNewWordsWhenGoogleGrows() {
        assertEquals("and the hall", LiveWords.after("turn on the kitchen lights and the hall", "turn on the kitchen lights"))
    }

    @Test
    fun afterSurvivesARevisionEarlierOn() {
        // Google merged "turn on" into one word after the draft was taken: counting would repeat "lights".
        assertEquals("and the hall", LiveWords.after("turnon the kitchen lights and the hall", "turn on the kitchen lights"))
        // ...or split a word: counting would hide "and".
        assertEquals("and the hall", LiveWords.after("turn on the kit chen lights and the hall", "turn on the kitchen lights"))
    }

    @Test
    fun draftThenGoogleLagShowsNothingTwice() {
        // Whisper's draft heard "lights", Google had only got to "kitchen" when the audio was taken.
        val tail = LiveWords.after("turn on the kitchen lights and the hall", "turn on the kitchen")
        assertEquals("Turn on the kitchen lights and the hall", LiveWords.merge("Turn on the kitchen lights", tail))
    }

    @Test
    fun startsLike() {
        assertTrue(LiveWords.startsLike("turn on the lights and the fan", "turn on the lights"))
        assertFalse(LiveWords.startsLike("and the fan", "turn on the lights"))
        assertFalse(LiveWords.startsLike("anything", ""))
    }
}
