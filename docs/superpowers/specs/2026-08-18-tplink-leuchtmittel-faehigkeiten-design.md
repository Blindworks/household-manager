# Moderne TP-Link-Leuchtmittel + Fähigkeiten-Modell — Design

**Datum:** 2026-08-18
**Status:** Entwurf, mit dem Nutzer abgestimmt

## Befund (gemessen, nicht vermutet)

Auslöser war „Kasa-Leuchtmittel lassen sich nicht einbinden“ mit dem Fehler
`Failed to communicate with Kasa device at IP 192.168.1.114 after 3 attempts`.
Messung im echten Netz am 2026-08-18:

| Gerät | TCP 9999 (klassisch Kasa) | TCP 80 (modernes TP-Link-Protokoll) |
|---|---|---|
| Steckdose „Kamera“ (192.168.1.116) | offen | – |
| Leuchtmittel (192.168.1.114) | **zu** | **offen**, antwortet auf `POST /app` mit `{"error_code":1003}` |

Daraus folgen drei Feststellungen:

1. **Das Gerät spricht das Kasa-Protokoll nicht.** TP-Link hat neuere Geräte auf
   denselben Stack umgestellt, den Tapo nutzt (verschlüsselter Handshake über
   HTTP statt XOR-Chiffre auf 9999). Kein zusätzlicher Glühbirnen-Payload für
   `transition_light_state` hätte geholfen — die Verbindung kommt nicht zustande.
2. **Der Protokollstack existiert bereits**: `TapoKlapDeviceConnection`,
   `TapoAesDeviceConnection`, Protokollwahl in `TapoDeviceFactory`, und
   `TapoDiscoveryService` implementiert die moderne UDP-Discovery (RSA + CRC32).
3. **Die eigentliche Lücke ist eine Filterlogik.** `SmartDeviceService.scanTapoDevices()`
   startet bei `discoverCloudDevices()` und nutzt die lokale Discovery nur, um
   diesen Cloud-Geräten IP und Protokoll zuzuordnen. Ein Gerät, das lokal
   gefunden wird, aber **in keinem hinterlegten Cloud-Konto steht, wird verworfen**.
   Das Leuchtmittel fällt zwischen die Stühle: Kasa kann es nicht ansprechen,
   Tapo wirft es weg.

Zusätzlich: `capabilities` steht an allen drei Upsert-Stellen hart auf `"SWITCH"`.
`TapoDeviceService` kann heute nur `turnOn`, `turnOff`, `getStatus` und Energiewerte —
weder Helligkeit noch Farbe, obwohl die Geräte es können.

## Ziel

1. Lokal erreichbare TP-Link-Geräte einbinden, auch ohne Eintrag in einem Cloud-Konto.
2. Ein echtes Fähigkeiten-Modell statt des hartkodierten `"SWITCH"`.
3. Helligkeit, Farbe und Farbtemperatur steuern — über API, Dashboard und Flow-Engine.
4. Das manuelle Hinzufügen per IP protokollunabhängig machen.

## Entscheidungen (mit dem Nutzer geklärt)

- **Umfang:** volle Unterstützung inkl. Helligkeit **und** Farbe.
- **Flow-Anbindung:** ja, eigener Aktions-Node.
- **Zugangsdaten:** unklar, ob Kasa- und Tapo-App dasselbe TP-Link-Konto nutzen.
  Deshalb wird **beides** unterstützt: das Backend probiert die konfigurierten
  Zugangsdaten der Reihe nach durch und meldet bei Fehlschlag klar, welche Konten
  es versucht hat.

## Architektur

### 1. Fähigkeiten-Modell

Die Spalte `capabilities` ist bereits ein String und wird als kommaseparierte
Liste genutzt — **keine Migration nötig**. Werte: `SWITCH`, `BRIGHTNESS`,
`COLOR`, `COLOR_TEMP`.

Abgeleitet wird sie **aus der Antwort des Geräts selbst** (`get_device_info`),
nicht aus einer Modellliste: ein Feld `brightness` ⇒ `BRIGHTNESS`, `hue`/`saturation`
⇒ `COLOR`, `color_temp` bzw. ein Farbtemperaturbereich ⇒ `COLOR_TEMP`. Eine
Modell-Tabelle würde bei jedem neuen Gerät veralten; die Selbstauskunft nicht.
Klassische Kasa-Steckdosen behalten `SWITCH`.

**Bewusst:** Die Ableitung läuft auch für die bestehenden Tapo-Lampen, die heute
als reine Schalter geführt werden. Sie bekommen ihre Fähigkeiten beim nächsten
Scan oder Refresh — ohne Datenmigration, weil der Wert ohnehin bei jedem Upsert
neu geschrieben wird.

### 2. Lokale Geräte ohne Cloud-Eintrag

`scanTapoDevices()` wird umgedreht: Cloud-Liste **und** lokale Discovery werden
zusammengeführt, Schlüssel ist die Geräte-ID. Geräte, die nur lokal auftauchen,
werden übernommen; Name und Modell liefert der Handshake (`get_device_info`).
Geräte, die nur in der Cloud stehen, verhalten sich wie bisher.

**Kehrseite, bewusst akzeptiert:** Ein lokal gefundenes Gerät ohne Cloud-Eintrag
ist nur steuerbar, wenn die Anmeldung mit einem der hinterlegten Konten gelingt.
Schlägt sie fehl, wird das Gerät trotzdem angelegt, aber als „nicht angemeldet“
markiert und im Frontend mit Hinweis gezeigt — besser ein sichtbares Problem als
ein stillschweigend fehlendes Gerät.

### 3. Zugangsdaten

Primär die vorhandenen `TAPO_EMAIL`/`TAPO_PASSWORD`. Zusätzlich ein optionales
zweites Paar (`TPLINK_ALT_EMAIL`/`TPLINK_ALT_PASSWORD`) für ein getrenntes
Kasa-Konto. Beim Handshake werden die Paare der Reihe nach probiert; das
erfolgreiche wird pro Gerät vermerkt, damit nicht bei jedem Zugriff neu geraten wird.
Fehlermeldung nennt die versuchten Konten (nur die E-Mail, nie das Passwort).

### 4. Steuerung

`TapoDeviceService` bekommt `setLightState(deviceId, ip, protocol, LightState)`
über `set_device_info`. `LightState` ist ein Record mit optionalen Feldern
`brightness` (1–100), `hue` (0–360), `saturation` (0–100), `colorTemp` (Kelvin).
Gesetzt wird nur, was angegeben ist.

API: `PUT /devices/{id}/light` mit demselben Objekt. Validierung an der Grenze:
Wertebereiche prüfen, und eine Fähigkeit, die das Gerät nicht meldet, wird mit
400 abgelehnt statt still ignoriert. Schreibend ⇒ MEMBER (`anyRequest`-Regel,
keine eigene Security-Zeile; `SecurityRulesTest` hält beide Richtungen fest).

### 5. Dashboard

Auf der Geräteseite und in der Schalter-Kachel: Helligkeitsregler und Farbwahl,
**nur** bei Geräten mit der jeweiligen Fähigkeit. Kein Regler bei einer Steckdose.

### 6. Flow-Node `light-set`

Felder: `deviceId` (Pflicht), `brightness`, `hue`, `saturation`, `colorTemp`
(alle optional, mindestens eines Pflicht — sonst Validierungsfehler beim Deploy).
Verhalten wie die übrigen Aktions-Nodes: ein Ausgangs-Port, Message unverändert
weiter, Fehler werden geschluckt und geloggt, damit ein nicht erreichbares
Leuchtmittel keinen Flow-Zweig abbricht.

## Reihenfolge der Umsetzung

Die erste Aufgabe ist **eine Messung, kein Feature**: ein Diagnose-Endpunkt, der
sich mit den hinterlegten Zugangsdaten am Gerät unter einer IP anmeldet und die
rohe `get_device_info`-Antwort zurückgibt. Erst wenn belegt ist, dass der
Handshake gelingt und welche Felder das Gerät tatsächlich meldet, wird darauf
aufgebaut. Ohne diesen Schritt wäre alles Weitere eine Wette auf ein
reverse-engineertes Protokoll.

Danach: Fähigkeiten-Ableitung → lokale Geräte übernehmen → Steuerung (Service,
API) → Dashboard → Flow-Node.

## Offene Risiken

- **Unbestätigt:** ob der KLAP-Handshake mit den vorhandenen Zugangsdaten gegen
  192.168.1.114 gelingt. Klärt der erste Umsetzungsschritt.
- **Unbestätigt:** die exakten Feldnamen in `get_device_info` dieses Modells.
  Ebenfalls Ergebnis des ersten Schritts.
- Die neun bestehenden Tapo-Geräte stehen in PROD auf „offline“, weil die lokale
  Discovery im Docker-Bridge-Netz nicht funktioniert. Dieses Design ändert daran
  nichts; es macht die Geräte über den manuellen IP-Weg aber erreichbar. Der
  Netzwerk-Umbau bleibt ein eigenes Thema.
