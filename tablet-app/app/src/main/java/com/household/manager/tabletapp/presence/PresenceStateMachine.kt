package com.household.manager.tabletapp.presence

/**
 * Hybrid-Logik der Anwesenheitserkennung, bewusst ohne Android-Abhängigkeiten:
 * Bewegung weckt das Display, ein erkanntes Gesicht hält es wach. Ohne beides
 * schaltet [tick] das Display nach Ablauf des Timeouts ab.
 *
 * Zeit wird als monotone Millisekunden übergeben (z. B. SystemClock.elapsedRealtime()).
 */
class PresenceStateMachine(private val timeoutMs: Long, startMs: Long) {

    enum class DisplayState { ON, OFF }

    var displayState: DisplayState = DisplayState.ON
        private set

    private var lastPresenceMs: Long = startMs

    fun onMotion(nowMs: Long): DisplayState {
        lastPresenceMs = nowMs
        displayState = DisplayState.ON
        return displayState
    }

    fun onFace(nowMs: Long): DisplayState {
        if (displayState == DisplayState.ON) {
            lastPresenceMs = nowMs
        }
        return displayState
    }

    fun tick(nowMs: Long): DisplayState {
        if (displayState == DisplayState.ON && nowMs - lastPresenceMs >= timeoutMs) {
            displayState = DisplayState.OFF
        }
        return displayState
    }
}
