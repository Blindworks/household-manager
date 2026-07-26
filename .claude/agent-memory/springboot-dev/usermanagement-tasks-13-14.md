# Usermanagement Tasks 13-14: Audit-Verdrahtung + Security-Regel-Tests

## Task 13: AuditService in Fach-Services verdrahtet
Chokepoints, die `AuditService.record(action, detail)` (nutzt `AuditActorResolver`, wirft nie)
nach der erfolgreichen Aktion aufrufen:
- `SwitchCommandService.toggle` -> `switch.toggle`
- `ManualEntityService.toggle` -> `entity.toggle`
- `NukiLockService.executeAction` -> `nuki.<action.name().toLowerCase()>` (nach dem API-Call, vor dem Nachpollen)
- `FlowService` create/update/importFlow/deploy(nur bei `result.valid()`)/setEnabled/delete -> `flow.create/update/import/deploy/enable|disable/delete`
- `CalendarEventService` create/update/delete/deleteOccurrence/updateOccurrence -> `calendar.create/update/delete/delete-occurrence/update-occurrence`
- `TelegramAgentService.handleUserMessage(chatId, text)`: kein neues Feld, stattdessen
  `AuditActorContext.set(AuditActor.telegram(chatId))` in try/finally um den kompletten
  bestehenden Methodenrumpf (inkl. dem existierenden try/catch) gelegt — Telegram hat keinen
  SecurityContext, daher der ThreadLocal-Override statt Dependency-Injection.

**Konstruktor-Fallout (mechanisch, aber leicht zu uebersehen):**
- `CalendarEventService` hat einen expliziten Konstruktor (kein `@RequiredArgsConstructor`) —
  das neue Feld muss dort von Hand ergaenzt werden, nicht nur als `private final` Deklaration.
- Tests mit `@InjectMocks` (z.B. `NukiLockServiceTest`) fallen beim `test-compile` NICHT auf,
  weil Mockito bei fehlendem passendem `@Mock` einfach `null` injiziert — das kompiliert, wirft
  aber erst zur Laufzeit eine NPE im ersten Test, der die neue Methode aufruft. `mvn test-compile`
  allein reicht also nicht, um alle betroffenen Tests zu finden; bei `@InjectMocks`-Tests IMMER
  pruefen, ob ein passendes neues `@Mock`-Feld noetig ist, auch wenn der Compiler schweigt.
- Ein DRY-Refactoring lohnt sich, wenn derselbe Audit-Call an mehreren Return-Points einer
  Methode noetig ist (z.B. `CalendarEventService.deleteOccurrence` mit 3 Branches): Branch-Logik
  in eine private Methode auslagern, Audit-Call einmal am Ende der oeffentlichen Methode.

## Task 14: SecurityRulesTest (WebMvc-Slice)
- Plan-Vorlage passt 1:1 auf die realen Packages/Klassen (`com.household.manager.calendar`,
  `com.household.manager.nuki`, `com.household.manager.controller`, `com.household.manager.security`).
- **Wichtiger Fallstrick, den der Plan nicht abdeckt:** `DisabledUserSessionFilter` fragt bei
  JEDEM Request mit einem `UserDetails`-Principal (das setzt auch `@WithMockUser`!) den
  `AppUserRepository` nach dem Enabled-Status ab. Ein ungestubbtes `@MockitoBean
  AppUserRepository` liefert per Mockito-Default `Optional.empty()` -> `.orElse(true)` ->
  der Filter loescht den SecurityContext -> JEDER eigentlich erlaubte Request wird faelschlich
  401 statt des erwarteten Status. Fix: globaler `@BeforeEach`-Stub
  `lenient().when(appUserRepository.findByUsername(anyString())).thenReturn(Optional.of(<enabled AppUser>))`.
  Das gilt fuer jeden `@WebMvcTest`-Slice, der `DisabledUserSessionFilter` importiert.
- **Zweiter Fallstrick:** `GlobalExceptionHandler`s `@ExceptionHandler(Exception.class)`-Catch-all
  (siehe [response-status-exception-handler.md](response-status-exception-handler.md)) faengt auch
  Springs `NoResourceFoundException` fuer Pfade ohne registrierten Controller ab und macht daraus
  einen 500 statt des von Spring MVC vorgesehenen 404 — genau das Muster, das der Plan fuer
  "Rolle darf durch, aber kein Controller im Slice -> 404 statt 403" nutzt (z.B.
  `adminKommtAnFlowsVorbei`, meine zusaetzlichen Tractive-Tests). Fix nur im Testslice, NICHT in
  Produktionscode: `GlobalExceptionHandler` per `@WebMvcTest(..., excludeFilters =
  @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = GlobalExceptionHandler.class))`
  aus dem Slice ausschliessen, dann faellt der Request auf Springs eingebaute 404-Behandlung
  zurueck. Das betrifft nur den Testkontext — in der echten laufenden App ist das ein
  bestehendes (nicht durch diese Tasks verursachtes) Verhalten: jede unbekannte URL liefert dort
  ebenfalls 500 statt 404, weil `GlobalExceptionHandler` global via `@RestControllerAdvice`
  gilt. Nicht im Rahmen von Task 13/14 behoben (Scope), aber wert, separat zu melden.
- Tractive-Login/-Logout sind Teil der bestehenden ADMIN-Regel (`/v1/tractive/login`,
  `/v1/tractive/logout` in SecurityConfig); zwei zusaetzliche Tests (MEMBER -> 403 wie
  Kalender-KIOSK-Testmuster, ADMIN -> 404 wie das Flows-Testmuster) decken das ab.
- `CalendarEventController.create` validiert NICHT selbst (kein `@Valid`) — Validierung liegt
  komplett in `CalendarEventService.validate(...)`. Da der Service im Slice gemockt ist, laeuft
  die echte Validierung nie; ein leerer `{}`-Body wird anstandslos deserialisiert und die
  MEMBER-Test-Erwartung `isCreated()` stimmt ohne Extra-Stubbing (der vom Plan angekuendigte
  "400 statt 201"-Fall trat hier nicht auf).

## Ergebnis
Volle Suite nach beiden Tasks: 767 Tests, 3 Errors (contextLoads + 2x HealthControllerTest,
alle DB-bedingt) — exakt Baseline, alles andere gruen.

## Nachtrag: Audit-Luecke bei SmartDeviceService-Aufrufern (Qualitaets-Review-Fix)
`SmartDeviceService.turnOn/turnOff` hat 3 Aufrufer, aber `SwitchCommandService.toggle` war der
einzige auditierte Pfad. Bewusst NICHT in `SmartDeviceService` selbst auditiert — sonst zaehlt
der Dashboard-Pfad (der ueber `SwitchCommandService` bereits `switch.toggle` schreibt) doppelt.
Stattdessen an den beiden fehlenden Aufrufstellen selbst:
- `SmartDeviceController` `/devices/{id}/on|off` -> `device.on`/`device.off`, Detail = Device-ID
  (kein sprechender Name ohne Zusatz-Lookup verfuegbar, daher bewusst nur die ID)
- `flowengine/nodes/SwitchDeviceNodeHandler` (Flow-Node `switch-device`) -> `switch.device`,
  Detail = `"<deviceId> -> on|off"`. Aktor wird automatisch SYSTEM (kein SecurityContext im
  Flow-Ausfuehrungsthread, `AuditActorResolver` faellt darauf zurueck).

**Lehre fuer Audit-Reviews generell:** wenn ein Service mehrere Aufrufer hat, IMMER alle
Aufrufer suchen (`grep -rn "smartDeviceService\.turnOn\|\.turnOff"` o.ae.), bevor man einen
Chokepoint als "der" Chokepoint annimmt — Task 13 hatte nur den Dashboard-Pfad auditiert und
den Geraete-Verwaltungs-Controller sowie den Flow-Node uebersehen, obwohl beide denselben
Service-Aufruf ausloesen.

**Test-Fallstrick bestaetigt sich:** `SwitchDeviceNodeHandler` wird an 3 Stellen per
`new SwitchDeviceNodeHandler(...)` konstruiert (`SwitchDeviceNodeHandlerTest`,
`FlowImportExampleTest`, `NodeCatalogFieldsTest`) — `mvn test-compile` findet alle drei
zuverlaessig, weil hier explizite Konstruktor-Aufrufe verwendet werden (kein `@InjectMocks`).
`NodeCatalogFieldsTest`/`FlowImportExampleTest` rufen `handle()` nie auf, brauchen also nur
einen zusaetzlichen `null`/`mock(AuditService.class)`-Parameter fuer die Kompilierung.

TelegramAgentServiceTest Bonus-Test: `AuditActorContext.get()` laesst sich sauber innerhalb
eines gemockten `toolRegistry.execute(...)`-Aufrufs (via `thenAnswer`) capturen, waehrend die
Tool-Schleife laeuft — kein Test-Setup-Umbau noetig. Zusaetzlich ergaenzt: ein Test, der
belegt, dass der Aktor auch nach einer Exception im try/finally geleert wird.

## Nachtrag: FLOW:<id> als eigener Audit-Aktor (finales Merge-Review, Fix 2)
Vorheriger Zustand (siehe Nachtrag oben) liess Flow-Ausfuehrungen einfach auf `AuditActor.system()`
zurueckfallen — nicht unterscheidbar von echtem Scheduler/Polling. Fix: `AuditActor.flow(long flowId)`
(`new AuditActor(AuditActorType.SYSTEM, "FLOW:" + flowId)`) plus `AuditActorContext.set/clear` in
try/finally **um den gesamten Rumpf von `FlowEngine.runFrom`**, nicht in einzelnen NodeHandlern.

**Warum `runFrom` der richtige Chokepoint ist:** in `flowengine/` gibt es im Produktionscode
GENAU EINEN Aufrufer von `runFrom` — `emitAsync` (`executor.execute(() -> runFrom(...))`). Jeder
Einstieg (Trigger-Feuerung ueber `FlowEngineListener` -> `TriggerNodeHandler.onEntityEvent(...)` ->
`ctx.emit(...)` -> `FlowRegistry.EngineNodeContext.emit` -> `engine.emitAsync`; `FlowService.inject`;
Delay-Node-Fortsetzung) laeuft ausschliesslich darueber. `runFrom` selbst ist NICHT rekursiv (iterative
Queue), also reicht ein einzelner try/finally-Wrap um die ganze Methode — kein Verdrahten in jedem
der 8 NodeHandler noetig. Verifiziert per `grep -rn "\.runFrom\(" backend/src/main/java` (genau der
eine Treffer in `emitAsync`).

**Testmuster (FlowEngineTest):** `ContextCapturingHandler` (eigener `NodeHandler`, speichert
`AuditActorContext.get()` in `handle()` in eine `AtomicReference`) + eigene `FlowRegistry`/`FlowEngine`-
Instanz mit synchronem `Runnable::run`-Executor (wie die bestehenden Tests) — kein Mockito noetig,
reines POJO-Handler-Pattern reicht, weil `FlowEngineTest` sowieso ohne Spring-Kontext laeuft.
