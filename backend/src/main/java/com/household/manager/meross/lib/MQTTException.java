package com.household.manager.meross.lib;

/**
 * Initially created by Tino on 15.04.19.
 */
public class MQTTException extends Exception {
    public MQTTException(String message) {
        super(message);
    }

    public MQTTException(Throwable throwable) {
        super(throwable);
    }
}
