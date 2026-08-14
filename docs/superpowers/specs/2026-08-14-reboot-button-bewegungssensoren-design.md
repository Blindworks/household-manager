# Modus „Bewegungssensoren" + Reboot-Button (2026-08-14)

## Ziel

Die Modus-Leiste im Dashboard-Footer wird umgebaut:

1. Der bestehende Produktions-Helfer `input_boolean.manual_bewegungssensoren` wird als
   Haus-Modus in die Leiste aufgenommen (Flows dazu folgen später separat).
2. Der Modus „Ausschalten" entfällt. An seiner Stelle steht ein **Aktions-Button
   „Reboot"**, der nach Bestätigungsdialog alle Docker-Container des
   Household-Manager-Compose-Projekts neu startet.

## Entscheidungen (mit Nutzer abgestimmt)

- **Reboot-Ziel:** Docker-Container des Compose-Projekts (Backend, Frontend,
  alexa-sidecar, blink-vision, mosquitto, zigbee2mqtt). Die externe MariaDB ist
  nicht Teil des Projekts und bleibt unberührt.
- **Berechtigung:** KIOSK darf den Reboot auslösen (Wandtablet-Anwendungsfall);
  Bestätigungsdialog davor.
- **Mechanismus:** eigener Rebooter-Sidecar mit Docker-Socket (Ansatz A). Der
  Socket (root-äquivalenter Host-Zugriff) bleibt aus dem LAN-exponierten Backend
  heraus; Muster wie blink-vision (kein LAN-Port, nur `app_net`).
- **Altlast:** `input_boolean.manual_ausschalten` (aktuell `on`, von keinem Flow
  genutzt) wird gelöscht — idempotente Bereinigung beim Start, aber nur solange
  die Entity den Modus-Marker trägt.

## 1. Modus „Bewegungssensoren"

- `HouseModes.CATALOG`: neuer Eintrag `("Bewegungssensoren", "sensors")` am Ende.
- Der `HouseModeInitializer` befördert den existierenden Helfer beim Start
  automatisch (Marker-Attribut ergänzen, Zustand/Name bleiben) — vorhandenes
  Verhalten, kein neuer Code, keine Migration.
- Frontend: kein Aufwand, die Leiste rendert Modi dynamisch aus `/api/v1/modes`.

## 2. „Ausschalten" raus, Reboot-Button rein

- „Ausschalten" wird aus `HouseModes.CATALOG` entfernt.
- `HouseModeInitializer` erhält eine Bereinigung: existiert
  `input_boolean.manual_ausschalten` **und** trägt es das Marker-Attribut `mode`,
  wird die Entity gelöscht. Der Marker-Check verhindert, dass ein später manuell
  angelegter Helfer gleichen Namens mitgelöscht wird.
- Frontend (`dashboard.component`): am Ende der Modus-Leiste ein fester
  Aktions-Button „Reboot" (Icon `restart_alt`, rote Tönung wie bisher
  „Ausschalten", gleiche `lumina__mode`-Optik). Klick öffnet einen
  Bestätigungsdialog nach bestehendem Muster (Nuki-/Schalter-Dialog):
  „System neu starten? Das Dashboard ist danach kurz nicht erreichbar."
- Nach Bestätigung: Anzeige „Neustart läuft…"; ab ~15 s pollt das Frontend alle
  5 s einen leichten GET-Endpunkt und lädt die Seite neu, sobald das Backend
  wieder antwortet.

## 3. Backend-Endpunkt

- `POST /api/v1/system/reboot`, neues Package
  `backend/src/main/java/com/household/manager/system/`
  (`SystemController`, `SystemRebootService`).
- `SecurityConfig`: Endpunkt in die KIOSK-POST-Whitelist; `SecurityRulesTest`
  wird erweitert.
- Audit-Eintrag `system.reboot` (Aktor-Auflösung wie üblich) **vor** dem
  Sidecar-Aufruf — nach dem Neustart wäre der Request-Kontext weg.
- Aufruf des Sidecars: `POST {REBOOTER_URL}/reboot` mit Header-Token
  (`X-Rebooter-Token`), kurzer Timeout. Antwort 202 → Erfolg an den Client.
- Fehlerbilder: `REBOOTER_URL`/`REBOOTER_TOKEN` nicht konfiguriert (z. B. lokale
  Entwicklung) → 400 mit Klartext; Sidecar nicht erreichbar/Fehler → 502.

## 4. Rebooter-Sidecar

- Neues Verzeichnis `rebooter/`: Alpine-Basisimage mit `docker-cli` und
  Python 3; ~50 Zeilen Standardbibliotheks-HTTP-Server.
- Ein Endpunkt: `POST /reboot`, Token-Pflicht via `X-Rebooter-Token`
  (Env `REBOOTER_TOKEN`); falscher/fehlender Token → 403, ohne konfiguriertes
  Token startet der Sidecar nicht (fail-closed).
- Ablauf: sofort 202 antworten, dann asynchron (~1 s später) `docker restart`
  aller Container mit dem eigenen Compose-Projekt-Label
  (`com.docker.compose.project`, per Self-Inspect über den Container-Hostname
  ermittelt) — **außer sich selbst**: der Sidecar ist zustandslos, ein
  Selbst-Neustart würde nur den Restart-Loop abbrechen.
- Compose: Service `rebooter`, nur `app_net`, **kein Port-Mapping**
  (Muster blink-vision), `/var/run/docker.sock` gemountet,
  `restart: unless-stopped`.
- Neue Envs: `REBOOTER_TOKEN` (Sidecar + Backend), `REBOOTER_URL` im Backend
  (`http://rebooter:8095`).

## 5. Tests

- `SecurityRulesTest`: KIOSK darf `POST /v1/system/reboot`.
- Backend-Unit-Tests: `SystemRebootService` (Sidecar-Client gemockt: Erfolg,
  nicht konfiguriert, Sidecar-Fehler); `HouseModeInitializer`-Bereinigung
  (löscht nur mit Marker, idempotent).
- Frontend-Tests: Reboot-Button rendert, Dialog öffnet, Bestätigen ruft den
  Service, Abbrechen nicht.

## Bewusst nicht enthalten

- Flows für „Bewegungssensoren" (folgen später).
- Host-Reboot, Neustart der externen MariaDB.
- Authentifizierung des Sidecars über das Shared-Token hinaus (kein LAN-Port,
  nur `app_net` — wie blink-vision).
