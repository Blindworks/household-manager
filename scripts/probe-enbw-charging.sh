#!/usr/bin/env bash
# Zeichnet die Antworten des inoffiziellen EnBW-Backends als Test-Fixtures auf.
# Aufruf:  ENBW_KEY=<Ocp-Apim-Subscription-Key> ./scripts/probe-enbw-charging.sh <lat> <lon>
# Der Key steht oeffentlich in der Kartenseite
#   https://www.enbw.com/elektromobilitaet/produkte/mobilityplus-app/ladestation-finden/map
# (im Quelltext: initMap({ ... apimSubscriptionKey: "..." })).
#
# Befund 2026-09-19: Der Server GRUPPIERT Stationen in grossen Kaesten selbst (grouped=true,
# stationId=null, dafuer ein viewPort) - unabhaengig von grouping=false. Erst Kaesten von
# etwa 0,01 Grad liefern zuverlaessig Einzelstationen; der Client bohrt gruppierte Eintraege
# ueber ihren viewPort nach. minPower=<kW> filtert serverseitig und spart die meisten Aufrufe.
set -euo pipefail
LAT="${1:?lat}"; LON="${2:?lon}"
BASE="https://api.emp.emob-enbw.com/emobility-public-api/api/v1"
OUT="backend/src/test/resources/charging"
mkdir -p "$OUT"
H=(-H "Origin: https://www.enbw.com" -H "Referer: https://www.enbw.com/" -H "Ocp-Apim-Subscription-Key: ${ENBW_KEY:?ENBW_KEY}")
# ~2 km Kasten: enthaelt Einzelstationen UND (in dichter Gegend) gruppierte Eintraege
FROM_LAT=$(awk "BEGIN{print $LAT-0.01}"); TO_LAT=$(awk "BEGIN{print $LAT+0.01}")
FROM_LON=$(awk "BEGIN{print $LON-0.015}"); TO_LON=$(awk "BEGIN{print $LON+0.015}")
curl -sS --http1.1 "${H[@]}" -o "$OUT/enbw-area.json" -w "area: HTTP %{http_code}\n" \
  "$BASE/chargestations?fromLat=$FROM_LAT&toLat=$TO_LAT&fromLon=$FROM_LON&toLon=$TO_LON&grouping=false&groupingDivisor=15&minPower=50"
STATION_ID=$(python -c "import json,io;d=json.load(io.open('$OUT/enbw-area.json',encoding='utf-8'));print([s for s in d if not s.get('grouped')][0]['stationId'])")
curl -sS --http1.1 "${H[@]}" -o "$OUT/enbw-station.json" -w "station $STATION_ID: HTTP %{http_code}\n" \
  "$BASE/chargestations/$STATION_ID"
echo "Fixtures unter $OUT"
