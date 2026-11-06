import { HOUSE_DEVICES, HouseDevice, devicesInArea } from './house-devices';
import { deviceAnchors, floorBase, toWorldX, toWorldZ } from './house-geometry.util';
import {
  BUBBLE_HEIGHT_M, DevicePositions, deviceAnchor, deviceBubbleAnchor, deviceHeightM, isPointInArea,
  samePositions, toPositionMap, validPlacement
} from './house-placement.util';

function find(predicate: (d: HouseDevice) => boolean): HouseDevice {
  const found = HOUSE_DEVICES.find(predicate);
  if (!found) {
    throw new Error('Geraet nicht gefunden');
  }
  return found;
}

const NONE: DevicePositions = new Map();

describe('deviceHeightM', () => {
  it('Lampe 2,3 m', () => {
    expect(deviceHeightM(find(d => d.kind === 'switch' && d.area === 'flur' && d.label === 'Licht'))).toBe(2.3);
  });

  it('Thermostat, Kontakt, Haustuer, Kamera, Bewegungsmelder, Taster 1,2 m', () => {
    for (const kind of ['thermostat', 'contact', 'door', 'camera', 'motion', 'button'] as const) {
      expect(deviceHeightM(find(d => d.kind === kind))).withContext(kind).toBe(1.2);
    }
  });

  it('alles andere 0,5 m', () => {
    expect(deviceHeightM(find(d => d.kind === 'climate'))).toBe(0.5);
    expect(deviceHeightM(find(d => d.kind === 'switch' && d.area === 'wohnzimmer' && d.label === 'Fernseher'))).toBe(0.5);
    expect(deviceHeightM(find(d => d.kind === 'appliance'))).toBe(0.5);
    expect(deviceHeightM(find(d => d.kind === 'waste'))).toBe(0.5);
    expect(deviceHeightM(find(d => d.kind === 'pet'))).toBe(0.5);
  });
});

describe('isPointInArea', () => {
  it('Rand ist inklusive', () => {
    expect(isPointInArea('bad', { x: 20, y: 340 })).toBeTrue();
    expect(isPointInArea('bad', { x: 250, y: 440 })).toBeTrue();
  });

  it('ausserhalb ist false', () => {
    expect(isPointInArea('bad', { x: 251, y: 400 })).toBeFalse();
  });

  it('Ausschnitt gehoert nicht zum Raum, sondern zum eigenen Raum', () => {
    expect(isPointInArea('flur', { x: 250, y: 400 })).toBeFalse();
    expect(isPointInArea('gaeste_wc', { x: 250, y: 400 })).toBeTrue();
  });

  it('ueberlappende Aussenbereiche zaehlen getrennt', () => {
    expect(isPointInArea('garden', { x: 300, y: 87 })).toBeFalse();
    expect(isPointInArea('terrace', { x: 300, y: 87 })).toBeTrue();
  });

  it('unbekannter Bereich: false', () => {
    expect(isPointInArea('unbekannt', { x: 100, y: 100 })).toBeFalse();
  });
});

describe('validPlacement', () => {
  const kuehlschrank = find(d => d.area === 'kueche' && d.label.includes('Kühlschrank'));

  it('gueltiger Punkt kommt zurueck', () => {
    const point = { x: 100, y: 300 };
    expect(validPlacement(kuehlschrank, new Map([[kuehlschrank.id, point]]))).toEqual(point);
  });

  it('Punkt im falschen Raum: null', () => {
    expect(validPlacement(kuehlschrank, new Map([[kuehlschrank.id, { x: 100, y: 150 }]]))).toBeNull();
  });

  it('ohne Eintrag: null', () => {
    expect(validPlacement(kuehlschrank, NONE)).toBeNull();
  });
});

describe('deviceAnchor', () => {
  it('platziert: x/z aus dem Punkt, y = Etagenboden + Hoehe nach Art', () => {
    const licht = find(d => d.kind === 'switch' && d.area === 'schlafzimmer' && !!d.lamp);
    const anchor = deviceAnchor(licht.id, new Map([[licht.id, { x: 100, y: 150 }]]))!;
    expect(anchor.x).toBeCloseTo(toWorldX(100), 6);
    expect(anchor.z).toBeCloseTo(toWorldZ(150), 6);
    expect(anchor.y).toBeCloseTo(floorBase('upper') + 2.3, 6);
  });

  it('gemischt: Ungeplatzte nehmen die Rasterplaetze in Reihenfolge von devicesInArea', () => {
    const devices = devicesInArea('bad');
    expect(devices.length).toBeGreaterThanOrEqual(3);
    const placed = devices[1];
    const positions = new Map([[placed.id, { x: 100, y: 400 }]]);
    const grid = deviceAnchors('bad', devices.length - 1);
    const unplaced = devices.filter(d => d !== placed);
    unplaced.forEach((device, i) => {
      const anchor = deviceAnchor(device.id, positions)!;
      expect(anchor.x).withContext(device.id).toBeCloseTo(grid[i].x, 6);
      expect(anchor.z).withContext(device.id).toBeCloseTo(grid[i].z, 6);
    });
    expect(deviceAnchor(placed.id, positions)!.x).toBeCloseTo(toWorldX(100), 6);
  });

  it('ohne Positionen: Raster des Bereichs', () => {
    for (const area of ['wohnzimmer', 'kueche', 'front']) {
      const devices = devicesInArea(area);
      const grid = deviceAnchors(area, devices.length);
      devices.forEach((device, i) => {
        const anchor = deviceAnchor(device.id, NONE)!;
        expect(anchor.x).withContext(device.id).toBeCloseTo(grid[i].x, 6);
        expect(anchor.z).withContext(device.id).toBeCloseTo(grid[i].z, 6);
      });
    }
  });

  it('unbekannte Id: null', () => {
    expect(deviceAnchor('gibt-es-nicht', NONE)).toBeNull();
    expect(deviceBubbleAnchor('gibt-es-nicht', NONE)).toBeNull();
  });
});

describe('deviceBubbleAnchor', () => {
  it('gleiches x/z, y = Etagenboden + 1,2 m', () => {
    const licht = find(d => d.kind === 'switch' && d.area === 'schlafzimmer' && !!d.lamp);
    const positions = new Map([[licht.id, { x: 100, y: 150 }]]);
    const anchor = deviceAnchor(licht.id, positions)!;
    const bubble = deviceBubbleAnchor(licht.id, positions)!;
    expect(bubble.x).toBe(anchor.x);
    expect(bubble.z).toBe(anchor.z);
    expect(bubble.y).toBeCloseTo(floorBase('upper') + BUBBLE_HEIGHT_M, 6);
  });
});

describe('samePositions / toPositionMap', () => {
  it('gleiche Eintraege in anderer Reihenfolge sind gleich', () => {
    const a = new Map([['x', { x: 1, y: 2 }], ['y', { x: 3, y: 4 }]]);
    const b = new Map([['y', { x: 3, y: 4 }], ['x', { x: 1, y: 2 }]]);
    expect(samePositions(a, b)).toBeTrue();
  });

  it('abweichender Wert ist ungleich', () => {
    expect(samePositions(new Map([['x', { x: 1, y: 2 }]]), new Map([['x', { x: 1, y: 3 }]]))).toBeFalse();
  });

  it('andere Groesse ist ungleich', () => {
    expect(samePositions(new Map([['x', { x: 1, y: 2 }]]), NONE)).toBeFalse();
  });

  it('toPositionMap bildet die Liste ab', () => {
    const map = toPositionMap([{ deviceId: 'a', x: 1, y: 2 }, { deviceId: 'b', x: 3, y: 4 }]);
    expect(map.get('a')).toEqual({ x: 1, y: 2 });
    expect(map.get('b')).toEqual({ x: 3, y: 4 });
    expect(map.size).toBe(2);
  });
});
