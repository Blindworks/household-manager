'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');

const { extractAirQualityMonitors, mapDeviceStates } = require('../smarthome');

function fixture(name) {
  return JSON.parse(fs.readFileSync(path.join(__dirname, 'fixtures', name), 'utf8'));
}

test('extractAirQualityMonitors finds AQMs and uses the stable serial as applianceId', () => {
  const monitors = extractAirQualityMonitors(fixture('smarthome-entities.json'));

  assert.equal(monitors.length, 2, 'the light must be filtered out');
  const bedroom = monitors[0];
  assert.equal(bedroom.applianceId, 'GAJ0000000000001');
  assert.equal(bedroom.entityId, '11111111-1111-4111-8111-111111111111');
  assert.equal(bedroom.friendlyName, 'Luftqualitätsmonitor Schlafzimmer', 'display name is trimmed');
  assert.equal(bedroom.modelName, 'Amazon Indoor Air Quality Monitor');
});

test('extractAirQualityMonitors falls back to the entity id when no serial is present', () => {
  const entities = fixture('smarthome-entities.json');
  entities[0].providerData.dmsDeviceIdentifiers = [];

  const monitors = extractAirQualityMonitors(entities);
  assert.equal(monitors[0].applianceId, '11111111-1111-4111-8111-111111111111');
});

test('extractAirQualityMonitors returns [] for empty/odd input', () => {
  assert.deepEqual(extractAirQualityMonitors(null), []);
  assert.deepEqual(extractAirQualityMonitors({}), []);
  assert.deepEqual(extractAirQualityMonitors([]), []);
});

test('mapDeviceStates maps range instances and temperature to flat sensor values', () => {
  const monitors = extractAirQualityMonitors(fixture('smarthome-entities.json'));
  const states = mapDeviceStates(fixture('smarthome-state.json'), monitors);

  assert.equal(states.length, 2);
  const bedroom = states[0];
  assert.equal(bedroom.applianceId, 'GAJ0000000000001');
  assert.equal(bedroom.friendlyName, 'Luftqualitätsmonitor Schlafzimmer');
  assert.equal(bedroom.iaq, 80.0);      // instance 9
  assert.equal(bedroom.pm25, 1.0);      // instance 6
  assert.equal(bedroom.voc, 15.0);      // instance 5
  assert.equal(bedroom.co, 0.0);        // instance 8
  assert.equal(bedroom.humidity, 53.0); // instance 4
  assert.equal(bedroom.temperature, 24.75);
});

test('mapDeviceStates ignores non-sensor capabilities and unmapped instances', () => {
  const monitors = extractAirQualityMonitors(fixture('smarthome-entities.json'));
  const states = mapDeviceStates(fixture('smarthome-state.json'), monitors);

  // ToggleController (instance 11), EndpointHealth and RangeController instance 7
  // must not appear as sensor values; the flat object has exactly the 6 sensors.
  const bedroom = states[0];
  assert.deepEqual(Object.keys(bedroom).sort(), [
    'applianceId', 'co', 'friendlyName', 'humidity', 'iaq', 'pm25', 'temperature', 'voc'
  ]);
});

test('mapDeviceStates converts Fahrenheit to Celsius', () => {
  const monitors = extractAirQualityMonitors(fixture('smarthome-entities.json'));
  const state = fixture('smarthome-state.json');
  state.deviceStates[0].capabilityStates[1] =
    '{"namespace":"Alexa.TemperatureSensor","name":"temperature","value":{"value":77.0,"scale":"FAHRENHEIT"}}';

  const states = mapDeviceStates(state, monitors);
  assert.equal(states[0].temperature, 25);
});

test('mapDeviceStates tolerates missing sensors and unknown devices', () => {
  const monitors = extractAirQualityMonitors(fixture('smarthome-entities.json'));
  const state = fixture('smarthome-state.json');
  // Drop PM2.5 (instance 6) from the first device.
  state.deviceStates[0].capabilityStates =
    state.deviceStates[0].capabilityStates.filter((c) => !c.includes('"instance":"6"'));
  // Add a state for a device that isn't a known monitor.
  state.deviceStates.push({
    entity: { entityId: 'unknown-device', entityType: 'ENTITY' },
    capabilityStates: []
  });

  const states = mapDeviceStates(state, monitors);
  assert.equal(states.length, 2, 'unknown device is skipped');
  assert.equal(states[0].pm25, null);
  assert.equal(states[0].iaq, 80.0);
});
