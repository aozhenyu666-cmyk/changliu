package com.changliu.core

import com.changliu.core.A11yDoctor.Action
import kotlin.test.Test
import kotlin.test.assertEquals

class A11yDoctorTest {
    private val h = 3_600_000L
    private val base = A11yDoctor.Observation(
        now = 100 * h, listed = true, switchOn = true, connected = true, disconnectedSince = 0,
        lastEventAt = 100 * h - 1_000, screenOn = true, canWrite = true, lastRestartAt = 0,
        restartsToday = 0, lastScheduledAt = 100 * h - h, busy = false,
    )

    private fun act(o: A11yDoctor.Observation) = A11yDoctor.decide(o).action

    @Test
    fun healthyDoesNothing() = assertEquals(Action.NONE, act(base))

    @Test
    fun removedIsWrittenBackOrReported() {
        assertEquals(Action.WRITE_BACK, act(base.copy(listed = false)))
        assertEquals(Action.WRITE_BACK, act(base.copy(switchOn = false)))
        assertEquals(Action.NOTIFY_MANUAL, act(base.copy(listed = false, canWrite = false)))
    }

    @Test
    fun zombieIsRestartedAfterGrace() {
        val z = base.copy(connected = false, disconnectedSince = base.now - 30_000)
        assertEquals(Action.NONE, act(z))
        assertEquals(Action.RESTART, act(z.copy(disconnectedSince = base.now - 120_000)))
        assertEquals(Action.NOTIFY_MANUAL, act(z.copy(disconnectedSince = base.now - 120_000, canWrite = false)))
        // 冷却中、今天次数用完：不再折腾
        assertEquals(Action.NONE, act(z.copy(disconnectedSince = base.now - 120_000, lastRestartAt = base.now - 60_000)))
        assertEquals(Action.NONE, act(z.copy(disconnectedSince = base.now - 120_000, restartsToday = 12)))
    }

    @Test
    fun silentWhileScreenOnIsRestarted() {
        assertEquals(Action.RESTART, act(base.copy(lastEventAt = base.now - 11 * 60_000)))
        // 熄屏时没事件是正常的
        assertEquals(Action.NONE, act(base.copy(lastEventAt = base.now - 11 * 60_000, screenOn = false, lastScheduledAt = base.now)))
    }

    @Test
    fun scheduledRestartOnlyWhenScreenOffAndIdle() {
        val due = base.copy(lastScheduledAt = base.now - 7 * h, screenOn = false)
        assertEquals(Action.RESTART, act(due))
        assertEquals(Action.NONE, act(due.copy(screenOn = true)))
        assertEquals(Action.NONE, act(due.copy(busy = true)))
        assertEquals(Action.NONE, act(due.copy(lastScheduledAt = base.now - 2 * h)))
        assertEquals(Action.NONE, A11yDoctor.decide(due, A11yDoctor.Config(scheduledEveryMs = 0)).action)
    }

    @Test
    fun restartOnlyTouchesOwnComponent() {
        val me = "com.changliu.app/com.changliu.app.infra.a11y.ChangliuA11yService"
        val other = "com.other/.Svc"
        assertEquals(other, A11yDoctor.without("$other:$me", me))
        assertEquals("$other:$me", A11yDoctor.with(other, me))
        assertEquals("$other:$me", A11yDoctor.with("$other:$me", me))
        assertEquals(me, A11yDoctor.with(null, me))
    }
}
