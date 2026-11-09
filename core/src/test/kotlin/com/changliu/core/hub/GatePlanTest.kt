package com.changliu.core.hub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GatePlanTest {

    private fun plan(
        credit: Int = 70,
        mode: AppMode = AppMode.GATE,
        strict: Boolean = false,
        hq: Boolean = false,
        hold: Boolean = false,
        cap: Int = 0,
        opens: Int = 0,
        base: Int = 10,
        missed: Boolean = false,
    ) = GatePlanner.plan(
        GateInput(
            mode = mode, credit = credit, strict = strict, hqActive = hq,
            hold = hold, passCap = cap, opensToday = opens, baseMinutes = base,
            companionMissed = missed,
        ),
    )

    @Test
    fun allowModeIsNotIntercepted() {
        assertNull(plan(mode = AppMode.ALLOW))
    }

    @Test
    fun trustOpensTheQuickDrill() {
        val p = plan(credit = 88)!!
        assertEquals(GateLevel.DRILL, p.level)
        assertEquals(GateMode.GATE, p.gate)
        assertEquals(1, p.rounds)
        assertFalse(p.companion)
    }

    @Test
    fun midCreditNeedsTwoRounds() {
        val p = plan(credit = 65)!!
        assertEquals(GateLevel.TRAIN, p.level)
        assertEquals(2, p.rounds)
        assertEquals(5, p.minutes)
        assertTrue(p.companion)
    }

    @Test
    fun waryCreditNeedsEvidence() {
        val p = plan(credit = 50)!!
        assertEquals(GateLevel.EVIDENCE, p.level)
        assertEquals(3, p.minutes)
        assertTrue(p.companion)
    }

    @Test
    fun repeatedOpensEscalateToTraining() {
        assertEquals(GateLevel.TRAIN, plan(credit = 90, opens = 2)!!.level)
        assertEquals(GateLevel.DRILL, plan(credit = 90, opens = 1)!!.level)
    }

    @Test
    fun lowCreditLocksOut() {
        val p = plan(credit = 39)!!
        assertEquals(GateLevel.LOCK, p.level)
        assertEquals(GateMode.BLOCK, p.gate)
        assertEquals(0, p.minutes)
        assertEquals(0, p.rounds)
        assertTrue(p.locked)
    }

    @Test
    fun zongkongRulesWinOverCredit() {
        assertEquals(GateMode.HQ, plan(credit = 95, hq = true)!!.gate)
        assertEquals(GateMode.BLOCK, plan(credit = 95, strict = true)!!.gate)
        assertEquals(GateMode.BLOCK, plan(credit = 95, hold = true)!!.gate)
        assertEquals(GateMode.BLOCK, plan(credit = 95, mode = AppMode.BLOCK)!!.gate)
    }

    @Test
    fun passCapShrinksEveryLevel() {
        assertEquals(2, plan(credit = 88, cap = 2)!!.minutes)
        assertEquals(2, plan(credit = 65, cap = 2)!!.minutes)
        assertEquals(2, plan(credit = 50, cap = 2)!!.minutes)
    }

    @Test
    fun minutesNeverExceedCeilingAndNeverBelowOne() {
        assertEquals(GatePlanner.MAX_MINUTES, plan(credit = 88, base = 90)!!.minutes)
        assertTrue(plan(credit = 88, cap = 1)!!.minutes >= 1)
        // 封死时不分东西，永远是 0
        assertEquals(0, plan(credit = 95, strict = true, cap = 5)!!.minutes)
    }

    @Test
    fun observeModeAlwaysNeedsEvidence() {
        assertEquals(GateLevel.EVIDENCE, plan(credit = 95, mode = AppMode.OBSERVE)!!.level)
    }

    @Test
    fun missedCompanionDebitsThePassMinutes() {
        val p = plan(credit = 65, missed = true)!!
        assertEquals(GateLevel.TRAIN, p.level)
        assertEquals(5 - GatePlanner.COMPANION_PENALTY, p.minutes)
        assertTrue(p.reason.contains("伴读"))

        // 3 分钟的 L3 扣完只剩 0，但"给了就是给了"，最低 1 分钟
        assertEquals(1, plan(credit = 50, missed = true)!!.minutes)
        assertEquals(10 - GatePlanner.COMPANION_PENALTY, plan(credit = 88, missed = true)!!.minutes)
    }

    @Test
    fun companionDebtNeverZeroesOrLocks() {
        // 扣到底也是 1 分钟，不是封死；关卡也不因欠账升级
        val p = plan(credit = 50, cap = 1, missed = true)!!
        assertEquals(1, p.minutes)
        assertEquals(GateLevel.EVIDENCE, p.level)
        assertFalse(p.locked)
        // 本来就封死的不受欠账影响
        assertEquals(0, plan(credit = 95, strict = true, missed = true)!!.minutes)
        assertEquals(0, plan(credit = 39, missed = true)!!.minutes)
    }
}
