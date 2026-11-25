package com.household.manager.laundry;

/** Was die Frage "Haengt Waesche im Keller?" ausgeloest hat. */
public enum LaundryTrigger {
    SWITCH_OFF,
    HUMIDITY_RISE,
    /** Von Hand gemeldet (Communicator, App), ohne vorherige Frage. */
    MANUAL
}
