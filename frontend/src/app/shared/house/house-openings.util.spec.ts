import { EntityState } from '../../models/entity-state.model';
import { HouseOpening } from '../../models/house-opening.model';
import { EntityMap } from './house-bubbles.util';
import { floorBase, toWorldX, toWorldZ } from './house-geometry.util';
import {
  absorbedDeviceIds, defaultSwing, hingeForSide, isInnerWall, isOnWall, linkableDevices, normalizeSegment, openingIsOpen, openingShape,
  overlapsAny, sideForHinge, snapToWall, wallSides, widthUnits
} from './house-openings.util';

const seg = (x1: number, y1: number, x2: number, y2: number) => ({ x1, y1, x2, y2 });

const TERRASSENTUER = 'wohnzimmer-contact-dirigera_9defac3086c54ac';
const HAUSTUER = 'front-door';
const HAUSTUER_KONTAKT = 'binary_sensor.dirigera_8902573d04ff4a2bafa66e0261015acb_1_contact';
const TERRASSE_KONTAKT = 'binary_sensor.dirigera_9defac3086c54acaabc8d1930d87e533_1_contact';

function entities(states: Record<string, string>): EntityMap {
  return new Map(Object.entries(states).map(([entityId, state]) =>
    [entityId, { entityId, state } as EntityState]));
}

function opening(overrides: Partial<HouseOpening> = {}): HouseOpening {
  return {
    id: 1, floor: 'ground', kind: 'WINDOW', size: 'NORMAL', x1: 20, y1: 130, x2: 20, y2: 170,
    hinge: 'START', swing: 'POSITIVE', deviceId: null, ...overrides
  };
}

describe('widthUnits', () => {
  it('liefert die Breite aller fuenf Paare in Plan-Einheiten', () => {
    expect(widthUnits('WINDOW', 'SMALL')).toBe(24);
    expect(widthUnits('WINDOW', 'NORMAL')).toBe(40);
    expect(widthUnits('WINDOW', 'WIDE')).toBe(64);
    expect(widthUnits('DOOR', 'NORMAL')).toBe(36);
    expect(widthUnits('DOOR', 'TERRACE')).toBe(72);
  });

  it('wirft fuer ein Paar, das es nicht gibt', () => {
    expect(() => widthUnits('DOOR', 'SMALL')).toThrow();
  });
});

describe('normalizeSegment', () => {
  it('waagerecht: linker Endpunkt zuerst', () => {
    expect(normalizeSegment(seg(100, 90, 60, 90))).toEqual(seg(60, 90, 100, 90));
  });

  it('senkrecht: oberer Endpunkt zuerst', () => {
    expect(normalizeSegment(seg(20, 170, 20, 130))).toEqual(seg(20, 130, 20, 170));
  });
});

describe('isOnWall', () => {
  it('Aussenwand ueber die Grenze Esszimmer/Kueche ist eine Wand', () => {
    expect(isOnWall('ground', seg(20, 200, 20, 260))).toBeTrue();
  });

  it('ueber das Haus hinaus ist keine Wand', () => {
    expect(isOnWall('ground', seg(20, 200, 20, 460))).toBeFalse();
  });

  it('mitten im Raum ist keine Wand', () => {
    expect(isOnWall('ground', seg(100, 150, 140, 150))).toBeFalse();
  });
});

describe('snapToWall', () => {
  it('projiziert die Mitte auf die naechste Wand', () => {
    expect(snapToWall('ground', { x: 25, y: 150 }, 'WINDOW', 'NORMAL')).toEqual(seg(20, 130, 20, 170));
  });

  it('zu weit weg ergibt null', () => {
    expect(snapToWall('ground', { x: 60, y: 150 }, 'WINDOW', 'NORMAL')).toBeNull();
  });

  it('nahe dem Wandende wird die Strecke nach innen geschoben', () => {
    expect(snapToWall('ground', { x: 20, y: 95 }, 'WINDOW', 'NORMAL')).toEqual(seg(20, 90, 20, 130));
  });

  it('zu kurzes Wandstueck ergibt null', () => {
    expect(snapToWall('ground', { x: 291, y: 400 }, 'DOOR', 'TERRACE')).toBeNull();
  });

  it('nimmt eine passende Wand, auch wenn eine naehere Linie die Projektion nicht abdeckt (T-Stoss)', () => {
    // x = 290 ist naeher (2), endet aber bei y = 370; y = 370 liegt 8 entfernt und deckt x = 288 ab.
    expect(snapToWall('ground', { x: 288, y: 362 }, 'WINDOW', 'NORMAL')).toEqual(seg(250, 370, 290, 370));
  });
});

describe('wallSides / isInnerWall', () => {
  it('Aussenwand links: Esszimmer auf der positiven Seite', () => {
    expect(wallSides('ground', seg(20, 130, 20, 170))).toEqual({ positive: 'esszimmer', negative: null });
    expect(isInnerWall('ground', seg(20, 130, 20, 170))).toBeFalse();
  });

  it('Trennwand Esszimmer/Wohnzimmer', () => {
    expect(wallSides('ground', seg(210, 130, 210, 170))).toEqual({ positive: 'wohnzimmer', negative: 'esszimmer' });
    expect(isInnerWall('ground', seg(210, 130, 210, 170))).toBeTrue();
  });

  it('Gaeste-WC-Kante im Flur beachtet den Ausschnitt', () => {
    expect(wallSides('ground', seg(230, 370, 260, 370))).toEqual({ positive: 'gaeste_wc', negative: 'flur' });
    expect(isInnerWall('ground', seg(230, 370, 260, 370))).toBeTrue();
  });
});

describe('defaultSwing', () => {
  it('Aussenwand: zur Seite mit dem Raum', () => {
    expect(defaultSwing('ground', seg(20, 130, 20, 170))).toBe('POSITIVE');
  });

  it('Innenwand: zur Seite des in HOUSE_ROOMS zuerst stehenden Raums', () => {
    expect(defaultSwing('ground', seg(210, 130, 210, 170))).toBe('NEGATIVE');
  });
});

describe('overlapsAny', () => {
  it('gleiche Linie mit ueberlappendem Intervall', () => {
    expect(overlapsAny(seg(20, 130, 20, 170), [seg(20, 160, 20, 200)])).toBeTrue();
  });

  it('nur beruehrend ist keine Ueberlappung', () => {
    expect(overlapsAny(seg(20, 130, 20, 170), [seg(20, 170, 20, 210)])).toBeFalse();
  });

  it('parallele andere Linie ist keine Ueberlappung', () => {
    expect(overlapsAny(seg(20, 130, 20, 170), [seg(210, 130, 210, 170)])).toBeFalse();
  });

  it('erkennt Ueberlappung auch bei nicht normalisierter Eingabe', () => {
    expect(overlapsAny(seg(20, 170, 20, 130), [seg(20, 200, 20, 160)])).toBeTrue();
  });
});

describe('linkableDevices', () => {
  it('Terrassentuer, Badfenster, Balkontuer und Haustuer, ohne Gaszaehler', () => {
    const labels = linkableDevices().map(d => `${d.area}:${d.label}`);
    expect(labels).toContain('wohnzimmer:Terrassentür');
    expect(labels).toContain('bad:Fenster');
    expect(labels).toContain('schlafzimmer:Balkontür');
    expect(labels).toContain('front:Haustür');
    expect(labels).not.toContain('waschkeller:Gaszähler');
  });
});

describe('absorbedDeviceIds', () => {
  it('verknuepfter Kontakt an einer Wand entfaellt als Geraet', () => {
    expect(absorbedDeviceIds([opening({ deviceId: TERRASSENTUER, x1: 20, y1: 200, x2: 20, y2: 240 })]))
      .toEqual(new Set([TERRASSENTUER]));
  });

  it('Haustuer bleibt Geraet, Element ohne Wand und ohne Sensor zaehlen nicht', () => {
    expect(absorbedDeviceIds([
      opening({ kind: 'DOOR', deviceId: HAUSTUER, x1: 20, y1: 200, x2: 20, y2: 236 }),
      opening({ id: 2, deviceId: TERRASSENTUER, x1: 100, y1: 150, x2: 140, y2: 150 }),
      opening({ id: 3, deviceId: null, x1: 20, y1: 260, x2: 20, y2: 300 }),
      opening({ id: 4, deviceId: 'gibt-es-nicht', x1: 20, y1: 300, x2: 20, y2: 340 })
    ]).size).toBe(0);
  });
});

describe('openingIsOpen', () => {
  it('Kontakt on ist offen', () => {
    expect(openingIsOpen(opening({ deviceId: TERRASSENTUER }), entities({ [TERRASSE_KONTAKT]: 'on' }))).toBeTrue();
  });

  it('off, unavailable oder fehlende Entitaet ist geschlossen', () => {
    const o = opening({ deviceId: TERRASSENTUER });
    expect(openingIsOpen(o, entities({ [TERRASSE_KONTAKT]: 'off' }))).toBeFalse();
    expect(openingIsOpen(o, entities({ [TERRASSE_KONTAKT]: 'unavailable' }))).toBeFalse();
    expect(openingIsOpen(o, entities({}))).toBeFalse();
  });

  it('Haustuer ueber den Tuerkontakt', () => {
    const o = opening({ kind: 'DOOR', deviceId: HAUSTUER });
    expect(openingIsOpen(o, entities({ [HAUSTUER_KONTAKT]: 'on' }))).toBeTrue();
    expect(openingIsOpen(o, entities({ [HAUSTUER_KONTAKT]: 'off' }))).toBeFalse();
  });

  it('ohne oder mit unbekannter deviceId immer geschlossen', () => {
    const all = entities({ [TERRASSE_KONTAKT]: 'on', [HAUSTUER_KONTAKT]: 'on' });
    expect(openingIsOpen(opening({ deviceId: null }), all)).toBeFalse();
    expect(openingIsOpen(opening({ deviceId: 'gibt-es-nicht' }), all)).toBeFalse();
  });
});

describe('openingShape', () => {
  const base = floorBase('ground');

  it('Rahmen eines geschlossenen Fensters NORMAL', () => {
    const shape = openingShape(opening(), false);
    expect(shape.open).toBeFalse();
    expect(shape.kind).toBe('WINDOW');
    expect(shape.floor).toBe('ground');
    const ys = shape.frame.map(c => c.y);
    expect(Math.min(...ys)).toBeCloseTo(base + 0.9, 6);
    expect(Math.max(...ys)).toBeCloseTo(base + 2.1, 6);
    shape.frame.forEach(c => expect(c.x).toBeCloseTo(toWorldX(20), 6));
  });

  it('geschlossen liegt das Panel in der Wandebene', () => {
    const shape = openingShape(opening(), false);
    expect(shape.panels.length).toBe(1);
    shape.panels[0].corners.forEach(c => expect(c.x).toBeCloseTo(toWorldX(20), 6));
  });

  it('offen dreht die freie Kante um 60 Grad, der Anschlag bleibt', () => {
    const closed = openingShape(opening(), false).panels[0].corners;
    const open = openingShape(opening({ hinge: 'START', swing: 'POSITIVE' }), true).panels[0].corners;
    const [hingeBottom, freeBottom, freeTop, hingeTop] = open;
    expect(freeBottom.x).toBeCloseTo(toWorldX(20) + Math.sin(Math.PI / 3) * 1.0, 6);
    expect(freeBottom.z).toBeCloseTo(toWorldZ(130) + Math.cos(Math.PI / 3) * 1.0, 6);
    expect(freeTop.x).toBeCloseTo(freeBottom.x, 6);
    expect(freeTop.z).toBeCloseTo(freeBottom.z, 6);
    expect(hingeBottom).toEqual(closed[0]);
    expect(hingeTop).toEqual(closed[3]);
    expect(hingeBottom.z).toBeCloseTo(toWorldZ(130), 6);
  });

  it('negative Seite und Anschlag am Ende', () => {
    const [, freeBottom] = openingShape(opening({ hinge: 'END', swing: 'NEGATIVE' }), true).panels[0].corners;
    expect(freeBottom.x).toBeCloseTo(toWorldX(20) - Math.sin(Math.PI / 3), 6);
    expect(freeBottom.z).toBeCloseTo(toWorldZ(170) - Math.cos(Math.PI / 3), 6);
  });

  it('Terrassentuer: zwei Fluegel mit halber Breite, Anschlag an beiden Enden', () => {
    const terrace = opening({ kind: 'DOOR', size: 'TERRACE', x1: 230, y1: 90, x2: 302, y2: 90 });
    const shape = openingShape(terrace, false);
    expect(shape.panels.length).toBe(2);
    const [a, b] = shape.panels.map(p => p.corners);
    expect(a[0].x).toBeCloseTo(toWorldX(230), 6);
    expect(a[1].x).toBeCloseTo(toWorldX(266), 6);
    expect(b[0].x).toBeCloseTo(toWorldX(302), 6);
    expect(b[1].x).toBeCloseTo(toWorldX(266), 6);
    expect(a[0].y).toBeCloseTo(base, 6);
    expect(a[3].y).toBeCloseTo(base + 2.1, 6);
  });
});

describe('hingeForSide / sideForHinge', () => {
  it('waagerechte Aussenwand oben, Blick nach -y: links ist das kleinere x', () => {
    const top = seg(60, 90, 100, 90);
    expect(hingeForSide(top, 'POSITIVE', 'LEFT')).toBe('START');
    expect(hingeForSide(top, 'POSITIVE', 'RIGHT')).toBe('END');
    expect(hingeForSide(top, 'NEGATIVE', 'LEFT')).toBe('END');
    expect(hingeForSide(top, 'NEGATIVE', 'RIGHT')).toBe('START');
  });

  it('senkrechte linke Aussenwand, Blick nach -x: links ist das groessere y', () => {
    const left = seg(20, 130, 20, 170);
    expect(hingeForSide(left, 'POSITIVE', 'LEFT')).toBe('END');
    expect(hingeForSide(left, 'POSITIVE', 'RIGHT')).toBe('START');
    expect(hingeForSide(left, 'NEGATIVE', 'LEFT')).toBe('START');
    expect(hingeForSide(left, 'NEGATIVE', 'RIGHT')).toBe('END');
  });

  it('sideForHinge kehrt hingeForSide um', () => {
    const top = seg(60, 90, 100, 90);
    const left = seg(20, 130, 20, 170);
    expect(sideForHinge(top, 'POSITIVE', 'START')).toBe('LEFT');
    expect(sideForHinge(top, 'POSITIVE', 'END')).toBe('RIGHT');
    expect(sideForHinge(left, 'POSITIVE', 'END')).toBe('LEFT');
    expect(sideForHinge(left, 'POSITIVE', 'START')).toBe('RIGHT');
    for (const s of [top, left]) {
      for (const swing of ['POSITIVE', 'NEGATIVE'] as const) {
        for (const side of ['LEFT', 'RIGHT'] as const) {
          expect(sideForHinge(s, swing, hingeForSide(s, swing, side))).toBe(side);
        }
      }
    }
  });

  it('nicht normalisierte Eingabe ergibt dasselbe', () => {
    expect(hingeForSide(seg(100, 90, 60, 90), 'POSITIVE', 'LEFT')).toBe('START');
  });
});
