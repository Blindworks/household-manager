package com.household.manager.common;

/**
 * Ergebnis eines Zustellversuchs (Telegram, Push). Die Dienste werfen nie; ob die
 * Nachricht wirklich rausging, steht hier, damit der Flow-Lauf es protokollieren kann.
 */
public record DeliveryResult(boolean delivered, String failure) {

    public static DeliveryResult ok() {
        return new DeliveryResult(true, null);
    }

    public static DeliveryResult failed(String reason) {
        return new DeliveryResult(false, reason);
    }

    /** Fehlgeschlagen, sobald einer fehlschlug; die Gründe werden mit "; " verbunden. */
    public DeliveryResult and(DeliveryResult other) {
        if (delivered) {
            return other;
        }
        if (other.delivered) {
            return this;
        }
        return failed(failure + "; " + other.failure);
    }
}
