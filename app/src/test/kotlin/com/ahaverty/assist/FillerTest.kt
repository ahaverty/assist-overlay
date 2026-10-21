package com.ahaverty.assist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mic noise that was sent as a question (transcripts 21 Oct 15:13: "The and"), and short replies that must still go. */
class FillerTest {
    @Test
    fun noiseIsFiller() {
        assertTrue(WhisperSpeech.isFiller("The and"))
        assertTrue(WhisperSpeech.isFiller(" you."))
        assertTrue(WhisperSpeech.isFiller("Hmm?"))
        assertTrue(WhisperSpeech.isFiller(""))
    }

    @Test
    fun shortRepliesAreNot() {
        assertFalse(WhisperSpeech.isFiller("Thanks."))
        assertFalse(WhisperSpeech.isFiller("Yes"))
        assertFalse(WhisperSpeech.isFiller("No, the other one"))
    }
}
