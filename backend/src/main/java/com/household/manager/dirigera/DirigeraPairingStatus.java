package com.household.manager.dirigera;

import java.time.Instant;

/**
 * Stand der Kopplung fuer die Admin-Seite. Traegt bewusst weder Token noch Fingerabdruck.
 *
 * @param expiresAt Ende des Knopfdruck-Fensters, nur bei {@link State#PRESS_BUTTON}
 * @param error     Grund, nur bei {@link State#FAILED}
 */
public record DirigeraPairingStatus(State state, Instant expiresAt, String error) {

    public enum State { IDLE, PRESS_BUTTON, PAIRED, FAILED }

    static DirigeraPairingStatus idle() {
        return new DirigeraPairingStatus(State.IDLE, null, null);
    }
}
