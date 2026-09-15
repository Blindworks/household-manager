package com.household.manager.zigbee.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.zigbee.ZigbeeBridgeRejectedException;
import com.household.manager.zigbee.ZigbeeBridgeUnavailableException;
import com.household.manager.zigbee.model.ZigbeeBridgeResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZigbeeBridgeRequestServiceTest {

    /** Fake-Client, der jedes Publish protokolliert. */
    private static final class FakeCommands implements ZigbeeBridgeCommands {
        boolean connected = true;
        boolean failPublish = false;
        final List<String> topics = new ArrayList<>();
        final List<String> payloads = new ArrayList<>();

        @Override public boolean isConnected() { return connected; }

        @Override public CompletableFuture<Void> publish(String topic, String payload) {
            topics.add(topic);
            payloads.add(payload);
            return failPublish
                    ? CompletableFuture.failedFuture(new RuntimeException("broker weg"))
                    : CompletableFuture.completedFuture(null);
        }
    }

    private final FakeCommands commands = new FakeCommands();
    private final ScheduledExecutorService replies = Executors.newSingleThreadScheduledExecutor();
    private ZigbeeBridgeRequestService service;

    @BeforeEach
    void setUp() {
        service = new ZigbeeBridgeRequestService(commands, new ObjectMapper(), Duration.ofMillis(300));
    }

    @AfterEach
    void tearDown() {
        replies.shutdownNow();
    }

    /** Liest die vom Service erzeugte Transaktions-ID aus dem publizierten JSON. */
    private String transactionOf(String payload) {
        String marker = "\"transaction\":\"";
        int start = payload.indexOf(marker) + marker.length();
        return payload.substring(start, payload.indexOf('"', start));
    }

    @Test
    void sendetRequestMitTransaktionUndLiefertDieAntwort() {
        replies.schedule(() -> service.onResponse(new ZigbeeBridgeResponse(
                "permit_join", transactionOf(commands.payloads.get(0)), true, null)), 50, TimeUnit.MILLISECONDS);

        ZigbeeBridgeResponse response = service.send("permit_join", Map.of("time", 240));

        assertThat(response.ok()).isTrue();
        assertThat(commands.topics).containsExactly("zigbee2mqtt/bridge/request/permit_join");
        assertThat(commands.payloads.get(0)).contains("\"time\":240").contains("\"transaction\":\"");
        assertThat(service.pendingCount()).isZero();
    }

    @Test
    void fehlerantwortWirdZurAusnahmeMitZ2mText() {
        replies.schedule(() -> service.onResponse(new ZigbeeBridgeResponse(
                "device/remove", transactionOf(commands.payloads.get(0)), false, "Device 'x' does not exist")),
                50, TimeUnit.MILLISECONDS);

        assertThatThrownBy(() -> service.send("device/remove", Map.of("id", "x")))
                .isInstanceOf(ZigbeeBridgeRejectedException.class)
                .hasMessage("Device 'x' does not exist");
    }

    @Test
    void timeoutRaeumtDieTransaktionAb() {
        assertThatThrownBy(() -> service.send("device/interview", Map.of("id", "x")))
                .isInstanceOf(ZigbeeBridgeUnavailableException.class)
                .hasMessageContaining("antwortet nicht");
        assertThat(service.pendingCount()).isZero();
    }

    @Test
    void keinPublishOhneVerbindung() {
        commands.connected = false;

        assertThatThrownBy(() -> service.send("permit_join", Map.of("time", 240)))
                .isInstanceOf(ZigbeeBridgeUnavailableException.class);
        assertThat(commands.topics).isEmpty();
    }

    @Test
    void gescheitertesPublishIstNichtErreichbar() {
        commands.failPublish = true;

        assertThatThrownBy(() -> service.send("permit_join", Map.of("time", 240)))
                .isInstanceOf(ZigbeeBridgeUnavailableException.class);
        assertThat(service.pendingCount()).isZero();
    }

    @Test
    void fremdeAntwortenWerdenIgnoriert() {
        service.onResponse(new ZigbeeBridgeResponse("permit_join", "nie-gesendet", true, null));
        service.onResponse(new ZigbeeBridgeResponse("permit_join", null, true, null));

        assertThat(service.pendingCount()).isZero();
    }
}
