# Household Manager - Setup & Start Anleitung

## Voraussetzungen

### Software
- ✅ **Java 21** - Installiert
- ✅ **Maven 3.9+** - Installiert
- ✅ **Node.js & npm** - Installiert
- ⚠️ **MariaDB/MySQL** - Muss laufen

### Datenbank Setup

1. **MariaDB/MySQL starten** (falls noch nicht gestartet):
   ```bash
   # Windows: MariaDB/MySQL als Service starten
   # Oder XAMPP/WAMP starten, falls verwendet
   ```

2. **Datenbank und Benutzer erstellen**:
   ```sql
   -- Mit root-Benutzer verbinden und ausführen:
   CREATE DATABASE IF NOT EXISTS household_manager;
   CREATE USER IF NOT EXISTS 'household_manager'@'localhost' IDENTIFIED BY 'root';
   GRANT ALL PRIVILEGES ON household_manager.* TO 'household_manager'@'localhost';
   FLUSH PRIVILEGES;
   ```

   **Alternativ**: Die Datenbank wird automatisch erstellt, wenn in `application.properties`
   `createDatabaseIfNotExist=true` gesetzt ist (bereits konfiguriert).

## Backend starten

### Option 1: Mit Maven
```bash
cd backend
mvn clean install
mvn spring-boot:run
```

### Option 2: Mit IDE (IntelliJ IDEA / Eclipse)
1. Projekt importieren als Maven-Projekt
2. `HouseholdManagerApplication.java` öffnen
3. Run/Debug starten

**Backend läuft auf**: `http://localhost:8080`

### Backend-Endpoints testen

Nach dem Start kannst du die API testen:

```bash
# Health Check
curl http://localhost:8080/api/management/health

# Neue Ablesung erstellen (Strom)
curl -X POST http://localhost:8080/api/v1/meter-readings \
  -H "Content-Type: application/json" \
  -d '{
    "meterType": "ELECTRICITY",
    "readingValue": 12345.50,
    "readingDate": "2026-02-06T14:30:00",
    "notes": "Erste Ablesung"
  }'

# Alle Ablesungen abrufen
curl http://localhost:8080/api/v1/meter-readings

# Ablesungen nach Typ (ELECTRICITY, GAS, WATER)
curl http://localhost:8080/api/v1/meter-readings/ELECTRICITY

# Neueste Ablesung für Strom
curl http://localhost:8080/api/v1/meter-readings/ELECTRICITY/latest

# Verbrauch berechnen (benötigt mindestens 2 Ablesungen)
curl http://localhost:8080/api/v1/meter-readings/ELECTRICITY/consumption
```

## Frontend starten

```bash
cd frontend
npm install    # Nur beim ersten Mal oder nach Dependency-Änderungen
ng serve
```

**Frontend läuft auf**: `http://localhost:4200`

### Frontend-Entwicklung

```bash
# Development-Server starten
ng serve

# Production-Build erstellen
ng build --configuration production

# Tests ausführen
ng test

# Linting
ng lint
```

## Komplette Anwendung testen

### 1. Backend starten (Terminal 1)
```bash
cd backend
mvn spring-boot:run
```
Warte bis du siehst: `Started HouseholdManagerApplication in X.XXX seconds`

### 2. Frontend starten (Terminal 2)
```bash
cd frontend
ng serve
```
Warte bis du siehst: `✔ Browser application bundle generation complete.`

### 3. Im Browser öffnen
Öffne `http://localhost:4200` in deinem Browser.

### 4. Zählerstand erfassen

1. Navigiere zur Meter-Readings-Seite
2. Wähle einen Zählertyp (Strom/Gas/Wasser)
3. Gib einen Zählerstand ein (z.B. `12345.50`)
4. Wähle das Datum
5. Optional: Füge Notizen hinzu
6. Klicke "Zählerstand speichern"

### 5. Verbrauch anzeigen

Nach dem Erstellen von mindestens 2 Ablesungen für denselben Zählertyp:
- Die Verbrauchsberechnung wird automatisch durchgeführt
- Täglicher Durchschnittsverbrauch wird angezeigt

## Troubleshooting

### Backend startet nicht

**Problem**: `Connection refused` oder `Cannot connect to database`
- **Lösung**: Stelle sicher, dass MariaDB/MySQL läuft
- Prüfe die Verbindungsdaten in `backend/src/main/resources/application.properties`

**Problem**: `Access denied for user 'household_manager'@'localhost'`
- **Lösung**: Führe die Datenbank-Setup-Befehle erneut aus (siehe oben)

### Frontend startet nicht

**Problem**: `npm ERR!` oder Dependency-Fehler
- **Lösung**:
  ```bash
  rm -rf node_modules package-lock.json
  npm install
  ```

**Problem**: `Port 4200 is already in use`
- **Lösung**:
  ```bash
  ng serve --port 4201
  ```

### CORS-Fehler

**Problem**: Browser blockiert API-Aufrufe
- **Lösung**: Das Backend ist bereits für CORS konfiguriert (`http://localhost:4200`)
- Falls ein anderer Port verwendet wird, muss dieser in `MeterReadingController.java` hinzugefügt werden

## Nächste Schritte

### Bereits implementiert ✅
- Zählerstand-Erfassung (Formular)
- Backend REST API
- Datenbank-Schema (Liquibase)
- Validierung und Fehlerbehandlung
- Verbrauchsberechnung

### Noch zu implementieren
- Dashboard mit Übersicht
- Visualisierung (Charts/Diagramme)
- Historische Daten-Ansicht
- Export-Funktion (CSV/PDF)
- Phase 2: Produktverwaltung mit Barcode-Scanner

## Projekt-Struktur

```
household-manager/
├── backend/              # Spring Boot Application
│   ├── src/main/java/    # Java Source Code
│   │   └── com/household/manager/
│   │       ├── controller/   # REST Controllers
│   │       ├── service/      # Business Logic
│   │       ├── repository/   # Data Access
│   │       ├── model/        # Entities
│   │       └── dto/          # Data Transfer Objects
│   └── src/main/resources/
│       ├── application.properties  # Konfiguration
│       └── db/changelog/          # Liquibase Migrationen
│
└── frontend/            # Angular 21 Application
    ├── src/app/
    │   ├── components/   # UI-Komponenten
    │   ├── services/     # API-Services
    │   ├── models/       # TypeScript-Interfaces
    │   └── pages/        # Seiten/Views
    └── src/assets/       # Statische Ressourcen
```

## Entwicklungs-Workflow

1. **Backend-Änderungen**:
   - Code ändern
   - Spring Boot startet automatisch neu (Spring DevTools)
   - Teste mit curl oder Frontend

2. **Frontend-Änderungen**:
   - Code ändern
   - Browser lädt automatisch neu (Hot Reload)
   - Prüfe Browser-Konsole auf Fehler

3. **Datenbank-Änderungen**:
   - Neues Liquibase-Changeset in `db/changelog/changes/` erstellen
   - Backend neu starten (Liquibase führt Migration automatisch aus)

## Tipps

- **Backend-Logs**: Zeigen SQL-Queries und API-Requests
- **Frontend DevTools**: Netzwerk-Tab zeigt API-Calls
- **Datenbank**: Verwende ein Tool wie DBeaver oder MySQL Workbench zur Inspektion
