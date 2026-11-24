package com.household.manager.ai;

/** Welcher Agent ein Tool sehen darf: der Telegram-Dialog, der Communicator oder der proaktive Hausverstand. */
public enum AgentScope {
    TELEGRAM,
    /** Sprach-App: sieht zusaetzlich die Telegram-Werkzeuge (CommunicatorAgentService fuehrt beide Scopes zusammen). */
    COMMUNICATOR,
    HOUSE
}
