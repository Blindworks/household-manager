'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');

const { extractAirQualityMonitors, mapDeviceStates } = require('../smarthome');

function fixture(name) {
  return JSON.parse(fs.readFileSync(path.join(__dirname, 'fixtures', name), 'utf8'));
}

test('extractAirQualityMonitors finds AQM and resolves sensor instances', () => {
  const monitors = extractAirQualityMonitors(fixture('phoenix-devices.json'));

  assert.equal(monitors.length, 1, 'Echo Dot must be filtered out');
  const m = monitors[0];
  assert.equal(m.applianceId, 'AAA_SonarCloudService_G0911W0793960XYZ');
  assert.equal(m.friendlyName, 'Luftsensor Wohnzimmer');
  assert.deepEqual(m.sensors.iaq, { namespace: 'Alexa.RangeController', instance: '2' });
  assert.deepEqual(m.sensors.pm25, { namespace: 'Alexa.RangeController', instance: '4' });
  assert.deepEqual(m.sensors.voc, { namespace: 'Alexa.RangeController', instance: '5' });
  assert.deepEqual(m.sensors.co, { namespace: 'Alexa.RangeController', instance: '6' });
  assert.deepEqual(m.sensors.humidity, { namespace: 'Alexa.RangeController', instance: '3' });
  assert.deepEqual(m.sensors.temperature, { namespace: 'Alexa.TemperatureSensor' });
});

test('extractAirQualityMonitors returns [] for empty/odd input', () => {
  assert.deepEqual(extractAirQualityMonitors(null), []);
  assert.deepEqual(extractAirQualityMonitors({}), []);
});

test('mapDeviceStates maps capability states to flat values', () => {
  const monitors = extractAirQualityMonitors(fixture('phoenix-devices.json'));
  const states = mapDeviceStates(fixture('phoenix-state.json'), monitors);

  assert.equal(states.length, 1);
  const s = states[0];
  assert.equal(s.applianceId, 'AAA_SonarCloudService_G0911W0793960XYZ');
  assert.equal(s.friendlyName, 'Luftsensor Wohnzimmer');
  assert.equal(s.iaq, 52.0);
  assert.equal(s.pm25, 3.0);
  assert.equal(s.voc, 128.5);
  assert.equal(s.co, 0.0);
  assert.equal(s.humidity, 48.2);
  assert.equal(s.temperature, 22.5);
});

test('mapDeviceStates converts Fahrenheit to Celsius', () => {
  const monitors = extractAirQualityMonitors(fixture('phoenix-devices.json'));
  const state = fixture('phoenix-state.json');
  state.deviceStates[0].capabilityStates[5] =
    '{"namespace":"Alexa.TemperatureSensor","name":"temperature","value":{"value":77.0,"scale":"FAHRENHEIT"}}';

  const states = mapDeviceStates(state, monitors);
  assert.equal(states[0].temperature, 25);
});

test('mapDeviceStates tolerates missing sensors and unreachable devices', () => {
  const monitors = extractAirQualityMonitors(fixture('phoenix-devices.json'));
  const state = fixture('phoenix-state.json');
  // PM2.5-Eintrag entfernen (instance 4)
  state.deviceStates[0].capabilityStates =
    state.deviceStates[0].capabilityStates.filter((c) => !c.includes('"instance":"4"'));

  const states = mapDeviceStates(state, monitors);
  assert.equal(states[0].pm25, null);
  assert.equal(states[0].iaq, 52.0);
});
