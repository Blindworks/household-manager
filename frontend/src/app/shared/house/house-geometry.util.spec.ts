import { HOUSE_ROOMS, OUTDOOR_AREAS } from './house-plan';
import {
  Vec3, areOutdoorAreasVisible, cameraForOverview, cameraForRoom, deviceAnchors, insideCutout, isFloorVisible, roomAnchor, roomBoxes,
  toPlanX, toPlanY, toWorldX, toWorldZ
} from './house-geometry.util';

describe('house-geometry.util', () => {
  const boxOf = (roomId: string) => roomBoxes().find(box => box.roomId === roomId)!;
  const bottom = (roomId: string) => {
    const box = boxOf(roomId);
    return box.center.y - box.size.y / 2;
  };
  const distance = (a: Vec3, b: Vec3) => Math.hypot(a.x - b.x, a.y - b.y, a.z - b.z);

  it('roomBoxes legt EG-Boden auf y=0, Keller auf -2.6, OG auf 2.6', () => {
    expect(bottom('wohnzimmer')).toBeCloseTo(0);
    expect(bottom('paincave')).toBeCloseTo(-2.6);
    expect(bottom('schlafzimmer')).toBeCloseTo(2.6);
    expect(boxOf('wohnzimmer').size.y).toBeCloseTo(2.6);
  });

  it('roomBoxes liefert je Rechteck eine Box', () => {
    const rectCount = HOUSE_ROOMS.reduce((sum, room) => sum + room.rects.length, 0);
    expect(roomBoxes().length).toBe(rectCount);
  });

  it('Wohnzimmer ist 5,25 x 4 m', () => {
    const box = boxOf('wohnzimmer');
    expect(box.size.x).toBeCloseTo(5.25);
    expect(box.size.z).toBeCloseTo(4);
  });

  it('Hausmitte liegt bei x=z=0', () => {
    // Wohnzimmer 210..420 / 90..250 => Mitte (315, 170) => (2.375, -2.375)
    const box = boxOf('wohnzimmer');
    expect(box.center.x).toBeCloseTo(2.375);
    expect(box.center.z).toBeCloseTo(-2.375);
  });

  it('Straßenseite liegt bei +z', () => {
    expect(roomAnchor('flur').z).toBeGreaterThan(roomAnchor('wohnzimmer').z);
  });

  it('roomAnchor liegt 1,2 m über dem Boden der Etage', () => {
    expect(roomAnchor('wohnzimmer').y).toBeCloseTo(1.2);
    expect(roomAnchor('schlafzimmer').y).toBeCloseTo(3.8);
    expect(roomAnchor('paincave').y).toBeCloseTo(-1.4);
  });

  it('isFloorVisible: nur die gewählte Etage ist sichtbar, alle anderen ausgeblendet', () => {
    expect(isFloorVisible('ground', 'ground')).toBeTrue();
    expect(isFloorVisible('upper', 'ground')).toBeFalse();
    expect(isFloorVisible('basement', 'ground')).toBeFalse();

    expect(isFloorVisible('basement', 'basement')).toBeTrue();
    expect(isFloorVisible('ground', 'basement')).toBeFalse();
    expect(isFloorVisible('upper', 'basement')).toBeFalse();
  });

  it('areOutdoorAreasVisible: Garten und Vorplatz nur im Erdgeschoss', () => {
    expect(areOutdoorAreasVisible('ground')).toBeTrue();
    expect(areOutdoorAreasVisible('upper')).toBeFalse();
    expect(areOutdoorAreasVisible('basement')).toBeFalse();
  });

  it('cameraForRoom zielt auf den Raum', () => {
    for (const room of HOUSE_ROOMS) {
      const view = cameraForRoom(room.id);
      const anchor = roomAnchor(room.id);
      expect(view.target.x).toBeCloseTo(anchor.x);
      expect(view.target.z).toBeCloseTo(anchor.z);
      const d = distance(view.position, view.target);
      expect(d).toBeGreaterThanOrEqual(4);
      expect(d).toBeLessThanOrEqual(12);
    }
  });

  it('cameraForOverview blickt von der Straßenseite schräg von oben auf die gewählte Etage', () => {
    const ground = cameraForOverview('ground');
    const upper = cameraForOverview('upper');
    expect(ground.position.z).toBeGreaterThan(ground.target.z);
    expect(ground.position.y).toBeGreaterThan(ground.target.y);
    expect(upper.target.y).toBeGreaterThan(ground.target.y);
    const d = distance(ground.position, ground.target);
    expect(d).toBeGreaterThanOrEqual(3);
    expect(d).toBeLessThanOrEqual(30);
  });

  it('toPlanX/toPlanY kehren toWorldX/toWorldZ um', () => {
    expect(toPlanX(toWorldX(315))).toBeCloseTo(315);
    expect(toPlanY(toWorldZ(170))).toBeCloseTo(170);
  });

  it('insideCutout erkennt das Gäste-WC im Flur', () => {
    expect(insideCutout('flur', 250, 400)).toBeTrue();
    expect(insideCutout('flur', 350, 300)).toBeFalse();
    expect(insideCutout('wohnzimmer', 250, 400)).toBeFalse();
  });
  describe('deviceAnchors', () => {
    const inRect = (p: Vec3, rect: { x1: number; y1: number; x2: number; y2: number }) =>
      toPlanX(p.x) >= rect.x1 && toPlanX(p.x) <= rect.x2 && toPlanY(p.z) >= rect.y1 && toPlanY(p.z) <= rect.y2;

    it('deviceAnchors liegen innerhalb des Raums und sind paarweise ≥ 0,5 m entfernt', () => {
      const anchors = deviceAnchors('buero', 9);
      const rect = HOUSE_ROOMS.find(r => r.id === 'buero')!.rects[0];
      // 0,4 m Wandabstand = 16 Skizzen-Einheiten
      const inner = { x1: rect.x1 + 16, y1: rect.y1 + 16, x2: rect.x2 - 16, y2: rect.y2 - 16 };

      expect(anchors.length).toBe(9);
      anchors.forEach(a => {
        expect(inRect(a, inner)).withContext(JSON.stringify(a)).toBeTrue();
        expect(a.y).toBeCloseTo(2.6 + 1.0);
      });
      for (let i = 0; i < anchors.length; i++) {
        for (let j = i + 1; j < anchors.length; j++) {
          expect(distance(anchors[i], anchors[j])).toBeGreaterThanOrEqual(0.5);
        }
      }
    });

    it('meidet Ausschnitte (Gäste-WC im Flur)', () => {
      deviceAnchors('flur', 4).forEach(a =>
        expect(insideCutout('flur', toPlanX(a.x), toPlanY(a.z))).withContext(JSON.stringify(a)).toBeFalse());
    });

    it('funktioniert für Außenbereiche auf Bodenhöhe', () => {
      const front = OUTDOOR_AREAS.find(a => a.id === 'front')!.rect;
      const anchors = deviceAnchors('front', 5);
      expect(anchors.length).toBe(5);
      anchors.forEach(a => {
        expect(inRect(a, front)).toBeTrue();
        expect(a.y).toBeCloseTo(1.0);
      });
      expect(deviceAnchors('garden', 2).length).toBe(2);
    });

    it('liefert bei 0 Geräten nichts', () => {
      expect(deviceAnchors('buero', 0)).toEqual([]);
    });
  });
});
