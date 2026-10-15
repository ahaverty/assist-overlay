package com.ahaverty.assist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Google's partials from replays on the phone (15 Oct): a new utterance in the same session, or a revision. */
class RestartTest {
    @Test
    fun freshShortUtterance() {
        assertTrue(WhisperSpeech.restarted("that recent transcription whisper replacement thing", " it's"))
    }

    @Test
    fun freshUtteranceThatArrivesLongAtOnce() {
        // Google said nothing for 5 s, then the new sentence came in 20 words long: it must not replace the old one.
        assertTrue(
            WhisperSpeech.restarted(
                " could you just thoroughly test that with some examples and have a look through the last few prompts I've done to see if you can replicate what I was seeing",
                " I just want to test out a long paragraph to see how the whisper gets on with replacing words as I",
            ),
        )
    }

    @Test
    fun revisionsAreNotRestarts() {
        assertFalse(WhisperSpeech.restarted("my Windows PC is having", "My windows PC is having a bit"))
        assertFalse(WhisperSpeech.restarted("could you have quadcode check", "could you have Claude code check if"))
        assertFalse(WhisperSpeech.restarted("that", "the recent"))
    }
}
