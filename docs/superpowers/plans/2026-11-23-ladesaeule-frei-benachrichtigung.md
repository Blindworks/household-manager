# Ladesäule frei – Push-Benachrichtigung Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Am Wandtablet je belegter Station einen Warteauftrag für eine Person anlegen; das Backend schickt genau einen Push, sobald ein Ladepunkt frei ist.

**Architecture:** In-Memory-`ChargingWatchService` im Paket `charging`, aufgerufen am Ende beider Polls von `ChargingPollingService`; eigener `ChargingWatchController`; Tablet-Seite lädt `/watches` mit jedem Refresh.

**Tech Stack:** Spring Boot 3.4 / Java 21, Angular 19 standalone.

**Spec:** `docs/superpowers/specs/2026-11-23-ladesaeule-frei-benachrichtigung-design.md`

## Global Constraints
- Verfall nach 4 Stunden (`ChargingWatchService.MAX_AGE = Duration.ofHours(4)`), nur im Speicher.
- Push-Titel „Ladesäule frei“, Text „<Station>: <free> von <total> Ladepunkten frei“ (bei 1 Punkt „Ladepunkt“).
- Kandidaten: aktive Nutzer mit ≥ 1 Push-Gerät, ohne `wallTablet`.
- `PUT`/`DELETE /v1/charging/watches/*/*` KIOSK; Supercharger-Id (`sc-`) ⇒ 400; schon frei ⇒ 409; Person ohne Push ⇒ 400; Station unbekannt ⇒ 404.

## Review Focus
- Poll-Fehler im Wächter darf den Poll nie kippen (`checkWatches` wirft nie) – Test in Task 1.
- Station ohne bekannten Stand (Snapshot leer) ⇒ Auftrag bleibt liegen, kein Push – Test in Task 1.
- Zwei Polls kurz hintereinander ⇒ nur ein Push (Auftrag vor dem Versand entfernt) – Test in Task 1.
- Fehlgeschlagener Push ⇒ Auftrag trotzdem erledigt – Test in Task 1.
- Antwort nach Abbestellen am Tablet überholt (Refresh zeigt alten Stand) ⇒ Tablet zeigt Stand der PUT/DELETE-Antwort – Task 3.

---

### Task 1: `ChargingWatchService` + Snapshot-Freizahl + Poll-Haken

**Files:** Create `backend/.../charging/ChargingWatchService.java`; Modify `ChargingSnapshot.java` (`Optional<int[]> freeAndTotal(String)` → free/total, Details vor Umkreis), `ChargingPollingService.java` (Aufruf `watchService.checkWatches()` am Ende von `pollArea` und `pollFavorites`); Test `ChargingWatchServiceTest.java`, Konstruktor-Anpassung in `ChargingPollingServiceTest`.

**Interfaces – Produces:**
- `record ChargingWatch(String stationId, String stationName, Long userId, String displayName, Instant createdAt)`
- `List<ChargingWatch> list()`; `void add(String stationId, Long userId)`; `void remove(String stationId, Long userId)`; `List<WatchCandidate> candidates()` (`record WatchCandidate(Long userId, String displayName)`); `void checkWatches()`

- [ ] Tests: `sendetEinenPushSobaldEinPunktFreiIst`, `sendetNurEinmal`, `wartetOhneBekanntenStand`, `verfaelltNachVierStundenOhnePush`, `pushFehlerBeendetAuftragTrotzdem`, `addLehntFreieStationAb` (409), `addLehntSuperchargerAb`, `addLehntPersonOhnePushGeraetAb`, `kandidatenOhneWandtabletUndOhneGeraet`.
- [ ] Implementieren, Tests grün, commit.

### Task 2: `ChargingWatchController` + Security

**Files:** Create `ChargingWatchController.java` (`GET /v1/charging/watches`, `GET /v1/charging/watch-candidates`, `PUT|DELETE /v1/charging/watches/{stationId}/{userId}` → `List<ChargingWatch>`); Modify `SecurityConfig` (KIOSK-Matcher); Test `SecurityRulesTest` (`kioskDarfLadeWartenAnlegenUndAufheben`).
- [ ] Test, Implementierung, commit.

### Task 3: Tablet-Oberfläche

**Files:** Modify `models/charging.model.ts` (`ChargingWatch`, `WatchCandidate`), `services/charging.service.ts` (`getWatches`, `getWatchCandidates`, `addWatch`, `removeWatch`), `tablet-charging.component.{ts,html,scss,spec.ts}`.
- Knopf „🔔 Benachrichtigen, wenn frei“ nur bei `!supercharger && free === 0 && total > 0` und ohne Auftrag; Auftrag zeigt „🔔 Benachrichtigt: Name“ (Tipp hebt auf); Auswahl-Dialog mit Kandidaten, leer ⇒ Hinweis „Niemand hat Push eingeschaltet“.
- Tests: Knopf nur bei 0 frei; Auswahl ruft `addWatch(stationId, userId)`; Tipp auf Hinweis ruft `removeWatch`.
- [ ] Tests, Implementierung, commit; CLAUDE.md-Absatz ergänzen.
