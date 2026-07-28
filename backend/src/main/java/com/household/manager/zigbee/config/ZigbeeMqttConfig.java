package com.household.manager.zigbee.config;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.mapper.ZigbeeEntityMapper;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
    private final ZigbeeEntityMapper zigbeeEntityMapper;
    private final EntityStateService entityStateService;

    private Mqtt3AsyncClient client;

    /** Eigener Scheduler nur fuer Resubscribe-Wiederholungen. */
    private final ScheduledExecutorService retryScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "zigbee-mqtt-retry");
                t.setDaemon(true);
                return t;
            });

    private static final int RESUBSCRIBE_MAX_DELAY_SECONDS = 60;

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
                .addDisconnectedListener(ctx -> log.warn(
                        "Zigbee MQTT getrennt (Quelle {}), Reconnect laeuft: {}",
                        ctx.getSource(), ctx.getCause().getMessage()))
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
        subscribe(1);
    }

    /**
     * Abonniert das Topic-Filter und wiederholt den Versuch bei Fehlschlag unbegrenzt
     * mit wachsendem Abstand (max. {@value #RESUBSCRIBE_MAX_DELAY_SECONDS}s).
     * <p>
     * Ohne diese Wiederholung bliebe der Client nach einem fehlgeschlagenen Subscribe
     * dauerhaft verbunden, ohne je wieder Nachrichten zu empfangen — ein lautloser
     * Dauerausfall, der genau so schon einmal aufgetreten ist.
     */
    private void subscribe(int attempt) {
        Mqtt3AsyncClient current = this.client;
        if (current == null) {
            return;
        }
        current.subscribeWith()
                .topicFilter(properties.getTopicFilter())
                .qos(MqttQos.AT_LEAST_ONCE)
                .callback(this::handle)
                .send()
                .whenComplete((subAck, throwable) -> {
                    if (throwable == null) {
                        log.info("Zigbee MQTT subscribed to {}", properties.getTopicFilter());
                        return;
                    }
                    int delay = Math.min(1 << Math.min(attempt, 6), RESUBSCRIBE_MAX_DELAY_SECONDS);
                    log.warn("Zigbee MQTT subscribe fehlgeschlagen (Versuch {}), erneuter Versuch in {}s: {}",
                            attempt, delay, throwable.getMessage());
                    retryScheduler.schedule(() -> subscribe(attempt + 1), delay, TimeUnit.SECONDS);
                });
    }

    private void handle(com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish publish) {
        try {
            String topic = publish.getTopic().toString();
            String payload = new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8);
            Optional<ParsedZigbeeMessage> parsed = parser.parse(topic, payload, publish.isRetain());
            parsed.ifPresent(msg -> {
                var events = readingService.record(msg);
                events.forEach(liveService::broadcast);
                reportEntityStates(msg);
            });
        } catch (Exception ex) {
            log.warn("Zigbee-MQTT-Nachricht konnte nicht verarbeitet werden (Topic {}): {}",
                    publish.getTopic(), ex.getMessage(), ex);
        }
    }

    private void reportEntityStates(ParsedZigbeeMessage message) {
        try {
            zigbeeEntityMapper.map(message).forEach(entityStateService::reportState);
            zigbeeEntityMapper.mapAction(message).ifPresent(entityStateService::reportEvent);
        } catch (Exception ex) {
            log.warn("Failed to report zigbee entity states for {}: {}",
                    message.friendlyName(), ex.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        retryScheduler.shutdownNow();
        if (client != null) {
            try {
                client.disconnect();
            } catch (Exception ex) {
                log.debug("Error during MQTT disconnect: {}", ex.getMessage());
            }
        }
    }
}
