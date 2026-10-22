# Systemprotokoll Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Admin-Seite „Systemprotokoll“ mit Job-Statustafel und Zeitleiste über Flow-Läufe (inkl. gestoppt/Rate-Limit/Fehler/verpasst/abgebrochen), Job-Störungen, Entity-Ereignisse und Backend-WARN/ERROR.

**Architecture:** Neues Backend-Paket `com.household.manager.systemlog` mit einem asynchronen, nie werfenden `SystemLogWriter` als einzigem Schreibpfad in `system_log_entry`. Die Erfassung dockt an fünf Stellen an:
- die Flow-Engine (Lauf-Tracing per ThreadLocal je Lauf, `_runId` in der `FlowMessage`),
- einen AOP-Aspekt um alle `@Scheduled`-Methoden plus ThreadLocal-Meldungen aus den Pollern,
- einen Entity-Event-Listener,
- einen Logback-Appender,
- einen Startdienst für verpasste Cron-/Sonnen-Termine und abgebrochene Läufe.

Frontend: eine Admin-Seite, ein Service.

**Tech Stack:** Spring Boot 3.4 / Java 21 / Liquibase / MariaDB, `spring-boot-starter-aop` (neu), Logback (Spring-Boot-Default), Angular 19 standalone.

**Spec:** `docs/superpowers/specs/2026-10-22-systemprotokoll-design.md`

## Global Constraints

- Aufbewahrung `system_log_entry`: **30 Tage**, Retention-Job nachts **03:30** als `@Transactional @Modifying @Query`-Bulk-Delete.
- Jeder Schreibpfad **wirft nie** und blockiert keinen Aufrufer-Thread (Flow-Executor, Scheduler, MQTT-Thread).
- Verpasste Termine: höchstens **7 Tage** zurück, höchstens **50 Einträge je Flow**, darüber ein Sammeleintrag. Kein Nachfeuern.
- Log-Zusammenfassung: gleicher Logger + gleiche formatierte Nachricht binnen **10 Minuten** ⇒ `repeat_count` +1 statt neuer Zeile.
- `message` gekappt auf **2000** Zeichen, Stacktrace höchstens **20** Zeilen in `details`.
- Status-Enum genau: `OK, STOPPED, RATE_LIMITED, ERROR, MISSED, WAITING, ABORTED, INFO, WARN, RECOVERED`. Kind-Enum: `FLOW_RUN, JOB, ENTITY, LOG`. Job-Zustand: `OK, FAILING, INACTIVE`.
- „Nur Probleme“ = `ERROR, STOPPED, RATE_LIMITED, MISSED, ABORTED, WARN`.
- Prioritätsregel für den Laufstatus: `ERROR` > `STOPPED`/`RATE_LIMITED` > `WAITING` > `OK`. Der Abbruch am Hop-Limit ist `ERROR`.
- Entity-Filter: Wechsel verwerfen, wenn **alter und neuer Zustand beide als Zahl parsen**. Alles andere und jedes `EntityEventFired` aufnehmen.
- API nur **ADMIN**: methodenloser Matcher `"/v1/admin/system-log", "/v1/admin/system-log/**"` vor der generischen `GET /v1/**`-Regel.
- Jede Liquibase-Änderung ein eigenes Changeset (MariaDB-DDL-Autocommit). Die Changesets laufen nie gegen die echte DB, deshalb vor dem Deploy ein Backup.
- JPA-Repositories liegen in `com.household.manager.repository`, Entities in `com.household.manager.model.entity` (`JpaConfig` scannt nur dort).
- Backend-Build mit JDK 21 (`JAVA_HOME` auf jdk-21.0.10 setzen). `contextLoads` ist lokal rot (keine DB) und kein Regressionssignal.
- Arbeitszweig: `feature/systemprotokoll`.

## Review Focus

1. **Ein flatternder Poller oder eine WARN-Schleife flutet die DB.** Erwartet: die Warteschlange ist begrenzt, gleichlautende WARN-Zeilen werden zusammengefasst, und eine volle Warteschlange verwirft, statt zu blockieren. Test in Task 1 (volle Queue blockiert nicht) und Task 8 (10-Minuten-Zusammenfassung).
2. **Der Log-Appender protokolliert seine eigenen DB-Fehler** (DB weg ⇒ Hibernate-WARN ⇒ neuer Eintrag ⇒ …). Erwartet: kein Eintrag aus dem Writer-Thread und aus dem Paket `systemlog`. Test in Task 8.
3. **Re-Deploy eines Flows mit offenem Delay.** Erwartet: genau ein `ABORTED` je offenem Lauf („durch Re-Deploy abgebrochen“), nicht null und nicht zwei. Das `forSeconds`-Cleanup darf den Pending-Eintrag nicht vorher still entfernen. Test in Task 4.
4. **Poller ohne Konfiguration** (Tractive ohne Token, EnBW ohne Key, `*.enabled=false`). Erwartet: grau `INACTIVE`, kein Protokolleintrag, und keine Minute später wieder `OK`. Test in Task 6.
5. **Erster Start nach dem Deploy (kein Lebenszeichen) und Neustart nach 3 Wochen Stillstand.** Erwartet: beim ersten Start kein einziger `MISSED`-Eintrag, nach langem Stillstand ≤ 50 Einträge je Flow plus Sammeleintrag, nur 7 Tage zurück. Test in Task 5.

---

### Task 1: Schema, Entities, Writer, Retention

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20261022-0071-create-system-log-entry.xml`, `20261022-0072-create-system-log-entry-indexes.xml`, `20261022-0073-create-system-job-status.xml`, `20261022-0074-create-system-flow-pending-run.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml` (Block „Systemprotokoll“ am Ende)
- Modify: `backend/pom.xml` (`spring-boot-starter-aop`, wird ab Task 6 gebraucht, hier schon ergänzen)
- Create: `model/entity/SystemLogEntry.java`, `model/entity/SystemJobStatus.java`, `model/entity/SystemFlowPendingRun.java`
- Create: `repository/SystemLogEntryRepository.java`, `repository/SystemJobStatusRepository.java`, `repository/SystemFlowPendingRunRepository.java`
- Create: `systemlog/SystemLogKind.java`, `systemlog/SystemLogStatus.java`, `systemlog/SystemLogEvent.java`, `systemlog/SystemLogWriter.java`, `systemlog/SystemLogRetentionJob.java`
- Test: `backend/src/test/java/com/household/manager/systemlog/SystemLogWriterTest.java`, `SystemLogRetentionJobTest.java`

**Interfaces:**
- Produces:
  - `enum SystemLogKind { FLOW_RUN, JOB, ENTITY, LOG }`
  - `enum SystemLogStatus { OK, STOPPED, RATE_LIMITED, ERROR, MISSED, WAITING, ABORTED, INFO, WARN, RECOVERED }`
  - `record SystemLogEvent(LocalDateTime occurredAt, SystemLogKind kind, SystemLogStatus status, String source, String title, String message, String detailsJson, Long flowId, String runId)` mit `static Builder builder()`.
  - `SystemLogWriter.write(SystemLogEvent event)`: nicht blockierend, wirft nie.
  - `SystemLogWriter.writeOrIncrement(SystemLogEvent event, Duration window)`: erhöht `repeat_count` der jüngsten Zeile mit gleichem `kind`+`source`+`message` innerhalb des Fensters, sonst neue Zeile. Für Task 8.
  - `SystemLogWriter.isWriterThread()`: `true` auf dem eigenen Schreib-Thread (für Task 8).
  - `SystemLogEntryRepository`: `Page<SystemLogEntry> search(...)` (Task 9), `findTopByKindAndSourceAndMessageAndOccurredAtAfterOrderByOccurredAtDesc(...)`, `@Transactional @Modifying @Query("delete from SystemLogEntry e where e.occurredAt < :cutoff") int deleteOlderThan(LocalDateTime cutoff)`.
  - `SystemFlowPendingRunRepository`: `findByFlowId(long)`, `deleteByRunId(String)` und `deleteByFlowId(long)` (beide als `@Transactional @Modifying @Query`).
- Tabellen:
  - `system_log_entry`: `id` BIGINT AI PK, `occurred_at` DATETIME(3) NOT NULL, `kind` VARCHAR(20) NOT NULL, `status` VARCHAR(20) NOT NULL, `source` VARCHAR(200) NOT NULL, `title` VARCHAR(200) NOT NULL, `message` VARCHAR(2000), `details` LONGTEXT, `flow_id` BIGINT, `run_id` VARCHAR(36), `repeat_count` INT NOT NULL DEFAULT 1. Indizes: `idx_sle_occurred_at(occurred_at)`, `idx_sle_kind_occurred(kind, occurred_at)`, `idx_sle_flow(flow_id)`, `idx_sle_run(run_id)`.
  - `system_job_status`: `job_key` VARCHAR(200) PK, `state` VARCHAR(20) NOT NULL, `last_run_at`, `last_success_at`, `last_failure_at`, `failing_since` (alle DATETIME), `last_message` VARCHAR(2000).
  - `system_flow_pending_run`: `run_id` VARCHAR(36) PK, `flow_id` BIGINT NOT NULL, `node_id` VARCHAR(100) NOT NULL, `flow_name` VARCHAR(200), `created_at` DATETIME NOT NULL, Index auf `flow_id`.

- [ ] **Step 1:** Branch `feature/systemprotokoll` von `main` anlegen. Spec und Plan liegen schon im Arbeitsbaum. Beide mit dem ersten Commit dieses Tasks einchecken.
- [ ] **Step 2: Failing tests schreiben**
  - `SystemLogWriterTest.write_persistsOnWriterThread`: schreiben, dann `writer.flushForTest()` (paketprivat, wartet bis die Queue leer ist). `verify(repository).save(argThat(e -> e.getKind() == FLOW_RUN && e.getRepeatCount() == 1))`.
  - `write_neverThrows_whenRepositoryFails`: `save` wirft `RuntimeException`. `write` wirft nicht, ein zweiter Eintrag wird danach trotzdem gespeichert.
  - `write_doesNotBlock_whenQueueFull`: Kapazität 2 (Konstruktor-Parameter), Repository blockiert per Latch. 10× `write` kehrt in < 500 ms zurück. Nach Freigabe ist `droppedCount()` == 7 oder 8 (der erste Eintrag steckt im Writer), und der nächste gespeicherte Eintrag trägt im `message`-Suffix „(n Einträge verworfen)“.
  - `write_truncatesMessageTo2000`.
  - `writeOrIncrement_incrementsWithinWindow` / `_createsNewRowAfterWindow` (Repository-Mock, `Clock` fest).
  - `SystemLogRetentionJobTest.purge_deletesOlderThan30Days`: `verify(repository).deleteOlderThan(now.minusDays(30))`. Wirft das Repository, wirft `purge()` nicht.
- [ ] **Step 3:** Run `mvn -q test -Dtest='SystemLog*Test'`. Erwartet: FAIL (Klassen fehlen).
- [ ] **Step 4:** Changesets, Entities (Lombok `@Getter @Setter @NoArgsConstructor`, Enums als `@Enumerated(STRING)`), Repositories, Enums, `SystemLogEvent` anlegen. Dazu `SystemLogWriter` (`@Component`) mit `ArrayBlockingQueue` (Default-Kapazität 5000), einem Daemon-Thread `system-log-writer` und `offer` statt `put`. Jede Exception beim Speichern wird nur auf `debug` geloggt, und das mit einem Logger, der in Task 8 ausgeschlossen wird. Dazu `@PreDestroy` zum Stoppen. Der Konstruktor nimmt `Clock`, Repository und Kapazität, der Spring-Konstruktor setzt 5000. `SystemLogRetentionJob` mit `@Scheduled(cron = "0 30 3 * * *")` und `purge()`.
- [ ] **Step 5:** Run `mvn -q test -Dtest='SystemLog*Test'`. Erwartet: PASS.
- [ ] **Step 6: Commit** `feat(systemlog): Tabellen, Writer und Aufbewahrung`

---

### Task 2: Flow-Lauf-Tracing in der Engine

**Files:**
- Create: `flowengine/FlowRunTrace.java`, `flowengine/FlowRunRecorder.java`, `flowengine/ConditionNodeHandler.java`, `flowengine/GateNodeHandler.java`
- Modify: `flowengine/FlowEngine.java` (`runFrom`), `flowengine/NodeContext.java` (+`reportFailure`), `flowengine/FlowRegistry.java` (`EngineNodeContext.reportFailure`)
- Modify: `flowengine/nodes/EntityConditionHandler.java`, `TimeConditionNodeHandler.java` (implementieren `ConditionNodeHandler`), `RateLimitNodeHandler.java` (implementiert `GateNodeHandler`), `DelayNodeHandler.java` (implementiert `DeferringNodeHandler`)
- Create: `flowengine/DeferringNodeHandler.java`
- Modify: `flowengine/FlowService.java` (`inject` setzt `"_injected", true` in die Message)
- Test: `flowengine/FlowEngineRunTraceTest.java`, Anpassung `FlowEngineTest` (Konstruktor)

**Interfaces:**
- Consumes: `SystemLogWriter.write`, `SystemLogEvent` (Task 1).
- Produces:
  - `FlowRunTrace.RUN_ID_KEY = "_runId"`
  - `FlowRunTrace.currentRunId()`: `Optional<String>` für den laufenden Thread (für Task 4)
  - `NodeContext.reportFailure(String reason)`: default-frei, in `EngineNodeContext` implementiert. Delegiert an `FlowRunTrace.failCurrent(nodeId, reason)`. Ohne laufenden Trace ein No-op.
  - Marker: `interface ConditionNodeHandler extends NodeHandler {}`, `interface GateNodeHandler extends NodeHandler {}` (`none()` heißt verworfen), `interface DeferringNodeHandler extends NodeHandler {}` (`none()` heißt wartet).
  - `FlowRunRecorder.record(long flowId, String runId, SystemLogStatus status, List<FlowRunTrace.Step> steps, boolean continuation, boolean injected)`: baut Titel (Flow-Name aus `FlowRepository`, Fallback „Flow <id>“), `message` (Kurzfassung, z. B. „gestoppt an ‚Nur nachts‘“) und `details` (JSON-Liste der Steps) und ruft `writer.write`. Wirft nie.
  - `record FlowRunTrace.Step(String nodeId, String type, String label, String outcome, String error)` mit `outcome` ∈ `port:<n>`, `no-target`, `stopped`, `dropped`, `deferred`, `failed`, `reported-failure`.

**Algorithmus für `runFrom`:** Die Engine legt zu Beginn einen `FlowRunTrace` an (ThreadLocal, im `finally` gelöscht).
- `runId` kommt aus `message.get("_runId")`, sonst `UUID.randomUUID()`. Damit `_runId` an die Nachfolger weitergeht, wird die Start-Message einmal mit `with(RUN_ID_KEY, runId)` ersetzt. `continuation` = Startnode ist kein `TriggerNodeHandler`.
- Je Node ein `Step`. Ein `ConditionNodeHandler`, der auf einen Port ohne Ziele ausgibt, ist `stopped`. Ein `GateNodeHandler` mit leerem Ergebnis ist `dropped`, ein `DeferringNodeHandler` mit leerem Ergebnis `deferred`. Eine Exception ist `failed`, `reportFailure` ergibt `reported-failure`. Das Hop-Limit ergibt einen Step `failed` mit „Hop-Limit erreicht“.
- Am Ende ergibt sich der Status nach der Prioritätsregel der Global Constraints und wird einmal per `FlowRunRecorder.record` geschrieben.
- Läufe aus `inject` werden mitprotokolliert (`injected=true` ⇒ Titel-Suffix „(Test)“).

- [ ] **Step 1: Failing tests** in `FlowEngineRunTraceTest`. Muster `FlowEngineTest`: `Runnable::run`-Executor, gemockter `FlowRunRecorder`, `ArgumentCaptor`.
  - `triggerToAction_recordsOk`: Status `OK`, `continuation=false`, zwei Steps.
  - `conditionOnUnconnectedPort_recordsStopped`: Condition-Testhandler, der Port 1 ohne Ziel bedient. Status `STOPPED`, Step-Outcome `stopped`.
  - `conditionOnConnectedPort1_isOk`: Port 1 hat ein Ziel. Status `OK`, also kein `STOPPED`.
  - `gateReturningNone_recordsRateLimited`
  - `deferringReturningNone_recordsWaiting`
  - `throwingNode_recordsError_andOtherBranchStillRuns`: Status `ERROR`, Aktion im Nachbarzweig wurde aufgerufen.
  - `reportFailure_recordsErrorWithoutAbortingBranch`: ein Handler ruft `ctx.reportFailure("Telegram 502")`, der Nachfolger läuft trotzdem. Status `ERROR`, Step `error == "Telegram 502"`.
  - `errorBeatsStopped`: zwei Zweige, einer stoppt, einer wirft ⇒ `ERROR`.
  - `continuationKeepsRunId`: Start an einer Nicht-Trigger-Node mit `_runId = "abc"` ⇒ `record(..., "abc", ..., continuation=true, ...)`.
  - `runIdIsPassedDownstream`: die Aktions-Node sieht `message.get("_runId")` gleich der aufgezeichneten `runId`.
  - `hopLimit_recordsError`
  - `recorderThrowing_doesNotBreakRun`
- [ ] **Step 2:** Run `mvn -q test -Dtest='FlowEngine*Test'`. Erwartet: FAIL.
- [ ] **Step 3:** Marker-Interfaces, `FlowRunTrace`, `FlowRunRecorder`, `NodeContext.reportFailure` und den Umbau von `runFrom` implementieren. Den `FlowEngine`-Konstruktor um `FlowRunRecorder` erweitern und `FlowEngineTest` sowie alle anderen `new FlowEngine(...)`-Aufrufe in Tests nachziehen (`grep -rn "new FlowEngine(" backend/src/test`). Testhandler, die `NodeContext` implementieren, um `reportFailure` ergänzen (`grep -rn "implements NodeContext" backend/src/test`).
- [ ] **Step 4:** Run `mvn -q test -Dtest='FlowEngine*Test,*NodeHandlerTest,*HandlerTest,FlowServiceTest'`. Erwartet: PASS.
- [ ] **Step 5: Commit** `feat(flowengine): Flow-Läufe ins Systemprotokoll`

---

### Task 3: Zustellfehler melden (Telegram, Push, Licht)

**Files:**
- Create: `common/DeliveryResult.java`
- Modify: `telegram/TelegramNotificationService.java`, `push/PushNotificationService.java`
- Modify: `flowengine/nodes/TelegramSendNodeHandler.java`, `PushSendNodeHandler.java`, `LightSetNodeHandler.java`
- Test: `telegram/TelegramNotificationServiceTest.java` und `push/PushNotificationServiceTest.java` (bestehende erweitern bzw. anlegen), `TelegramSendNodeHandlerTest`, `PushSendNodeHandlerTest`, `LightSetNodeHandlerTest`

**Interfaces:**
- Consumes: `NodeContext.reportFailure(String)` (Task 2).
- Produces: `record DeliveryResult(boolean delivered, String failure)` mit `static ok()`, `static failed(String)` und `DeliveryResult and(DeliveryResult other)` (fehlgeschlagen, sobald einer fehlschlug, Gründe mit „; “ verbunden).
  - `TelegramNotificationService.sendTo(long, String)` und `sendToAllowedChats(String)` liefern `DeliveryResult`. Nicht konfiguriert ⇒ `failed("Telegram nicht konfiguriert")`.
  - `PushNotificationService.sendToAll` und `sendToUser` liefern `DeliveryResult`. Keine Subscriptions ⇒ `failed("keine Push-Geräte")`, jeder HTTP-Fehler ≥ 400 außer 404/410 ⇒ failed. 404/410 allein (Selbstbereinigung) ist **kein** Fehler. Das Laden scheitert ⇒ failed.
  - Bestehende Aufrufer, die das Ergebnis ignorieren (Agent, Telegram-Bot, Push-Controller `/test`), bleiben unverändert und kompilieren weiter.
- `alexa-announce` braucht keinen Umbau: `AlexaAnnouncementService.announce` wirft bei Fehlern, die Engine macht daraus schon `ERROR`.

- [ ] **Step 1: Failing tests**
  - `TelegramNotificationServiceTest`:
    - `sendTo_returnsFailed_whenApiThrows`: Grund enthält die Exception-Nachricht.
    - `sendToAllowedChats_returnsFailed_ifAnyChatFails`
    - `sendTo_returnsFailed_whenNotConfigured`
  - `PushNotificationServiceTest`:
    - `sendToAll_returnsFailed_whenNoSubscriptions`
    - `sendToUser_returnsFailed_onHttp500`
    - `sendToAll_returnsOk_whenOnlyExpired410`
    - `sendToAll_neverThrows_whenRepositoryFails` (liefert failed).
  - Node-Tests:
    - `handle_reportsFailure_whenDeliveryFails`: `verify(ctx).reportFailure(contains("…"))`, die Message geht trotzdem auf Port 0 raus.
    - `handle_doesNotReportFailure_whenDelivered`
    - für `light-set`: `handle_reportsFailure_whenDeviceThrows`
- [ ] **Step 2:** Run `mvn -q test -Dtest='TelegramNotificationServiceTest,PushNotificationServiceTest,TelegramSendNodeHandlerTest,PushSendNodeHandlerTest,LightSetNodeHandlerTest'`. Erwartet: FAIL.
- [ ] **Step 3:** Implementieren. Die Logzeilen bleiben, der Vertrag „wirft nie“ bleibt.
- [ ] **Step 4:** Gleiches Kommando. Erwartet: PASS. Danach `mvn -q test -Dtest='*Telegram*,*Push*,Agent*'` für die Aufrufer: PASS.
- [ ] **Step 5: Commit** `feat(flowengine): nicht zugestellte Nachrichten als Laufehler`

---

### Task 4: Abgebrochene Läufe (Delay, forSeconds)

**Files:**
- Create: `systemlog/FlowPendingRunTracker.java`
- Modify: `flowengine/nodes/DelayNodeHandler.java`, `flowengine/nodes/EntityStateTriggerHandler.java`, `flowengine/FlowRegistry.java` (`deploy`, `undeploy`), `flowengine/FlowEngine.java` (fertige Fortsetzung entfernt den Pending-Eintrag)
- Test: `systemlog/FlowPendingRunTrackerTest.java`, Ergänzungen in `DelayNodeHandlerTest`, `EntityStateTriggerHandlerTest`, `FlowRegistryTest` (anlegen, falls nicht vorhanden)

**Interfaces:**
- Consumes: `SystemLogWriter`, `SystemFlowPendingRunRepository` (Task 1), `FlowRunTrace.RUN_ID_KEY`/`currentRunId()` (Task 2).
- Produces (alle Methoden werfen nie):
  - `FlowPendingRunTracker.register(long flowId, String nodeId, String runId)`
  - `complete(String runId)`: löscht still.
  - `abortAll(long flowId, String reason)`: je Zeile ein `ABORTED`-Eintrag mit `run_id`, `message = reason`, danach löschen.
  - `abortAllOnStartup()`: alle Zeilen ⇒ `ABORTED` „Backend-Neustart“, läuft vor dem Flow-Bootstrap, siehe Task 5.

**Regeln:**
- Delay: `register` in `handle` mit `runId` aus der Message.
- forSeconds: `startTimer` vergibt eine neue `runId`, registriert sie und legt sie beim Ablauf als `_runId` in die emittierte Message. Wird der Timer regulär storniert (Zustand verlässt den Bereich), ruft das `complete` ohne Eintrag auf.
- Die Engine ruft am Ende jedes `runFrom` mit `continuation=true` `complete(runId)` auf, für forSeconds auch beim Start an der Trigger-Node, wenn `_runId` schon gesetzt war.
- **Reihenfolge in `FlowRegistry.deploy/undeploy`:** zuerst `abortAll(flowId, "durch Re-Deploy abgebrochen")` bzw. bei `undeploy` `"Flow deaktiviert oder gelöscht"`, danach `runCleanups` und `cancelScheduledFutures`. Sonst entfernt das forSeconds-Cleanup den Eintrag vorher still.
- `FlowRegistry.deploy` beim allerersten Deploy eines Flows ohne alte Version ruft `abortAll` nicht auf.

- [ ] **Step 1: Failing tests**
  - `FlowPendingRunTrackerTest.abortAll_writesOneAbortedPerPendingRun_andDeletes`
  - `complete_deletesWithoutLogEntry`
  - `abortAllOnStartup_usesNeustartReason`
  - `repositoryFailure_neverThrows`
  - `DelayNodeHandlerTest.handle_registersPendingRunWithMessageRunId`
  - `EntityStateTriggerHandlerTest.forSeconds_registersPendingRun_andEmitsSameRunId`
  - `forSeconds_cancelledByStateChange_completesWithoutAbort`
  - `FlowRegistryTest.redeploy_abortsBeforeCleanups`: `InOrder` über `tracker.abortAll` vor dem Cleanup-Runnable.
  - `undeploy_abortsWithDeactivatedReason`
  - `firstDeploy_doesNotAbort`
- [ ] **Step 2:** Run `mvn -q test -Dtest='FlowPendingRunTrackerTest,DelayNodeHandlerTest,EntityStateTriggerHandlerTest,FlowRegistryTest'`. Erwartet: FAIL.
- [ ] **Step 3:** Implementieren. Der `FlowRegistry`-Konstruktor bekommt den Tracker, dazu `ObjectProvider`, falls ein Zyklus entsteht.
- [ ] **Step 4:** Gleiches Kommando plus `FlowEngine*Test`. Erwartet: PASS.
- [ ] **Step 5: Commit** `feat(flowengine): abgebrochene Läufe protokollieren`

---

### Task 5: Verpasste Termine und Lebenszeichen

**Files:**
- Create: `systemlog/SystemHeartbeat.java`, `systemlog/MissedTriggerDetector.java`
- Modify: `flowengine/TriggerNodeHandler.java` (+Default-Methode), `flowengine/nodes/ScheduleTriggerHandler.java`, `flowengine/nodes/SunTriggerHandler.java`, `flowengine/FlowEngineBootstrap.java` (ruft vor dem Deploy `tracker.abortAllOnStartup()` und `detector.detectMissed()`)
- Test: `systemlog/MissedTriggerDetectorTest.java`, `systemlog/SystemHeartbeatTest.java`, Ergänzungen in `ScheduleTriggerHandlerTest`, `SunTriggerHandlerTest`

**Interfaces:**
- Consumes: `ApplicationSettingsService` (Kategorie `SYSTEM_LOG`, Schlüssel `last_alive_at`, ISO-`Instant`), `FlowRepository.findByEnabledTrueAndDeployedDefinitionNotNull()`, `SystemLogWriter`, `FlowPendingRunTracker` (Task 4).
- Produces:
  - `TriggerNodeHandler.occurrencesBetween(NodeConfig config, ZonedDateTime from, ZonedDateTime to, int limit)`: `List<ZonedDateTime>`, Default `List.of()`. Schedule: `CronExpression.parse(cron).next(...)` in Schleife, Zone `clock.getZone()`. Sun: über das bestehende `nextOccurrence`, in Schleife.
  - `SystemHeartbeat`: `@Scheduled(fixedDelay = 60_000)` `beat()` schreibt `Instant.now(clock)`. `Optional<Instant> lastAlive()` liest defensiv (unlesbar ⇒ leer).
  - `MissedTriggerDetector.detectMissed()`:
    - liest `lastAlive` **vor** dem ersten `beat()`; leer ⇒ nichts.
    - `from = max(lastAlive, now − 7 Tage)`, `to = now`.
    - je aktivem Flow und Trigger-Node: `occurrencesBetween(..., limit 51)`. Je Termin bis 50 ein `MISSED`-Eintrag („Backend lief nicht von <lastAlive> bis <now>“, `occurred_at` = Termin). Bei 51 statt des 51. ein Sammeleintrag „weitere verpasste Termine ausgelassen“.
    - Wirft nie.
  - Damit `lastAlive` nicht schon überschrieben ist, startet `SystemHeartbeat` mit `initialDelay = 60_000`.

- [ ] **Step 1: Failing tests**
  - `MissedTriggerDetectorTest`:
    - `noHeartbeat_writesNothing`
    - `cronEveryHour_backendDown3h_writesThreeMissed`: Termine 07:00, 08:00, 09:00 bei lastAlive 06:30, now 09:30.
    - `downFor3Weeks_cappedAt7DaysAnd50PerFlow_plusSummary`: Cron stündlich ⇒ genau 50 `MISSED` + 1 Sammeleintrag.
    - `disabledFlow_isIgnored` (kommt nicht aus dem Repository)
    - `sunTrigger_usesOccurrencesBetween`
    - `handlerThrows_otherFlowsStillChecked`
  - `ScheduleTriggerHandlerTest.occurrencesBetween_usesClockZone`: Cron `0 0 6 * * *`, Clock Europe/Berlin ⇒ 06:00 Berlin, nicht UTC.
  - `SunTriggerHandlerTest.occurrencesBetween_returnsSunsetsWithOffset`
  - `SystemHeartbeatTest.lastAlive_unreadable_returnsEmpty`
- [ ] **Step 2:** Run `mvn -q test -Dtest='MissedTriggerDetectorTest,SystemHeartbeatTest,ScheduleTriggerHandlerTest,SunTriggerHandlerTest'`. Erwartet: FAIL.
- [ ] **Step 3:** Implementieren. Reihenfolge in `FlowEngineBootstrap.onApplicationReady`: `abortAllOnStartup()`, `detectMissed()`, dann der bestehende Deploy. Beide in eigenem `try/catch`, der Bootstrap darf nie daran scheitern.
- [ ] **Step 4:** Gleiches Kommando. Erwartet: PASS.
- [ ] **Step 5: Commit** `feat(systemlog): verpasste Cron-/Sonnen-Termine`

---

### Task 6: Job-Monitor und Aspekt

**Files:**
- Create: `systemlog/SystemJobMonitor.java`, `systemlog/ScheduledJobAspect.java`, `systemlog/JobState.java`
- Test: `systemlog/SystemJobMonitorTest.java`, `systemlog/ScheduledJobAspectTest.java`

**Interfaces:**
- Consumes: `SystemJobStatusRepository`, `SystemLogWriter` (Task 1).
- Produces:
  - `enum JobState { OK, FAILING, INACTIVE }`
  - `SystemJobMonitor`:
    - `void reportFailure(String reason)` und `void reportInactive(String reason)`: wirken auf den Job des laufenden Threads (ThreadLocal, vom Aspekt gesetzt). Ohne laufenden Job ein No-op mit `debug`-Log.
    - `void recordOutcome(String jobKey, JobState state, String message)`: explizit, für Nicht-`@Scheduled`-Jobs (Telegram).
    - `List<JobStatusView> statuses()` für Task 9, mit `record JobStatusView(String jobKey, JobState state, LocalDateTime lastRunAt, LocalDateTime lastSuccessAt, LocalDateTime lastFailureAt, LocalDateTime failingSince, String lastMessage)`.
  - `ScheduledJobAspect`: `@Around("@annotation(org.springframework.scheduling.annotation.Scheduled)")`. Job-Key = `<SimpleClassName ohne CGLIB-Suffix>.<methodName>`. Setzt das ThreadLocal und ruft `proceed()` auf.
    - Ausgang: Exception ⇒ FAILING (Exception wird weitergeworfen), sonst Meldung aus dem ThreadLocal (Failure > Inactive), sonst OK.
    - Danach `recordOutcome` und ThreadLocal löschen.
  - **Übergangsregeln in `recordOutcome`:**
    - Zustand im Speicher-Cache (`ConcurrentHashMap`), beim Start aus der Tabelle vorgeladen.
    - OK→FAILING: `ERROR`-Eintrag (`kind=JOB`, Titel „<Key> gestört“, Grund) und `failingSince = now`.
    - FAILING→OK: `RECOVERED`-Eintrag mit Dauer „gestört seit <failingSince> (<n> min)“.
    - INACTIVE↔irgendwas: kein Eintrag. INACTIVE→FAILING gilt als OK→FAILING.
    - FAILING→FAILING: nur Tafel aktualisieren.
    - DB-Upsert nur bei Zustandswechsel oder wenn `last_run_at` > 5 min alt ist. Wirft nie.

- [ ] **Step 1: Failing tests**
  - `SystemJobMonitorTest`:
    - `okToFailing_writesErrorOnce`: zweimal FAILING ⇒ ein Eintrag.
    - `failingToOk_writesRecoveredWithDuration`
    - `inactive_writesNothing_andStaysInactive`: INACTIVE, INACTIVE ⇒ 0 Einträge, Zustand INACTIVE.
    - `inactiveToFailing_writesError`
    - `reportFailure_withoutRunningJob_isNoop`
    - `repositoryFailure_neverThrows`
  - `ScheduledJobAspectTest`: AspectJ-Proxy über `AspectJProxyFactory` um eine Testklasse mit `@Scheduled`-Methoden.
    - `exception_recordsFailing_andRethrows`
    - `reportFailureInside_recordsFailing_notOk`
    - `reportInactiveInside_recordsInactive`
    - `normalReturn_recordsOk`
    - `jobKey_isClassDotMethod_withoutCglibSuffix`
- [ ] **Step 2:** Run `mvn -q test -Dtest='SystemJobMonitorTest,ScheduledJobAspectTest'`. Erwartet: FAIL.
- [ ] **Step 3:** Implementieren.
- [ ] **Step 4:** Gleiches Kommando. Erwartet: PASS. Danach `mvn -q test` komplett: es darf nichts neu rot werden außer der bekannten `contextLoads`-Baseline. Die Proxy-Einführung ist hier das Risiko.
- [ ] **Step 5: Commit** `feat(systemlog): Statustafel der Hintergrund-Jobs`

---

### Task 7: Poller anbinden

**Files (Modify), je an der Stelle, an der heute ein Fehler geschluckt oder „nicht konfiguriert“ übersprungen wird:**
- `tractive/TractivePollingService.java`: ohne Token und `enabled=false` ⇒ inactive. Cloud-Fehler, Rate-Limit und `TractiveAuthException` bei vorhandenem Token ⇒ failure.
- `charging/ChargingPollingService.java`: ohne Key bzw. ohne Zuhause ⇒ inactive, 401/502 bei gesetztem Key ⇒ failure.
- `nuki/NukiPollingService.java`, `meross/service/MerossElectricityPollingService.java`, `tapo/TapoPowerPollingService.java`, `shelly/ShellyPollingService.java`, `blink/BlinkPollingService.java` (nicht angemeldet ⇒ inactive), `thermostat/ThermostatPollingService.java` (kein Hub ⇒ inactive), `service/WeatherPollingService.java`, `service/AirrohrPollingService.java`, `service/AlexaAirQualityPollingService.java`, `service/SmartDeviceStatePollingService.java`, `service/WasteCalendarPollingService.java`, `network/NetworkSpeedtestService.java`, `agent/AgentWakeService.java` (Agent aus ⇒ inactive), `zigbee/service/ZigbeeAvailabilityWatchdog.java` (Zustand FAILED ⇒ failure)
- `telegram/TelegramPollingService.java`: kein `@Scheduled`, deshalb explizit `recordOutcome("TelegramPollingService.poll", …)` nach jedem `getUpdates`. Nicht konfiguriert bzw. `TELEGRAM_ENABLED=false` ⇒ INACTIVE.
- Die übrigen `@Scheduled`-Jobs (Retention, Aggregation, Reminder, Publisher …) bleiben ohne Änderung. Für sie reicht der Aspekt.

**Interfaces:**
- Consumes: `SystemJobMonitor.reportFailure/reportInactive/recordOutcome` (Task 6).

**Regel:** Ein Poller mit mehreren Geräten, bei dem einzelne Geräte scheitern (Meross, Tapo, Shelly), meldet `reportFailure` erst, wenn **alle** Abfragen des Zyklus scheiterten oder die Cloud-Anmeldung scheiterte. Ein einzelnes offline-Gerät ist kein Job-Ausfall, sonst wäre die Tafel dauerhaft rot.

- [ ] **Step 1: Failing tests** je Poller im bestehenden Test (`verify(jobMonitor).reportFailure(...)` / `reportInactive(...)` / `never()`). Mindestens:
  - `TractivePollingServiceTest.poll_withoutToken_reportsInactive`
  - `TractivePollingServiceTest.poll_cloudError_reportsFailure`
  - `ChargingPollingServiceTest.poll_withoutApiKey_reportsInactive`
  - `MerossElectricityPollingServiceTest.poll_oneDeviceFails_doesNotReportFailure`
  - `MerossElectricityPollingServiceTest.poll_loginFails_reportsFailure`
  - `NukiPollingServiceTest.poll_cloudError_reportsFailure`
  - `TelegramPollingServiceTest.notConfigured_recordsInactive`
  - für die übrigen genannten Poller je ein `…_reportsFailure` auf ihrem Fehlerpfad.
- [ ] **Step 2:** Run `mvn -q test -Dtest='*PollingService*Test,ZigbeeAvailabilityWatchdogTest,AgentWakeServiceTest,NetworkSpeedtestServiceTest'`. Erwartet: FAIL.
- [ ] **Step 3:** `SystemJobMonitor` per Konstruktor injizieren und an den Fehler- bzw. Inaktiv-Stellen aufrufen. Bestehende Logzeilen bleiben.
- [ ] **Step 4:** Gleiches Kommando. Erwartet: PASS.
- [ ] **Step 5: Commit** `feat(systemlog): Poller melden Störung und Inaktivität`

---

### Task 8: Entity-Ereignisse und Log-Appender

**Files:**
- Create: `systemlog/EntityEventLogListener.java`, `systemlog/SystemLogAppender.java`, `systemlog/SystemLogAppenderRegistrar.java`
- Test: `systemlog/EntityEventLogListenerTest.java`, `systemlog/SystemLogAppenderTest.java`

**Interfaces:**
- Consumes: `SystemLogWriter.write/writeOrIncrement/isWriterThread` (Task 1), `EntityStateChangedEvent`, `EntityEventFired`.
- Produces:
  - `EntityEventLogListener`:
    - `@EventListener onStateChanged(EntityStateChangedEvent)`: `kind=ENTITY`, `status=INFO` (oder `WARN` bei `newState == "unavailable"`), `source=entityId`, `title=entityId`, `message="<old> → <new>"`.
    - `@EventListener onEventFired(EntityEventFired)`: `message = action`.
    - Rein numerische Wechsel werden verworfen. Hilfsfunktion `static boolean isNumericChange(String oldState, String newState)` (beide `new BigDecimal(trim)` parsebar; `null` ist nicht numerisch).
  - `SystemLogAppender extends AppenderBase<ILoggingEvent>`:
    - ab `Level.WARN`
    - ignoriert, wenn `writer.isWriterThread()` oder der Logger mit `com.household.manager.systemlog` beginnt
    - sonst `writeOrIncrement(kind=LOG, status=WARN|ERROR, source=Logger-Name (letzte zwei Segmente), title=gekürzte Nachricht (200), message=formatierte Nachricht, details=Stacktrace ≤ 20 Zeilen, window=10 min)`
  - `SystemLogAppenderRegistrar`: `@EventListener(ApplicationReadyEvent)` hängt den Appender an den Root-Logger (`LoggerContext` aus `LoggerFactory.getILoggerFactory()`), `@PreDestroy` entfernt ihn.

- [ ] **Step 1: Failing tests**
  - `EntityEventLogListenerTest`:
    - `numericToNumeric_isSkipped` (`"21.5"` → `"21.6"`)
    - `numericToUnavailable_isLoggedAsWarn`
    - `onOff_isLogged`
    - `sunPhase_isLogged` (`day` → `dusk`)
    - `eventFired_isLoggedWithAction`
    - `nullOldState_isLogged`
  - `SystemLogAppenderTest`:
    - `info_isIgnored`
    - `warn_isWrittenWithWindow10Min`
    - `error_withThrowable_detailsCappedAt20Lines`
    - `ownPackageLogger_isIgnored`
    - `writerThread_isIgnored`
- [ ] **Step 2:** Run `mvn -q test -Dtest='EntityEventLogListenerTest,SystemLogAppenderTest'`. Erwartet: FAIL.
- [ ] **Step 3:** Implementieren.
- [ ] **Step 4:** Gleiches Kommando. Erwartet: PASS.
- [ ] **Step 5: Commit** `feat(systemlog): Entity-Ereignisse und Backend-Warnungen`

---

### Task 9: API und Security

**Files:**
- Create: `systemlog/SystemLogController.java`, `systemlog/SystemLogQueryService.java`, `systemlog/SystemLogDtos.java`
- Modify: `security/SecurityConfig.java` (ADMIN-Block vor der generischen GET-Regel, Kommentar im Stil der Nachbarn), `repository/SystemLogEntryRepository.java` (`search`)
- Test: `systemlog/SystemLogQueryServiceTest.java`, `SystemLogControllerTest.java` (WebMvc), Ergänzung `security/SecurityRulesTest.java`

**Interfaces:**
- Consumes: Repositories (Task 1), `SystemJobMonitor.statuses()` (Task 6).
- Produces:
  - `GET /v1/admin/system-log/entries?from&to&kind(mehrfach)&status(mehrfach)&flowId&q&problemsOnly&page&size`
    - liefert `Page<SystemLogEntryDto>` mit `record SystemLogEntryDto(long id, LocalDateTime occurredAt, SystemLogKind kind, SystemLogStatus status, String source, String title, String message, JsonNode details, Long flowId, String runId, int repeatCount)`, neueste zuerst.
    - `size` Default 50, maximal 200.
    - `from`/`to` Default: letzte 24 h.
    - `q` sucht per `LIKE` in `title`, `message` und `source`.
    - `problemsOnly=true` setzt die Status-Liste aus den Global Constraints und überschreibt `status`.
  - `GET /v1/admin/system-log/runs/{runId}`: alle Einträge eines Laufs aufsteigend, für die Fortsetzungen nach einem Delay.
  - `GET /v1/admin/system-log/jobs`: `List<JobStatusView>`, sortiert FAILING, dann OK, dann INACTIVE, innerhalb alphabetisch.
  - `GET /v1/admin/system-log/flows`: `List<{id, name}>` aller Flows, die im Zeitraum vorkommen, für das Filter-Dropdown.

- [ ] **Step 1: Failing tests**
  - `SystemLogQueryServiceTest`:
    - `problemsOnly_overridesStatusFilter`
    - `sizeCappedAt200`
    - `defaultWindowIs24h`
    - `jobs_sortedFailingOkInactive`
  - `SystemLogControllerTest.entries_returnsPage`
  - `SecurityRulesTest`:
    - `systemLog_entries_admin_ok`
    - `systemLog_entries_member_forbidden`
    - `systemLog_entries_kiosk_forbidden`
    - `systemLog_jobs_member_forbidden`
- [ ] **Step 2:** Run `mvn -q test -Dtest='SystemLogQueryServiceTest,SystemLogControllerTest,SecurityRulesTest'`. Erwartet: FAIL.
- [ ] **Step 3:** Implementieren. `search` als JPQL mit optionalen Parametern (`:kinds is null or e.kind in :kinds` usw.). Leere Listen gibt der Service als `null` weiter.
- [ ] **Step 4:** Gleiches Kommando. Erwartet: PASS.
- [ ] **Step 5: Commit** `feat(systemlog): Admin-API`

---

### Task 10: Admin-Seite „Systemprotokoll“

**Files:**
- Create: `frontend/src/app/models/system-log.model.ts`, `frontend/src/app/services/system-log.service.ts`, `frontend/src/app/pages/admin-system-log/admin-system-log.component.{ts,html,scss,spec.ts}`, `frontend/src/app/shared/system-log-view.util.ts` (+`.spec.ts`)
- Modify: `frontend/src/app/app.routes.ts` (Route `admin/system-log`, `adminGuard`, Titel „Systemprotokoll - Household Manager“, nach `admin/audit-log`), `frontend/src/app/components/header/header.component.ts` (`{ path: '/admin/system-log', label: 'Systemprotokoll', minRole: 'ADMIN' }` nach Audit-Log), `frontend/src/styles.scss` (`.admin-system-log` in `$admin-scopes`)

**Interfaces:**
- Consumes: API aus Task 9.
- Produces (`system-log-view.util.ts`, einzige Definition):
  - `statusLabel(status)`: `OK` „ok“, `STOPPED` „an Bedingung gestoppt“, `RATE_LIMITED` „Rate-Limit“, `ERROR` „Fehler“, `MISSED` „verpasst“, `WAITING` „wartet“, `ABORTED` „abgebrochen“, `INFO` „Info“, `WARN` „Warnung“, `RECOVERED` „wieder ok“. Exhaustiv per `switch` ohne `default`.
  - `statusTone(status)`: `'ok' | 'warn' | 'error' | 'neutral'`
  - `kindIcon(kind)`: Material-Symbol-Namen
  - `stepOutcomeLabel(outcome)`
  - `PROBLEM_STATUSES`
  - `RANGE_OPTIONS`: Heute / 24 h / 7 Tage / 30 Tage

**Seitenaufbau:**
- Wurzelklasse `.admin-system-log`.
- Oben die Job-Tafel: FAILING rot mit „gestört seit …“ und Grund, darunter die einklappbaren Gruppen „ok (n)“ und „inaktiv (n)“, standardmäßig zu.
- Filterleiste: Art als Checkboxen, Status-Select, Flow-Select (aus `/flows`), Zeitraum, Freitext und der Schalter „Nur Probleme“.
- Liste: Zeit, Icon, Status-Badge, Titel, Kurztext, „×n“ bei `repeatCount > 1`.
- Klick klappt auf:
  - Flow-Lauf: Step-Liste aus `details`. Wenn `runId` gesetzt ist und es mehr als einen Eintrag gibt, wird `/runs/{runId}` geladen und Start und Fortsetzung werden gemeinsam angezeigt.
  - LOG: Stacktrace in `<pre>`.
- „Mehr laden“ (nächste Seite) und der Knopf „Neu laden“. Kein Auto-Refresh.
- Ein Ladefehler zeigt eine Meldung, die Tafel bleibt stehen.

- [ ] **Step 1: Failing tests**
  - `system-log-view.util.spec.ts`:
    - jedes Enum hat ein Label
    - `PROBLEM_STATUSES` enthält genau ERROR, STOPPED, RATE_LIMITED, MISSED, ABORTED, WARN
  - `admin-system-log.component.spec.ts` (HttpTestingController):
    - `renders failing job first with since text`
    - `problemsOnly toggle sends problemsOnly=true`
    - `expanding flow run shows steps from details`
    - `expanding run with runId loads /runs/{runId}`
    - `load more appends next page`
    - `error on entries keeps job board`
- [ ] **Step 2:** Run `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/system-log*' --include='**/admin-system-log/**'`. Erwartet: FAIL.
- [ ] **Step 3:** Implementieren (separate HTML/SCSS, `@for` mit `track`, gemeinsamer Feldstil über `$admin-scopes`).
- [ ] **Step 4:** Gleiches Kommando. Erwartet: PASS. Danach `npx ng build`: keine neuen Fehler (das vorbestehende Dashboard-SCSS-Budget-ERROR ist bekannt).
- [ ] **Step 5: Commit** `feat(frontend): Admin-Seite Systemprotokoll`

---

### Task 11: Doku und Gesamtprüfung

**Files:**
- Modify: `CLAUDE.md`: neuer Abschnitt „Systemprotokoll“ unter „Benutzerverwaltung & API-Sicherheit“ bzw. bei den Flow-Engine-Abschnitten. Kernaussagen aus Spec und Global Constraints:
  - Writer wirft nie
  - `_runId`
  - Marker-Interfaces für Bedingung, Gate und Deferring (ein neuer Bedingungs-Node muss `ConditionNodeHandler` implementieren, sonst erscheint „gestoppt“ als „ok“)
  - `DeliveryResult`
  - Abort-Reihenfolge in `FlowRegistry`
  - INACTIVE-Regel für neue Poller
  - Zahlenfilter
  - 30 Tage
  - Changesets 0071–0074 nie gegen die echte DB gelaufen
- [ ] **Step 1:** `mvn -q test` komplett (mit JDK 21). Erwartet: nur die bekannte `contextLoads`-Baseline rot.
- [ ] **Step 2:** Frontend komplett: `npx ng test --watch=false --browsers=ChromeHeadless`. Erwartet: nur die bekannte Baseline aus der Memory (3 App/Hero-Fails, SmartDeviceList-Flake).
- [ ] **Step 3:** CLAUDE.md ergänzen.
- [ ] **Step 4: Commit** `docs: Systemprotokoll in CLAUDE.md`
