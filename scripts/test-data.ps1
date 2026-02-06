# PowerShell Script zum Erstellen von Testdaten für Household Manager
# Verwendung: .\scripts\test-data.ps1

$baseUrl = "http://localhost:8080/api/v1/meter-readings"

Write-Host "==================================" -ForegroundColor Cyan
Write-Host "Household Manager - Testdaten erstellen" -ForegroundColor Cyan
Write-Host "==================================" -ForegroundColor Cyan
Write-Host ""

# Funktion zum Erstellen einer Ablesung
function Create-MeterReading {
    param(
        [string]$MeterType,
        [decimal]$ReadingValue,
        [string]$ReadingDate,
        [string]$Notes
    )

    $body = @{
        meterType = $MeterType
        readingValue = $ReadingValue
        readingDate = $ReadingDate
        notes = $Notes
    } | ConvertTo-Json

    try {
        $response = Invoke-RestMethod -Uri $baseUrl -Method Post -Body $body -ContentType "application/json"
        Write-Host "✓ Ablesung erstellt: $MeterType = $ReadingValue (ID: $($response.id))" -ForegroundColor Green
        return $response
    } catch {
        Write-Host "✗ Fehler bei $MeterType = $ReadingValue : $($_.Exception.Message)" -ForegroundColor Red
        return $null
    }
}

# Health Check
Write-Host "1. Health Check..." -ForegroundColor Yellow
try {
    $health = Invoke-RestMethod -Uri "http://localhost:8080/api/management/health"
    Write-Host "✓ Backend ist erreichbar (Status: $($health.status))" -ForegroundColor Green
    Write-Host ""
} catch {
    Write-Host "✗ Backend ist nicht erreichbar!" -ForegroundColor Red
    Write-Host "Bitte starten Sie das Backend mit: cd backend && mvn spring-boot:run" -ForegroundColor Yellow
    exit 1
}

# Testdaten für Strom (ELECTRICITY)
Write-Host "2. Erstelle Testdaten für Strom..." -ForegroundColor Yellow
Create-MeterReading -MeterType "ELECTRICITY" -ReadingValue 10000.00 -ReadingDate "2026-01-01T12:00:00" -Notes "Jahresbeginn 2026"
Create-MeterReading -MeterType "ELECTRICITY" -ReadingValue 10150.50 -ReadingDate "2026-01-15T12:00:00" -Notes "Mitte Januar"
Create-MeterReading -MeterType "ELECTRICITY" -ReadingValue 10300.75 -ReadingDate "2026-02-01T12:00:00" -Notes "Monatsablesung Februar"
Create-MeterReading -MeterType "ELECTRICITY" -ReadingValue 10450.25 -ReadingDate "2026-02-06T12:00:00" -Notes "Aktuelle Ablesung"
Write-Host ""

# Testdaten für Gas (GAS)
Write-Host "3. Erstelle Testdaten für Gas..." -ForegroundColor Yellow
Create-MeterReading -MeterType "GAS" -ReadingValue 5000.00 -ReadingDate "2026-01-01T12:00:00" -Notes "Jahresbeginn 2026"
Create-MeterReading -MeterType "GAS" -ReadingValue 5250.50 -ReadingDate "2026-01-15T12:00:00" -Notes "Mitte Januar - Heizperiode"
Create-MeterReading -MeterType "GAS" -ReadingValue 5520.30 -ReadingDate "2026-02-01T12:00:00" -Notes "Monatsablesung Februar"
Create-MeterReading -MeterType "GAS" -ReadingValue 5650.80 -ReadingDate "2026-02-06T12:00:00" -Notes "Aktuelle Ablesung"
Write-Host ""

# Testdaten für Wasser (WATER)
Write-Host "4. Erstelle Testdaten für Wasser..." -ForegroundColor Yellow
Create-MeterReading -MeterType "WATER" -ReadingValue 2000.00 -ReadingDate "2026-01-01T12:00:00" -Notes "Jahresbeginn 2026"
Create-MeterReading -MeterType "WATER" -ReadingValue 2015.50 -ReadingDate "2026-01-15T12:00:00" -Notes "Mitte Januar"
Create-MeterReading -MeterType "WATER" -ReadingValue 2032.25 -ReadingDate "2026-02-01T12:00:00" -Notes "Monatsablesung Februar"
Create-MeterReading -MeterType "WATER" -ReadingValue 2045.75 -ReadingDate "2026-02-06T12:00:00" -Notes "Aktuelle Ablesung"
Write-Host ""

# Zusammenfassung
Write-Host "==================================" -ForegroundColor Cyan
Write-Host "Zusammenfassung" -ForegroundColor Cyan
Write-Host "==================================" -ForegroundColor Cyan

try {
    $allReadings = Invoke-RestMethod -Uri $baseUrl
    Write-Host "Gesamt: $($allReadings.Count) Ablesungen erstellt" -ForegroundColor Green

    $electricityReadings = Invoke-RestMethod -Uri "$baseUrl/ELECTRICITY"
    Write-Host "  - Strom: $($electricityReadings.Count) Ablesungen" -ForegroundColor White

    $gasReadings = Invoke-RestMethod -Uri "$baseUrl/GAS"
    Write-Host "  - Gas: $($gasReadings.Count) Ablesungen" -ForegroundColor White

    $waterReadings = Invoke-RestMethod -Uri "$baseUrl/WATER"
    Write-Host "  - Wasser: $($waterReadings.Count) Ablesungen" -ForegroundColor White
    Write-Host ""

    # Verbrauchsstatistiken
    Write-Host "Verbrauchsstatistiken:" -ForegroundColor Cyan

    try {
        $electricityConsumption = Invoke-RestMethod -Uri "$baseUrl/ELECTRICITY/consumption"
        Write-Host "  Strom: $($electricityConsumption.consumption) kWh (Ø $($electricityConsumption.averageDailyConsumption) kWh/Tag)" -ForegroundColor White
    } catch {}

    try {
        $gasConsumption = Invoke-RestMethod -Uri "$baseUrl/GAS/consumption"
        Write-Host "  Gas: $($gasConsumption.consumption) m³ (Ø $($gasConsumption.averageDailyConsumption) m³/Tag)" -ForegroundColor White
    } catch {}

    try {
        $waterConsumption = Invoke-RestMethod -Uri "$baseUrl/WATER/consumption"
        Write-Host "  Wasser: $($waterConsumption.consumption) m³ (Ø $($waterConsumption.averageDailyConsumption) m³/Tag)" -ForegroundColor White
    } catch {}

} catch {
    Write-Host "Fehler beim Abrufen der Zusammenfassung: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host ""
Write-Host "✓ Testdaten erfolgreich erstellt!" -ForegroundColor Green
Write-Host "Öffne http://localhost:4200 um die Daten im Frontend zu sehen." -ForegroundColor Yellow
