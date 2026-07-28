# Zigbee-Ausfallerkennung und -härtung — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ein Ausfall der Zigbee-Anbindung wird binnen Minuten sichtbar, aktiv gemeldet und nach Möglichkeit selbst geheilt — ohne beim Wiederanlaufen einen Fehlalarm auszulösen.

**Architecture:** Ein `ZigbeeStreamMonitor` (rein im Speicher) ist die einzige Definition von „die Anbindung lebt" und wird vom MQTT-Handler bei jeder Nachricht gefüttert. Ein `ZigbeeAvailabilityWatchdog` (`@Scheduled`) fragt ihn minütlich, versucht bei Stille erst eine Selbstheilung und meldet erst danach den Ausfall über eine EVENT-Entität, auf die ein Flow die Telegram-Warnung setzt. Parallel wird der bestehende MQTT-Client gehärtet und die Flow-Engine so angepasst, dass Übergänge aus `unavailable` heraus nichts auslösen.

**Tech Stack:** Java 21, Spring Boot 3.4.1, HiveMQ MQTT-Client (Mqtt3), Lombok, JUnit 5 + Mockito + AssertJ, Angular 19.

**Spec:** `docs/superpowers/specs/2026-07-28-zigbee-ausfallerkennung-design.md`

---

## Vorbereitung (einmalig pro Session)

Der Backend-Build braucht JDK 21; die Standard-`JAVA_HOME` dieser Maschine zeigt auf JDK 17.
**Vor jedem Maven-Befehl** in der Bash-Shell:

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
```

Alle Maven-Befehle laufen aus `backend/`. Es gibt keinen `mvnw`-Wrapper.

**Vorbestehende Testfehler, die nichts mit dieser Arbeit zu tun haben** und ignoriert werden
müssen: `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` scheitern
lokal an „Access denied for user 'root'@'localhost'" (keine Test-DB auf dieser Maschine).
Immer gezielt einzelne Testklassen laufen lassen, nicht die ganze Suite.

Gearbeitet wird auf dem bereits angelegten Branch `feature/zigbee-ausfallerkennung`.

---

## Dateiübersicht

**Neu:**

| Datei | Verantwortung |
|---|---|
| `backend/src/main/java/com/household/manager/zigbee/parser/ZigbeeAvailability.java` | Wertobjekt: Geräteverfügbarkeit aus einem `/availability`-Topic |
| `backend/src/main/java/com/household/manager/zigbee/model/ZigbeeStreamStatus.java` | Urteil über den Zustand der Anbindung (Wertobjekt) |
| `backend/src/main/java/com/household/manager/zigbee/config/ZigbeeWatchdogProperties.java` | Konfiguration des Watchdogs |
| `backend/src/main/java/com/household/manager/zigbee/service/ZigbeeStreamMonitor.java` | Einzige Definition von „die Anbindung lebt" |
| `backend/src/main/java/com/household/manager/zigbee/service/ZigbeeConnectionControl.java` | Schnittstelle für den erzwungenen Reconnect (entkoppelt Watchdog vom MQTT-Client) |
| `backend/src/main/java/com/household/manager/zigbee/service/ZigbeeAvailabilityWatchdog.java` | Zustandsautomat, Selbstheilung, `unavailable`, Alarm-Event |
| `backend/src/main/java/com/household/manager/zigbee/dto/ZigbeeHealthResponse.java` | Antwort des Health-Endpunkts |

**Geändert:**

| Datei | Änderung |
|---|---|
| `backend/src/main/java/com/household/manager/zigbee/config/ZigbeeMqttConfig.java` | Logging, Disconnect-Sichtbarkeit, Resubscribe-Wiederholung, eigener Executor, Monitor-Verdrahtung, `ZigbeeConnectionControl` |
| `backend/src/main/java/com/household/manager/zigbee/service/ZigbeeMessageParser.java` | `parseBridgeState` und `parseAvailability` |
| `backend/src/main/java/com/household/manager/zigbee/controller/ZigbeeController.java` | `GET /v1/zigbee/health` |
| `backend/src/main/java/com/household/manager/flowengine/nodes/EntityStateTriggerHandler.java` | Kein Feuern bei `unavailable` |
| `backend/src/main/resources/application.properties` | Watchdog-Properties |
| `frontend/src/app/pages/zigbee/*` | Ausfall-Banner |
| `CLAUDE.md` | Dokumentation der neuen Mechanik |

**Tests neu/erweitert:** `ZigbeeMessageParserTest`, `ZigbeeStreamMonitorTest`,
`ZigbeeAvailabilityWatchdogTest`, `EntityStateTriggerHandlerTest`.

---

## Task 1: MQTT-Fehler sichtbar machen

Heute wird ein Verarbeitungsfehler auf `debug` und ohne Stacktrace geloggt — in PROD
unsichtbar. Außerdem sieht man Reconnect-Zyklen nirgends.

**Files:**
- Modify: `backend/src/main/java/com/household/manager/zigbee/config/ZigbeeMqttConfig.java`

- [ ] **Step 1: Fehler-Logging anheben und Disconnect sichtbar machen**

In `handle(...)` den `catch`-Block ersetzen:

```java
        } catch (Exception ex) {
            log.warn("Zigbee-MQTT-Nachricht konnte nicht verarbeitet werden (Topic {}): {}",
                    publish.getTopic(), ex.getMessage(), ex);
        }
```

Achtung: `publish` muss dafür im `catch` erreichbar sein — der Parameter ist es bereits.

In `start()` beim Client-Bau einen Disconnect-Listener ergänzen, direkt nach
`.addConnectedListener(...)`:

```java
                .addDisconnectedListener(ctx -> log.warn(
                        "Zigbee MQTT getrennt (Quelle {}), Reconnect laeuft: {}",
                        ctx.getSource(), ctx.getCause().getMessage()))
```

- [ ] **Step 2: Kompilieren**

```bash
cd backend && mvn -q compile
```

Erwartung: kein Fehler.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/household/manager/zigbee/config/ZigbeeMqttConfig.java
git commit -m "fix(zigbee): MQTT-Fehler und Reconnects sichtbar loggen"
```

---

## Task 2: Resubscribe wiederholen statt einmal zu warnen

Der wahrscheinlichste Kandidat für den beobachteten Dauerausfall: scheitert `subscribe()`
einmal, bleibt der Client verbunden, ohne je wieder ein Topic zu abonnieren.

> **Nachtrag aus dem Review — die naive Wiederholung erzeugt einen neuen Fehler.**
> `addConnectedListener(ctx -> subscribe())` startet bei *jedem* Connect eine neue
> Retry-Kette, ohne eine laufende zu beenden. Scheitert Kette A und gelingt danach der
> Auto-Reconnect (→ Kette B, erfolgreich), feuert der noch anstehende Versuch von Kette A
> bis zu 60 s später ebenfalls erfolgreich — es gibt dann **zwei** Subscriptions auf
> demselben Topic-Filter. HiveMQ dedupliziert nicht, also läuft `handle(...)` pro Nachricht
> zweimal: doppelte Messwerte in der DB und **jeder Tastendruck feuert seinen Flow doppelt**.
> Lautlos, ausgerechnet in dem Feature, das lautlose Fehler sichtbar machen soll.
>
> Deshalb braucht die Kette einen **Generationszähler**: ein `AtomicInteger`, den der
> ConnectedListener vor `subscribe()` hochzählt und den `subscribe(attempt, generation)`
> mitführt; passt die Generation nicht mehr, bricht die Kette wortlos ab. Die Prüfung muss
> **vor dem Absenden und erneut im `whenComplete`** stehen, bevor ein Folgeversuch geplant
> wird.

**Files:**
- Modify: `backend/src/main/java/com/household/manager/zigbee/config/ZigbeeMqttConfig.java`

- [ ] **Step 1: Wiederholung mit Backoff einbauen**

Feld ergänzen (bei den anderen Feldern):

```java
    /** Eigener Scheduler nur fuer Resubscribe-Wiederholungen. */
    private final ScheduledExecutorService retryScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "zigbee-mqtt-retry");
                t.setDaemon(true);
                return t;
            });

    private static final int RESUBSCRIBE_MAX_DELAY_SECONDS = 60;
```

`subscribe()` ersetzen durch eine Variante mit Versuchszähler:

```java
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
```

Imports ergänzen:

```java
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
```

In `stop()` den Scheduler mit herunterfahren, vor dem `client.disconnect()`:

```java
        retryScheduler.shutdownNow();
```

- [ ] **Step 2: Kompilieren**

```bash
cd backend && mvn -q compile
```

Erwartung: kein Fehler.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/household/manager/zigbee/config/ZigbeeMqttConfig.java
git commit -m "fix(zigbee): fehlgeschlagenes MQTT-Subscribe wiederholen"
```

---

## Task 3: Verarbeitung vom Netty-Event-Loop entkoppeln

`.callback(this::handle)` läuft heute auf dem Netty-Event-Loop und führt dort synchrone
DB-Transaktionen aus. Hängt die Datenbank, steht der komplette MQTT-Client still —
inklusive Keepalive und Reconnect.

Bewusst **ein einziger** Thread, kein Pool: mehrere Threads könnten Nachrichten desselben
Geräts umsortieren, und bei einem Türkontakt wäre ein vertauschtes „offen"/„zu" fatal.

> **Nachtrag aus dem Review — die beschränkte Queue bewirkt nichts.**
> HiveMQ wickelt den Executor in `Schedulers.from(executor)`. Ein RxJava-`ExecutorWorker`
> sammelt die Runnables in seiner *eigenen unbeschränkten* Queue und submittet sich selbst
> nur, wenn `wip.getAndIncrement() == 0` — in der `ArrayBlockingQueue` steht damit immer
> höchstens **eine** Task. Sie läuft nie voll, der `RejectedExecutionHandler` feuert im
> Normalbetrieb nie, die „Queue voll"-Fehlermeldung ist toter Code.
>
> Das echte Verhalten bei hängender Datenbank ist RxJava-Backpressure (`observeOn` puffert
> bis 128, danach drosselt HiveMQ Richtung Broker) — funktional in Ordnung, aber eben nicht
> das, was der Code behauptet. Keine Warnung zusagen, die nie kommt: den tatsächlichen
> Mechanismus im Javadoc benennen statt eine Kapazitätsgrenze zu inszenieren.
>
> Zweite Folge: `ThreadPoolExecutor` ruft den Reject-Handler auch bei `isShutdown()` auf.
> Wird der Executor **vor** dem `disconnect()` gestoppt, ist der Shutdown der einzige
> realistische Auslöser — und die Meldung „deutet auf eine hängende Datenbank hin" wäre in
> praktisch jedem echten Vorkommen irreführend. In `stop()` deshalb **erst** disconnecten,
> **dann** die Executors herunterfahren.

**Files:**
- Modify: `backend/src/main/java/com/household/manager/zigbee/config/ZigbeeMqttConfig.java`

- [ ] **Step 1: Executor anlegen und im Callback verwenden**

Felder ergänzen:

```java
    private static final int HANDLER_QUEUE_CAPACITY = 1000;

    /**
     * Verarbeitung laeuft bewusst auf GENAU EINEM Thread: mehrere Threads koennten
     * Nachrichten desselben Geraets umsortieren, und bei einem Tuerkontakt waere ein
     * vertauschtes "offen"/"zu" fatal. Der Netty-Event-Loop bleibt trotzdem frei,
     * sodass eine haengende Datenbank Keepalive und Reconnect nicht mehr blockiert.
     */
    private final ThreadPoolExecutor handlerExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(HANDLER_QUEUE_CAPACITY),
            r -> {
                Thread t = new Thread(r, "zigbee-mqtt-handler");
                t.setDaemon(true);
                return t;
            },
            (r, executor) -> log.error(
                    "Zigbee-Verarbeitungsqueue voll ({} Eintraege) — Nachricht verworfen. "
                            + "Das deutet auf eine haengende Datenbank hin.",
                    HANDLER_QUEUE_CAPACITY));
```

Import ergänzen:

```java
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
```

In `subscribe(int attempt)` den Callback um den Executor erweitern:

```java
                .callback(this::handle)
                .executor(handlerExecutor)
```

**Korrigiert nach der Umsetzung:** Eine Überladung `callback(Consumer, Executor)` gibt es in
`hivemq-mqtt-client:1.3.17` **nicht** — `callback(...)` nimmt genau ein Argument und liefert
ein `Call.Ex`, das `executor(...)` anbietet. Verifiziert per `javap` gegen das Jar; intern
landet der Executor in `MqttAsyncClient.subscribe(...)` bei
`observeOnBoth(Schedulers.from(executor), true)`, der Callback läuft also tatsächlich nicht
mehr auf dem Netty-Event-Loop.

In `stop()` ergänzen, neben `retryScheduler.shutdownNow()`:

```java
        handlerExecutor.shutdownNow();
```

- [ ] **Step 2: Kompilieren**

```bash
cd backend && mvn -q compile
```

Erwartung: kein Fehler. Die HiveMQ-Signatur `callback(Consumer<Mqtt3Publish>, Executor)`
existiert — schlägt der Aufruf fehl, ist der Import von `java.util.concurrent.Executor`
zu prüfen.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/household/manager/zigbee/config/ZigbeeMqttConfig.java
git commit -m "fix(zigbee): MQTT-Verarbeitung vom Netty-Event-Loop entkoppeln"
```

---

## Task 4: Flow-Engine feuert nicht mehr aus `unavailable` heraus

Ohne diesen Schritt macht der Watchdog die Lage schlechter: ein Türkontakt, der beim
Ausfall „offen" war, springt bei der Erholung von `unavailable` auf `on` und erfüllt
`nowMatches && !beforeMatched` — Flow #2 („Tür offen bei Abwesenheit") sendete einen
Fehlalarm. **Dieser Task muss vor Task 10 fertig sein.**

**Files:**
- Modify: `backend/src/main/java/com/household/manager/flowengine/nodes/EntityStateTriggerHandler.java`
- Test: `backend/src/test/java/com/household/manager/flowengine/nodes/EntityStateTriggerHandlerTest.java`

- [ ] **Step 1: Fehlschlagende Tests schreiben**

An die bestehende Testklasse anhängen (die Helfer `config(...)` und `event(...)` sind
dort bereits vorhanden):

```java
    @Test
    void feuertNichtBeimWiederanlaufenAusUnavailable() {
        NodeConfig config = config(Map.of(
                "entityId", "binary_sensor.zigbee_eingangstuer_contact",
                "operator", "==",
                "value", "on"));

        handler.onEntityEvent(event("unavailable", "on"), config, ctx);

        assertTrue(emitted.isEmpty(),
                "Erholung aus unavailable darf kein Ereignis sein");
    }

    @Test
    void feuertNichtBeimAusfallNachUnavailable() {
        NodeConfig config = config(Map.of(
                "entityId", "binary_sensor.zigbee_eingangstuer_contact",
                "operator", "changed"));

        handler.onEntityEvent(event("on", "unavailable"), config, ctx);

        assertTrue(emitted.isEmpty(),
                "Der Ausfall selbst darf kein Ereignis sein");
    }

    @Test
    void feuertNichtBeiChangedAusUnavailable() {
        NodeConfig config = config(Map.of(
                "entityId", "binary_sensor.zigbee_eingangstuer_contact",
                "operator", "changed"));

        handler.onEntityEvent(event("unavailable", "off"), config, ctx);

        assertTrue(emitted.isEmpty(),
                "Auch 'changed' darf beim Wiederanlaufen nicht feuern");
    }

    @Test
    void feuertWeiterhinBeiEchtemZustandswechsel() {
        NodeConfig config = config(Map.of(
                "entityId", "binary_sensor.zigbee_eingangstuer_contact",
                "operator", "==",
                "value", "on"));

        handler.onEntityEvent(event("off", "on"), config, ctx);

        assertEquals(1, emitted.size(),
                "Ein echter Wechsel muss unveraendert feuern");
    }
```

- [ ] **Step 2: Tests laufen lassen, Fehlschlag bestätigen**

```bash
cd backend && mvn test -Dtest=EntityStateTriggerHandlerTest
```

Erwartung: FAIL. Die ersten drei Tests scheitern, weil aktuell gefeuert wird
(`emitted` ist nicht leer). `feuertWeiterhinBeiEchtemZustandswechsel` besteht bereits.

- [ ] **Step 3: Implementieren**

In `EntityStateTriggerHandler` eine Konstante ergänzen, neben `OP_CHANGED`:

```java
    private static final String STATE_UNAVAILABLE = "unavailable";
```

`onEntityEvent` am Anfang erweitern:

```java
    @Override
    public void onEntityEvent(EntityStateChangedEvent event, NodeConfig config, NodeContext ctx) {
        // Der Ausfall selbst ist kein Ereignis der beobachteten Groesse — ohne diese
        // Unterdrueckung loesten "!=" und "changed" bei jedem Aussetzer aus.
        // Das Wiederanlaufen feuert dagegen BEWUSST normal: unterdrueckte man es,
        // bliebe Flow #4 ("Feuer-Verdacht", Temperatur > 40) nach jedem Zigbee-Ausfall
        // entwaffnet, bis die Temperatur erst unter 40 faellt und wieder steigt — ein
        // waehrend des Ausfalls ausgebrochenes Feuer wuerde nie gemeldet. Der Preis ist
        // eine moegliche Doppelmeldung (Flow #2, Tuer bereits vor dem Ausfall offen);
        // eine Dopplung ist aergerlich, ein verschluckter Brandalarm nicht.
        if (STATE_UNAVAILABLE.equals(event.newState())) {
            cancelTimer(ctx);
            return;
        }

        String operator = config.string("operator").orElse(OP_CHANGED);
        // ... unveraendert weiter
```

- [ ] **Step 4: Tests laufen lassen**

```bash
cd backend && mvn test -Dtest=EntityStateTriggerHandlerTest
```

Erwartung: PASS, alle Tests der Klasse.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/flowengine/nodes/EntityStateTriggerHandler.java backend/src/test/java/com/household/manager/flowengine/nodes/EntityStateTriggerHandlerTest.java
git commit -m "fix(flowengine): keine Trigger beim Uebergang von/nach unavailable"
```

- [ ] **Step 6: Befund zu `EntityConditionHandler` festhalten (keine Codeänderung)**

Die Spec verlangte eine Prüfung. Ergebnis: `StateComparator.matches` kann `"unavailable"`
nicht als Zahl parsen, numerische Operatoren liefern damit bereits `false` — eine
Bedingung auf einer ausgefallenen Entität wertet korrekt zu „nicht erfüllt".

**Aber:** `!=` vergleicht bei nicht-numerischen Werten als String, deshalb ist
`unavailable != on` **wahr**. Eine Bedingung „Tür ist nicht offen" wäre bei einem
Ausfall also erfüllt. Das ist eine bestehende Semantik, die nicht Teil dieses Entwurfs
ist — nicht ändern, sondern nur in `CLAUDE.md` festhalten (siehe Task 13).

---

## Task 5: Parser erkennt `bridge/state` und `availability`

zigbee2mqtt publiziert diese Topics ohnehin; heute verwirft `isDeviceTopic` sie, weil sie
ein `/` enthalten. Payload-Formate unterscheiden sich je nach zigbee2mqtt-Version:
neuere schicken `{"state":"online"}`, ältere den nackten Text `online`. Beide müssen
funktionieren.

**Files:**
- Create: `backend/src/main/java/com/household/manager/zigbee/parser/ZigbeeAvailability.java`
- Modify: `backend/src/main/java/com/household/manager/zigbee/service/ZigbeeMessageParser.java`
- Test: `backend/src/test/java/com/household/manager/zigbee/service/ZigbeeMessageParserTest.java`

- [ ] **Step 1: Fehlschlagende Tests schreiben**

An `ZigbeeMessageParserTest` anhängen:

```java
    @Test
    void liestBridgeStateAlsJson() {
        Optional<String> result = parser.parseBridgeState(
                "zigbee2mqtt/bridge/state", "{\"state\":\"online\"}");

        assertThat(result).contains("online");
    }

    @Test
    void liestBridgeStateAlsKlartext() {
        Optional<String> result = parser.parseBridgeState(
                "zigbee2mqtt/bridge/state", "offline");

        assertThat(result).contains("offline");
    }

    @Test
    void ignoriertBridgeStateAufFremdemTopic() {
        assertThat(parser.parseBridgeState("zigbee2mqtt/Kueche", "online")).isEmpty();
    }

    @Test
    void liestGeraeteVerfuegbarkeit() {
        Optional<ZigbeeAvailability> result = parser.parseAvailability(
                "zigbee2mqtt/Temperatur Buero/availability", "{\"state\":\"offline\"}");

        assertThat(result).isPresent();
        assertThat(result.get().friendlyName()).isEqualTo("Temperatur Buero");
        assertThat(result.get().online()).isFalse();
    }

    @Test
    void liestGeraeteVerfuegbarkeitAlsKlartext() {
        Optional<ZigbeeAvailability> result = parser.parseAvailability(
                "zigbee2mqtt/Motion Flur/availability", "online");

        assertThat(result).isPresent();
        assertThat(result.get().online()).isTrue();
    }

    @Test
    void ignoriertBridgeAlsGeraeteVerfuegbarkeit() {
        assertThat(parser.parseAvailability("zigbee2mqtt/bridge/availability", "online"))
                .isEmpty();
    }

    @Test
    void wertTopicsBleibenUnveraendert() {
        Optional<ParsedZigbeeMessage> result =
                parser.parse("zigbee2mqtt/Kueche", "{\"temperature\":21.5}");

        assertThat(result).isPresent();
        assertThat(valueOf(result.get(), MeasurementType.TEMPERATURE))
                .isEqualByComparingTo("21.5");
    }
```

Import in der Testklasse ergänzen:

```java
import com.household.manager.zigbee.parser.ZigbeeAvailability;
```

- [ ] **Step 2: Tests laufen lassen, Fehlschlag bestätigen**

```bash
cd backend && mvn test -Dtest=ZigbeeMessageParserTest
```

Erwartung: Kompilierfehler — `parseBridgeState`, `parseAvailability` und
`ZigbeeAvailability` existieren nicht.

- [ ] **Step 3: Wertobjekt anlegen**

`backend/src/main/java/com/household/manager/zigbee/parser/ZigbeeAvailability.java`:

```java
package com.household.manager.zigbee.parser;

/**
 * Verfuegbarkeitsmeldung eines Zigbee-Geraets aus dem Topic
 * {@code zigbee2mqtt/<friendly_name>/availability}.
 */
public record ZigbeeAvailability(String friendlyName, boolean online) {
}
```

- [ ] **Step 4: Parser erweitern**

In `ZigbeeMessageParser` Konstanten ergänzen:

```java
    private static final String BRIDGE_STATE_TOPIC = "zigbee2mqtt/bridge/state";
    private static final String AVAILABILITY_SUFFIX = "/availability";
    private static final String BRIDGE_NAME = "bridge";
```

Import ergänzen:

```java
import com.household.manager.zigbee.parser.ZigbeeAvailability;
```

Methoden ergänzen:

```java
    /**
     * Liest den Zustand von zigbee2mqtt selbst ({@code online}/{@code offline}).
     * Leer, wenn das Topic nicht {@value #BRIDGE_STATE_TOPIC} ist.
     */
    public Optional<String> parseBridgeState(String topic, String payload) {
        if (!BRIDGE_STATE_TOPIC.equals(topic)) {
            return Optional.empty();
        }
        return stateFromPayload(payload);
    }

    /**
     * Liest die Verfuegbarkeit eines einzelnen Geraets.
     * <p>
     * Geraetenamen mit '/' werden — wie schon in {@link #isDeviceTopic} — nicht
     * unterstuetzt; das ist eine bewusst geteilte Annahme mit dem bestehenden
     * Identitaetsmodell.
     */
    public Optional<ZigbeeAvailability> parseAvailability(String topic, String payload) {
        if (topic == null || !topic.startsWith(TOPIC_PREFIX) || !topic.endsWith(AVAILABILITY_SUFFIX)) {
            return Optional.empty();
        }
        String name = topic.substring(TOPIC_PREFIX.length(), topic.length() - AVAILABILITY_SUFFIX.length());
        if (name.isEmpty() || name.contains("/") || BRIDGE_NAME.equals(name)) {
            return Optional.empty();
        }
        return stateFromPayload(payload)
                .map(state -> new ZigbeeAvailability(name, "online".equalsIgnoreCase(state)));
    }

    /**
     * Neuere zigbee2mqtt-Versionen senden {@code {"state":"online"}}, aeltere den
     * nackten Text {@code online}. Beides muss funktionieren.
     */
    private Optional<String> stateFromPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        String trimmed = payload.trim();
        if (trimmed.startsWith("{")) {
            try {
                JsonNode root = objectMapper.readTree(trimmed);
                JsonNode state = root.get("state");
                if (state != null && state.isTextual() && !state.asText().isBlank()) {
                    return Optional.of(state.asText().trim());
                }
            } catch (Exception ex) {
                log.debug("Zigbee-Status-Payload nicht parsebar: {}", ex.getMessage());
            }
            return Optional.empty();
        }
        return Optional.of(trimmed);
    }
```

- [ ] **Step 5: Tests laufen lassen**

```bash
cd backend && mvn test -Dtest=ZigbeeMessageParserTest
```

Erwartung: PASS, alle Tests der Klasse (auch die vorbestehenden).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/zigbee/parser/ZigbeeAvailability.java backend/src/main/java/com/household/manager/zigbee/service/ZigbeeMessageParser.java backend/src/test/java/com/household/manager/zigbee/service/ZigbeeMessageParserTest.java
git commit -m "feat(zigbee): bridge/state und Geraete-availability parsen"
```

---

## Task 6: Watchdog-Konfiguration

**Files:**
- Create: `backend/src/main/java/com/household/manager/zigbee/config/ZigbeeWatchdogProperties.java`
- Modify: `backend/src/main/resources/application.properties`

- [ ] **Step 1: Properties-Klasse anlegen**

```java
package com.household.manager.zigbee.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Schwellen der Zigbee-Ausfallerkennung.
 * <p>
 * Bewusst in application.properties und nicht in der Datenbank: anders als bei der
 * Tractive-Home-Definition gibt es keinen Grund, diese Werte im laufenden Betrieb
 * zu verstellen.
 */
@Component
@ConfigurationProperties(prefix = "zigbee.watchdog")
@Getter
@Setter
public class ZigbeeWatchdogProperties {

    private boolean enabled = true;

    /**
     * Stille, ab der ein Ausfall vermutet wird. Abgeleitet aus den PROD-Daten:
     * die sieben Temperatursensoren melden im Minutenabstand, totale Stille ueber
     * 15 Minuten ist damit sicher ein Ausfall. Nach einigen Tagen Betrieb gegen die
     * tatsaechlichen Melde-Abstaende nachziehen.
     */
    private int staleAfterMinutes = 15;

    /** Frist nach dem Selbstheilungsversuch, bevor Alarm geschlagen wird. */
    private int recoverGraceMinutes = 5;

    public Duration staleAfter() {
        return Duration.ofMinutes(staleAfterMinutes);
    }

    public Duration recoverGrace() {
        return Duration.ofMinutes(recoverGraceMinutes);
    }
}
```

- [ ] **Step 2: application.properties ergänzen**

Direkt unter dem bestehenden `zigbee.mqtt.client-id` (aktuell Zeile 120) einfügen:

```properties
zigbee.watchdog.enabled=${ZIGBEE_WATCHDOG_ENABLED:true}
zigbee.watchdog.stale-after-minutes=${ZIGBEE_WATCHDOG_STALE_AFTER_MINUTES:15}
zigbee.watchdog.recover-grace-minutes=${ZIGBEE_WATCHDOG_RECOVER_GRACE_MINUTES:5}
```

- [ ] **Step 3: Kompilieren**

```bash
cd backend && mvn -q compile
```

Erwartung: kein Fehler.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/household/manager/zigbee/config/ZigbeeWatchdogProperties.java backend/src/main/resources/application.properties
git commit -m "feat(zigbee): Konfiguration fuer die Ausfallerkennung"
```

---

## Task 7: `ZigbeeStreamMonitor`

Die einzige Definition von „die Anbindung lebt" — Watchdog, Health-Endpunkt und
Meldungstext fragen alle diese Klasse, damit sie nicht auseinanderlaufen können.

**Files:**
- Create: `backend/src/main/java/com/household/manager/zigbee/model/ZigbeeStreamStatus.java`
- Create: `backend/src/main/java/com/household/manager/zigbee/service/ZigbeeStreamMonitor.java`
- Test: `backend/src/test/java/com/household/manager/zigbee/service/ZigbeeStreamMonitorTest.java`

- [ ] **Step 1: Wertobjekt anlegen**

`backend/src/main/java/com/household/manager/zigbee/model/ZigbeeStreamStatus.java`:

```java
package com.household.manager.zigbee.model;

import java.time.Instant;
import java.util.List;

/**
 * Urteil ueber den Zustand der Zigbee-Anbindung.
 *
 * @param health         Gesamturteil
 * @param lastMessageAt  wann kam zuletzt irgendeine Geraetenachricht
 * @param silentMinutes  wie lange ist es seitdem still
 * @param bridgeState    letzter von zigbee2mqtt gemeldeter Zustand, oder null
 * @param offlineDevices Geraete, die zigbee2mqtt als offline meldet
 */
public record ZigbeeStreamStatus(
        Health health,
        Instant lastMessageAt,
        long silentMinutes,
        String bridgeState,
        List<String> offlineDevices) {

    public enum Health {
        /** Nachrichten kommen an. */
        OK,
        /** Keine Nachricht innerhalb der Schwelle. */
        STILL,
        /** zigbee2mqtt meldet sich selbst als offline. */
        BRIDGE_OFFLINE
    }

    public boolean healthy() {
        return health == Health.OK;
    }
}
```

- [ ] **Step 2: Fehlschlagenden Test schreiben**

`backend/src/test/java/com/household/manager/zigbee/service/ZigbeeStreamMonitorTest.java`:

```java
package com.household.manager.zigbee.service;

import com.household.manager.zigbee.config.ZigbeeWatchdogProperties;
import com.household.manager.zigbee.model.ZigbeeStreamStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ZigbeeStreamMonitorTest {

    private static final Instant START = Instant.parse("2026-07-28T12:00:00Z");

    /** Verstellbare Uhr, damit Stille ohne echtes Warten testbar ist. */
    private static final class TestClock extends Clock {
        private Instant now = START;

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final TestClock clock = new TestClock();
    private final ZigbeeWatchdogProperties properties = new ZigbeeWatchdogProperties();
    private final ZigbeeStreamMonitor monitor = new ZigbeeStreamMonitor(properties, clock);

    @Test
    void istDirektNachDemStartGesund() {
        assertThat(monitor.status().health()).isEqualTo(ZigbeeStreamStatus.Health.OK);
    }

    @Test
    void bleibtGesundSolangeNachrichtenKommen() {
        clock.advance(Duration.ofMinutes(14));
        monitor.recordMessage("Temperatur Buero");
        clock.advance(Duration.ofMinutes(14));

        assertThat(monitor.status().health()).isEqualTo(ZigbeeStreamStatus.Health.OK);
    }

    @Test
    void meldetStilleNachDerSchwelle() {
        clock.advance(Duration.ofMinutes(16));

        ZigbeeStreamStatus status = monitor.status();

        assertThat(status.health()).isEqualTo(ZigbeeStreamStatus.Health.STILL);
        assertThat(status.silentMinutes()).isEqualTo(16);
    }

    @Test
    void meldetBridgeOfflineSofort() {
        monitor.recordBridgeState("offline");

        assertThat(monitor.status().health()).isEqualTo(ZigbeeStreamStatus.Health.BRIDGE_OFFLINE);
    }

    @Test
    void bridgeOnlineHebtDenOfflineZustandWiederAuf() {
        monitor.recordBridgeState("offline");
        monitor.recordBridgeState("online");

        assertThat(monitor.status().health()).isEqualTo(ZigbeeStreamStatus.Health.OK);
    }

    @Test
    void fuehrtOfflineGemeldeteGeraete() {
        monitor.recordAvailability("Motion Flur", false);
        monitor.recordAvailability("Temperatur Buero", true);

        assertThat(monitor.status().offlineDevices()).containsExactly("Motion Flur");
    }

    @Test
    void geraetDasWiederOnlineIstVerschwindetAusDerListe() {
        monitor.recordAvailability("Motion Flur", false);
        monitor.recordAvailability("Motion Flur", true);

        assertThat(monitor.status().offlineDevices()).isEmpty();
    }
}
```

- [ ] **Step 3: Test laufen lassen, Fehlschlag bestätigen**

```bash
cd backend && mvn test -Dtest=ZigbeeStreamMonitorTest
```

Erwartung: Kompilierfehler — `ZigbeeStreamMonitor` existiert nicht.

- [ ] **Step 4: Monitor implementieren**

`backend/src/main/java/com/household/manager/zigbee/service/ZigbeeStreamMonitor.java`:

```java
package com.household.manager.zigbee.service;

import com.household.manager.zigbee.config.ZigbeeWatchdogProperties;
import com.household.manager.zigbee.model.ZigbeeStreamStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Die einzige Definition von "die Zigbee-Anbindung lebt". Watchdog, Health-Endpunkt
 * und Meldungstext fragen alle diese Klasse, damit sie nicht auseinanderlaufen koennen
 * (gleiches Muster wie {@code TractiveHomeResolver} fuer "zu Hause").
 * <p>
 * Rein im Speicher, kein DB-Zugriff. Der Zustand ueberlebt einen Neustart bewusst
 * NICHT: die Stille-Uhr startet bei jedem Deploy neu, sonst loeste jeder Neustart
 * sofort einen Fehlalarm aus.
 */
@Component
public class ZigbeeStreamMonitor {

    private static final String BRIDGE_ONLINE = "online";

    private final ZigbeeWatchdogProperties properties;
    private final Clock clock;

    private volatile Instant lastMessageAt;
    private volatile String bridgeState;

    /** friendlyName -> online. Nur Geraete, zu denen zigbee2mqtt etwas gemeldet hat. */
    private final Map<String, Boolean> deviceAvailability = new ConcurrentHashMap<>();

    @Autowired
    public ZigbeeStreamMonitor(ZigbeeWatchdogProperties properties) {
        this(properties, Clock.systemUTC());
    }

    // Package-private fuer Tests mit verstellbarer Uhr.
    ZigbeeStreamMonitor(ZigbeeWatchdogProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.lastMessageAt = clock.instant();
    }

    /** Von jeder eingehenden Geraetenachricht aufzurufen. */
    public void recordMessage(String friendlyName) {
        lastMessageAt = clock.instant();
        if (friendlyName != null && !friendlyName.isBlank()) {
            deviceAvailability.put(friendlyName, Boolean.TRUE);
        }
    }

    public void recordBridgeState(String state) {
        bridgeState = state;
    }

    public void recordAvailability(String friendlyName, boolean online) {
        if (friendlyName != null && !friendlyName.isBlank()) {
            deviceAvailability.put(friendlyName, online);
        }
    }

    public Instant lastMessageAt() {
        return lastMessageAt;
    }

    public ZigbeeStreamStatus status() {
        Instant last = lastMessageAt;
        long silentMinutes = Duration.between(last, clock.instant()).toMinutes();
        List<String> offline = deviceAvailability.entrySet().stream()
                .filter(entry -> Boolean.FALSE.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();

        ZigbeeStreamStatus.Health health;
        if (bridgeState != null && !BRIDGE_ONLINE.equalsIgnoreCase(bridgeState)) {
            health = ZigbeeStreamStatus.Health.BRIDGE_OFFLINE;
        } else if (silentMinutes >= properties.staleAfter().toMinutes()) {
            health = ZigbeeStreamStatus.Health.STILL;
        } else {
            health = ZigbeeStreamStatus.Health.OK;
        }
        return new ZigbeeStreamStatus(health, last, silentMinutes, bridgeState, offline);
    }
}
```

- [ ] **Step 5: Test laufen lassen**

```bash
cd backend && mvn test -Dtest=ZigbeeStreamMonitorTest
```

Erwartung: PASS, alle sieben Tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/zigbee/model/ZigbeeStreamStatus.java backend/src/main/java/com/household/manager/zigbee/service/ZigbeeStreamMonitor.java backend/src/test/java/com/household/manager/zigbee/service/ZigbeeStreamMonitorTest.java
git commit -m "feat(zigbee): ZigbeeStreamMonitor als einzige Lebendigkeits-Definition"
```

---

## Task 8: Monitor an den MQTT-Handler verdrahten

**Files:**
- Modify: `backend/src/main/java/com/household/manager/zigbee/config/ZigbeeMqttConfig.java`

- [ ] **Step 1: Monitor injizieren und füttern**

Feld ergänzen:

```java
    private final ZigbeeStreamMonitor streamMonitor;
```

`handle(...)` so erweitern, dass Status-Topics ausgewertet und Gerätenachrichten
gemeldet werden. Der Rumpf innerhalb des `try` wird zu:

```java
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
                streamMonitor.recordMessage(msg.friendlyName());
                var events = readingService.record(msg);
                events.forEach(liveService::broadcast);
                reportEntityStates(msg);
            });
```

Imports ergänzen:

```java
import com.household.manager.zigbee.parser.ZigbeeAvailability;
import com.household.manager.zigbee.service.ZigbeeStreamMonitor;
```

- [ ] **Step 2: Kompilieren**

```bash
cd backend && mvn -q compile
```

Erwartung: kein Fehler.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/household/manager/zigbee/config/ZigbeeMqttConfig.java
git commit -m "feat(zigbee): MQTT-Handler fuettert den StreamMonitor"
```

---

## Task 9: `ZigbeeConnectionControl` für die Selbstheilung

Der Watchdog muss einen Reconnect erzwingen können, ohne den MQTT-Client zu kennen —
sonst wäre er nicht testbar.

**Files:**
- Create: `backend/src/main/java/com/household/manager/zigbee/service/ZigbeeConnectionControl.java`
- Modify: `backend/src/main/java/com/household/manager/zigbee/config/ZigbeeMqttConfig.java`

- [ ] **Step 1: Schnittstelle anlegen**

```java
package com.household.manager.zigbee.service;

/**
 * Erlaubt dem Watchdog, die MQTT-Verbindung neu aufzubauen, ohne den Client zu kennen.
 */
public interface ZigbeeConnectionControl {

    /**
     * Trennt die Verbindung und baut sie samt Subscription neu auf.
     * Darf nie werfen — der Aufrufer ist ein Scheduler.
     */
    void forceReconnect();
}
```

- [ ] **Step 2: Verbindungsaufbau aus `start()` herauslösen**

**Wichtig:** Ein reines `disconnect()` genügt **nicht**. HiveMQs `automaticReconnect`
greift nur bei unerwarteten Verbindungsabbrüchen — ein vom Programm angestoßenes
`disconnect()` gilt als gewollt und schaltet den Auto-Reconnect ab. Ein `forceReconnect`,
das nur trennt, würde die Anbindung also **endgültig** killen statt sie zu heilen.
Deshalb muss nach dem Trennen explizit neu verbunden werden.

In `start()` den Verbindungsteil durch einen Aufruf ersetzen. Aus:

```java
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
```

wird:

```java
        connect();
```

Und die neue Methode:

```java
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
```

- [ ] **Step 3: `ZigbeeConnectionControl` implementieren**

Klassendeklaration ändern:

```java
public class ZigbeeMqttConfig implements ZigbeeConnectionControl {
```

Methode ergänzen:

```java
    /**
     * Erzwungener Neuaufbau: trennen UND danach explizit neu verbinden.
     * <p>
     * Das explizite connect() ist zwingend — HiveMQs automaticReconnect greift nur bei
     * unerwarteten Abbruechen, ein selbst angestossenes disconnect() gilt als gewollt
     * und schaltet ihn ab. Ein forceReconnect, das nur trennt, wuerde die Anbindung
     * endgueltig killen statt sie zu heilen.
     * <p>
     * Der anschliessende ConnectedListener loest ein frisches Subscribe aus — genau
     * das heilt den Fall "verbunden, aber ohne Subscription".
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
```

Import ergänzen:

```java
import com.household.manager.zigbee.service.ZigbeeConnectionControl;
```

- [ ] **Step 4: Kompilieren**

```bash
cd backend && mvn -q compile
```

Erwartung: kein Fehler.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/zigbee/service/ZigbeeConnectionControl.java backend/src/main/java/com/household/manager/zigbee/config/ZigbeeMqttConfig.java
git commit -m "feat(zigbee): ZigbeeConnectionControl fuer erzwungenen Reconnect"
```

---

## Task 10: `ZigbeeAvailabilityWatchdog`

**Wichtig — Attribute nicht verlieren:** `EntityStateWriter.upsert` überschreibt die
Attribute bei **jedem** Update. Ein `unavailable` ohne Attribute würde `unit`,
`deviceClass` und `batteryPercent` aller Zigbee-Entitäten löschen. Die in der Datenbank
liegenden Attribute (JSON-String) müssen deshalb zurück in eine Map gelesen und
mitgegeben werden.

**Files:**
- Create: `backend/src/main/java/com/household/manager/zigbee/service/ZigbeeAvailabilityWatchdog.java`
- Test: `backend/src/test/java/com/household/manager/zigbee/service/ZigbeeAvailabilityWatchdogTest.java`

- [ ] **Step 1: Fehlschlagenden Test schreiben**

`backend/src/test/java/com/household/manager/zigbee/service/ZigbeeAvailabilityWatchdogTest.java`:

```java
package com.household.manager.zigbee.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.EntityState;
import com.household.manager.zigbee.config.ZigbeeWatchdogProperties;
import com.household.manager.zigbee.model.ZigbeeStreamStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ZigbeeAvailabilityWatchdogTest {

    @Mock
    private ZigbeeStreamMonitor monitor;
    @Mock
    private EntityStateService entityStateService;
    @Mock
    private ZigbeeConnectionControl connectionControl;

    private final ZigbeeWatchdogProperties properties = new ZigbeeWatchdogProperties();
    private ZigbeeAvailabilityWatchdog watchdog;

    @BeforeEach
    void setUp() {
        watchdog = new ZigbeeAvailabilityWatchdog(
                monitor, properties, entityStateService, connectionControl, new ObjectMapper());
        when(entityStateService.find(isNull(), eq(EntitySource.ZIGBEE)))
                .thenReturn(List.of(sensorEntity(), buttonEntity()));
    }

    private EntityState sensorEntity() {
        return EntityState.builder()
                .entityId("sensor.zigbee_temperatur_buero_temperature")
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.ZIGBEE)
                .sourceRef("Temperatur Buero")
                .friendlyName("Temperatur Buero Temperatur")
                .state("21.3")
                .attributes("{\"unit\":\"°C\",\"deviceClass\":\"temperature\"}")
                .build();
    }

    private EntityState buttonEntity() {
        return EntityState.builder()
                .entityId("event.zigbee_button_buero_action")
                .domain(EntityDomain.EVENT)
                .source(EntitySource.ZIGBEE)
                .sourceRef("Button Buero")
                .friendlyName("Button Buero Taster")
                .state("single")
                .build();
    }

    private void silentFor(long minutes) {
        when(monitor.status()).thenReturn(new ZigbeeStreamStatus(
                ZigbeeStreamStatus.Health.STILL, Instant.parse("2026-07-28T12:00:00Z"),
                minutes, "online", List.of()));
    }

    private void healthy() {
        when(monitor.status()).thenReturn(new ZigbeeStreamStatus(
                ZigbeeStreamStatus.Health.OK, Instant.parse("2026-07-28T12:00:00Z"),
                0, "online", List.of()));
    }

    @Test
    void tutNichtsSolangeAllesLaeuft() {
        healthy();

        watchdog.check();

        verifyNoInteractions(connectionControl);
        verify(entityStateService, never()).reportState(any());
    }

    @Test
    void versuchtBeiStilleZuerstDieSelbstheilung() {
        silentFor(16);

        watchdog.check();

        verify(connectionControl).forceReconnect();
        verify(entityStateService, never()).reportState(any());
        verify(entityStateService, never()).reportEvent(any());
    }

    @Test
    void meldetKeinenAlarmWennDieSelbstheilungGreift() {
        silentFor(16);
        watchdog.check();
        healthy();

        watchdog.check();

        verify(entityStateService, never()).reportEvent(any());
    }

    @Test
    void meldetAusfallErstNachDerGnadenfrist() {
        silentFor(16);
        watchdog.check();
        silentFor(16 + properties.getRecoverGraceMinutes() + 1);

        watchdog.check();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportEvent(captor.capture());
        assertThat(captor.getValue().entityId()).isEqualTo("event.zigbee_bridge_status");
        assertThat(captor.getValue().state()).isEqualTo("failed");
    }

    @Test
    void setztNurZustandsEntitaetenAufUnavailableUndBehaeltDieAttribute() {
        silentFor(16);
        watchdog.check();
        silentFor(99);

        watchdog.check();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, times(1)).reportState(captor.capture());
        EntityStateUpdate update = captor.getValue();
        assertThat(update.entityId()).isEqualTo("sensor.zigbee_temperatur_buero_temperature");
        assertThat(update.state()).isEqualTo("unavailable");
        assertThat(update.attributes()).containsEntry("deviceClass", "temperature");
    }

    @Test
    void meldetDenAusfallNurEinmal() {
        silentFor(16);
        watchdog.check();
        silentFor(99);
        watchdog.check();
        watchdog.check();
        watchdog.check();

        verify(entityStateService, times(1)).reportEvent(any());
    }

    @Test
    void gibtEntwarnungWennDieAnbindungZurueckkommt() {
        silentFor(16);
        watchdog.check();
        silentFor(99);
        watchdog.check();
        reset(entityStateService);
        healthy();

        watchdog.check();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportEvent(captor.capture());
        assertThat(captor.getValue().state()).isEqualTo("recovered");
    }

    @Test
    void wirftNiemals() {
        when(monitor.status()).thenThrow(new IllegalStateException("boom"));

        watchdog.check();
    }
}
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

```bash
cd backend && mvn test -Dtest=ZigbeeAvailabilityWatchdogTest
```

Erwartung: Kompilierfehler — `ZigbeeAvailabilityWatchdog` existiert nicht.

- [ ] **Step 3: Watchdog implementieren**

`backend/src/main/java/com/household/manager/zigbee/service/ZigbeeAvailabilityWatchdog.java`:

```java
package com.household.manager.zigbee.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.EntityState;
import com.household.manager.zigbee.config.ZigbeeWatchdogProperties;
import com.household.manager.zigbee.model.ZigbeeStreamStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Erkennt den Ausfall der Zigbee-Anbindung, versucht ihn zuerst selbst zu heilen und
 * meldet ihn erst danach.
 * <p>
 * Der Zwischenzustand RECOVERING existiert, damit ein kurzer Aussetzer nicht nachts
 * das Handy weckt: erst wird ein Reconnect erzwungen, und nur wenn danach innerhalb
 * der Gnadenfrist immer noch nichts ankommt, gilt die Anbindung als ausgefallen.
 * <p>
 * Gemeldet wird ueber die EVENT-Entitaet {@code event.zigbee_bridge_status}; die
 * eigentliche Telegram-Warnung ist ein Flow. Das haelt Wortlaut und Empfaenger ohne
 * Redeploy aenderbar — hat aber den offengelegten Preis, dass die Ausfallmeldung
 * selbst an der Flow-Engine haengt. Fuer einen Zigbee-Ausfall traegt das, weil das
 * Backend dabei laeuft; fuer einen Backend-Ausfall waere dieser Weg untauglich.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ZigbeeAvailabilityWatchdog {

    private static final String STATE_UNAVAILABLE = "unavailable";
    private static final String EVENT_SOURCE_REF = "bridge";

    /** Die Melde-Entitaet selbst darf nie unavailable werden. */
    private static final String STATUS_ENTITY_ID =
            EntityIds.build(EntityDomain.EVENT, EntitySource.ZIGBEE, EVENT_SOURCE_REF, "status");

    private enum Phase { HEALTHY, RECOVERING, FAILED }

    private final ZigbeeStreamMonitor monitor;
    private final ZigbeeWatchdogProperties properties;
    private final EntityStateService entityStateService;
    private final ZigbeeConnectionControl connectionControl;
    private final ObjectMapper objectMapper;

    private Phase phase = Phase.HEALTHY;
    private long silentAtRecoveryStart;

    /** Wirft nie — ein Fehler hier darf den Scheduler nicht stilllegen. */
    @Scheduled(fixedDelayString = "60000", initialDelayString = "60000")
    public void check() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            evaluate();
        } catch (Exception ex) {
            log.warn("Zigbee-Watchdog fehlgeschlagen: {}", ex.getMessage(), ex);
        }
    }

    private void evaluate() {
        ZigbeeStreamStatus status = monitor.status();

        if (status.healthy()) {
            if (phase == Phase.FAILED) {
                log.info("Zigbee-Anbindung ist zurueck");
                reportStatusEvent("recovered", status);
            } else if (phase == Phase.RECOVERING) {
                log.info("Zigbee-Anbindung hat sich nach dem erzwungenen Reconnect selbst erholt");
            }
            phase = Phase.HEALTHY;
            return;
        }

        switch (phase) {
            case HEALTHY -> {
                log.warn("Zigbee still seit {} Minuten (Zustand {}) — erzwinge Reconnect",
                        status.silentMinutes(), status.health());
                silentAtRecoveryStart = status.silentMinutes();
                phase = Phase.RECOVERING;
                connectionControl.forceReconnect();
            }
            case RECOVERING -> {
                long waited = status.silentMinutes() - silentAtRecoveryStart;
                if (waited >= properties.recoverGrace().toMinutes()) {
                    log.error("Zigbee-Anbindung ausgefallen: seit {} Minuten keine Nachricht (Zustand {})",
                            status.silentMinutes(), status.health());
                    phase = Phase.FAILED;
                    markEntitiesUnavailable();
                    reportStatusEvent("failed", status);
                }
            }
            case FAILED -> {
                // Bewusst still: einmal melden, nicht minuetlich wiederholen. Sonst wird
                // die Warnung stummgeschaltet und hilft beim naechsten Mal nicht mehr.
            }
        }
    }

    /**
     * EVENT-Entitaeten werden ausgenommen: ein Ereignis hat keinen fortdauernden
     * Zustand, "unavailable" waere dort bedeutungslos.
     */
    private void markEntitiesUnavailable() {
        for (EntityState entity : entityStateService.find(null, EntitySource.ZIGBEE)) {
            if (entity.getDomain() == EntityDomain.EVENT) {
                continue;
            }
            if (STATE_UNAVAILABLE.equals(entity.getState())) {
                continue;
            }
            entityStateService.reportState(EntityStateUpdate.builder()
                    .entityId(entity.getEntityId())
                    .domain(entity.getDomain())
                    .source(EntitySource.ZIGBEE)
                    .sourceRef(entity.getSourceRef())
                    .friendlyName(entity.getFriendlyName())
                    .state(STATE_UNAVAILABLE)
                    .attributes(readAttributes(entity))
                    .build());
        }
    }

    /**
     * EntityStateWriter.upsert ueberschreibt die Attribute bei JEDEM Update. Ohne das
     * Zurueckreichen der gespeicherten Attribute wuerden unit, deviceClass und
     * batteryPercent aller Zigbee-Entitaeten beim Ausfall geloescht.
     */
    private Map<String, Object> readAttributes(EntityState entity) {
        String raw = entity.getAttributes();
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception ex) {
            log.warn("Attribute von {} nicht lesbar, werden beim Ausfall verworfen: {}",
                    entity.getEntityId(), ex.getMessage());
            return Map.of();
        }
    }

    private void reportStatusEvent(String state, ZigbeeStreamStatus status) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("reason", status.health() == ZigbeeStreamStatus.Health.BRIDGE_OFFLINE
                ? "bridge_offline" : "stream_silent");
        attributes.put("silentMinutes", status.silentMinutes());
        attributes.put("offlineDevices", status.offlineDevices());
        if (status.bridgeState() != null) {
            attributes.put("bridgeState", status.bridgeState());
        }

        entityStateService.reportEvent(EntityStateUpdate.builder()
                .entityId(STATUS_ENTITY_ID)
                .domain(EntityDomain.EVENT)
                .source(EntitySource.ZIGBEE)
                .sourceRef(EVENT_SOURCE_REF)
                .friendlyName("Zigbee-Anbindung")
                .state(state)
                .attributes(attributes)
                .build());
    }
}
```

- [ ] **Step 4: Test laufen lassen**

```bash
cd backend && mvn test -Dtest=ZigbeeAvailabilityWatchdogTest
```

Erwartung: PASS, alle acht Tests.

- [ ] **Step 5: Prüfen, dass `@EnableScheduling` aktiv ist**

```bash
cd backend && grep -rn "EnableScheduling" src/main/java
```

Erwartung: mindestens ein Treffer. Fehlt die Annotation, läuft der Watchdog nie —
dann `@EnableScheduling` an der Anwendungsklasse ergänzen.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/zigbee/service/ZigbeeAvailabilityWatchdog.java backend/src/test/java/com/household/manager/zigbee/service/ZigbeeAvailabilityWatchdogTest.java
git commit -m "feat(zigbee): Watchdog mit Selbstheilung, unavailable und Alarm-Event"
```

---

## Task 11: Health-Endpunkt

**Files:**
- Create: `backend/src/main/java/com/household/manager/zigbee/dto/ZigbeeHealthResponse.java`
- Modify: `backend/src/main/java/com/household/manager/zigbee/controller/ZigbeeController.java`

- [ ] **Step 1: DTO anlegen**

```java
package com.household.manager.zigbee.dto;

import lombok.Builder;

import java.time.Instant;
import java.util.List;

/**
 * Zustand der Zigbee-Anbindung fuer das Frontend.
 */
@Builder
public record ZigbeeHealthResponse(
        String health,
        boolean healthy,
        Instant lastMessageAt,
        long silentMinutes,
        String bridgeState,
        List<String> offlineDevices) {
}
```

- [ ] **Step 2: Endpunkt ergänzen**

In `ZigbeeController` Feld ergänzen:

```java
    private final ZigbeeStreamMonitor streamMonitor;
```

Methode ergänzen:

```java
    @GetMapping("/health")
    public ResponseEntity<ZigbeeHealthResponse> getHealth() {
        ZigbeeStreamStatus status = streamMonitor.status();
        return ResponseEntity.ok(ZigbeeHealthResponse.builder()
                .health(status.health().name())
                .healthy(status.healthy())
                .lastMessageAt(status.lastMessageAt())
                .silentMinutes(status.silentMinutes())
                .bridgeState(status.bridgeState())
                .offlineDevices(status.offlineDevices())
                .build());
    }
```

Imports ergänzen:

```java
import com.household.manager.zigbee.dto.ZigbeeHealthResponse;
import com.household.manager.zigbee.model.ZigbeeStreamStatus;
import com.household.manager.zigbee.service.ZigbeeStreamMonitor;
```

- [ ] **Step 3: Security-Regel prüfen**

```bash
cd backend && grep -n "v1/\*\*\|zigbee" src/main/java/com/household/manager/config/SecurityConfig.java
```

Erwartung: `GET /v1/**` ist für KIOSK freigegeben; damit ist `/v1/zigbee/health` ohne
eigene Regel lesbar. Der Endpunkt enthält keine Geheimnisse — das ist gewollt, damit
das Wandtablet den Ausfall anzeigen kann. **Keine** neue Regel ergänzen.

- [ ] **Step 4: Kompilieren**

```bash
cd backend && mvn -q compile
```

Erwartung: kein Fehler.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/zigbee/dto/ZigbeeHealthResponse.java backend/src/main/java/com/household/manager/zigbee/controller/ZigbeeController.java
git commit -m "feat(zigbee): Health-Endpunkt fuer den Zustand der Anbindung"
```

---

## Task 12: Ausfall-Banner im Frontend

**Files:**
- Modify: `frontend/src/app/pages/zigbee/zigbee.component.ts`
- Modify: `frontend/src/app/pages/zigbee/zigbee.component.html`
- Modify: `frontend/src/app/pages/zigbee/zigbee.component.scss`
- Modify: `frontend/src/app/services/zigbee.service.ts`

- [ ] **Step 1: Model ergänzen**

Ans Ende von `frontend/src/app/models/zigbee.model.ts` anhängen:

```typescript
/** Zustand der Zigbee-Anbindung (GET /api/v1/zigbee/health). */
export interface ZigbeeHealth {
  health: 'OK' | 'STILL' | 'BRIDGE_OFFLINE';
  healthy: boolean;
  lastMessageAt: string;
  silentMinutes: number;
  bridgeState: string | null;
  offlineDevices: string[];
}
```

- [ ] **Step 2: Service-Methode ergänzen**

In `frontend/src/app/services/zigbee.service.ts` den Import erweitern:

```typescript
import { ZigbeeDevice, ZigbeeHealth, ZigbeeMeasurement, ZigbeeMeasurementType } from '../models/zigbee.model';
```

und nach `getMeasurements(...)` ergänzen:

```typescript
  getHealth(): Observable<ZigbeeHealth> {
    return this.http.get<ZigbeeHealth>(`${this.baseUrl}/health`).pipe(
      catchError(this.handleError)
    );
  }
```

- [ ] **Step 3: Komponente erweitern**

In `frontend/src/app/pages/zigbee/zigbee.component.ts` den Model-Import um
`ZigbeeHealth` erweitern:

```typescript
import {
  ZigbeeDevice,
  ZigbeeHealth,
  ZigbeeLiveEvent,
  ZigbeeMeasurementType
} from '../../models/zigbee.model';
```

Feld neben `devices` ergänzen:

```typescript
  health: ZigbeeHealth | null = null;
```

Abo-Feld neben `devicesSub` ergänzen:

```typescript
  private healthSub?: Subscription;
```

Methode ergänzen:

```typescript
  /** Fehler bewusst still: ein nicht erreichbarer Health-Endpunkt darf die Seite nicht stören. */
  private loadHealth(): void {
    this.healthSub = this.zigbeeService.getHealth().subscribe({
      next: (health) => (this.health = health),
      error: () => (this.health = null)
    });
  }
```

In `ngOnInit()` als erste Zeile `this.loadHealth();` ergänzen, und in `ngOnDestroy()`
neben den anderen Abmeldungen:

```typescript
    this.healthSub?.unsubscribe();
```

- [ ] **Step 4: Banner ins Template**

In `frontend/src/app/pages/zigbee/zigbee.component.html` direkt nach
`<h1>Zigbee-Sensoren</h1>` einfügen (die Datei nutzt die neue `@if`-Syntax):

```html
  @if (health && !health.healthy) {
    <div class="outage-banner">
      <strong>Zigbee-Anbindung gestört.</strong>
      @if (health.health === 'BRIDGE_OFFLINE') {
        <span>zigbee2mqtt meldet sich als offline.</span>
      } @else {
        <span>Seit {{ health.silentMinutes }} Minuten keine Nachricht empfangen.</span>
      }
      <span>Die angezeigten Werte sind nicht aktuell.</span>
    </div>
  }
```

- [ ] **Step 5: Styling ergänzen**

In `frontend/src/app/pages/zigbee/zigbee.component.scss` **innerhalb** von
`.zigbee-page { ... }` einfügen (die Datei verschachtelt alles darunter):

```scss
  .outage-banner {
    display: flex;
    flex-wrap: wrap;
    gap: 0.5rem;
    margin-bottom: 1.5rem;
    padding: 0.75rem 1rem;
    border: 1px solid #d9534f;
    border-radius: 8px;
    background: rgba(217, 83, 79, 0.12);
    color: #d9534f;
  }
```

- [ ] **Step 6: Build prüfen**

```bash
cd frontend && npx ng build --configuration production
```

Erwartung: Build erfolgreich.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/models/zigbee.model.ts frontend/src/app/services/zigbee.service.ts frontend/src/app/pages/zigbee
git commit -m "feat(zigbee): Ausfall-Banner auf der Zigbee-Seite"
```

---

## Task 13: Hinweis im Dashboard-Footer

**Files:**
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts`
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.html`
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.scss`

**Achtung zwei Fallstricke:**
1. Das Markup muss **direkt** in `dashboard.component.html` stehen — die `lumina`-Styles
   sind dort gekapselt und griffen in einer Kind-Komponente lautlos nicht.
2. `dashboard.component.html` nutzt die **alte** Syntax (`*ngIf`/`*ngFor`), nicht `@if`.
   Diesem Stil folgen, nicht mischen.

- [ ] **Step 1: Komponente erweitern**

In `dashboard.component.ts` ergänzen (Import-Pfade an die bestehenden anpassen):

```typescript
  zigbeeHealth: ZigbeeHealth | null = null;
```

und in `ngOnInit()`:

```typescript
    this.zigbeeService.getHealth().subscribe({
      next: (health) => (this.zigbeeHealth = health),
      error: () => (this.zigbeeHealth = null)
    });
```

`ZigbeeService` per `inject(ZigbeeService)` einbinden, wie die anderen Services in
dieser Komponente.

- [ ] **Step 2: Karte in den Footer**

In `dashboard.component.html` direkt **vor** dem schließenden `</footer>` (aktuell
Zeile 342) einfügen:

```html
    <div class="lumina-card lumina__zigbee-outage"
         *ngIf="zigbeeHealth && !zigbeeHealth.healthy">
      <div class="lumina__secured-icon">
        <span class="material-symbols-outlined">sensors_off</span>
      </div>
      <div>
        <p class="lumina__secured-title">Zigbee gestört</p>
        <p class="lumina__secured-detail">
          {{ zigbeeHealth.health === 'BRIDGE_OFFLINE'
              ? 'zigbee2mqtt ist offline'
              : 'Seit ' + zigbeeHealth.silentMinutes + ' Min. keine Daten' }}
        </p>
      </div>
    </div>
```

- [ ] **Step 3: Styling ergänzen**

In `dashboard.component.scss` bei den anderen `lumina__`-Klassen:

```scss
.lumina__zigbee-outage {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  border-color: rgba(217, 83, 79, 0.5);

  .lumina__secured-icon {
    color: #d9534f;
  }
}
```

- [ ] **Step 4: Build prüfen**

```bash
cd frontend && npx ng build --configuration production
```

Erwartung: Build erfolgreich. Schlägt er an einer `lumina__secured-*`-Klasse fehl oder
sieht die Karte falsch aus, die tatsächlich vorhandenen Klassennamen aus der
Nuki-Karte (Zeile 261 ff.) übernehmen.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/pages/dashboard
git commit -m "feat(dashboard): Hinweis bei gestoerter Zigbee-Anbindung"
```

---

## Task 14: Dokumentation

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Abschnitt ergänzen**

Im Abschnitt „Smart Device Integrations" nach dem Zigbee-Teil (bzw. als neuer
Unterabschnitt, falls es keinen gibt) ergänzen:

```markdown
### Zigbee-Ausfallerkennung
- `ZigbeeStreamMonitor` ist die **einzige** Definition von „die Anbindung lebt" — Watchdog, Health-Endpunkt und Meldungstext fragen dieselbe Klasse, damit sie nicht auseinanderlaufen (Muster wie `TractiveHomeResolver`). Rein im Speicher; der Zustand überlebt einen Neustart bewusst **nicht**, sonst löste jeder Deploy einen Fehlalarm aus
- `ZigbeeAvailabilityWatchdog` (minütlich) läuft über drei Zustände: bei Stille ≥ `stale-after-minutes` erst ein erzwungener Reconnect (**ohne** Meldung), und erst wenn nach `recover-grace-minutes` immer noch nichts ankommt, werden die Entitäten `unavailable` und `event.zigbee_bridge_status` feuert mit State `failed`. **Einmal** melden, nicht minütlich — sonst wird die Warnung stummgeschaltet
- Die Telegram-Warnung ist ein **Flow** auf diesem Event, kein Java-Code. **Preis:** die Ausfallmeldung hängt damit selbst an der Flow-Engine. Für Zigbee trägt das (das Backend läuft ja); für einen künftigen *Backend*-Ausfall wäre dieser Weg untauglich
- **`unavailable` darf die Attribute nicht löschen:** `EntityStateWriter.upsert` überschreibt sie bei jedem Update, deshalb liest der Watchdog die gespeicherten Attribute aus der DB zurück und gibt sie mit. Ohne das verlören alle Zigbee-Entitäten beim Ausfall `unit`, `deviceClass` und `batteryPercent`
- EVENT-Entitäten (Taster) werden vom `unavailable` ausgenommen — ein Ereignis hat keinen fortdauernden Zustand
- ~~**Die Flow-Engine feuert nicht mehr bei Übergängen von/nach `unavailable`**~~ **VERALTET — diese Regel wurde während der Umsetzung umgedreht.** Gültig ist: nur der Übergang **nach** `unavailable` wird unterdrückt, der Übergang **heraus** feuert normal. Grund: bei beidseitiger Unterdrückung bliebe Flow #4 („Feuer-Verdacht", `Temperatur > 40`) nach jedem Ausfall entwaffnet, bis die Temperatur unter 40 fällt und wieder steigt — ein während des Ausfalls ausgebrochenes Feuer würde nie gemeldet. Maßgeblich ist die revidierte Spec (Punkt 5) und der Abschnitt in `CLAUDE.md`; dieser Plan-Text ist nur noch Historie
- **Bekannte Semantik-Falle, bewusst nicht geändert:** `StateComparator` vergleicht nicht-numerische Werte als String, deshalb ist `unavailable != on` **wahr**. Eine `entity-condition` „Tür ist nicht offen" ist bei einem Ausfall also erfüllt. Numerische Operatoren sind davon nicht betroffen (`unavailable` parst nicht als Zahl → immer `false`)
- MQTT-Härtung: fehlgeschlagenes Subscribe wird mit Backoff wiederholt (vorher: einmal geloggt, nie erneut versucht — lautloser Dauerausfall); die Verarbeitung läuft auf **genau einem** eigenen Thread statt auf dem Netty-Event-Loop, damit eine hängende DB nicht Keepalive und Reconnect blockiert — ein Pool wäre falsch, er könnte Nachrichten desselben Geräts umsortieren und bei einem Türkontakt „offen"/„zu" vertauschen
- `zigbee2mqtt/bridge/state` und `<gerät>/availability` werden ausgewertet (vorher verworfen) und unterscheiden in der Meldung, *wer* weg ist
- Konfiguration: `zigbee.watchdog.enabled`, `stale-after-minutes` (15), `recover-grace-minutes` (5). Die 15 Minuten sind aus PROD-Daten abgeleitet, aber nur über ein Zeitfenster verifiziert — nach einigen Tagen Betrieb nachziehen
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: Zigbee-Ausfallerkennung dokumentieren"
```

---

## Task 15: Telegram-Flow anlegen

Erst **nach** dem Deployment des Backends ausführen — die Entität
`event.zigbee_bridge_status` existiert vorher nicht, und `flow_deploy` würde sie nicht
auflösen können.

**Files:** keine (Flow wird über den flow-mcp-server in PROD angelegt)

- [ ] **Step 1: Node-Katalog und Entität prüfen**

`flow_node_types` aufrufen sowie `flow_list_entities` mit `domain: event`.
Erwartung: `event.zigbee_bridge_status` ist gelistet.

- [ ] **Step 2: Flow anlegen**

`flow_create` mit Name „Zigbee-Anbindung ausgefallen" und dieser Definition:

```json
{
  "nodes": [
    {
      "id": "trigger",
      "type": "entity-event-trigger",
      "name": "Zigbee-Status",
      "config": { "entityId": "event.zigbee_bridge_status", "action": "failed" }
    },
    {
      "id": "melden",
      "type": "telegram-send",
      "name": "Warnung senden",
      "config": {
        "text": "Zigbee-Anbindung ausgefallen: seit {{silentMinutes}} Minuten keine Nachricht ({{reason}}). Tür-, Fenster- und Temperatur-Flows sind bis auf Weiteres wirkungslos."
      }
    }
  ],
  "wires": [ { "from": { "node": "trigger", "port": 0 }, "to": { "node": "melden" } } ]
}
```

Die Platzhalter-Syntax vorher gegen `docs/flows/flow-import-format.md` prüfen und bei
Abweichung anpassen.

- [ ] **Step 3: Deployen und aktivieren**

`flow_deploy` mit der neuen Flow-ID, danach `flow_set_enabled` auf `true`.
Erwartung: `ValidationResult` ohne Fehler.

- [ ] **Step 4: Testen**

`flow_inject` auf den Trigger-Node und anschließend `flow_debug_entries` prüfen.
Erwartung: eine Telegram-Nachricht kommt an.

- [ ] **Step 5: Zweiten Flow für die Entwarnung anlegen**

Gleiches Vorgehen mit `"action": "recovered"` und dem Text
„Zigbee-Anbindung ist wieder da."

---

## Abschluss

- [ ] **Alle berührten Testklassen laufen lassen**

```bash
cd backend && mvn test -Dtest='Zigbee*Test,EntityStateTriggerHandlerTest,ZigbeeEntityMapperTest'
```

Erwartung: PASS. `HouseholdManagerApplicationTests` und `HealthControllerTest` sind
hier bewusst nicht dabei (siehe Vorbereitung).

- [ ] **Frontend-Build**

```bash
cd frontend && npx ng build --configuration production
```

- [ ] **Branch abschließen**

Über die `superpowers:finishing-a-development-branch`-Skill entscheiden, wie der Branch
integriert wird.
