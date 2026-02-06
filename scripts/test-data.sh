#!/bin/bash
# Bash Script zum Erstellen von Testdaten für Household Manager
# Verwendung: ./scripts/test-data.sh

BASE_URL="http://localhost:8080/api/v1/meter-readings"

# Farben für Output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

echo -e "${CYAN}==================================${NC}"
echo -e "${CYAN}Household Manager - Testdaten erstellen${NC}"
echo -e "${CYAN}==================================${NC}"
echo ""

# Funktion zum Erstellen einer Ablesung
create_reading() {
    local meter_type=$1
    local reading_value=$2
    local reading_date=$3
    local notes=$4

    response=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL" \
        -H "Content-Type: application/json" \
        -d "{
            \"meterType\": \"$meter_type\",
            \"readingValue\": $reading_value,
            \"readingDate\": \"$reading_date\",
            \"notes\": \"$notes\"
        }")

    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')

    if [ "$http_code" -eq 201 ]; then
        id=$(echo "$body" | grep -o '"id":[0-9]*' | grep -o '[0-9]*')
        echo -e "${GREEN}✓${NC} Ablesung erstellt: $meter_type = $reading_value (ID: $id)"
    else
        echo -e "${RED}✗${NC} Fehler bei $meter_type = $reading_value : HTTP $http_code"
    fi
}

# Health Check
echo -e "${YELLOW}1. Health Check...${NC}"
health_response=$(curl -s -w "\n%{http_code}" http://localhost:8080/api/management/health)
health_code=$(echo "$health_response" | tail -n1)

if [ "$health_code" -eq 200 ]; then
    echo -e "${GREEN}✓${NC} Backend ist erreichbar"
    echo ""
else
    echo -e "${RED}✗${NC} Backend ist nicht erreichbar!"
    echo -e "${YELLOW}Bitte starten Sie das Backend mit: cd backend && mvn spring-boot:run${NC}"
    exit 1
fi

# Testdaten für Strom (ELECTRICITY)
echo -e "${YELLOW}2. Erstelle Testdaten für Strom...${NC}"
create_reading "ELECTRICITY" 10000.00 "2026-01-01T12:00:00" "Jahresbeginn 2026"
create_reading "ELECTRICITY" 10150.50 "2026-01-15T12:00:00" "Mitte Januar"
create_reading "ELECTRICITY" 10300.75 "2026-02-01T12:00:00" "Monatsablesung Februar"
create_reading "ELECTRICITY" 10450.25 "2026-02-06T12:00:00" "Aktuelle Ablesung"
echo ""

# Testdaten für Gas (GAS)
echo -e "${YELLOW}3. Erstelle Testdaten für Gas...${NC}"
create_reading "GAS" 5000.00 "2026-01-01T12:00:00" "Jahresbeginn 2026"
create_reading "GAS" 5250.50 "2026-01-15T12:00:00" "Mitte Januar - Heizperiode"
create_reading "GAS" 5520.30 "2026-02-01T12:00:00" "Monatsablesung Februar"
create_reading "GAS" 5650.80 "2026-02-06T12:00:00" "Aktuelle Ablesung"
echo ""

# Testdaten für Wasser (WATER)
echo -e "${YELLOW}4. Erstelle Testdaten für Wasser...${NC}"
create_reading "WATER" 2000.00 "2026-01-01T12:00:00" "Jahresbeginn 2026"
create_reading "WATER" 2015.50 "2026-01-15T12:00:00" "Mitte Januar"
create_reading "WATER" 2032.25 "2026-02-01T12:00:00" "Monatsablesung Februar"
create_reading "WATER" 2045.75 "2026-02-06T12:00:00" "Aktuelle Ablesung"
echo ""

# Zusammenfassung
echo -e "${CYAN}==================================${NC}"
echo -e "${CYAN}Zusammenfassung${NC}"
echo -e "${CYAN}==================================${NC}"

all_readings=$(curl -s "$BASE_URL")
count=$(echo "$all_readings" | grep -o '"id":' | wc -l)
echo -e "${GREEN}Gesamt: $count Ablesungen erstellt${NC}"

electricity_count=$(curl -s "$BASE_URL/ELECTRICITY" | grep -o '"id":' | wc -l)
echo "  - Strom: $electricity_count Ablesungen"

gas_count=$(curl -s "$BASE_URL/GAS" | grep -o '"id":' | wc -l)
echo "  - Gas: $gas_count Ablesungen"

water_count=$(curl -s "$BASE_URL/WATER" | grep -o '"id":' | wc -l)
echo "  - Wasser: $water_count Ablesungen"
echo ""

# Verbrauchsstatistiken
echo -e "${CYAN}Verbrauchsstatistiken:${NC}"

electricity_consumption=$(curl -s "$BASE_URL/ELECTRICITY/consumption" 2>/dev/null)
if [ $? -eq 0 ] && [ ! -z "$electricity_consumption" ]; then
    consumption=$(echo "$electricity_consumption" | grep -o '"consumption":[0-9.]*' | grep -o '[0-9.]*')
    avg=$(echo "$electricity_consumption" | grep -o '"averageDailyConsumption":[0-9.]*' | grep -o '[0-9.]*')
    echo "  Strom: $consumption kWh (Ø $avg kWh/Tag)"
fi

gas_consumption=$(curl -s "$BASE_URL/GAS/consumption" 2>/dev/null)
if [ $? -eq 0 ] && [ ! -z "$gas_consumption" ]; then
    consumption=$(echo "$gas_consumption" | grep -o '"consumption":[0-9.]*' | grep -o '[0-9.]*')
    avg=$(echo "$gas_consumption" | grep -o '"averageDailyConsumption":[0-9.]*' | grep -o '[0-9.]*')
    echo "  Gas: $consumption m³ (Ø $avg m³/Tag)"
fi

water_consumption=$(curl -s "$BASE_URL/WATER/consumption" 2>/dev/null)
if [ $? -eq 0 ] && [ ! -z "$water_consumption" ]; then
    consumption=$(echo "$water_consumption" | grep -o '"consumption":[0-9.]*' | grep -o '[0-9.]*')
    avg=$(echo "$water_consumption" | grep -o '"averageDailyConsumption":[0-9.]*' | grep -o '[0-9.]*')
    echo "  Wasser: $consumption m³ (Ø $avg m³/Tag)"
fi

echo ""
echo -e "${GREEN}✓ Testdaten erfolgreich erstellt!${NC}"
echo -e "${YELLOW}Öffne http://localhost:4200 um die Daten im Frontend zu sehen.${NC}"
