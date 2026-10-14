package com.ahaverty.assist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenIntentTest {
    @Test
    fun clearAsksAboutTheScreen() {
        listOf(
            "What's on my screen?",
            "summarise this article",
            "Can you translate this",
            "is this legit",
            "Is this a scam?",
            "what does this say",
            "reply to this email for me",
            "what do you think of these reviews",
            "who is this?",
            "what am I looking at",
            "what's the cheapest thing on here",
            "add this recipe to the meal plan",
            "explain this",
        ).forEach { assertTrue(it, ScreenIntent.pointsAtScreen(it)) }
    }

    @Test
    fun loneThisOrHereIsNotEnough() {
        listOf(
            "turn this light off",
            "is Dana here yet",
            "turn the lights on in here",
            "how far is town from here",
            "what's the weather this week",
            "set an alarm on my phone for 7",
            "play that song again",
            "check this out, the heating is on",
            "turn off these lights",
            "this morning I had porridge, how many carbs",
            "answer that question again",
            "add milk to the shopping list",
        ).forEach { assertFalse(it, ScreenIntent.pointsAtScreen(it)) }
    }
}
