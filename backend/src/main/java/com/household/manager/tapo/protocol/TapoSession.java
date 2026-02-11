package com.household.manager.tapo.protocol;

import java.security.PrivateKey;
import java.util.Arrays;

public class TapoSession {

    private final String deviceIp;
    private final String cookie;
    private final PrivateKey privateKey;
    private final byte[] aesKey;
    private final byte[] iv;

    public TapoSession(String deviceIp, String cookie, PrivateKey privateKey, byte[] aesKey, byte[] iv) {
        this.deviceIp = deviceIp;
        this.cookie = cookie;
        this.privateKey = privateKey;
        this.aesKey = Arrays.copyOf(aesKey, aesKey.length);
        this.iv = Arrays.copyOf(iv, iv.length);
    }

    public String getDeviceIp() {
        return deviceIp;
    }

    public String getCookie() {
        return cookie;
    }

    public PrivateKey getPrivateKey() {
        return privateKey;
    }

    public byte[] getAesKey() {
        return Arrays.copyOf(aesKey, aesKey.length);
    }

    public byte[] getIv() {
        return Arrays.copyOf(iv, iv.length);
    }
}
