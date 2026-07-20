import { z } from 'zod';
import { ApiError } from './api-client.js';

const DEFINITION_SHAPE = z
  .object({
    nodes: z.array(z.record(z.unknown())).describe('Nodes des Graphen (id, type, name?, position?, config)'),
    wires: z.array(z.record(z.unknown())).describe('Verbindungen: { from: { node, port }, to: { node } }'),
  })
  .passthrough();

const DEFINITION_HINT =
  'Format der definition: { "nodes": [ { "id", "type", "name"?, "position"?, "config" } ], ' +
  '"wires": [ { "from": { "node", "port" }, "to": { "node" } } ] }. ' +
  'Verfügbare Node-Typen inkl. Pflichtfeldern liefert flow_node_types; ' +
  'Referenzwerte liefern flow_list_entities (entityId), flow_list_switch_devices (deviceId) ' +
  'und flow_list_alexa_devices (deviceSerials). Vollständige Format-Doku: docs/flows/flow-import-format.md.';

function parseDefinition(json) {
  if (json == null) {
    return null;
  }
  try {
    return JSON.parse(json);
  } catch {
    return json;
  }
}

function toFlowDetail(flow) {
  return {
    ...flow,
    draftDefinition: parseDefinition(flow.draftDefinition),
    deployedDefinition: parseDefinition(flow.deployedDefinition),
  };
}

const READ_ONLY = { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false };
const WRITE = { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false };

export const toolDefinitions = [
  {
    name: 'flow_list',
    title: 'Flows auflisten',
    description:
      'Listet alle Flows der Flow-Engine mit id, name, description, enabled, deployed, deployedAt, updatedAt.',
    inputSchema: {},
    annotations: READ_ONLY,
    handler: async (client) => ({ flows: await client.get('/v1/flows') }),
  },
  {
    name: 'flow_get',
    title: 'Flow lesen',
    description:
      'Liefert einen Flow im Detail. draftDefinition (Arbeitsstand) und deployedDefinition (aktiver Stand) ' +
      'werden als geparste Objekte zurückgegeben. ' + DEFINITION_HINT,
    inputSchema: { id: z.number().int().describe('Flow-ID') },
    annotations: READ_ONLY,
    handler: async (client, { id }) => toFlowDetail(await client.get(`/v1/flows/${id}`)),
  },
  {
    name: 'flow_create',
    title: 'Flow anlegen',
    description:
      'Legt einen neuen Flow aus einer vollständigen Definition an. Der Flow ist danach DEAKTIVIERT und ' +
      'NICHT deployt — scharf wird er erst durch flow_deploy (validiert) und flow_set_enabled. ' +
      DEFINITION_HINT,
    inputSchema: {
      name: z.string().min(1).describe('Anzeigename des Flows'),
      description: z.string().optional().describe('Optionale Freitextbeschreibung'),
      definition: DEFINITION_SHAPE.describe('Der Flow-Graph aus nodes und wires'),
    },
    annotations: WRITE,
    handler: async (client, { name, description, definition }) => {
      const flow = await client.post('/v1/flows/import', {
        schemaVersion: 1,
        name,
        description,
        definition,
      });
      return {
        ...toFlowDetail(flow),
        hint: 'Flow ist deaktiviert und nicht deployt. Nächste Schritte: flow_deploy (validiert), dann flow_set_enabled.',
      };
    },
  },
  {
    name: 'flow_update',
    title: 'Flow ändern',
    description:
      'Aktualisiert Name, Beschreibung und/oder Draft-Definition eines Flows. Nur übergebene Felder werden ' +
      'geändert. Eine geänderte Definition landet im Draft und wird erst durch flow_deploy aktiv. ' +
      DEFINITION_HINT,
    inputSchema: {
      id: z.number().int().describe('Flow-ID'),
      name: z.string().min(1).optional().describe('Neuer Anzeigename'),
      description: z.string().optional().describe('Neue Beschreibung'),
      definition: DEFINITION_SHAPE.optional().describe('Neue Draft-Definition (nodes + wires)'),
    },
    annotations: WRITE,
    handler: async (client, { id, name, description, definition }) =>
      toFlowDetail(
        await client.put(`/v1/flows/${id}`, {
          name,
          description,
          draftDefinition: definition === undefined ? undefined : JSON.stringify(definition),
        })
      ),
  },
  {
    name: 'flow_deploy',
    title: 'Flow deployen',
    description:
      'Validiert die Draft-Definition und schaltet sie bei Erfolg aktiv (draft → deployed). Bei ' +
      'Validierungsfehlern kommt { valid: false, ... } mit den Fehlermeldungen zurück — dann die Definition ' +
      'per flow_update korrigieren und erneut deployen. Der bisherige Deploy-Stand bleibt bei Fehlern unangetastet.',
    inputSchema: { id: z.number().int().describe('Flow-ID') },
    annotations: WRITE,
    handler: async (client, { id }) => {
      try {
        return await client.post(`/v1/flows/${id}/deploy`);
      } catch (error) {
        if (error instanceof ApiError && error.status === 400 && typeof error.body === 'object') {
          return error.body; // ValidationResult: fachliches Ergebnis, kein Toolfehler
        }
        throw error;
      }
    },
  },
  {
    name: 'flow_set_enabled',
    title: 'Flow aktivieren/deaktivieren',
    description:
      'Aktiviert (enabled=true) oder deaktiviert (enabled=false) einen Flow. Aktivieren registriert die ' +
      'deployte Definition in der Engine; ohne Deploy läuft auch ein aktivierter Flow nicht.',
    inputSchema: {
      id: z.number().int().describe('Flow-ID'),
      enabled: z.boolean().describe('true = aktivieren, false = deaktivieren'),
    },
    annotations: { ...WRITE, idempotentHint: true },
    handler: async (client, { id, enabled }) => client.post(`/v1/flows/${id}/${enabled ? 'enable' : 'disable'}`),
  },
  {
    name: 'flow_delete',
    title: 'Flow löschen',
    description: 'Löscht einen Flow endgültig und entfernt ihn aus der Engine. Nicht rückgängig machbar.',
    inputSchema: { id: z.number().int().describe('Flow-ID') },
    annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: true, openWorldHint: false },
    handler: async (client, { id }) => {
      await client.delete(`/v1/flows/${id}`);
      return { deleted: true, id };
    },
  },
  {
    name: 'flow_node_types',
    title: 'Node-Katalog',
    description:
      'Liefert alle verfügbaren Node-Typen der Flow-Engine mit Konfig-Feldern (key, label, type, required, ' +
      'options), Ausgangs-Ports und trigger-Flag. Vor dem Bauen einer Definition aufrufen.',
    inputSchema: {},
    annotations: READ_ONLY,
    handler: async (client) => ({ nodeTypes: await client.get('/v1/flows/node-types') }),
  },
  {
    name: 'flow_inject',
    title: 'Trigger testweise auslösen',
    description:
      'Feuert eine deployte Trigger-Node von Hand (Test-Inject), optional mit Payload-Werten für die ' +
      'Message (z. B. entityId, newState). Ergebnisse lassen sich über flow_debug_entries an einer ' +
      'Debug-Node beobachten.',
    inputSchema: {
      flowId: z.number().int().describe('Flow-ID'),
      nodeId: z.string().min(1).describe('ID der Trigger-Node innerhalb des Flows'),
      payload: z.record(z.unknown()).optional().describe('Optionale Payload-Werte der Message'),
    },
    annotations: WRITE,
    handler: async (client, { flowId, nodeId, payload }) => {
      await client.post(`/v1/flows/${flowId}/nodes/${encodeURIComponent(nodeId)}/inject`, { payload });
      return { injected: true, flowId, nodeId };
    },
  },
  {
    name: 'flow_debug_entries',
    title: 'Debug-Einträge lesen',
    description:
      'Liest den Debug-Puffer einer Debug-Node eines Flows (jüngste Messages, die die Node erreicht haben).',
    inputSchema: {
      flowId: z.number().int().describe('Flow-ID'),
      nodeId: z.string().min(1).describe('ID der Debug-Node innerhalb des Flows'),
    },
    annotations: READ_ONLY,
    handler: async (client, { flowId, nodeId }) => ({
      entries: await client.get(`/v1/flows/${flowId}/nodes/${encodeURIComponent(nodeId)}/debug`),
    }),
  },
  {
    name: 'flow_list_entities',
    title: 'Entitäten auflisten',
    description:
      'Listet Entitäten der Entity-State-Schicht als Referenz für entityId in Trigger- und Condition-Nodes. ' +
      'Optional nach domain (z. B. binary_sensor, sensor, switch, light, input_boolean, event) und source ' +
      '(z. B. ZIGBEE, TASMOTA, ALEXA, TABLET, MANUAL) filtern.',
    inputSchema: {
      domain: z.string().optional().describe('Filter auf Entity-Domain, z. B. binary_sensor'),
      source: z.string().optional().describe('Filter auf Quelle, z. B. ZIGBEE'),
    },
    annotations: READ_ONLY,
    handler: async (client, { domain, source }) => {
      const query = new URLSearchParams();
      if (domain) query.set('domain', domain);
      if (source) query.set('source', source);
      const suffix = query.size > 0 ? `?${query}` : '';
      const entities = await client.get(`/v1/entities${suffix}`);
      return {
        entities: entities.map((e) => ({
          entityId: e.entityId,
          displayName: e.displayName,
          domain: e.domain,
          source: e.source,
          state: e.state,
          lastChanged: e.lastChanged,
        })),
      };
    },
  },
  {
    name: 'flow_list_switch_devices',
    title: 'Schaltbare Smart-Geräte auflisten',
    description:
      'Listet SmartDevices (Kasa/Tapo/Meross) als Referenz für die numerische deviceId der switch-device-Node.',
    inputSchema: {},
    annotations: READ_ONLY,
    handler: async (client) => {
      const devices = await client.get('/devices');
      return {
        devices: devices.map((d) => ({
          deviceId: d.id,
          deviceName: d.deviceName,
          deviceType: d.deviceType,
          model: d.model,
          isOnline: d.isOnline,
          isPoweredOn: d.isPoweredOn,
          capabilities: d.capabilities,
        })),
      };
    },
  },
  {
    name: 'flow_list_alexa_devices',
    title: 'Alexa-Geräte auflisten',
    description:
      'Listet Alexa-Geräte (serialNumber, name, deviceType, ttsCapable) als Referenz für deviceSerials ' +
      'der alexa-announce-Node. Nur ttsCapable-Geräte können Ansagen ausgeben.',
    inputSchema: {},
    annotations: READ_ONLY,
    handler: async (client) => ({ devices: await client.get('/v1/alexa/devices') }),
  },
];
