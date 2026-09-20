---
name: charging-price-per-kwh
description: EnBW price-per-kWh parsed from tariffDescription for charging favorites; area-only stations have no price
metadata:
  type: project
---

Branch `feature/charging-price` (2026-09-20), commit 257a4261, not yet merged to main.

- Price comes only from the EnBW **detail** response (`chargePoints[].connectors[].tariffInfo.tariffDescription`,
  e.g. "Preis je DC kWh: Ab 0,34 €"). The area/umkreis response only has `priceGroup`, no price — so
  area-only (non-favorite) stations always have `pricePerKwh: null`.
- `EnbwChargingClient.parsePricePerKwh(String)` is a static regex parser (`(\d+)[,.](\d{2})\s*€`), package-visible
  for tests. `ChargePoint.pricePerKwh` picks the connector with the highest `maxPowerInKw` that has a parsable
  price, falling back to the first parsable one.
- `ChargingDtos.StationResponse.pricePerKwh` (favorites only) = minimum over the station's charge points with a
  price; `ChargingQueryService.areaRow` always passes `null`.
- `ChargingEntityMapper` adds attribute `pricePerKwh` (min over points) only when non-null — same pattern as
  `maxPowerKw`.
- All 12 charge points in the real fixture `enbw-station.json` share the identical tariffDescription (0,34 €) —
  useful as a quick fixture-level regression check.
- Never verified against the live EnBW API — only against the recorded fixture and hand-built JSON in tests.

See also [[project.md]] general charging module notes if one exists.
