'use strict';

/**
 * Mapping helpers for the Amazon Smart Air Quality Monitor.
 *
 * Isolates the Amazon-specific, brittle format inside the sidecar: input is the
 * raw getSmarthomeEntities() / querySmarthomeDevices() payload, output are flat
 * objects the Spring Boot backend consumes.
 *
 * Discovery: the monitors live in the Alexa behaviors "entities" API
 * (getSmarthomeEntities -> `/api/behaviors/entities?skillId=amzn1.ask.1p.smarthome`),
 * identified by `providerData.deviceType === 'AIR_QUALITY_MONITOR'`. The old
 * `/api/phoenix` discovery returns only `{success:true}` for these accounts.
 *
 * State: queried with querySmarthomeDevices(entityIds, 'ENTITY') against
 * `/api/phoenix/state`; each device returns `capabilityStates` (an array of JSON
 * strings) with numbered `Alexa.RangeController` instances plus a
 * `Alexa.TemperatureSensor`.
 *
 * Sensor identity: Amazon exposes the range sensors as bare numbered instances
 * with NO asset labels anywhere in the API. The instance -> sensor mapping is
 * therefore fixed for the Amazon monitor hardware; it was verified against the
 * Alexa app (identical monitors use identical instance numbers). IAQ uses a
 * 0-100 scale where HIGHER is better.
 */

// RangeController instance number -> our sensor key, for the Amazon monitor model.
const RANGE_INSTANCE_SENSORS = {
  '9': 'iaq',      // Indoor Air Quality score (0-100, higher = better)
  '6': 'pm25',     // Feinstaub / particulate matter (µg/m³)
  '5': 'voc',      // VOC-Index
  '8': 'co',       // Carbon monoxide (ppm)
  '4': 'humidity'  // Relative humidity (%)
};

function asEntityList(entities) {
  if (Array.isArray(entities)) return entities;
  if (entities && Array.isArray(entities.entities)) return entities.entities;
  return [];
}

function isAirQualityMonitor(entity) {
  return !!(entity && entity.providerData && entity.providerData.deviceType === 'AIR_QUALITY_MONITOR');
}

/** Stable hardware serial from the DMS identifiers, or null if absent. */
function stableSerial(entity) {
  const ids =
    entity.providerData && Array.isArray(entity.providerData.dmsDeviceIdentifiers)
      ? entity.providerData.dmsDeviceIdentifiers
      : [];
  return ids.length && ids[0].deviceSerialNumber ? ids[0].deviceSerialNumber : null;
}

/**
 * Filters the smart-home entities down to Amazon Air Quality Monitors.
 * `applianceId` is the stable hardware serial (falls back to the entity UUID);
 * `entityId` is the UUID required for the state query.
 */
function extractAirQualityMonitors(entities) {
  const monitors = [];
  for (const entity of asEntityList(entities)) {
    if (!isAirQualityMonitor(entity)) continue;
    if (!entity.id) continue;
    const applianceId = stableSerial(entity) || entity.id;
    monitors.push({
      applianceId,
      entityId: entity.id,
      friendlyName: (entity.displayName || '').trim(),
      modelName: entity.description || null
    });
  }
  return monitors;
}

function parseCapabilityStates(deviceState) {
  const parsed = [];
  const states = Array.isArray(deviceState.capabilityStates) ? deviceState.capabilityStates : [];
  for (const raw of states) {
    try {
      parsed.push(typeof raw === 'string' ? JSON.parse(raw) : raw);
    } catch (err) {
      // skip broken entries; a single bad state must not lose the reading
    }
  }
  return parsed;
}

function toCelsius(value) {
  if (!value || typeof value.value !== 'number') return null;
  if (value.scale === 'FAHRENHEIT') return Math.round(((value.value - 32) * 5) / 9 * 100) / 100;
  return value.value;
}

/**
 * Maps a querySmarthomeDevices() response to flat per-device sensor values.
 * Devices are matched by their entity UUID; unknown devices are skipped and
 * missing individual sensors yield null. Non-sensor capabilities
 * (ToggleController, EndpointHealth, unmapped range instances) are ignored.
 */
function mapDeviceStates(stateResponse, monitors) {
  const byEntityId = new Map();
  for (const m of monitors) byEntityId.set(m.entityId, m);

  const results = [];
  const deviceStates =
    stateResponse && Array.isArray(stateResponse.deviceStates) ? stateResponse.deviceStates : [];
  for (const ds of deviceStates) {
    const monitor = byEntityId.get(ds.entity && ds.entity.entityId);
    if (!monitor) continue;

    const values = {
      applianceId: monitor.applianceId,
      friendlyName: monitor.friendlyName,
      iaq: null,
      pm25: null,
      voc: null,
      co: null,
      temperature: null,
      humidity: null
    };
    for (const cap of parseCapabilityStates(ds)) {
      if (cap.namespace === 'Alexa.TemperatureSensor' && cap.name === 'temperature') {
        values.temperature = toCelsius(cap.value);
      } else if (cap.namespace === 'Alexa.RangeController' && cap.name === 'rangeValue') {
        const sensor = RANGE_INSTANCE_SENSORS[String(cap.instance)];
        if (sensor && typeof cap.value === 'number') values[sensor] = cap.value;
      }
    }
    results.push(values);
  }
  return results;
}

module.exports = { extractAirQualityMonitors, mapDeviceStates, RANGE_INSTANCE_SENSORS };
