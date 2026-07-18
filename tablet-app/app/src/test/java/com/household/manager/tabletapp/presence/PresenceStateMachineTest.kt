package com.household.manager.tabletapp.presence

import com.household.manager.tabletapp.presence.PresenceStateMachine.DisplayState
import org.junit.Assert.assertEquals
import org.junit.Test

class PresenceStateMachineTest {

    private val timeoutMs = 60_000L

    private fun machine(startMs: Long = 0L) = PresenceStateMachine(timeoutMs, startMs)

    @Test
    fun `starts with display on`() {
        assertEquals(DisplayState.ON, machine().displayState)
    }

    @Test
    fun `stays on before timeout`() {
        val m = machine()
        assertEquals(DisplayState.ON, m.tick(timeoutMs - 1))
    }

    @Test
    fun `turns off after timeout without presence`() {
        val m = machine()
        assertEquals(DisplayState.OFF, m.tick(timeoutMs))
    }

    @Test
    fun `motion wakes display from off`() {
        val m = machine()
        m.tick(timeoutMs)
        assertEquals(DisplayState.ON, m.onMotion(timeoutMs + 1))
    }

    @Test
    fun `motion resets the timeout`() {
        val m = machine()
        m.onMotion(50_000)
        assertEquals(DisplayState.ON, m.tick(50_000 + timeoutMs - 1))
        assertEquals(DisplayState.OFF, m.tick(50_000 + timeoutMs))
    }

    @Test
    fun `face keeps display awake while on`() {
        val m = machine()
        m.onFace(50_000)
        assertEquals(DisplayState.ON, m.tick(50_000 + timeoutMs - 1))
    }

    @Test
    fun `face does not wake display from off`() {
        val m = machine()
        m.tick(timeoutMs)
        assertEquals(DisplayState.OFF, m.onFace(timeoutMs + 1))
    }
}
