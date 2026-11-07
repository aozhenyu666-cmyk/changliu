package com.changliu.core.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.random.Random

class ProactiveMemoryTest {

    private fun input(
        trigger: Trigger,
        state: FlowState = FlowState.ONLINE,
        now: Long = 1_000_000L,
        pressure: Boolean = false,
        lastActAt: Long = 0L,
        lastSame: Long = 0L,
        morningAsked: Boolean = false,
    ) = ProactiveInput(now, trigger, state, pressure, lastActAt, lastSame, morningAsked)

    @Test
    fun nothingHappensWithoutTrigger() {
        assertFalse(ProactivePolicy.decide(input(Trigger.NONE)).speak)
    }

    @Test
    fun sameCauseCoolsDownFor90Seconds() {
        val d = ProactivePolicy.decide(
            input(Trigger.HEARTBEAT, now = 100_000, lastSame = 100_000 - 30_000),
        )
        assertFalse(d.speak)
        assertTrue(d.reason.contains("冷却"))
    }

    @Test
    fun totalCooldownAppliesAcrossCauses() {
        val d = ProactivePolicy.decide(
            input(Trigger.SILENT, now = 100_000, lastActAt = 100_000 - 10_000),
        )
        assertFalse(d.speak)
    }

    @Test
    fun pressureSuppressesHeartbeatButNotSilence() {
        val heartbeat = ProactivePolicy.decide(input(Trigger.HEARTBEAT, pressure = true))
        assertFalse(heartbeat.speak)
        val silent = ProactivePolicy.decide(input(Trigger.SILENT, pressure = true))
        assertTrue(silent.speak)
        assertEquals(Level.L1, silent.level)
    }

    @Test
    fun immersedIsLeftToEnforcement() {
        val d = ProactivePolicy.decide(input(Trigger.HEARTBEAT, state = FlowState.IMMERSED))
        assertFalse(d.speak)
    }

    @Test
    fun morningAskedOnlyOnce() {
        assertFalse(ProactivePolicy.decide(input(Trigger.MORNING_UNLOCK, morningAsked = true)).speak)
        assertTrue(ProactivePolicy.decide(input(Trigger.MORNING_UNLOCK, morningAsked = false)).speak)
    }

    @Test
    fun everyTriggerHasTextForEveryMood() {
        for (t in Trigger.entries - Trigger.NONE) {
            for (m in Mood.entries) {
                assertTrue(ProactiveText.forTrigger(t, m).isNotBlank(), "$t/$m")
            }
        }
    }

    @Test
    fun memoryNeedsThreePiecesOfEvidence() {
        val now = 10_000_000L
        val two = listOf(
            LedgerEvent(now - 1_000, "transition", "ONLINE", "GLIDING"),
            LedgerEvent(now - 2_000, "transition", "ONLINE", "GLIDING"),
        )
        assertTrue(MemoryDigest.digest(two, now).isEmpty())

        val three = two + LedgerEvent(now - 3_000, "transition", "ONLINE", "GLIDING")
        val out = MemoryDigest.digest(three, now)
        assertEquals(1, out.size)
        assertEquals("glide-after-online", out[0].key)
        assertEquals(3, out[0].evidence.size)
    }

    @Test
    fun memoryIgnoresOldEvidence() {
        val now = 10_000_000L
        val old = (1..3).map {
            LedgerEvent(now - MemoryDigest.WINDOW_MS - it * 1_000, "answer", grounded = false)
        }
        assertTrue(MemoryDigest.digest(old, now).isEmpty())
    }

    @Test
    fun ungroundedAnswersBecomeMemory() {
        val now = 20_000_000L
        val events = (1..3).map { LedgerEvent(now - it * 1_000, "answer", grounded = false) }
        val out = MemoryDigest.digest(events, now)
        assertTrue(out.any { it.key == "ungrounded-answers" })
    }

    @Test
    fun tuningNeedsFiveGlides() {
        assertTrue(Tuning.propose(listOf(MemoryFact("glide-after-online", 4))).isEmpty())
        val out = Tuning.propose(listOf(MemoryFact("glide-after-online", 5)))
        assertEquals(1, out.size)
        assertTrue(out[0].deltas.containsKey(Mood.STRICT))
    }

    @Test
    fun tuningForSilenceIsWarmer() {
        val out = Tuning.propose(listOf(MemoryFact("silent-episodes", 3)))
        assertEquals(1, out.size)
        assertTrue(out[0].deltas.containsKey(Mood.WARM))
    }

    @Test
    fun tuningDeltasAreAdditiveAndBounded() {
        val engine = MoodEngine(Random(9), spikeChance = 0.0)
        val base = engine.weights(70, FlowState.ONLINE).getValue(Mood.STRICT)
        val tuned = engine.weights(70, FlowState.ONLINE, mapOf(Mood.STRICT to 10.0)).getValue(Mood.STRICT)
        assertTrue(tuned > base)
        val floor = engine.weights(70, FlowState.ONLINE, mapOf(Mood.WARM to -1_000.0)).getValue(Mood.WARM)
        assertTrue(floor >= 2.0)
    }
}
