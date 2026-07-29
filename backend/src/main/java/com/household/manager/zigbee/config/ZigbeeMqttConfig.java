package com.household.manager.zigbee.config;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.lifecycle.MqttDisconnectSource;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.mapper.ZigbeeEntityMapper;
import com.household.manager.zigbee.parser.ParsedZigbeeMessage;
import com.household.manager.zigbee.parser.ZigbeeAvailability;
import com.household.manager.zigbee.service.ZigbeeConnectionControl;
import com.household.manager.zigbee.service.ZigbeeLiveService;
import com.household.manager.zigbee.service.ZigbeeMessageParser;
import com.household.manager.zigbee.service.ZigbeeReadingService;
import com.household.manager.zigbee.service.ZigbeeStreamMonitor;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verbindet sich beim Start mit dem MQTT-Broker, abonniert die zigbee2mqtt-Topics
 * und leitet jede Nachricht durch Parser + ReadingService. Startet die App auch
 * dann, wenn der Broker (noch) nicht erreichbar ist (Auto-Reconnect).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ZigbeeMqttConfig implements ZigbeeConnectionControl {

    private final ZigbeeMqttProperties properties;
    private final ZigbeeMessageParser parser;
    private final ZigbeeReadingService readingService;
    private final ZigbeeLiveService liveService;
    private final ZigbeeEntityMapper zigbeeEntityMapper;
    private final EntityStateService entityStateService;
    private final ZigbeeStreamMonitor streamMonitor;

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
     * Von {@link #stop()} VOR dem {@code disconnect()} gesetzt. Sowohl ein regulaeres
     * Herunterfahren als auch {@link #forceReconnect()} loesen einen USER-Disconnect
     * aus, den der Disconnected-Listener sonst nicht unterscheiden kann. Ohne dieses
     * Flag wuerde ein vom Watchdog erzwungener Reconnect als "gewolltes Shutdown"
     * geloggt - eine Fehlspur ausgerechnet in dem Log, das einen Ausfall wie den
     * 22-Stunden-PROD-Vorfall diagnostizierbar machen soll.
     */
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

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
                // Bei einer unbeschraenkten LinkedBlockingQueue kann dieser Zweig im
                // Normalbetrieb NICHT feuern - execute() lehnt nur bei voller (bounded)
                // Queue oder nach shutdown() ab, und Letzteres faengt der Zweig oben ab.
                // Bleibt als Leitplanke, falls die Queue spaeter wieder beschraenkt wird -
                // wer diese Zeile im Log sieht, sucht sonst einen Fehler, den es (heute)
                // gar nicht geben kann.
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
                        // Zwei Quellen fuer ein selbst angestossenes disconnect():
                        // stop() (regulaeres Herunterfahren, kein Reconnect zu
                        // erwarten) und forceReconnect() (Watchdog-Selbstheilung,
                        // ein explizites connect() folgt sofort). Ohne die
                        // shuttingDown-Unterscheidung wuerden BEIDE hier als
                        // "gewolltes Shutdown" geloggt - ein erzwungener Reconnect
                        // waere dann im Log nicht von einem echten Shutdown zu
                        // unterscheiden, obwohl er ein Alarmsignal ist.
                        if (shuttingDown.get()) {
                            log.info("Zigbee MQTT Verbindung geschlossen (gewolltes Shutdown)");
                        } else {
                            log.warn("Zigbee MQTT Verbindung von uns getrennt (erzwungener "
                                    + "Reconnect durch den Watchdog), Neuverbindung folgt");
                        }
                        return;
                    }
                    log.warn("Zigbee MQTT getrennt (Quelle {}), Reconnect laeuft: {}",
                            ctx.getSource(), ctx.getCause().getMessage());
                })
                .buildAsync();
        this.client = builtClient;

        connect();
    }

    /**
     * Baut die eigentliche Verbindung auf. Herausgeloest aus {@link #start()}, damit
     * {@link #forceReconnect()} nach einem selbst angestossenen {@code disconnect()}
     * explizit neu verbinden kann — HiveMQs {@code automaticReconnect} greift nur bei
     * unerwarteten Abbruechen, ein gewolltes disconnect() schaltet ihn ab.
     */
    private void connect() {
        Mqtt3AsyncClient current = this.client;
        if (current == null) {
            return;
        }
        var connectBuilder = current.connectWith();
        if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
            connectBuilder = connectBuilder.simpleAuth()
                    .username(properties.getUsername())
                    .password(properties.getPassword().getBytes(StandardCharsets.UTF_8))
                    .applySimpleAuth();
        }
        connectBuilder.send().whenComplete((ack, throwable) -> {
            if (throwable != null) {
                log.warn("Zigbee MQTT connect fehlgeschlagen (Auto-Reconnect laeuft): {}",
                        throwable.getMessage());
            } else {
                log.info("Zigbee MQTT connected to {}:{}", properties.getHost(), properties.getPort());
            }
        });
    }

    /**
     * Erzwungener Neuaufbau: trennen UND danach explizit neu verbinden.
     * <p>
     * Das explizite {@link #connect()} ist zwingend — HiveMQs automaticReconnect greift
     * nur bei unerwarteten Abbruechen, ein selbst angestossenes disconnect() gilt als
     * gewollt und schaltet ihn ab. Ein forceReconnect, das nur trennt, wuerde die
     * Anbindung endgueltig killen statt sie zu heilen.
     * <p>
     * Der anschliessende ConnectedListener loest ein frisches Subscribe aus — genau das
     * heilt den Fall "verbunden, aber ohne Subscription".
     */
    @Override
    public void forceReconnect() {
        Mqtt3AsyncClient current = this.client;
        if (current == null) {
            return;
        }
        log.warn("Zigbee MQTT: erzwungener Reconnect durch den Watchdog");
        current.disconnect().whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                log.warn("Zigbee MQTT: Trennen vor dem Reconnect fehlgeschlagen: {}",
                        throwable.getMessage());
            }
            connect();
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
     * <p>
     * Die Generationspruefung allein reicht NICHT: HiveMQ laesst ein {@code subscribe()}
     * waehrend eines laufenden Auto-Reconnects nicht scheitern, sondern queued es intern
     * (siehe {@code MqttSubscriptionHandler}) und liefert es erst beim naechsten CONNACK
     * aus - der Client bleibt dabei {@code DISCONNECTED_RECONNECT}, es gibt also KEINEN
     * neuen {@code ConnectedListener}-Aufruf und damit keine Generationsaenderung, bis
     * die Verbindung tatsaechlich zurueckkommt. Ein alter Retry-Versuch koennte in genau
     * diesem Fenster (Sekunden bis Minuten) den Generations-Check passieren und trotzdem
     * noch in die interne Warteschlange geraten; beim naechsten CONNACK wuerde er dann
     * ZUSAETZLICH zur frischen Subscription des neuen ConnectedListener-Aufrufs
     * ausgeliefert. Deshalb zusaetzlich pruefen, ob der Client ueberhaupt verbunden ist -
     * ist er das nicht, bricht dieser Versuch wortlos ab; der ConnectedListener
     * subscribed beim naechsten erfolgreichen Connect ohnehin neu.
     */
    private void subscribe(int attempt, int generation) {
        if (generation != subscribeGeneration.get()) {
            return;
        }
        Mqtt3AsyncClient current = this.client;
        if (current == null) {
            return;
        }
        if (!current.getConfig().getState().isConnected()) {
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

            Optional<String> bridgeState = parser.parseBridgeState(topic, payload);
            if (bridgeState.isPresent()) {
                streamMonitor.recordBridgeState(bridgeState.get());
                log.info("zigbee2mqtt meldet sich als {}", bridgeState.get());
                return;
            }

            Optional<ZigbeeAvailability> availability = parser.parseAvailability(topic, payload);
            if (availability.isPresent()) {
                streamMonitor.recordAvailability(
                        availability.get().friendlyName(), availability.get().online());
                return;
            }

            Optional<ParsedZigbeeMessage> parsed = parser.parse(topic, payload, publish.isRetain());
            parsed.ifPresent(msg -> {
                // Retained Nachrichten duerfen die Stille-Uhr NICHT zuruecksetzen: nach
                // einem Re-Subscribe spielt der Broker den letzten retained Wert jedes
                // Geraets erneut aus, ohne dass eine einzige frische Funk-Nachricht kam.
                // Ein recordMessage(...) hier wuerde die Anbindung bei einem zappelnden
                // Client so lange "lebendig" erscheinen lassen, wie er zappelt. Die
                // uebrige Verarbeitung (Messwerte speichern, Entity-States melden)
                // bleibt fuer retained Nachrichten unveraendert - der Wert selbst ist
                // ja weiterhin gueltig.
                if (!publish.isRetain()) {
                    streamMonitor.recordMessage(msg.friendlyName());
                }
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
        // client.disconnect() liefert ein CompletableFuture<Void>, dessen Abschluss wir
        // hier mit Timeout ABWARTEN, bevor die Executors herunterfahren. Der reine Aufruf
        // ohne join() haette das Abschaltfenster nicht wirklich geschlossen - die
        // shutdownNow()-Aufrufe liefen praktisch sofort danach, typischerweise bevor das
        // DISCONNECT-Paket ueberhaupt auf der Leitung war.
        //
        // handlerExecutor faehrt bewusst per shutdown() + awaitTermination() herunter,
        // NICHT per shutdownNow(): Letzteres unterbricht den laufenden
        // zigbee-mqtt-handler-Thread hart. Steckt der gerade in einer
        // @Transactional-DB-Operation (readingService.record), rollt die Transaktion
        // zwar korrekt zurueck, aber ein Messwert geht verloren und es kann bei jedem
        // Herunterfahren eine JDBCConnectionException geloggt werden.
        shuttingDown.set(true);
        if (client != null) {
            try {
                client.disconnect().orTimeout(3, TimeUnit.SECONDS).exceptionally(t -> null).join();
            } catch (Exception ex) {
                log.debug("Error during MQTT disconnect: {}", ex.getMessage());
            }
        }
        retryScheduler.shutdownNow();
        handlerExecutor.shutdown();
        try {
            if (!handlerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                handlerExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            handlerExecutor.shutdownNow();
        }
    }
}
