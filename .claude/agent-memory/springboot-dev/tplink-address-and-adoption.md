---
name: tplink-address-and-adoption
description: Tapo Task 3 (2026-08-18) - PUT /devices/{id}/address, scanTapoDevices Cloud+lokal-Merge, buildMetadata()-Ueberschreib-Falle
metadata:
  type: project
---

Umgesetzt auf `feature/tplink-leuchtmittel`, Commit `feat(tapo): Adresse manuell setzen und
ohne lokale Discovery steuerbar bleiben`. Aufbauend auf [[tplink-modern-device-probe]] und
[[tplink-capability-mapper]].

**Kernbefund, der den Plan-Text praezisiert:** `TapoDeviceService.buildMetadata(dto)` baut bei
jedem `upsertTapoDevice`-Durchlauf eine **komplett neue** Metadata-Map aus dem Cloud-DTO — sie
kennt das zuvor gespeicherte `authProtocol` nicht. Fand die lokale Discovery in dieser Runde
nichts (`localDevice == null`, in PROD der Normalfall), wurde das Feld dadurch bei jedem Scan
stillschweigend geloescht, auch wenn die IP selbst (eigenes Entity-Feld, nicht Teil der
Metadata-Map) erhalten blieb. Behoben: `extractAuthProtocol(device)` **vor** dem Ueberschreiben
lesen und bei fehlendem `localDevice` zurueckschreiben.

**Ordnungs-Detail, das sich als bereits korrekt herausstellte:** Der Plan vermutete den Bug in
`device.setOnline(localDevice != null)`, das *vor* dem Live-Probe laeuft. Tatsaechlich
ueberschreibt ein erfolgreicher Probe `device.setOnline(state.online())` danach unbedingt, und
`TapoDeviceState.fromLocal(...)` setzt `online` bei Erfolg immer hart auf `true` — die
Reihenfolge war also bereits harmlos, sobald eine IP bekannt ist. Der reale Ausfall lag
ausschliesslich daran, dass ohne den neuen `PUT /devices/{id}/address`-Endpunkt in PROD **nie**
eine IP existierte (leeres Feld → `getStatus` wirft sofort "Keine lokale IP bekannt", bevor der
Probe ueberhaupt versucht wird). Test dafuer trotzdem geschrieben und gruen bestaetigt — kein
Fehlalarm, aber keine Ordnungsaenderung noetig gewesen.

**scanTapoDevices() jetzt Merge statt Filter:** Cloud- und lokale Liste werden ueber `deviceId`
zusammengefuehrt (seit dem Review-Nachzug case-insensitiv, siehe unten). Ein nur lokal gefundenes
Geraet (kein Cloud-Treffer, z.B. anderes TP-Link-Konto in der Cloud-Liste) wird ueber
`upsertLocalOnlyTapoDevice` **trotzdem angelegt**, aber mit `online=false` und Klartext-Hinweis in
`metadata.localDiscoveryError` — sichtbar-aber-offline schlaegt fehlend. Ein fehlgeschlagener
Handshake bei einem Geraet bricht den Scan der uebrigen nicht ab (try/catch **innerhalb**
`upsertLocalOnlyTapoDevice`, um den reinen Probe-Fehlschlag von einem Persistenz-Fehlschlag zu
trennen — siehe Review-Nachzug).

## Review-Nachzug (Commit `fix(tapo): Geraeteidentitaet...`, 2026-08-18, nach Task 4)

Ein Senior-Review fand 1 kritischen + mehrere wichtige Punkte an der obigen Umsetzung:

- **KRITISCH — Identitaetspruefung fehlte:** `PUT /devices/{id}/address` probte nur die IP, ohne
  zu pruefen, dass das antwortende Geraet die bearbeitete Zeile ist. Bei neun fast identischen
  Tapo-Geraeten im LAN haette eine vertippte-aber-gueltige IP ein ANDERES physisches Geraet
  getroffen und dessen Zustand ueber die falsche DB-Zeile geschrieben. `addKasaDeviceByIp` war
  strukturell immun (identifiziert ueber die geprobte deviceId), dieser Pfad nicht — bis jetzt.
  Fix: `TapoAddressProbeResult` traegt jetzt `deviceId` (aus `handshake.info()` via
  `firstText(info, "device_id", "deviceId")`), `setTapoDeviceAddress` vergleicht sie
  case-insensitiv gegen `device.getExternalDeviceId()` und wirft `IllegalArgumentException` (400)
  **vor** jeder Entity-Mutation, wenn sie fehlt oder abweicht.
- **WICHTIG — Verbindungscache nicht invalidiert:** `TapoDeviceService.localConnectionCache` ist
  auf `deviceId:protocol` geschluesselt und **ignoriert das ipAddress-Argument** in
  `getOrCreateLocalConnection` (`computeIfAbsent`!). Ohne `clearLocalConnection(deviceId)` nach
  einer erfolgreichen manuellen Adress-Korrektur haette ein bereits gecachtes Verbindungsobjekt
  weiter gegen die ALTE IP gesprochen — `turnOn`/`turnOff` haetten (falls die alte IP inzwischen
  einem anderen Geraet gehoert) das falsche Geraet geschaltet. `clearLocalConnection` existierte
  schon (fuer `rediscoverIp`), wurde aber von `setTapoDeviceAddress` nie aufgerufen.
- **WICHTIG — Persistenz-Fehler pro Geraet geschluckt, aber Transaktion trotzdem tot:**
  `scanAndPersistDevices` ist `@Transactional`, `SmartDevice` nutzt IDENTITY (Hibernate flusht bei
  `save()` sofort). Ein Constraint-Verstoss markiert die Transaktion **beim Auftreten**
  rollback-only — ein lokal gefangenes Java-Exception rettet die anderen Geraete beim Commit
  trotzdem nicht (`UnexpectedRollbackException` reisst ALLES mit), waehrend die Log-Zeile
  "Failed to adopt ... continuing" das Gegenteil behauptet hatte. **Entscheidung:** try/catch um
  `upsertLocalOnlyTapoDevice(...)` in der Merge-Schleife entfernt — Persistenz-Fehler propagieren
  jetzt ehrlich, genau wie `upsertTapoDevice` es fuer den Cloud-Pfad ohnehin schon tat. Alternative
  (REQUIRES_NEW pro Geraet via Self-Injection) verworfen: echte Isolation waere moeglich, aber der
  haeufige Fehlerfall (Handshake, kein DB-Fehler) ist bereits VOR der Persistenz isoliert; die
  Selbst-Invoke-Umgehung fuer eine seltene DB-Race waere unverhaeltnismaessig fuer diesen Umfang.
- **WICHTIG — deviceId-Merge ungeprueft case-sensitiv:** Cloud-`deviceId` und lokale
  `get_device_info.device_id` sind zwei unabhaengige Quellen; eine reine Gross-/Kleinschreibungs-
  Abweichung haette dasselbe physische Geraet ein zweites Mal als local-only angelegt. Fix: beide
  Seiten vor dem Keying/Vergleich durch `normalizeTapoDeviceId` (`toUpperCase(Locale.ROOT)`).
  Zusaetzlich: warnt, wenn ein local-only-Geraet dieselbe IP traegt wie ein bereits ueber die
  Cloud-Liste gematchtes Geraet (Verdacht auf denselben physischen Fall trotz Normalisierung).
- **WICHTIG — "gefunden, aber falsches Konto" ist im echten Discovery-Pfad unerreichbar:**
  `TapoDiscoveryService.discoverLocalDevices` erzeugt ein `TapoDiscoveryDevice` **nur nach**
  erfolgreichem `getDeviceInfo()` und verwirft jeden Fehlschlag vorher (`catch (Exception
  ignored)`). Der zweite `getStatus()`-Aufruf in `upsertLocalOnlyTapoDevice` kann also strukturell
  nie an "falschem Konto" scheitern — dafuer waere die Discovery schon vorher gescheitert.
  **Entscheidung (bewusste Abweichung von den beiden vorgeschlagenen Repair-Optionen):** statt die
  Discovery-Schicht umzubauen (haette `TapoDiscoveryDevice`/Rueckgabetyp-Semantik ueberall
  veraendert, u.a. den `deviceIpCache`/`workingProtocolCache`-Fuellpfad in
  `discoverLocalDevices()`) oder den Zweig ersatzlos zu streichen: try/catch UND Test **behalten**
  (der zweite Aufruf ist ein echter, eigener Fehlermodus — Netz-Race zwischen Discovery und
  Nachfrage, oder das Geraet laesst nur eine gleichzeitige Verbindung zu), aber Javadoc/Log-Text/
  Metadata-Hinweis auf die tatsaechlich moegliche Ursache umformuliert statt "anderes Konto" zu
  behaupten. Test umbenannt (`DEVFOREIGN`/"Fremdkonto" → `DEVFLAKY`/"voruebergehend nicht
  erreichbar"), Mechanik unveraendert (die war schon korrekt, nur die Erzaehlung drumherum nicht).
- **Minor:** `metadata.remove("localDiscoveryError")` nach erfolgreicher manueller Adress-Korrektur
  ergaenzt (sonst blieb ein veralteter Fehlerhinweis in der UI stehen); `refreshTapoDeviceState`
  uebernimmt jetzt auch `state.capabilities()` (vorher nur online/poweredOn/name/model — die Spec
  verspricht Faehigkeiten-Erkennung bei Scan ODER Refresh); `TapoCapabilityMapper`-Javadoc
  verspricht keine Entity-State-Events mehr (Faehigkeiten erreichen diese Schicht nie).

**TapoDeviceService.probeAddress(ip):** neuer oeffentlicher Einstieg, teilt sich die
KLAP-dann-AES-Schleife mit dem bereits vorhandenen `probeStaticDevice` ueber ein gemeinsames
privates `tryLocalHandshake(ip, label)`. Bewusst **kein** Instanzfeld fuer das erfolgreiche
Protokoll (TapoDeviceService ist ein Singleton-Bean) — stattdessen ein lokaler Record
`LocalHandshakeResult(protocol, info)` als Rueckgabewert, um eine Race zwischen einem
geplanten Scan und einem parallelen manuellen "Adresse setzen"-Request zu vermeiden.

**scanTapoDevices() wurde package-private** (statt `private`), rein damit der Test das Ergebnis
als `List<SmartDevice>` direkt pruefen kann — `scanAndPersistDevices` gibt nur DTOs zurueck.

**Endpunkt-Vertrag:** `PUT /devices/{id}/address`, Body `{"ip": "..."}` (IPv4, gleiche Pattern
wie `KasaManualAddRequest` inkl. Fuehrende-Null-Ablehnung). 400 bei unbekannter ID oder
Nicht-TAPO-Geraet, 502 (ueber die bereits vorhandene `TapoException`-Zuordnung im
`GlobalExceptionHandler`) bei Nichterreichbarkeit — dabei wird **nichts** persistiert, der Probe
laeuft vor jeder Entity-Aenderung. Security: bewusst kein neuer Matcher, faellt wie
`POST /devices/kasa` auf `anyRequest → MEMBER`.
