package com.ahaverty.assist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoTest {
    // Both echoes below were sent to Friday as questions on 15 Oct.
    @Test
    fun splitWordsAreTheReplysOwn() {
        val reply = "I don't have a dataset of other type 1 adults your age, so I can't give you a true percentile."
        assertFalse(Echo.isInterruption("I don't have a data set", reply))
        assertFalse(Echo.isInterruption("I don't have a data set of other type 1 adults", reply))
    }

    @Test
    fun misheardNamesAreTheReplysOwn() {
        val reply = "This is a build job: mapping new CamAPS FX screens and notifications into the glucose tools."
        assertFalse(Echo.isInterruption("mapping new cam apsfx", reply))
        assertFalse(Echo.isInterruption("mapping new cam apsfx Screens and notifications", reply))
    }

    @Test
    fun numbersReadOutAreTheReplysOwn() {
        val reply = "Yesterday you were in range 96.7 percent of the time."
        assertFalse(Echo.isInterruption("in range ninety six point seven percent", reply))
    }

    @Test
    fun oneLetterOffIsTheReplysOwn() {
        assertFalse(Echo.isInterruption("the kitchen light are brighter", "The kitchen lights are brighter now."))
    }

    @Test
    fun alanTalkingOverStillCounts() {
        val reply = "Yesterday you were in range 96.7 percent of the time, and in the tight range for 75.2 percent of it."
        assertTrue(Echo.isInterruption("stop", reply))
        assertTrue(Echo.isInterruption("no I meant the day before", reply))
        assertTrue(Echo.isInterruption("what about my average last week", reply))
        assertTrue(Echo.isInterruption("turn off the office lights", "The kitchen lights are brighter now."))
    }

    @Test
    fun wholeSentenceEcho() {
        val reply = "I don't have a dataset of other type 1 adults your age."
        assertTrue(Echo.isEcho("I don't have a data set of other type 1 adults", reply))
        assertFalse(Echo.isEcho("I don't have a data set stop", reply))
        assertFalse(Echo.isEcho("forget that and check my glucose now please", reply))
    }
}
