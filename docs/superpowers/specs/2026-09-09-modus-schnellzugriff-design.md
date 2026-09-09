# Modus-Schnellzugriff nach Uhrzeit (Wandtablet)

**Datum:** 2026-09-09
**Status:** Entwurf, genehmigt

## Problem

Die Modus-Leiste im Dashboard-Footer steht im Ruhezustand eingeklappt: sichtbar sind nur
die Symbole der *aktiven* Modi, alle Schaltflächen liegen hinter einem Tipp auf die Karte.
Das ist auf dem Wandtablet gewollt — aber der Nachtmodus wird jeden Abend gebraucht und
kostet dafür zwei Bedienschritte.

Gewünscht: Ein Modus, der zu einer bestimmten Tageszeit fällig ist, steht in dieser Zeit
direkt als Knopf da — und verschwindet wieder, sobald er eingeschaltet ist.

## Entscheidungen

- **Beliebig viele Modi mit je eigenem Zeitfenster**, gepflegt in der Datenbank statt fest im
  Code. Der erste Anwendungsfall ist `input_boolean.manual_nachtmodus`, aber die Lösung
  kennt ihn nicht namentlich.
- **Das Backend entscheidet, ob ein Modus fällig ist**, und reichert `GET /v1/modes` um ein
  Flag an. Das Frontend rechnet nichts und lädt nichts zusätzlich — die Modi werden ohnehin
  alle 30 s neu geholt. Muster: `GET /api/devices` mit `confirmRequired`.
- **Nur im Tablet-Modus** (`ViewModeService.isTabletView()`); die Browser-Ansicht bleibt
  unverändert.
- **Platzierung neben der eingeklappten Modus-Karte**, nicht in ihr.

### Verworfene Alternativen

- **Frontend rechnet aus einer gelieferten Fensterliste.** Sekundengenau und unabhängig vom
  Backend-Takt, dafür existiert die Fensterlogik (inkl. Mitternachtsübergang) doppelt,
  sobald sie je ein Flow oder eine zweite Ansicht braucht.
- **Ohne Schema, per Flow und Marker-Helfer.** Kein Backend-Code, ohne Redeploy änderbar —
  aber die Kopplung hängt an einer Namenskonvention, und ein umbenannter Helfer lässt das
  Feature still ins Leere laufen (dieselbe Falle wie bei Kalender-Kategorien und
  Blink-Sync-Namen).

## Datenmodell

Neue Tabelle `mode_quick_access` (Liquibase-Changeset):

| Spalte | Typ | Bedeutung |
|---|---|---|
| `id` | BIGINT PK | |
| `entity_id` | VARCHAR(255) NOT NULL **UNIQUE** | Modus-Entity-ID; ein Modus hat höchstens ein Fenster |
| `from_time` | TIME NOT NULL | Beginn, **inklusive** |
| `to_time` | TIME NOT NULL | Ende, **exklusiv** |
| `active` | BOOLEAN NOT NULL DEFAULT TRUE | Zeile pausierbar, ohne sie zu löschen |

**Fenster über Mitternacht:** `from > to` bedeutet, dass das Fenster den Tageswechsel
überspannt (20:00–06:00 ist von 20:00:00 bis 05:59:59 offen).

**`from == to` wird mit 400 abgelehnt.** Sonst wären „nie" und „immer" nicht
unterscheidbar — die Mehrdeutigkeit wird an der API-Grenze beseitigt, nicht im Resolver
geraten.

**Kein Fremdschlüssel auf `entity_states`**, konsistent zu `entity_tile_visibility`.
Stattdessen prüft das Anlegen/Ändern gegen `HouseModeQueryService`, dass die Entity-ID
wirklich ein Haus-Modus ist (400 sonst). **Kehrseite:** eine Zeile, deren Modus später aus
`HouseModes.CATALOG` verschwindet, bleibt wirkungslos stehen; die Admin-Liste zeigt dann die
rohe Entity-ID statt eines Anzeigenamens, damit das sichtbar ist.

## Backend

### Resolver

`ModeQuickAccessResolver` ist die **einzige** Definition von „dieser Modus ist jetzt fällig"
(Muster `TractiveHomeResolver`, `PowerConsumerQueryService.findConsumer`). Er nutzt die
vorhandene `Clock`-Bean (Europe/Berlin).

**Er wirft nie.** Ein DB-Fehler liefert „nichts ist fällig" plus Log-Warnung. Andernfalls
würde eine kaputte Konfigurationstabelle die gesamte Modus-Leiste des Wandtablets mit
einem 500 ausknipsen — die Anreicherung ist eine Zutat, kein tragender Teil der Antwort.

### API-Anreicherung

`ModeResponse` bekommt ein Feld `quickAccess` (boolean). Gesetzt wird es im
`ModeResponseMapper`, damit Listen- **und** Toggle-Antwort dasselbe Flag tragen, ohne dass
es an zwei Stellen berechnet wird.

### Pflege-Endpunkte

Eigener Pfad **`/v1/mode-quick-access`**, bewusst nicht `/v1/modes/quick-access`: unter
`/v1/modes` steht bereits `{entityId}`, und die Kollision `/series` vs. `/{type}` bei den
Zählerständen ist als reale Falle dokumentiert.

- `GET /v1/mode-quick-access` — Liste
- `POST /v1/mode-quick-access` — anlegen
- `PUT /v1/mode-quick-access/{id}` — ändern (Voll-PUT)
- `DELETE /v1/mode-quick-access/{id}` — löschen

Validierung: unbekannte oder nicht-Modus-Entity-ID → 400; `from == to` → 400; bereits
belegter Modus → 409 mit klarer Meldung.

### Security und Audit

Eine **methodenlose** ADMIN-Zeile auf `/v1/mode-quick-access/**`, platziert **vor** der
generischen Regel `GET /v1/**` → KIOSK (Muster `/v1/presence/settings`,
`/v1/tractive/home-settings`). Das Wandtablet braucht die Konfiguration nie — es bekommt das
fertige Flag über `/v1/modes`, das über die generische Regel KIOSK-lesbar bleibt.

Audit: `mode.quick-access.create` / `.update` / `.delete`.

## Frontend

### Dashboard

`ModeEntity` bekommt `quickAccess: boolean`.

Ein Modus erscheint als Schnellzugriff-Knopf, wenn **alle vier** Bedingungen gelten:

1. `quickAccess` ist true (das Backend hält ihn für fällig),
2. sein Zustand ist **nicht** `on` — eingeschaltet hat der Knopf seinen Zweck erfüllt, und
   der aktive Modus erscheint ohnehin als Symbol in der eingeklappten Karte,
3. die Ansicht läuft im Tablet-Modus,
4. die Modus-Leiste ist **eingeklappt** — ausgeklappt stünde der Modus sonst doppelt.

Das Markup liegt **direkt in `dashboard.component.html`** (die `lumina`-Styles sind in
`dashboard.component.scss` gekapselt und griffen in einer Kind-Komponente lautlos nicht) und
ist ein **Geschwister** der Modus-Karte innerhalb von `lumina__modes-area`, nicht ihr Kind:
als Kind würde jeder Tipp zusätzlich die Karte aufklappen.

Der Klick geht auf das bestehende `toggleMode()`. Damit greifen die Aktivierungs-Checks von
„Abwesend" und „Toni allein" auch hier, falls diese Modi je ein Fenster bekommen.

Optisch werden die vorhandenen `lumina__mode`-Klassen wiederverwendet; neues CSS bleibt
minimal, weil `dashboard.component.scss` schon heute das `anyComponentStyle`-Budget reißt.

Mehrere gleichzeitig fällige Modi stehen nebeneinander in Katalogreihenfolge, in einer
Zeile mit `flex-wrap: nowrap` und seitlichem Scrollen — ein Umbruch an dieser Stelle hat
schon einmal (fünfter Eintrag der Ansichtsleiste) dem darunterliegenden Raster Höhe
gestohlen und einen Höhenketten-Test gebrochen.

### Admin-Seite

`pages/admin-mode-quick-access/`, Route `admin/mode-quick-access`, nach dem Muster der
Netzwerk-Geräte-Seite: Tabelle mit Modus-Dropdown (aus `GET /v1/modes`), Von-/Bis-Zeitfeld,
Aktiv-Schalter, Anlegen und Löschen. Das Aktiv-Umschalten sendet einen **Voll-PUT** — ein
Teil-PUT läse `active` serverseitig als „aktiv".

## Bewusst akzeptierte Grenzen

- **Bis zu 30 s Verzögerung.** Die Sichtbarkeit wechselt erst mit dem nächsten
  Modus-Refresh. Bei einem Nachtmodus ohne Belang.
- **Maßgeblich ist die Uhr des Servers**, nicht die des Tablets. Im selben Haushalt
  dieselbe.
- **Fällt das Backend aus oder scheitert die Abfrage**, ist nichts fällig: der Knopf bleibt
  weg, die Leiste funktioniert unverändert weiter.
- **Ein Fenster je Modus.** Zwei getrennte Zeitfenster für denselben Modus (morgens und
  abends) sind nicht ausdrückbar; das UNIQUE hält das fest.

## Tests

**Backend**
- Resolver: normales Fenster (drin/davor/danach), Fenster über Mitternacht, beide Grenzen
  (`from` inklusive, `to` exklusiv), inaktive Zeile zählt nicht, DB-Fehler ergibt „nichts
  fällig" statt einer Ausnahme.
- Validierung: fremde Entity-ID → 400, Nicht-Modus-Entity → 400, `from == to` → 400,
  doppelter Modus → 409.
- Mapper: `quickAccess` steht in Listen- und in Toggle-Antwort.
- `SecurityRulesTest`: KIOSK und MEMBER kommen an `/v1/mode-quick-access` **nicht** heran
  (auch lesend nicht), ADMIN schon; `GET /v1/modes` bleibt KIOSK-lesbar.

**Frontend**
- Dashboard: Knopf erscheint bei `quickAccess` + Zustand `off` im Tablet-Modus; verschwindet
  bei Zustand `on`; verschwindet in der Browser-Ansicht; verschwindet bei ausgeklappter
  Leiste; Klick ruft `toggleMode`; ein Klick auf den Knopf klappt die Karte **nicht** auf.
- Admin-Seite: Laden, Anlegen, Aktiv-Umschalten sendet alle Felder, Löschen.

## Rollout

1. Deployen.
2. Auf `admin/mode-quick-access` eine Zeile für den Nachtmodus anlegen (z. B. 20:00–06:00).
3. Am Wandtablet gegenprüfen: Knopf steht ab der Von-Zeit da, verschwindet beim Einschalten,
   kommt beim Ausschalten innerhalb des Fensters zurück.
