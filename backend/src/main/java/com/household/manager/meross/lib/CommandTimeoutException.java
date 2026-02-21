package com.household.manager.meross.lib;

/**
 * Initially created by Tino on 15.04.19.
 */
public class CommandTimeoutException extends Throwable {
    public CommandTimeoutException(Throwable throwable) {
        super(throwable);
    }

    public CommandTimeoutException() {

    }
}
