import { after, before, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { createApiClient } from '../src/api-client.js';
import { toolDefinitions } from '../src/tools.js';

const tools = Object.fromEntries(toolDefinitions.map((t) => [t.name, t]));

// Stub-Backend: Routen und aufgezeichnete Requests werden je Test gesetzt/geleert.
let server;
let client;
let routes;
let requests;

function route(method, path, status, body) {
  routes.set(`${method} ${path}`, { status, body });
}

before(async () => {
  server = http.createServer((req, res) => {
    let rawBody = '';
    req.on('data', (chunk) => (rawBody += chunk));
    req.on('end', () => {
      requests.push({ method: req.method, url: req.url, body: rawBody ? JSON.parse(rawBody) : null });
      const match = routes.get(`${req.method} ${req.url}`);
      if (!match) {
        res.writeHead(404, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ message: 'not found' }));
        return;
      }
      res.writeHead(match.status, { 'Content-Type': 'application/json' });
      res.end(match.body === undefined ? '' : JSON.stringify(match.body));
    });
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  client = createApiClient(`http://127.0.0.1:${server.address().port}`);
});

beforeEach(() => {
  routes = new Map();
  requests = [];
});

after(() => server.close());

test('flow_list liefert die Flows des Backends', async () => {
  route('GET', '/v1/flows', 200, [{ id: 1, name: 'Test', enabled: true, deployed: false }]);
  const result = await tools.flow_list.handler(client, {});
  assert.equal(result.flows.length, 1);
  assert.equal(result.flows[0].name, 'Test');
});

test('flow_get parst draft- und deployedDefinition zu Objekten', async () => {
  route('GET', '/v1/flows/7', 200, {
    id: 7,
    name: 'Test',
    draftDefinition: '{"nodes":[{"id":"a"}],"wires":[]}',
    deployedDefinition: null,
  });
  const result = await tools.flow_get.handler(client, { id: 7 });
  assert.deepEqual(result.draftDefinition, { nodes: [{ id: 'a' }], wires: [] });
  assert.equal(result.deployedDefinition, null);
});

test('flow_create schickt den Import-Wrapper mit schemaVersion 1 und gibt den Deploy-Hinweis zurück', async () => {
  route('POST', '/v1/flows/import', 200, {
    id: 9,
    name: 'Neu',
    enabled: false,
    draftDefinition: '{"nodes":[],"wires":[]}',
  });
  const definition = { nodes: [], wires: [] };
  const result = await tools.flow_create.handler(client, { name: 'Neu', description: 'Desc', definition });

  assert.deepEqual(requests[0].body, { schemaVersion: 1, name: 'Neu', description: 'Desc', definition });
  assert.equal(result.id, 9);
  assert.match(result.hint, /flow_deploy/);
});

test('flow_update serialisiert die Definition als draftDefinition-String', async () => {
  route('PUT', '/v1/flows/3', 200, { id: 3, name: 'Umbenannt', draftDefinition: '{"nodes":[],"wires":[]}' });
  const definition = { nodes: [], wires: [] };
  await tools.flow_update.handler(client, { id: 3, name: 'Umbenannt', definition });

  assert.equal(requests[0].body.name, 'Umbenannt');
  assert.equal(requests[0].body.draftDefinition, JSON.stringify(definition));
});

test('flow_deploy reicht ein 400-ValidationResult als fachliches Ergebnis durch', async () => {
  route('POST', '/v1/flows/5/deploy', 400, { valid: false, errors: ['entityId fehlt'] });
  const result = await tools.flow_deploy.handler(client, { id: 5 });
  assert.equal(result.valid, false);
  assert.deepEqual(result.errors, ['entityId fehlt']);
});

test('flow_deploy liefert das ValidationResult bei Erfolg', async () => {
  route('POST', '/v1/flows/5/deploy', 200, { valid: true, errors: [], warnings: [] });
  const result = await tools.flow_deploy.handler(client, { id: 5 });
  assert.equal(result.valid, true);
});

test('flow_set_enabled trifft den enable- bzw. disable-Endpoint', async () => {
  route('POST', '/v1/flows/4/enable', 200, { id: 4, enabled: true });
  route('POST', '/v1/flows/4/disable', 200, { id: 4, enabled: false });
  const on = await tools.flow_set_enabled.handler(client, { id: 4, enabled: true });
  const off = await tools.flow_set_enabled.handler(client, { id: 4, enabled: false });
  assert.equal(on.enabled, true);
  assert.equal(off.enabled, false);
});

test('flow_list_entities filtert per Query und trimmt die Antwortfelder', async () => {
  route('GET', '/v1/entities?domain=binary_sensor', 200, [
    {
      entityId: 'binary_sensor.flur',
      displayName: 'Flur',
      domain: 'binary_sensor',
      source: 'ZIGBEE',
      state: 'on',
      lastChanged: '2026-07-20T10:00:00',
      attributes: { geheim: true },
    },
  ]);
  const result = await tools.flow_list_entities.handler(client, { domain: 'binary_sensor' });
  assert.equal(result.entities[0].entityId, 'binary_sensor.flur');
  assert.equal(result.entities[0].attributes, undefined);
});

test('flow_list_switch_devices mappt SmartDevices auf deviceId', async () => {
  route('GET', '/devices', 200, [
    { id: 12, deviceName: 'Flurlicht', deviceType: 'TAPO', model: 'P110', isOnline: true, isPoweredOn: false },
  ]);
  const result = await tools.flow_list_switch_devices.handler(client, {});
  assert.equal(result.devices[0].deviceId, 12);
  assert.equal(result.devices[0].deviceName, 'Flurlicht');
});

test('nicht erreichbares Backend ergibt eine lesbare Fehlermeldung', async () => {
  const deadClient = createApiClient('http://127.0.0.1:1');
  await assert.rejects(
    () => tools.flow_list.handler(deadClient, {}),
    (error) => /nicht erreichbar/.test(error.message)
  );
});
