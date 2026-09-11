# Vorrats-Dialog auf dem Wandtablet – Design

Datum: 2026-09-11

## Anlass

Der Klick auf eine Vorrats-Kachel im Dashboard-Footer öffnet den Erfassungs-Dialog
(Einkauf zubuchen / Bestand korrigieren). Auf dem Wandtablet (Rolle KIOSK) öffnet
er sich, das Speichern liefert aber 403: `POST /v1/pet-supplies/{key}/purchases`
und `/corrections` fallen auf die `anyRequest → MEMBER`-Regel. Außerdem sieht der
Dialog kaputt aus: die Formularstyles (`.lumina__petfood-form`) stammen aus dem
dunklen Theme (Labels `rgba(192,198,214,0.7)`, Inputs `rgba(0,0,0,0.25)` mit
`color: inherit`), der Dialog selbst ist aber weiß — graue Kästen mit fast
unsichtbarem Text.

## Entscheidungen

- KIOSK darf **Einkauf zubuchen und Bestand korrigieren**. `PUT …/target` bleibt
  MEMBER (der Dialog bietet es nicht an).
- Mengeneingabe als **Stepper + editierbares Zahlenfeld** (tablettauglich ohne
  Bildschirmtastatur, am PC weiter tippbar).

## 1. Backend – Security

- `SecurityConfig`: `/v1/pet-supplies/*/purchases` und `/v1/pet-supplies/*/corrections`
  in die bestehende KIOSK-POST-Whitelist (mit Kommentar: Erfassungs-Dialog auf dem
  Wandtablet).
- `SecurityRulesTest`: `kioskDarfKeinenEinkaufBuchen` → `kioskDarfEinkaufBuchen` (200),
  `kioskDarfKeineBestandskorrekturBuchen` → `kioskDarfBestandKorrigieren` (200), neu
  `kioskDarfZielbestandNichtAendern` (PUT `/target` → 403).
- Audit unverändert (Aktor kommt aus dem SecurityContext, also der Kiosk-Nutzer).
- Kein Rollencheck im Frontend.

## 2. Dialog – Aufbau (`dashboard.component.html`)

- **Kopf:** Vorrats-Icon (`petSupplyIcon`) + Titel `{{name}} erfassen` + Schließen.
- **Zusammenfassung:** Restmenge groß (Space Grotesk, wie `.lumina__sensor-metric-value`),
  daneben „von {{target}} {{unit}}"; zwei Chips „{{percent}} %" und
  „reicht ~{{days}} Tage" in der Ton-Farbe (`data-tone` ok/warn/critical); darunter
  der Balken.
- **Zwei helle Karten** („Einkauf zubuchen", „Bestand korrigieren"), Grid
  `repeat(auto-fit, minmax(260px, 1fr))`. Jede Karte:
  - **Stepper:** `−`/`+`-Knöpfe (≥ 56 px) um ein zentriertes Zahlenfeld
    (`inputmode="decimal"`). Schrittweite `supply.step`; Untergrenze `step` beim
    Einkauf, `0` bei der Korrektur. Das Rechnen liegt in einer reinen Util
    `shared/pet-supply-entry.util.ts`:
    - `stepAmount(current, step, direction, min)` – rundet aufs Raster
      (`Math.round(x / step) * step`, dann auf die Nachkommastellen des Rasters
      gerundet, damit keine `0.30000000000000004` entstehen), klemmt bei `min`.
    - `purchasePresets(supply)` – Chips ¼ Ziel, ½ Ziel, „Auffüllen" (= Ziel − Bestand,
      nur wenn > 0); alle aufs Raster gerundet, Werte ≤ 0 und Dubletten entfallen.
      Keine artikelspezifischen Zahlen im Frontend (Futter: 12 / 24 / Auffüllen;
      VomiSan: 15 / 30 / Auffüllen).
    - `correctionDelta(newAmount, current)` – Differenz fürs Vorschau-Label.
  - **Nur Einkauf:** Chip-Reihe aus `purchasePresets`; Klick setzt den Betrag.
  - **Nur Korrektur:** Vorschauzeile „−3 Dosen gegenüber jetzt" / „+2 …" /
    „unverändert".
  - Notizfeld (optional, `maxlength=255`), darunter voll breiter Knopf mit Icon:
    grün (`#16a34a`) „Zubuchen" (`add_shopping_cart`), dunkel-neutral (`#0f172a`)
    „Korrigieren" (`inventory_2`). Beide gesperrt bei `petSupplySaving` und bei
    ungültigem Betrag.
- **Rückmeldung:** Fehler als roter Banner mit Icon (`error`); nach Erfolg eine grüne
  Zeile (`petSupplySuccess`, z. B. „+12 Dosen gebucht" / „Bestand auf 40 Dosen
  gesetzt"), gelöscht beim nächsten Öffnen und beim nächsten Speichern. Die
  Zusammenfassung springt sofort auf den neuen Stand (bestehendes Verhalten von
  `mutatePetSupply`).
- **Link „Historie und Zielbestand auf der Vorrats-Seite" nur außerhalb des
  Tablet-Modus** (`!isTabletView`): `/pet-food` hat keinen Zurück-Knopf, das Tablet
  wäre dort gefangen.

## 3. Styles – zweite Datei

- Alle Dialog-Styles (`.lumina__petfood-summary`, `-figures`, `-forms`, `-form`,
  `-error`, `-page-link` und die neuen Stepper-/Chip-/Karten-Klassen) wandern in
  `frontend/src/app/pages/dashboard/dashboard-pet-supply-dialog.scss`; die Komponente
  bekommt `styleUrls: ['./dashboard.component.scss', './dashboard-pet-supply-dialog.scss']`.
  Das `anyComponentStyle`-Budget gilt pro Datei; `dashboard.component.scss` liegt bei
  ~28 von 32 kB. Die Kapselung bleibt, beide Dateien gehören derselben Komponente.
- Die Kachel-Styles (`.lumina__petfood`, `-track`, `-fill`) bleiben in
  `dashboard.component.scss` — sie gehören zur Kachel, nicht zum Dialog.
- Farbwelt des hellen Dialogs: Text `#0f172a`/`#475569`, Panels
  `rgba(15,23,42,0.04)`, Ränder `rgba(15,23,42,0.12)`, Töne `#16a34a` /
  `#d97706` / `#dc2626`.

## 4. Tests

- Backend: die drei Tests aus Abschnitt 1.
- Frontend `shared/pet-supply-entry.util.spec.ts`: Stepping im Raster (0,5 und 1),
  keine Gleitkomma-Artefakte, Untergrenze, Presets (¼/½/Auffüllen, Rundung,
  Auffüllen entfällt bei vollem Vorrat, Dubletten entfallen), Delta.
- Frontend `dashboard.component.spec.ts`: `−`/`+` ändern den Betrag im Raster und
  nicht unter die Grenze; Chip setzt den Betrag; Submit ruft `recordPurchase` /
  `correctStock` mit Key, Betrag und Notiz; Erfolgszeile nach Speichern; Fehler
  sichtbar; Link fehlt im Tablet-Modus und ist außerhalb vorhanden.

## Bewusste Grenzen

- Der Dialog bleibt ein reiner UI-Pfad ohne Bestätigungsdialog — eine Fehlbuchung
  ist über die Korrektur jederzeit heilbar.
- `/tablet/toni` bleibt rein anzeigend; gebucht wird nur über das Dashboard.
