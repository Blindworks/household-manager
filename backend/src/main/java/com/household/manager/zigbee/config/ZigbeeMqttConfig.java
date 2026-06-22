package com.household.manager.zigbee.config;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.household.manager.zigbee.parser.ParsedZigbeeMessage;
import com.household.manager.zigbee.service.ZigbeeLiveService;
import com.household.manager.zigbee.service.ZigbeeMessageParser;
import com.household.manager.zigbee.service.ZigbeeReadingService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Verbindet sich beim Start mit dem MQTT-Broker, abonniert die zigbee2mqtt-Topics
 * und leitet jede Nachricht durch Parser + ReadingService. Startet die App auch
 * dann, wenn der Broker (noch) nicht erreichbar ist (Auto-Reconnect).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ZigbeeMqttConfig {

    private final ZigbeeMqttProperties properties;
    private final ZigbeeMessageParser parser;
    private final ZigbeeReadingService readingService;
    private final ZigbeeLiveService liveService;

    private Mqtt3AsyncClient client;

    @PostConstruct
    public void start() {
        if (!properties.isEnabled()) {
            log.info("Zigbee MQTT integration disabled");
            return;
        }

        Mqtt3AsyncClient builtClient = MqttClient.builder()
                .useMqttVersion3()
                .identifier(properties.getClientId())
                .serverHost(properties.getHost())
                .serverPort(properties.getPort())
                .automaticReconnectWithDefaultConfig()
                .addConnectedListener(ctx -> subscribe())
                .buildAsync();
        this.client = builtClient;

        var connectBuilder = builtClient.connectWith();
        if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
            connectBuilder = connectBuilder.simpleAuth()
                    .username(properties.getUsername())
                    .password(properties.getPassword().getBytes(StandardCharsets.UTF_8))
                    .applySimpleAuth();
        }
        connectBuilder.send().whenComplete((ack, throwable) -> {
            if (throwable != null) {
                log.warn("Zigbee MQTT initial connect failed (will auto-reconnect): {}", throwable.getMessage());
            } else {
                log.info("Zigbee MQTT connected to {}:{}", properties.getHost(), properties.getPort());
            }
        });
    }

    private void subscribe() {
        if (client == null) {
            return;
        }
        client.subscribeWith()
                .topicFilter(properties.getTopicFilter())
                .qos(MqttQos.AT_LEAST_ONCE)
                .callback(this::handle)
                .send()
                .whenComplete((subAck, throwable) -> {
                    if (throwable != null) {
                        log.warn("Zigbee MQTT subscribe failed: {}", throwable.getMessage());
                    } else {
                        log.info("Zigbee MQTT subscribed to {}", properties.getTopicFilter());
                    }
                });
    }

    private void handle(com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish publish) {
        try {
            String topic = publish.getTopic().toString();
            String payload = new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8);
            Optional<ParsedZigbeeMessage> parsed = parser.parse(topic, payload);
            parsed.ifPresent(msg -> {
                var events = readingService.record(msg);
                events.forEach(liveService::broadcast);
            });
        } catch (Exception ex) {
            log.debug("Failed to handle Zigbee MQTT message: {}", ex.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        if (client != null) {
            try {
                client.disconnect();
            } catch (Exception ex) {
                log.debug("Error during MQTT disconnect: {}", ex.getMessage());
            }
        }
    }
}
