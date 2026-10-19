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
    fun mergeDropsAWordGoogleWasBehindOn() {
        // Google's snapshot was a word behind the draft: its "well" is already in it.
        assertEquals("is not working well still.", LiveWords.merge("is not working well still.", "well"))
        assertEquals("is not working well still. it's kind", LiveWords.merge("is not working well still.", "well still it's kind"))
    }

    @Test
    fun tailAfterFindsTheDraftsEndInGoogleText() {
        // A real turn: Whisper had "set it higher then" before Google showed past "yeah go".
        assertEquals("", LiveWords.tailAfter("yeah go and sell it higher then", "Yeah, go and set it higher then.", "yeah go"))
        assertEquals("please", LiveWords.tailAfter("yeah go and sell it higher then please", "Yeah, go and set it higher then.", "yeah go"))
        // The draft's last words appear earlier too: only from about where Google was counts.
        assertEquals(
            "and the hall",
            LiveWords.tailAfter("turn on the lights then turn on the lights and the hall", "turn on the lights then turn on the lights", "turn on the lights then turn"),
        )
        // A clipped last word can't be found: the caller falls back to merge().
        assertEquals(null, LiveWords.tailAfter("the entire paragraph as if I'm just", "the entire paragraph as if I", "the entire"))
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

    @Test
    fun carriesOnFromAShorterFinal() {
        // 19 Oct: Google's partial had the whole question, its final dropped the end.
        assertTrue(LiveWords.carriesOn("what temperature is my office", "What temperature?"))
        assertTrue(LiveWords.carriesOn("what temperature is my office", "What temperature is"))
        assertFalse(LiveWords.carriesOn("what temperature", "What temperature?"))
        assertFalse(LiveWords.carriesOn("what time is it", "What temperature?"))
    }

    @Test
    fun shortWhisperQuestionIsCaught() {
        assertTrue(WhisperSpeech.isShort(2, drafted = 0, google = 5)) // "What temperature?"
        assertTrue(WhisperSpeech.isShort(2, drafted = 0, google = 4))
        assertFalse(WhisperSpeech.isShort(4, drafted = 0, google = 5))
        assertFalse(WhisperSpeech.isShort(5, drafted = 0, google = 5))
        assertFalse(WhisperSpeech.isShort(1, drafted = 0, google = 2)) // "Thanks." for "thank you"
    }
}
