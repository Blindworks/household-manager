import { HOUSE_BOUNDS, HOUSE_ROOMS, OUTDOOR_AREAS, PlanRect, areaFloor, outdoorAreasOn, roomById } from './house-plan';

function intersect(a: PlanRect, b: PlanRect): PlanRect | null {
  const r = {
    x1: Math.max(a.x1, b.x1),
    y1: Math.max(a.y1, b.y1),
    x2: Math.min(a.x2, b.x2),
    y2: Math.min(a.y2, b.y2),
  };
  return r.x1 < r.x2 && r.y1 < r.y2 ? r : null;
}

function covers(outer: PlanRect, inner: PlanRect): boolean {
  return outer.x1 <= inner.x1 && outer.y1 <= inner.y1 && outer.x2 >= inner.x2 && outer.y2 >= inner.y2;
}

describe('house-plan', () => {
  it('alle Raum-Ids sind eindeutig', () => {
    const ids = HOUSE_ROOMS.map(r => r.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('jeder Raum liegt innerhalb von HOUSE_BOUNDS', () => {
    for (const room of HOUSE_ROOMS) {
      for (const rect of room.rects) {
        expect(covers(HOUSE_BOUNDS, rect)).withContext(room.id).toBeTrue();
      }
    }
  });

  it('je Etage überlappen sich Räume nur über erklärte cutouts', () => {
    for (let i = 0; i < HOUSE_ROOMS.length; i++) {
      for (let j = i + 1; j < HOUSE_ROOMS.length; j++) {
        const a = HOUSE_ROOMS[i];
        const b = HOUSE_ROOMS[j];
        if (a.floor !== b.floor) continue;
        for (const ra of a.rects) {
          for (const rb of b.rects) {
            const overlap = intersect(ra, rb);
            if (!overlap) continue;
            const explained =
              (a.cutouts ?? []).some(c => covers(c, overlap)) ||
              (b.cutouts ?? []).some(c => covers(c, overlap));
            expect(explained).withContext(`${a.id} x ${b.id}`).toBeTrue();
          }
        }
      }
    }
  });

  it('kennt genau die abgestimmten Räume', () => {
    expect(HOUSE_ROOMS.map(r => r.id).sort()).toEqual([
      'bad', 'buero', 'esszimmer', 'flur', 'gaeste_wc', 'kellereingang', 'kueche',
      'laufband', 'paincave', 'schlafzimmer', 'treppenhaus', 'vorratskeller',
      'waschkeller', 'wohnzimmer',
    ]);
  });

  it('trägt die abgestimmten Anzeigenamen', () => {
    expect(roomById('laufband')!.label).toBe('Laufband-Raum');
    expect(roomById('paincave')!.label).toBe('Pain Cave');
    expect(OUTDOOR_AREAS.find(a => a.id === 'front')!.label).toBe('Vor dem Haus');
  });

  it('Schlafzimmer überspannt die ganze Rückseite', () => {
    expect(roomById('schlafzimmer')!.rects).toEqual([{ x1: 20, y1: 90, x2: 420, y2: 240 }]);
  });

  it('trägt die nachgeschärften Raummaße', () => {
    const r = (x1: number, y1: number, x2: number, y2: number): PlanRect => ({ x1, y1, x2, y2 });
    expect(roomById('esszimmer')!.rects).toEqual([r(20, 90, 210, 240)]);
    expect(roomById('kueche')!.rects).toEqual([r(20, 240, 210, 350)]);
    expect(roomById('wohnzimmer')!.rects).toEqual([r(210, 90, 420, 270)]);
    expect(roomById('flur')!.rects).toEqual([r(210, 270, 420, 440)]);
    expect(roomById('flur')!.cutouts).toEqual([r(210, 370, 290, 440)]);
    expect(roomById('buero')!.rects).toEqual([r(20, 240, 250, 340)]);
    expect(roomById('bad')!.rects).toEqual([r(20, 340, 250, 440)]);
    expect(roomById('treppenhaus')!.rects).toEqual([r(250, 240, 420, 440)]);
    expect(roomById('vorratskeller')!.rects).toEqual([r(20, 90, 210, 280)]);
    expect(roomById('waschkeller')!.rects).toEqual([r(20, 280, 210, 440)]);
  });

  it('Balkon liegt im Obergeschoss', () => {
    expect(OUTDOOR_AREAS.find(a => a.id === 'balcony')).toEqual(
      { id: 'balcony', label: 'Balkon', floor: 'upper', rect: { x1: 130, y1: 40, x2: 310, y2: 90 } });
  });

  it('outdoorAreasOn liefert die Außenbereiche der Etage', () => {
    expect(outdoorAreasOn('ground').map(a => a.id)).toEqual(['garden', 'terrace', 'front']);
    expect(outdoorAreasOn('upper').map(a => a.id)).toEqual(['balcony']);
    expect(outdoorAreasOn('basement')).toEqual([]);
  });

  it('areaFloor kennt Räume und Außenbereiche', () => {
    expect(areaFloor('balcony')).toBe('upper');
    expect(areaFloor('bad')).toBe('upper');
    expect(areaFloor('garden')).toBe('ground');
    expect(areaFloor('xyz')).toBeNull();
  });
});
