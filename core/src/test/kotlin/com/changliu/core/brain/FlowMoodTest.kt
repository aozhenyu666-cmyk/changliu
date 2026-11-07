package com.changliu.core.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.random.Random

class FlowMoodTest {

    @Test
    fun screenOffIsOffline() {
        val e = FlowEstimator.estimate(FlowEvidence(interactive = false, silenceMs = 0))
        assertEquals(FlowState.OFFLINE, e.state)
    }

    @Test
    fun longSilenceIsSilent() {
        val e = FlowEstimator.estimate(
            FlowEvidence(interactive = true, silenceMs = FlowEstimator.SILENT_MS + 1),
        )
        assertEquals(FlowState.SILENT, e.state)
    }

    @Test
    fun longEntertainmentWithoutGroundingIsImmersed() {
        val e = FlowEstimator.estimate(
            FlowEvidence(
                interactive = true,
                entertainmentMs = FlowEstimator.IMMERSED_MS + 60_000,
                lastAnswerGrounded = false,
                silenceMs = 0,
            ),
        )
        assertEquals(FlowState.IMMERSED, e.state)
    }

    @Test
    fun ungroundedAnswerIsGliding() {
        val e = FlowEstimator.estimate(
            FlowEvidence(
                interactive = true,
                lastAnswerGrounded = false,
                lastAnswerDelayMs = 8_000,
                silenceMs = 60_000,
            ),
        )
        assertEquals(FlowState.GLIDING, e.state)
        assertTrue(e.confidence >= 0.8f)
    }

    @Test
    fun groundedAnswerStaysOnline() {
        val e = FlowEstimator.estimate(
            FlowEvidence(
                interactive = true,
                lastAnswerGrounded = true,
                lastAnswerDelayMs = 40_000,
                silenceMs = 60_000,
            ),
        )
        assertEquals(FlowState.ONLINE, e.state)
    }

    @Test
    fun moodHoldsForAWhile() {
        val engine = MoodEngine(Random(1), spikeChance = 0.0)
        val first = engine.pick(1_000L, credit = 70, state = FlowState.ONLINE)
        val second = engine.pick(2_000L, credit = 70, state = FlowState.ONLINE)
        assertEquals(first.mood, second.mood)
        assertFalse(second.spike)
        assertTrue(first.heldUntil > 1_000L)
    }

    @Test
    fun spikeCanBeForced() {
        val engine = MoodEngine(Random(2), spikeChance = 1.0)
        val pick = engine.pick(0L, credit = 70, state = FlowState.ONLINE)
        assertTrue(pick.spike)
        assertTrue(pick.mood == Mood.WILD || pick.mood == Mood.WARM)
    }

    @Test
    fun lowCreditFavorsStrictOverWarm() {
        val engine = MoodEngine(Random(3), spikeChance = 0.0)
        val w = engine.weights(credit = 30, state = FlowState.ONLINE)
        assertTrue(w.getValue(Mood.STRICT) > w.getValue(Mood.WARM))
    }

    @Test
    fun glidingRaisesStrictWeight() {
        val engine = MoodEngine(Random(4), spikeChance = 0.0)
        val online = engine.weights(70, FlowState.ONLINE).getValue(Mood.STRICT)
        val gliding = engine.weights(70, FlowState.GLIDING).getValue(Mood.STRICT)
        assertTrue(gliding > online)
    }
}
