package com.household.manager.zigbee.config;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.lifecycle.MqttDisconnectSource;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    /**
     * Zaehlt, welche Connect-/Resubscribe-Kette aktuell gueltig ist. Jeder
     * {@code addConnectedListener}-Aufruf startet eine neue Kette und zaehlt hoch;
     * eine laufende Retry-Kette einer AELTEREN Verbindung erkennt daran, dass sie
     * ueberholt wurde, und bricht wortlos ab, statt ein zweites Mal erfolgreich zu
     * subscriben. Ohne das koennten zwei Ketten (die alte, noch retry-ende, und die
     * neue, durch einen frischen Connect ausgeloeste) beide erfolgreich subscriben -
     * zwei aktive Subscriptions auf demselben Topic-Filter, jede Nachricht wird
     * doppelt verarbeitet (doppelte DB-Zeilen, doppelt feuernde Flows).
     */
    private final AtomicInteger subscribeGeneration = new AtomicInteger(0);

    /**
     * Verarbeitung laeuft bewusst auf GENAU EINEM Thread: mehrere Threads koennten
     * Nachrichten desselben Geraets umsortieren, und bei einem Tuerkontakt waere ein
     * vertauschtes "offen"/"zu" fatal. Der Netty-Event-Loop bleibt trotzdem frei,
     * sodass eine haengende Datenbank Keepalive und Reconnect nicht mehr blockiert.
     * <p>
     * Unbeschraenkte Queue mit Absicht, keine Kapazitaetsgrenze: HiveMQ wickelt diesen
     * Executor in {@code Schedulers.from(executor)} (RxJava). Der resultierende
     * {@code ExecutorScheduler.ExecutorWorker} haelt selbst nur maximal EINE Task in
     * dieser Queue - er sammelt Runnables in seiner eigenen unbeschraenkten
     * Warteschlange und submittet sich nur neu, wenn keine Task mehr laeuft. Das
     * tatsaechliche Backpressure-Verhalten bei einer haengenden Datenbank kommt von
     * RxJavas {@code observeOn} (puffert bis 128, drosselt danach Richtung Broker),
     * nicht von dieser Queue. Eine begrenzte Queue mit RejectedExecutionHandler wuerde
     * im Normalbetrieb nie greifen; eine unbeschraenkte mit diesem Kommentar ist
     * ehrlicher als eine beschraenkte, die nichts bewirkt.
     */
    private final ThreadPoolExecutor handlerExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            r -> {
                Thread t = new Thread(r, "zigbee-mqtt-handler");
                t.setDaemon(true);
                return t;
            },
            (r, executor) -> {
                if (executor.isShutdown()) {
                    // Erwartet: stop() disconnectet zuerst und faehrt die Executors
                    // erst danach herunter, aber eine letzte Nachricht kann in diesem
                    // kurzen Fenster trotzdem eintreffen. Regulaeres Herunterfahren,
                    // kein Fehler.
                    log.debug("Zigbee-Verarbeitung nach Shutdown verworfen "
                            + "(Nachricht waehrend des Abschaltens)");
                    return;
                }
                log.error("Zigbee-Verarbeitung abgelehnt, obwohl der Executor noch laeuft "
                        + "- unerwarteter Zustand.");
            });

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
                .addDisconnectedListener(ctx -> {
                    if (ctx.getSource() == MqttDisconnectSource.USER) {
                        // Selbst angestossenes disconnect() (siehe stop()) - kein
                        // Ausfall, kein Reconnect zu erwarten. Ohne diese
                        // Unterscheidung wuerde jedes regulaere Herunterfahren ein
                        // irrefuehrendes "Reconnect laeuft" loggen.
                        log.info("Zigbee MQTT Verbindung geschlossen (gewolltes Shutdown)");
                        return;
                    }
                    log.warn("Zigbee MQTT getrennt (Quelle {}), Reconnect laeuft: {}",
                            ctx.getSource(), ctx.getCause().getMessage());
                })
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
        subscribe(1, subscribeGeneration.incrementAndGet());
    }

    /**
     * Abonniert das Topic-Filter und wiederholt den Versuch bei Fehlschlag unbegrenzt
     * mit wachsendem Abstand (max. {@value #RESUBSCRIBE_MAX_DELAY_SECONDS}s).
     * <p>
     * Ohne diese Wiederholung bliebe der Client nach einem fehlgeschlagenen Subscribe
     * dauerhaft verbunden, ohne je wieder Nachrichten zu empfangen — ein lautloser
     * Dauerausfall, der genau so schon einmal aufgetreten ist.
     * <p>
     * {@code generation} identifiziert die Kette, die diesen Versuch gestartet hat.
     * Wird zwischenzeitlich ein neuer Connect ausgeloest (neue Generation), bricht
     * diese Kette wortlos ab — sowohl vor dem Senden als auch im {@code whenComplete},
     * bevor ein Folgeversuch geplant wird. Sonst koennten eine alte, noch retry-ende
     * Kette und eine neue, durch einen frischen Connect ausgeloeste Kette beide
     * erfolgreich subscriben und zu doppelten Subscriptions fuehren.
     */
    private void subscribe(int attempt, int generation) {
        if (generation != subscribeGeneration.get()) {
            return;
        }
        Mqtt3AsyncClient current = this.client;
        if (current == null) {
            return;
        }
        current.subscribeWith()
                .topicFilter(properties.getTopicFilter())
                .qos(MqttQos.AT_LEAST_ONCE)
                .callback(this::handle)
                .executor(handlerExecutor)
                .send()
                .whenComplete((subAck, throwable) -> {
                    if (generation != subscribeGeneration.get()) {
                        return;
                    }
                    if (throwable == null) {
                        log.info("Zigbee MQTT subscribed to {}", properties.getTopicFilter());
                        return;
                    }
                    int delay = Math.min(1 << Math.min(attempt, 6), RESUBSCRIBE_MAX_DELAY_SECONDS);
                    log.warn("Zigbee MQTT subscribe fehlgeschlagen (Versuch {}), erneuter Versuch in {}s: {}",
                            attempt, delay, throwable.getMessage());
                    retryScheduler.schedule(() -> subscribe(attempt + 1, generation), delay, TimeUnit.SECONDS);
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
        // Erst disconnecten, DANN die Executors herunterfahren: andersherum ruft
        // ThreadPoolExecutor#shutdownNow() den RejectedExecutionHandler auch fuer
        // Nachrichten auf, die waehrend des Abschaltfensters noch eintreffen - bei
        // minuetlich meldenden Sensoren realistisch, und ohne diese Reihenfolge waere
        // das der haeufigste Ausloeser der Verwerfen-Meldung ueberhaupt.
        if (client != null) {
            try {
                client.disconnect();
            } catch (Exception ex) {
                log.debug("Error during MQTT disconnect: {}", ex.getMessage());
            }
        }
        retryScheduler.shutdownNow();
        handlerExecutor.shutdownNow();
    }
}
