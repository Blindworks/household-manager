'use strict';

/**
 * Mapping helpers for Amazon smart home (phoenix) responses.
 *
 * Isolates the Amazon-specific, brittle format inside the sidecar: input is
 * the raw getSmarthomeDevices() / querySmarthomeDevices() payload, output are
 * flat objects the Spring Boot backend consumes. AQM sensors are generic
 * Alexa.RangeController instances; the instance -> sensor mapping is resolved
 * via the asset ids in the capability resources.
 */

// Asset ids from Amazon's public asset catalog identifying AQM sensors.
const SENSOR_ASSET_IDS = {
  'Alexa.AirQuality.IndoorAirQuality': 'iaq',
  'Alexa.AirQuality.ParticulateMatter': 'pm25',
  'Alexa.AirQuality.VolatileOrganicCompounds': 'voc',
  'Alexa.AirQuality.CarbonMonoxide': 'co',
  'Alexa.AirQuality.Humidity': 'humidity'
};

function collectAppliances(locationDetails) {
  if (!locationDetails || typeof locationDetails !== 'object') return [];
  const appliances = [];
  for (const location of Object.values(locationDetails)) {
    const bridges =
      (location && location.amazonBridgeDetails && location.amazonBridgeDetails.amazonBridgeDetails) || {};
    for (const bridge of Object.values(bridges)) {
      const details = (bridge && bridge.applianceDetails && bridge.applianceDetails.applianceDetails) || {};
      for (const appliance of Object.values(details)) {
        appliances.push(appliance);
      }
    }
  }
  return appliances;
}

function isAirQualityMonitor(appliance) {
  const types = Array.isArray(appliance.applianceTypes) ? appliance.applianceTypes : [];
  return types.includes('AIR_QUALITY_MONITOR');
}

function extractSensorInstances(appliance) {
  const sensors = {};
  const caps = Array.isArray(appliance.capabilities) ? appliance.capabilities : [];
  for (const cap of caps) {
    if (cap.interfaceName === 'Alexa.TemperatureSensor') {
      sensors.temperature = { namespace: 'Alexa.TemperatureSensor' };
      continue;
    }
    if (cap.interfaceName !== 'Alexa.RangeController' || !cap.instance) continue;
    const friendlyNames =
      (cap.resources && Array.isArray(cap.resources.friendlyNames)) ? cap.resources.friendlyNames : [];
    for (const fn of friendlyNames) {
      const key = SENSOR_ASSET_IDS[fn && fn.value && fn.value.assetId];
      if (key) {
        sensors[key] = { namespace: 'Alexa.RangeController', instance: cap.instance };
        break;
      }
    }
  }
  return sensors;
}

/**
 * Filters the discovery payload down to Amazon Air Quality Monitors and
 * resolves which RangeController instance belongs to which sensor.
 */
function extractAirQualityMonitors(locationDetails) {
  return collectAppliances(locationDetails)
    .filter(isAirQualityMonitor)
    .map((a) => ({
      applianceId: a.applianceId,
      entityId: a.entityId,
      friendlyName: a.friendlyName,
      manufacturerName: a.manufacturerName,
      modelName: a.modelName,
      sensors: extractSensorInstances(a)
    }))
    .filter((m) => Object.keys(m.sensors).length > 0);
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
 * Unknown devices and unreachable endpoints (listed under `errors`) are
 * silently skipped; missing individual sensors yield null.
 */
function mapDeviceStates(stateResponse, monitors) {
  const byId = new Map();
  for (const m of monitors) {
    byId.set(m.applianceId, m);
    if (m.entityId) byId.set(m.entityId, m);
  }

  const results = [];
  const deviceStates =
    (stateResponse && Array.isArray(stateResponse.deviceStates)) ? stateResponse.deviceStates : [];
  for (const ds of deviceStates) {
    const monitor = byId.get(ds.entity && ds.entity.entityId);
    if (!monitor) continue;

    const caps = parseCapabilityStates(ds);
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
    for (const [key, ref] of Object.entries(monitor.sensors)) {
      if (ref.namespace === 'Alexa.TemperatureSensor') {
        const cap = caps.find((c) => c.namespace === 'Alexa.TemperatureSensor');
        values.temperature = cap ? toCelsius(cap.value) : null;
      } else {
        const cap = caps.find(
          (c) => c.namespace === 'Alexa.RangeController' && c.instance === ref.instance
        );
        if (cap && typeof cap.value === 'number') values[key] = cap.value;
      }
    }
    results.push(values);
  }
  return results;
}

module.exports = { extractAirQualityMonitors, mapDeviceStates, SENSOR_ASSET_IDS };
