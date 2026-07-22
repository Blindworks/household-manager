# Blink-Gesichtserkennung (Vision-Integration)

## Architektur
- `VisionPersonService`: CRUD fuer `VisionPerson`/`VisionPersonPhoto`; Foto-Upload ruft
  `VisionSidecarClient.computeEmbedding` (Python-Sidecar `blink-vision/`, InsightFace) und
  pusht danach die komplette Personenliste per `pushPersons` (best effort, Sidecar holt sie
  bei eigenem Start ohnehin per GET nach — Push-Fehler duerfen die CRUD-Operation nicht brechen).
- `VisionRecognitionService`: nimmt Erkennungs-Webhooks entgegen, persistiert Historie
  (`VisionRecognition`) und feuert ein Entity-EVENT (`event.vision_blink_door_person`,
  `EntityStateService.reportEvent`, nicht `reportState` — folgt dem Zigbee-Taster-Muster aus
  `ZigbeeEntityMapper.mapAction`). Heartbeat/unavailable-Timer folgt 1:1 dem Muster aus
  `TabletPresenceService` (Clock-Bean, `@Scheduled`, AtomicReference-Flag gegen doppeltes Melden).

## Transaktionsgrenzen bei Hook-Pattern-Services (wichtig, wiederkehrende Frage)
`processRecognition` (und aehnliche Orchestrierungs-Methoden, die zwei unabhaengige,
gegenseitig fehlertolerante Hooks aufrufen — Persistenz + Event) bekommen bewusst
KEIN `@Transactional`:
- `repository.save(...)` traegt ueber Spring Data JPA seine eigene Transaktionsgrenze.
- `EntityStateService.reportEvent`/`reportState` rufen intern `EntityStateWriter` mit
  `@Transactional(propagation = REQUIRES_NEW)` auf (siehe [[entitystate-facade]]) — eine
  eigene Transaktion ist dort also sowieso vorhanden.
- Ein `@Transactional` auf der Orchestrierungs-Methode wuerde nur unnoetig eine gemeinsame
  Transaktion um zwei bewusst unabhaengige Hooks legen, ohne Atomaritaetsgewinn (die
  Hooks fangen ihre Fehler ja gerade ab, damit sie einander NICHT mitreissen).
- Konsistent mit dem bestehenden Muster: Weder `TabletPresenceService.reportPresence` noch
  `EntityStateService.reportState`/`reportEvent` selbst sind `@Transactional`.
- Randnotiz: Ein *gefangener* Fehler markiert eine ggf. doch vorhandene umschliessende
  Transaktion NICHT automatisch als rollback-only (Spring macht das nur bei aus der
  Advice-Methode herauspropagierenden Exceptions) — waere also technisch auch mit
  `@Transactional` kein Problem gewesen, aber unnoetig.

## Testmuster: verstellbare Clock statt zweitem Service-Objekt
Bei Heartbeat/Timeout-Tests mit Mockito NIE einen zweiten Service mit neu gebauten Mocks
aufbauen, um "Zeit vorspulen" zu simulieren — die `@Mock`-Felder sind pro Testmethode neu,
aber wenn man denselben Mock an zwei Service-Instanzen haengt, verifiziert man ungewollt
gegen den kumulierten Aufruf-Zustand beider Objekte (funktioniert zufaellig, ist aber fragil
und schwer lesbar). Sauberer: eine anonyme `Clock`-Subklasse, die `instant()` aus einer
`AtomicReference<Instant>` liest, die der Test per `.set(...)` vorspult. Ein einziges
Service-Objekt bleibt bestehen. Siehe `VisionRecognitionServiceTest.staleHeartbeatMarksEntityUnavailable`.

## Sonstiges
- Clock-Bean existiert bereits zentral in `ClockConfig` (Europe/Berlin, siehe
  [[waste-collection-clock]]) — neue Services mit Zeitbedarf injizieren einfach `Clock`,
  keine neue Bean noetig.
- `VisionSidecarClient.SidecarPerson(Long personId, String name, List<float[]> embeddings)`
  ist das Payload-Format fuer `pushPersons`; Embeddings werden in der DB als JSON-String
  (`VisionPersonPhoto.embedding`) gespeichert, ueber `ObjectMapper` hin- und rueckserialisiert.
  Unlesbare Embeddings werden beim Aufbau der Sidecar-Payload geloggt und uebersprungen,
  nicht geworfen (ein kaputter Datensatz darf nicht die ganze Liste blockieren).
