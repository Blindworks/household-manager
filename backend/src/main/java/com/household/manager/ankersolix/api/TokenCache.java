package com.household.manager.ankersolix.api;

public class TokenCache {

    private static final long EXPIRY_BUFFER_SECONDS = 60;

    private String authToken;
    private String gtoken;
    private long   expiresAtEpochSeconds;

    public synchronized void store(String authToken, String gtoken, long expiresAtEpochSeconds) {
        this.authToken             = authToken;
        this.gtoken                = gtoken;
        this.expiresAtEpochSeconds = expiresAtEpochSeconds;
    }

    public synchronized boolean isValid() {
        if (authToken == null || gtoken == null) return false;
        long nowEpoch = System.currentTimeMillis() / 1000;
        return nowEpoch < (expiresAtEpochSeconds - EXPIRY_BUFFER_SECONDS);
    }

    public synchronized String getAuthToken() {
        if (!isValid()) throw new IllegalStateException("No valid Anker token.");
        return authToken;
    }

    public synchronized String getGtoken() {
        if (!isValid()) throw new IllegalStateException("No valid Anker token.");
        return gtoken;
    }

    public synchronized void invalidate() {
        authToken             = null;
        gtoken                = null;
        expiresAtEpochSeconds = 0;
    }
}