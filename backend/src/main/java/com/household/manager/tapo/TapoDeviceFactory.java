package com.household.manager.tapo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;

@Component
public class TapoDeviceFactory {

    private final HttpClient httpClient = HttpClient.newBuilder().build();
    private final ObjectMapper objectMapper;

    public TapoDeviceFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public TapoLocalDeviceConnection create(
            TapoAuthProtocol protocol,
            String ipAddress,
            String username,
            String password
    ) {
        return switch (protocol) {
            case AES -> new TapoAesDeviceConnection(httpClient, objectMapper, username, password, ipAddress);
            case KLAP, UNKNOWN -> new TapoKlapDeviceConnection(httpClient, objectMapper, username, password, ipAddress);
        };
    }
}
