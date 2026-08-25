# Anwesenheitserkennung per WLAN (Handy) — Design

**Datum:** 2026-08-25
**Status:** Entwurf validiert (Brainstorming mit Nutzer abgeschlossen)

## Ziel

Anwesenheits-/Abwesenheitserfassung pro Person im Haushalt. Erste Quelle: das
iPhone der Person im heimischen WLAN (2+ iPhones im Haushalt). Die Erkennung
liefert Entitäten für Flows, eine Dashboard-Kachel und steuert den Modus
„Abwesend" automatisch (über Flows, nicht über Java-Code).

## Gewählter Ansatz

**Backend-Polling per TCP-Probe** (Ansatz A). Verworfen wurden:

- **Push vom Handy (iOS-Kurzbefehle):** Ankunft wäre sekundenschnell, aber die
  Abmeldung ist strukturell kaputt — beim Verlassen des WLANs ist das Handy
  schon draußen und erreicht das LAN-only-Backend nicht mehr. Bleibt als
  mögliche spätere *Ergänzung* für schnellere Ankunftserkennung.
- **Router-API:** Der TP-Link AX6000 hat kein offenes API (Befund aus dem
  Netzwerk-Monitoring).
- **ARP-Scan-Sidecar:** Layer-2-genau, aber neuer Container mit Host-Networking
  ist für zwei iPhones unverhältnismäßig. ICMP-Ping und ARP gehen aus dem
  Docker-Bridge-Netz ohnehin nicht (dokumentierte Einschränkung); Unicast-TCP
  geht nachweislich.

## Kernidee der Probe

Ein iPhone im WLAN beantwortet einen TCP-Connect fast immer *irgendwie* —
typisch auf Port 62078 (lockdownd), sonst mit einem RST („Connection refused").
Ein abwesendes Gerät antwortet gar nicht (Timeout). Deshalb zählt **jede
TCP-Antwort als anwesend, auch eine Ablehnung**. Der bestehende
`TcpPortProbe` des network-Moduls kennt diese Unterscheidung nicht („refused"
und „timeout" sind für ihn beides „zu") — das presence-Modul bekommt eine
eigene Drei-Zustands-Probe.

## Modul und Datenmodell

Neues Modul `backend/src/main/java/com/household/manager/presence/` —
eigenständig, nicht Teil von `network/`: Es geht um Personen, nicht um
Infrastruktur, und die Probe-Semantik ist eine andere.

**Tabelle `presence_device`** (Liquibase, Repository in
`com.household.manager.repository` — JpaConfig-Einschränkung):

| Spalte | Bedeutung |
|---|---|
| `id` | PK |
| `user_id` | FK auf `app_user`, `ON DELETE CASCADE` |
| `name` | z. B. „iPhone Benedikt" |
| `host` | IP-Adresse (feste DHCP-Reservierung vorausgesetzt) |
| `active` | Toggle |
| `created_at` / `updated_at` | Timestamps |

Mehrere Geräte pro Person sind erlaubt; anwesend ist, wessen *irgendein*
aktives Gerät antwortet.

**Karenzzeit** in `application_settings`, Kategorie `PRESENCE`
(`away-grace-minutes`, Default **10**). Fassade nach dem Muster
`TractiveHomeSettingsService`: defensives Parsen, unplausible Werte fallen auf
den Default zurück, Lesen wirft nie.

**Keine eigene Historientabelle** in v1 — „anwesend seit" liefert
`lastChanged` des Entity-State-Layers gratis.

## Erkennungslogik

`PresencePollingService`, `@Scheduled` alle 30 s (`fixedDelay`,
konfigurierbar `presence.poll-interval-ms`), wirft nie:

- **Probe je Gerät** (`PhoneProbe`-Interface, Socket-Implementierung): TCP-
  Connect nacheinander auf **62078, 80, 443**, Timeout 2 s je Port. Verbindung
  angenommen **oder abgelehnt** ⇒ Gerät hat geantwortet ⇒ anwesend, sofort.
  Timeout auf allen Ports ⇒ in diesem Zyklus still. Erste Antwort gewinnt
  (keine weiteren Ports probieren).
- **`lastSeen` pro Gerät im Speicher** (Muster `NetworkDeviceStatusMonitor`).
  Eine Person gilt als **abwesend erst**, wenn *alle* ihre aktiven Geräte
  länger als die Karenzzeit still sind. **Anwesend gilt sofort** mit der ersten
  Antwort.
- **Neustart-Verhalten:** `lastSeen` überlebt den Neustart nicht. Bis seit dem
  Start die Karenzzeit verstrichen ist, wird bei Stille **kein Update
  gemeldet** — die Entität behält ihren letzten Wert aus der DB (Muster
  Tractive: nie raten). Anwesende Handys antworten ohnehin binnen Sekunden.
- **Welche Personen bekommen eine Entität:** nur Personen mit mindestens
  einer `presence_device`-Zeile. Ein Benutzer ohne jede Geräte-Zeile bekommt
  **keine** Entität (Muster Tractive ohne Home-Koordinaten). Hat eine Person
  Geräte, aber alle sind **deaktiviert**, wird ihre Entität `unavailable`
  gemeldet (blind ist blind — ein Flow darauf soll es sehen können).
- **Fehlerisolation:** Fehler pro Gerät isoliert; ein DB-Fehler beim Laden der
  Geräteliste überspringt den Zyklus, ohne Zustände zu verändern — `lastSeen`
  bleibt stehen, es wird nichts fälschlich `off`.

## Entitäten

Neuer `EntitySource.PRESENCE`; Melde-Muster wie `TabletPresenceService`,
gemeldet wird in **jedem** Poll-Zyklus (Muster Nuki/Tractive — `lastUpdated`
bleibt frisch, Flows feuern nur bei echten Wertwechseln):

- **Pro Person:** `binary_sensor.presence_<userId>_home` — State
  `on`/`off`/`unavailable`, `deviceClass: presence`, Friendly Name aus dem
  `displayName` des Benutzers. Attribute: `lastSeenAt`, `personUserId`.
- **Aggregat:** `binary_sensor.presence_household` („Jemand zu Hause") —
  `on`, sobald mindestens eine erfasste Person `on` ist; `off`, wenn alle
  erfassten Personen `off` sind; `unavailable` nur, wenn *alle* erfassten
  Personen `unavailable` sind („erfasst" = mindestens eine
  `presence_device`-Zeile; gibt es gar keine erfasste Person, wird das
  Aggregat nicht gemeldet). Die Aggregation läuft **in Java im selben Poll-Zyklus**, nicht als
  Flow — sie ist Fachlogik, und ein Flow-Umweg würde eine Zyklus-Verzögerung
  einbauen.

## Modus-Automatik „Abwesend" — Flows, kein Java

Beim **Rollout via flow-mcp** angelegt (create → deploy → enable), nicht im
Code (dasselbe Argument wie bei der Zigbee-Telegram-Warnung: Wortlaut,
Bedingungen, Empfänger ohne Redeploy änderbar):

- Flow „Alle weg": Trigger `presence_household` → `off`, Aktion Modus
  „Abwesend" einschalten.
- Flow „Jemand kommt": Trigger `presence_household` → `on`, Aktion Modus
  „Abwesend" ausschalten.

Ob „Alle weg" zusätzlich „Toni allein" berücksichtigt, wird beim Flow-Autoring
entschieden — bewusst Flow-Ebene, nicht Teil dieses Designs.

**Offen benannte Konsequenzen:**

1. Ein Flow schaltet den Modus **direkt** — die Aktivierungs-Checks des
   Dashboards (Fenster zu? Verbraucher ≥ 50 W?) laufen dabei nicht.
   Konsistent mit dem Bestand („Flows, Telegram und die API schalten
   direkt"), aber bewusst so gewollt.
2. Flow-Engine-Semantik gilt: der Übergang *nach* `unavailable` ist
   unterdrückt, die Erholung feuert normal. Da die Quelle lokal ist, tritt
   `unavailable` praktisch nur bei „keine Geräte konfiguriert" auf.

## API `/api/v1/presence`

- `GET /status` — alles für die Anzeige in einem Abruf: Personen mit State,
  `lastSeenAt`, Geräten (je Gerät `lastSeenAt`, `active`), plus Aggregat.
  **Alle Zeitstempel als `LocalDateTime` in Haushaltszeit** (Review-Fund aus
  dem Netzwerk-Monitoring: kein gemischtes Instant/LocalDateTime im selben
  Antwortbaum).
- Geräte-CRUD: `GET/POST /devices`, `PUT/DELETE /devices/{id}` — **Voll-PUT
  inkl. `active`** (Teil-PUT-Falle aus dem Kategorien-Muster: ein fehlendes
  `active` gälte serverseitig als „aktiv").
- `GET/PUT /settings` — die Karenzzeit.

## Security

- Geräte-CRUD und Settings: **ADMIN**, Matcher **vor** der generischen
  `GET /v1/**`-Regel. Geräte-Schreibzugriffe methodenspezifisch (Muster
  Netzwerk-Geräte); der Settings-Matcher **methodenlos** (deckt PUT mit ab,
  Muster `tractive/home-settings` — sonst könnte das Wandtablet die
  Einstellungen lesen).
- `GET /status` bleibt über die generische Regel **KIOSK**-lesbar — die
  Dashboard-Kachel läuft damit auf dem Wandtablet.
- Beide Richtungen in `SecurityRulesTest`.
- Audit: `presence.device.create/update/delete`, `presence.settings.update`.

## Frontend

- **Admin-Seite „Anwesenheit"** (Route `admin/presence`, Muster der
  Netzwerk-Geräte-Seite): Karenzzeit-Feld plus Geräteliste — Person (Dropdown
  aus `GET /v1/users`), Name, IP, Aktiv-Toggle.
- **Dashboard-Kachel** im Footer (neben Türschloss/Hund): pro Person ein
  Punkt (grün/grau) mit Name, bei Abwesenheit „seit HH:mm". Markup **direkt
  in `dashboard.component.html`** (lumina-Kapselung — Kind-Komponenten
  rendern lautlos ungestylt). Rein anzeigend, Refresh im bestehenden
  30-s-Rhythmus des Dashboards.

## Voraussetzungen im Router / auf den iPhones (Doku, kein Code)

- Feste **DHCP-Reservierung** je iPhone (dasselbe Muster wie bei manuell
  angelegten Kasa-Geräten — ohne Discovery findet niemand eine gewechselte
  IP nach).
- iOS-Einstellung „**Private WLAN-Adresse**" für das Heim-WLAN auf **„Fest"**
  (nicht „Rotierend", Option seit iOS 18) — sonst wechselt die MAC und die
  Reservierung greift nicht mehr.

## Tests

- **Kernlogik als Unit-Tests mit `Clock`-Steuerung:** Karenzzeit-Übergänge,
  „refused zählt als anwesend", Neustart-Verhalten (kein Update während der
  Anlauf-Karenz), Aggregat-Regeln, Person ohne Geräte ⇒ `unavailable`.
- Controller-Test für `/status` und Geräte-CRUD.
- `SecurityRulesTest`: ADMIN-Pflicht für Geräte/Settings **und**
  KIOSK-Lesbarkeit von `/status`.
- Frontend-Tests für Admin-Seite und Dashboard-Kachel.

## Bewusste v1-Grenzen

- Keine Gäste-Erkennung, keine manuelle Übersteuerung („Urlaubsmodus").
- Keine Präsenz-Historie (nur `lastChanged` der Entität).
- Kein Ankunfts-Push vom Handy (Ansatz B als mögliche spätere Ergänzung).
- Erkennung hängt an festen IPs; wechselt ein iPhone die MAC (rotierende
  private WLAN-Adresse), fällt die Person still auf „abwesend" — sichtbar
  nur am `lastSeenAt` auf der Admin-Seite.

## Rollout-Reihenfolge

1. Deploy (Liquibase legt `presence_device` an; ohne Geräte meldet nichts).
2. DHCP-Reservierungen im Router prüfen/anlegen; iPhone-Einstellung „Private
   WLAN-Adresse: Fest" prüfen.
3. Geräte auf der Admin-Seite erfassen; Karenzzeit bei Bedarf anpassen.
4. Einige Tage beobachten (Dashboard-Kachel/`lastSeenAt`), ob nächtliche
   Aussetzer auftreten; Karenzzeit ggf. nachziehen.
5. **Erst danach** die beiden Modus-Flows via flow-mcp anlegen
   (create → deploy → enable).
