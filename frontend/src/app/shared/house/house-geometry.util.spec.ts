import { HOUSE_ROOMS } from './house-plan';
import { cameraForOverview, cameraForRoom, floorOpacity, roomAnchor, roomBoxes, Vec3 } from './house-geometry.util';

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

  it('floorOpacity: über der gewählten Etage Glas, gewählte und darunter solide', () => {
    expect(floorOpacity('upper', 'ground')).toBe('glass');
    expect(floorOpacity('ground', 'ground')).toBe('solid');
    expect(floorOpacity('basement', 'ground')).toBe('solid');

    expect(floorOpacity('ground', 'basement')).toBe('glass');
    expect(floorOpacity('upper', 'basement')).toBe('glass');
    expect(floorOpacity('basement', 'basement')).toBe('solid');
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
});
