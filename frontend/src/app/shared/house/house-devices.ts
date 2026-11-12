/**
 * Zuordnung der Geraete und Sensoren zu Raeumen und Aussenbereichen der 3D-Hausansicht
 * (Stand 2026-11-05, vom Nutzer bestaetigt). Einzige Definition der Zuordnung.
 *
 * Bewusst nicht enthalten: Geraete ohne Raum (Router, Wasserpumpe, Ladestation, Klimageraet,
 * Lichterkette, Staubsauger-Ladegeraet, Fire Tablet). Die Balkontuer ist seit 2026-11-11
 * ein Kontakt im Schlafzimmer (Fenster und Tueren an der Wand). Aussen zeigt v1 genau vier
 * Bubbles (Muelltonne, Toni, Haustuer, Garten): Haustuer-Kamera und Feinstaub entfallen.
 */
/** Art des 3D-Koerpers eines Grossgeraets (siehe `house-appliance-bodies.util.ts`); einzige Markierung. */
export type ApplianceBodyKind = 'washer' | 'dishwasher' | 'fridge' | 'tv' | 'pc' | 'printer' | 'treadmill' | 'coffee';

export type HouseDevice =
  | { kind: 'climate'; id: string; area: string; label: string; temperature?: string; humidity?: string; iaq?: string; roomSensor: boolean }
  | { kind: 'thermostat'; id: string; area: string; label: string; temperature: string; target: string; heating: string }
  | { kind: 'contact'; id: string; area: string; label: string; entityId: string }
  | { kind: 'motion'; id: string; area: string; label: string; entityId: string }
  | { kind: 'button'; id: string; area: string; label: string; entityIds: string[] }
  /** `lamp`: leuchtet in der Szene warm (sonst ein kuehler Punkt fuer „an“); einzige Definition, was Licht ist. */
  | { kind: 'switch'; id: string; area: string; label: string; entityId: string; powerEntityId?: string; lamp?: true; body?: ApplianceBodyKind }
  | { kind: 'appliance'; id: string; area: string; label: string; switchEntityId: string; powerEntityId: string; runningEntityId: string; finishedEntityId: string; body?: ApplianceBodyKind }
  /** Haustuer: Schloss und Tuerkontakt als ein Geraet. */
  | { kind: 'door'; id: string; area: string; label: string; lockEntityId: string; contactEntityId: string }
  | { kind: 'camera'; id: string; area: string; label: string; armedEntityId: string }
  | { kind: 'waste'; id: string; area: 'front'; label: string; entityId: string }
  | { kind: 'pet'; id: string; area: string; label: string; homeEntityId: string };

/** Raumfuehler mit Temperatur und Feuchte (`<prefix>_temperature|_humidity`, optional `_iaq`). */
function roomClimate(area: string, prefix: string, label = 'Raumklima', withIaq = false): HouseDevice {
  return {
    kind: 'climate', id: `${area}-climate`, area, label, roomSensor: true,
    temperature: `${prefix}_temperature`, humidity: `${prefix}_humidity`,
    ...(withIaq ? { iaq: `${prefix}_iaq` } : {})
  };
}

function thermostat(area: string, n: number, label = 'Heizung'): HouseDevice {
  return {
    kind: 'thermostat', id: `${area}-thermostat-${n}`, area, label,
    temperature: `sensor.thermostat_${n}_temperature`,
    target: `sensor.thermostat_${n}_target`,
    heating: `binary_sensor.thermostat_${n}_heating`
  };
}

function meross(area: string, label: string, ref: string): HouseDevice {
  return {
    kind: 'switch', id: `${area}-meross-${ref}`, area, label,
    entityId: `switch.meross_${ref}`, powerEntityId: `sensor.meross_${ref}_power`
  };
}

function plug(area: string, label: string, source: 'tapo' | 'kasa', ref: string, withPower = false): HouseDevice {
  return {
    kind: 'switch', id: `${area}-${source}-${ref.slice(0, 8)}`, area, label,
    entityId: `switch.${source}_${ref}`,
    ...(withPower ? { powerEntityId: `sensor.${source}_${ref}_power` } : {})
  };
}

/** Markiert ein Grossgeraet mit seiner Koerperart (siehe `body`). */
function withBody(device: HouseDevice, body: ApplianceBodyKind): HouseDevice {
  return device.kind === 'switch' || device.kind === 'appliance' ? { ...device, body } : device;
}

/** Markiert einen Schalter als Lampe (siehe `lamp`). */
function asLamp(device: HouseDevice): HouseDevice {
  return device.kind === 'switch' ? { ...device, lamp: true } : device;
}

function contact(area: string, label: string, entityId: string): HouseDevice {
  return { kind: 'contact', id: `${area}-contact-${entityId.split('.')[1].slice(0, 24)}`, area, label, entityId };
}

function motion(area: string, entityId: string): HouseDevice {
  return { kind: 'motion', id: `${area}-motion`, area, label: 'Bewegungsmelder', entityId };
}

function button(area: string, entityIds: string[]): HouseDevice {
  return { kind: 'button', id: `${area}-buttons`, area, label: 'Taster', entityIds };
}

function appliance(area: string, label: string, ref: string, helperPrefix: string): HouseDevice {
  return {
    kind: 'appliance', id: `${area}-appliance-${helperPrefix}`, area, label,
    switchEntityId: `switch.meross_${ref}`, powerEntityId: `sensor.meross_${ref}_power`,
    runningEntityId: `input_boolean.manual_${helperPrefix}_laeuft`,
    finishedEntityId: `input_boolean.manual_${helperPrefix}_fertig`
  };
}

export const HOUSE_DEVICES: readonly HouseDevice[] = [
  // Wohnzimmer
  roomClimate('wohnzimmer', 'sensor.alexa_gaj2300425330047', 'Raumklima', true),
  thermostat('wohnzimmer', 8),
  thermostat('wohnzimmer', 3, 'Heizung Fenster'),
  { kind: 'camera', id: 'wohnzimmer-camera', area: 'wohnzimmer', label: 'Kamera', armedEntityId: 'binary_sensor.blink_511671_armed' },
  withBody(meross('wohnzimmer', 'Fernseher', '2112152941687590863548e1e9817812'), 'tv'),
  asLamp(plug('wohnzimmer', 'Stehlampe', 'tapo', '802366e0c88d0dba80336f9f7363f8c4216c350a')),
  asLamp(plug('wohnzimmer', 'Stern', 'tapo', '80225ff5f331563993ff0df4adbedae5243f7caa')),
  { kind: 'switch', id: 'wohnzimmer-meross-couch', area: 'wohnzimmer', label: 'Couch-Lichterkette',
    entityId: 'switch.meross_24041984068833510d06c4e7ae039150', lamp: true },
  contact('wohnzimmer', 'Terrassentür', 'binary_sensor.dirigera_9defac3086c54acaabc8d1930d87e533_1_contact'),

  // Esszimmer
  roomClimate('esszimmer', 'sensor.dirigera_dcc8d385781d4071adf85f403b0cb997'),
  thermostat('esszimmer', 2),
  asLamp(plug('esszimmer', 'Lampe', 'kasa', '8012e05a14a26399f80f21e4fbff42ac1cdf5b50')),

  // Kueche
  roomClimate('kueche', 'sensor.dirigera_4a8044b0974e4885b2f60967af2201e1'),
  thermostat('kueche', 4),
  motion('kueche', 'binary_sensor.zigbee_motion_kueche_occupancy'),
  withBody(meross('kueche', 'Kaffeemaschine', '2112150005305590863548e1e98183b3'), 'coffee'),
  withBody(meross('kueche', 'Kühlschrank', '2112155672709990863548e1e98181eb'), 'fridge'),
  meross('kueche', 'Küchenzeile', '2205069806156351080148e1e991d1f0'),
  withBody(appliance('kueche', 'Spülmaschine', '2205060757549251080148e1e991c4f9', 'spuelmaschine'), 'dishwasher'),

  // Flur
  roomClimate('flur', 'sensor.dirigera_f2af71e53e924b0895cec17793205733'),
  thermostat('flur', 6),
  motion('flur', 'binary_sensor.zigbee_motion_flur_occupancy'),
  asLamp(plug('flur', 'Licht', 'tapo', '80232e39ed1bbdd537ab39cac50eede2216b55c7')),

  // Laufband-Raum
  roomClimate('laufband', 'sensor.zigbee_temperatur_aqara_laufband'),
  withBody(plug('laufband', 'Laufband', 'tapo', '802238b61a02b19027d329013d5647a9243f6e59', true), 'treadmill'),

  // Schlafzimmer
  roomClimate('schlafzimmer', 'sensor.alexa_gaj230043055005a'),
  thermostat('schlafzimmer', 5),
  asLamp(plug('schlafzimmer', 'Licht', 'tapo', '80238e15a36182550737e4ce654d2f2420b98b5c')),
  withBody(meross('schlafzimmer', 'Fernseher', '2112153143211390863548e1e98182b1'), 'tv'),
  contact('schlafzimmer', 'Balkontür', 'binary_sensor.dirigera_f3992ae92ca54fb6b276c2c4d5f832c9_1_contact'),

  // Buero
  roomClimate('buero', 'sensor.zigbee_temperatur_aqara_buero'),
  thermostat('buero', 7),
  motion('buero', 'binary_sensor.zigbee_motion_buero_occupancy'),
  button('buero', [
    'event.dirigera_e66219cc17d542fab6ce241b53855360_1_action',
    'event.dirigera_e66219cc17d542fab6ce241b53855360_2_action',
    'event.zigbee_button_buero_action'
  ]),
  withBody(meross('buero', 'PC', '2112159340612090863548e1e98183ba'), 'pc'),
  asLamp(meross('buero', 'Desktop Light', '24041948162696510d06c4e7ae038ae1')),
  withBody(meross('buero', 'Drucker', '24041903314491510d06c4e7ae038da2'), 'printer'),
  asLamp(plug('buero', 'Lichtstreifen', 'tapo', '8023261c2124d3bf658efedd6f6c46c020952267')),

  // Bad
  roomClimate('bad', 'sensor.zigbee_temperatur_aqara_badezimmer'),
  thermostat('bad', 1),
  contact('bad', 'Fenster', 'binary_sensor.dirigera_07ddd369cdff4593a0f5c995dfcea5f2_1_contact'),

  // Treppenhaus
  roomClimate('treppenhaus', 'sensor.dirigera_bffb9dee1dbc4670964f8a1ef8d11b5c'),
  motion('treppenhaus', 'binary_sensor.zigbee_motion_treppenhaus_occupancy'),
  button('treppenhaus', [
    'event.dirigera_4ab740b2efb543018bf563c702722938_1_action',
    'event.dirigera_4ab740b2efb543018bf563c702722938_2_action',
    'event.zigbee_schalter_treppenhaus_action'
  ]),
  asLamp(plug('treppenhaus', 'Licht', 'kasa', '80121a1cd98950323721a763bc9f1b9f1ce0f7c0')),

  // Kellereingang
  roomClimate('kellereingang', 'sensor.zigbee_temperatur_aqara_keller'),
  motion('kellereingang', 'binary_sensor.zigbee_motion_keller_occupancy'),

  // Pain Cave
  roomClimate('paincave', 'sensor.zigbee_temperatur_buero'),

  // Waschkeller
  withBody(appliance('waschkeller', 'Waschmaschine', '2112156531504590863548e1e9817420', 'waschmaschine'), 'washer'),
  contact('waschkeller', 'Gaszähler', 'binary_sensor.zigbee_sensor_gas_contact'),

  // Garten: nur die Aussentemperatur
  { kind: 'climate', id: 'garden-climate', area: 'garden', label: 'Garten', roomSensor: false,
    temperature: 'sensor.zigbee_temperatur_aqara_garten_temperature' },

  // Vor dem Haus
  { kind: 'waste', id: 'front-waste', area: 'front', label: 'Müllabfuhr', entityId: 'sensor.waste_calendar_next_collection' },
  { kind: 'pet', id: 'front-pet-toni', area: 'front', label: 'Toni', homeEntityId: 'binary_sensor.tractive_pqbzjech_home' },
  { kind: 'door', id: 'front-door', area: 'front', label: 'Haustür',
    lockEntityId: 'lock.nuki_18241688572', contactEntityId: 'binary_sensor.dirigera_8902573d04ff4a2bafa66e0261015acb_1_contact' }
];

/** Geraete eines Raums oder Aussenbereichs (`area` = Raum-Id oder Aussen-Id). */
export function devicesInArea(area: string): HouseDevice[] {
  return HOUSE_DEVICES.filter(device => device.area === area);
}
