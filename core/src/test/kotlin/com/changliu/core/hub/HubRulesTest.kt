package com.changliu.core.hub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HubRulesTest {

    @Test
    fun strictestOpinionWins() {
        val d = PolicyEngine.decide(
            listOf(
                ConstraintOpinion(AppMode.OBSERVE, "一手"),
                ConstraintOpinion(AppMode.BLOCK, "总控"),
                ConstraintOpinion(AppMode.GATE, "驾驶员"),
            ),
        )
        assertEquals(AppMode.BLOCK, d?.mode)
        assertEquals(AppMode.GATE, PolicyEngine.decide(listOf(ConstraintOpinion(AppMode.GATE, "a"), ConstraintOpinion(AppMode.ALLOW, "b")))?.mode)
    }

    @Test
    fun gateModeRespectsHqAndStrict() {
        assertEquals(GateMode.HQ, PolicyEngine.gateModeFor(AppMode.GATE, zkStrict = false, hqActive = true))
        assertEquals(GateMode.BLOCK, PolicyEngine.gateModeFor(AppMode.GATE, zkStrict = true, hqActive = false))
        assertEquals(GateMode.BLOCK, PolicyEngine.gateModeFor(AppMode.BLOCK, zkStrict = false, hqActive = false))
        assertEquals(GateMode.GATE, PolicyEngine.gateModeFor(AppMode.GATE, zkStrict = false, hqActive = false))
        assertEquals(null, PolicyEngine.gateModeFor(AppMode.OBSERVE, zkStrict = false, hqActive = false))
    }

    @Test
    fun questionRouterOrder() {
        assertEquals(QuestionSource.MAINLINE, QuestionRouter.choose(setOf(QuestionSource.TEMPLATE, QuestionSource.MAINLINE)))
        assertEquals(QuestionSource.BANK, QuestionRouter.choose(setOf(QuestionSource.BANK, QuestionSource.MODEL)))
        assertEquals(QuestionSource.TEMPLATE, QuestionRouter.choose(emptySet()))
        assertEquals(QuestionSource.MODEL, QuestionRouter.choose(setOf(QuestionSource.MODEL, QuestionSource.TEMPLATE)))
    }

    @Test
    fun toolPolicyTightenAndLoosen() {
        val constraint = ToolRule("constraint_set", ToolLevel.T2)
        assertEquals(ToolLevel.T2, ToolPolicy.levelFor(constraint, stricter = true))
        assertEquals(ToolLevel.T3, ToolPolicy.levelFor(constraint, stricter = false))
        assertFalse(ToolPolicy.needsConfirm(ToolLevel.T2))
        assertTrue(ToolPolicy.needsConfirm(ToolLevel.T3))
        assertTrue(ToolPolicy.mayUndo(ToolLevel.T2))
        assertTrue(ToolPolicy.forbidden("disable_accessibility"))
        assertTrue(ToolPolicy.forbidden("delete_ledger"))
        assertFalse(ToolPolicy.forbidden("get_stats"))
    }

    @Test
    fun wakeRulesCapCooldownNight() {
        val now = 10_000_000L
        val state = WakeState("2026-11-08", count = 12)
        assertFalse(WakeRules.decide(WakeTrigger.DAILY_REVIEW, now, state, "2026-11-08", 20).allow)

        val fresh = WakeState("2026-11-08", count = 1, lastAt = mapOf(WakeTrigger.LIVE_ENDED to now - 5 * 60_000L))
        assertFalse(WakeRules.decide(WakeTrigger.LIVE_ENDED, now, fresh, "2026-11-08", 20).allow)

        val ok = WakeState("2026-11-08", count = 1)
        assertTrue(WakeRules.decide(WakeTrigger.LIVE_ENDED, now, ok, "2026-11-08", 20).allow)
        assertFalse(WakeRules.decide(WakeTrigger.LIVE_ENDED, now, ok, "2026-11-08", 23).allow)

        val after = WakeRules.afterWake(ok, WakeTrigger.LIVE_ENDED, now, "2026-11-08")
        assertEquals(2, after.count)
        assertEquals(now, after.lastAt[WakeTrigger.LIVE_ENDED])
    }
}
