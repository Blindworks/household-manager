package com.household.manager.tapo.session;

/**
 * Extension point for local Tapo control.
 * Intended for future local auth/login, RSA handshake and AES payload exchange.
 */
public interface TapoLocalSession extends AutoCloseable {

    void open();

    @Override
    void close();
}
