# Household Manager - Test-Anleitung

## Erfassung von Zählerständen testen

### Backend API-Tests

#### 1. Health Check
Stelle sicher, dass das Backend läuft:

```bash
curl http://localhost:8080/api/management/health
```

Erwartete Antwort:
```json
{
  "status": "UP"
}
```

#### 2. Erste Ablesung erstellen (Strom)

```bash
curl -X POST http://localhost:8080/api/v1/meter-readings \
  -H "Content-Type: application/json" \
  -d '{
    "meterType": "ELECTRICITY",
    "readingValue": 10000.00,
    "readingDate": "2026-01-01T12:00:00",
    "notes": "Jahresbeginn - erste Ablesung"
  }'
```

Erwartete Antwort (Status 201):
```json
{
  "id": 1,
  "meterType": "ELECTRICITY",
  "readingValue": 10000.00,
  "readingDate": "2026-01-01T12:00:00",
  "notes": "Jahresbeginn - erste Ablesung",
  "consumption": null,
  "daysSinceLastReading": null,
  "createdAt": "2026-02-06T...",
  "updatedAt": "2026-02-06T..."
}
```

#### 3. Zweite Ablesung erstellen (für Verbrauchsberechnung)

```bash
curl -X POST http://localhost:8080/api/v1/meter-readings \
  -H "Content-Type: application/json" \
  -d '{
    "meterType": "ELECTRICITY",
    "readingValue": 10250.50,
    "readingDate": "2026-02-01T12:00:00",
    "notes": "Monatsablesung Februar"
  }'
```

Erwartete Antwort:
```json
{
  "id": 2,
  "meterType": "ELECTRICITY",
  "readingValue": 10250.50,
  "readingDate": "2026-02-01T12:00:00",
  "notes": "Monatsablesung Februar",
  "consumption": 250.50,
  "daysSinceLastReading": 31,
  "createdAt": "2026-02-06T...",
  "updatedAt": "2026-02-06T..."
}
```

#### 4. Verbrauch abrufen

```bash
curl http://localhost:8080/api/v1/meter-readings/ELECTRICITY/consumption
```

Erwartete Antwort:
```json
{
  "meterType": "ELECTRICITY",
  "currentReading": 10250.50,
  "previousReading": 10000.00,
  "consumption": 250.50,
  "currentReadingDate": "2026-02-01T12:00:00",
  "previousReadingDate": "2026-01-01T12:00:00",
  "daysBetweenReadings": 31,
  "averageDailyConsumption": 8.08
}
```

#### 5. Alle Ablesungen abrufen

```bash
curl http://localhost:8080/api/v1/meter-readings
```

#### 6. Ablesungen nach Typ filtern

```bash
# Strom
curl http://localhost:8080/api/v1/meter-readings/ELECTRICITY

# Gas
curl http://localhost:8080/api/v1/meter-readings/GAS

# Wasser
curl http://localhost:8080/api/v1/meter-readings/WATER
```

#### 7. Neueste Ablesung abrufen

```bash
curl http://localhost:8080/api/v1/meter-readings/ELECTRICITY/latest
```

### Validierungs-Tests

#### Test 1: Ungültige Ablesung (kleiner als vorherige)

**Szenario**: Neue Ablesung ist kleiner als die vorherige

```bash
curl -X POST http://localhost:8080/api/v1/meter-readings \
  -H "Content-Type: application/json" \
  -d '{
    "meterType": "ELECTRICITY",
    "readingValue": 9000.00,
    "readingDate": "2026-02-06T12:00:00"
  }'
```

Erwartete Antwort (Status 400):
```
New reading value (9000.00) cannot be less than previous reading (10250.50). If the meter was reset, please add a note explaining this.
```

#### Test 2: Fehlende Pflichtfelder

```bash
curl -X POST http://localhost:8080/api/v1/meter-readings \
  -H "Content-Type: application/json" \
  -d '{
    "meterType": "ELECTRICITY"
  }'
```

Erwartete Antwort (Status 400):
```json
{
  "message": "Validation failed",
  "errors": [
    "Reading value is required",
    "Reading date is required"
  ]
}
```

#### Test 3: Negativer Wert

```bash
curl -X POST http://localhost:8080/api/v1/meter-readings \
  -H "Content-Type: application/json" \
  -d '{
    "meterType": "ELECTRICITY",
    "readingValue": -100.00,
    "readingDate": "2026-02-06T12:00:00"
  }'
```

Erwartete Antwort (Status 400):
```
Reading value must be greater than zero
```

### Frontend-Tests (Manuell)

#### Test-Szenario 1: Neue Ablesung erfassen

1. Öffne `http://localhost:4200/meter-readings`
2. Klicke auf "Neuen Zählerstand erfassen" (falls vorhanden)
3. **Zählertyp**: Wähle "Strom (kWh)"
4. **Zählerstand**: Eingabe `12345.50`
5. **Ablesedatum**: Wähle heutiges Datum
6. **Notizen**: Eingabe "Testablesung"
7. Klicke "Zählerstand speichern"

**Erwartetes Ergebnis**:
- ✅ Success-Message erscheint: "Zählerstand erfolgreich erfasst!"
- ✅ Formular wird zurückgesetzt
- ✅ Liste wird aktualisiert (falls vorhanden)

#### Test-Szenario 2: Validierung - Leere Felder

1. Lasse alle Felder leer
2. Klicke "Zählerstand speichern"

**Erwartetes Ergebnis**:
- ✅ Fehler bei Zählertyp: "Dieses Feld ist erforderlich"
- ✅ Fehler bei Zählerstand: "Dieses Feld ist erforderlich"
- ✅ Formular wird nicht abgeschickt

#### Test-Szenario 3: Validierung - Ungültiger Wert

1. **Zählertyp**: Wähle "Strom"
2. **Zählerstand**: Eingabe `-100` (negativer Wert)
3. Klicke "Zählerstand speichern"

**Erwartetes Ergebnis**:
- ✅ Fehler: "Der Wert muss mindestens 0 sein"

#### Test-Szenario 4: Validierung - Ungültiges Format

1. **Zählertyp**: Wähle "Strom"
2. **Zählerstand**: Eingabe `12345.678` (3 Dezimalstellen)
3. Klicke "Zählerstand speichern"

**Erwartetes Ergebnis**:
- ✅ Fehler: "Bitte geben Sie eine gültige Zahl ein (max. 2 Dezimalstellen)"

#### Test-Szenario 5: Zurücksetzen-Button

1. Fülle alle Felder aus
2. Klicke "Zurücksetzen"

**Erwartetes Ergebnis**:
- ✅ Alle Felder werden geleert
- ✅ Datum wird auf heute zurückgesetzt
- ✅ Keine Error-Messages sichtbar

#### Test-Szenario 6: Backend-Validierung

1. Erstelle eine erste Ablesung: Strom = `10000.00`
2. Erstelle eine zweite Ablesung: Strom = `9000.00` (kleiner!)

**Erwartetes Ergebnis**:
- ✅ Error-Message erscheint mit Backend-Fehlermeldung
- ✅ Formular bleibt ausgefüllt
- ✅ Keine neue Ablesung wird erstellt

### Browser DevTools überprüfen

#### Netzwerk-Tab
1. Öffne DevTools (F12)
2. Wechsle zum "Network" Tab
3. Sende eine Ablesung ab

**Erwarte**:
- POST-Request zu `http://localhost:8080/api/v1/meter-readings`
- Status: 201 Created (bei Erfolg)
- Status: 400 Bad Request (bei Validierungsfehler)

#### Konsole
- ✅ Keine Fehler in der Konsole
- ⚠️ Bei API-Fehlern: Strukturierte Fehler-Logs

### Testdaten-Script

Siehe `scripts/test-data.sh` für ein komplettes Testdaten-Script.

## Performance-Tests

### Mehrere Ablesungen schnell erstellen

```bash
# 10 Ablesungen für Strom erstellen
for i in {1..10}; do
  curl -X POST http://localhost:8080/api/v1/meter-readings \
    -H "Content-Type: application/json" \
    -d "{
      \"meterType\": \"ELECTRICITY\",
      \"readingValue\": $((10000 + i * 100)).00,
      \"readingDate\": \"2026-01-$(printf %02d $i)T12:00:00\",
      \"notes\": \"Automatische Testablesung #$i\"
    }"
  echo ""
done
```

### Antwortzeiten messen

```bash
# Mit curl -w für Timing
curl -X POST http://localhost:8080/api/v1/meter-readings \
  -H "Content-Type: application/json" \
  -d '{
    "meterType": "ELECTRICITY",
    "readingValue": 12345.00,
    "readingDate": "2026-02-06T12:00:00"
  }' \
  -w "\nResponse Time: %{time_total}s\n"
```

**Erwartete Antwortzeit**: < 500ms

## Checkliste für vollständigen Test

### Backend
- [ ] Health-Check erfolgreich
- [ ] Erste Ablesung erstellen funktioniert
- [ ] Zweite Ablesung erstellen funktioniert
- [ ] Verbrauch wird korrekt berechnet
- [ ] Validierung: Negativer Wert wird abgelehnt
- [ ] Validierung: Kleinerer Wert als vorherige Ablesung wird abgelehnt
- [ ] Alle Ablesungen abrufen funktioniert
- [ ] Nach Typ filtern funktioniert
- [ ] Neueste Ablesung abrufen funktioniert

### Frontend
- [ ] Formular wird korrekt angezeigt
- [ ] Alle Felder sind vorhanden
- [ ] Zählertyp-Dropdown zeigt alle Typen
- [ ] Einheit wird basierend auf Typ angezeigt
- [ ] Datum-Picker funktioniert
- [ ] Validierung funktioniert (Client-seitig)
- [ ] Submit sendet Daten an Backend
- [ ] Success-Message wird angezeigt
- [ ] Error-Message wird bei Fehler angezeigt
- [ ] Formular-Reset funktioniert
- [ ] Loading-State während Submit

### Integration
- [ ] Frontend kann mit Backend kommunizieren
- [ ] CORS ist korrekt konfiguriert
- [ ] Datums-Format ist kompatibel
- [ ] Fehler vom Backend werden korrekt im Frontend angezeigt
- [ ] Nach erfolgreichem Submit wird die Liste aktualisiert

## Bekannte Probleme & Workarounds

### Problem: CORS-Fehler
**Symptom**: Browser blockiert API-Requests
**Lösung**: Backend ist für `http://localhost:4200` konfiguriert - stelle sicher, dass Frontend auf diesem Port läuft

### Problem: Datum-Format-Fehler
**Symptom**: 400 Bad Request beim Absenden
**Lösung**: Frontend sendet jetzt korrektes ISO-Format mit Zeitkomponente

### Problem: "Cannot connect to database"
**Symptom**: Backend startet nicht
**Lösung**:
1. MariaDB/MySQL starten
2. Datenbank und User erstellen (siehe SETUP.md)
3. Verbindungsdaten in `application.properties` prüfen
