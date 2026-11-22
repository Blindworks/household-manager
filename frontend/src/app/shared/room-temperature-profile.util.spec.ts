import {
  ROOM_TEMPERATURE_PROFILES,
  profileForRoom,
  profileForTemperatureEntity,
  roomTemperatureColor,
  roomTemperatureColorPieces,
  roomTemperatureLevel
} from './room-temperature-profile.util';
import { HOUSE_DEVICES } from './house/house-devices';

describe('room-temperature-profile.util', () => {
  const { bath, living, bedroom, basement, hallway } = ROOM_TEMPERATURE_PROFILES;

  it('ordnet Bad waermer, Schlafzimmer kuehler und Keller ganz kalt ein', () => {
    expect(bath.min).toBeGreaterThan(living.min);
    expect(bedroom.max).toBeLessThan(living.max);
    expect(basement.max).toBeLessThan(bedroom.max);
  });

  it('waehlt das Profil je Raum, sonst nach Etage', () => {
    expect(profileForRoom('bad')).toBe(bath);
    expect(profileForRoom('gaeste_wc')).toBe(bath);
    expect(profileForRoom('schlafzimmer')).toBe(bedroom);
    expect(profileForRoom('treppenhaus')).toBe(hallway);
    expect(profileForRoom('wohnzimmer')).toBe(living);
    expect(profileForRoom('paincave')).toBe(basement);
    expect(profileForRoom('gibt-es-nicht')).toBeNull();
  });

  it('erkennt den Raum ueber die Entity-ID der Geraeteliste', () => {
    expect(profileForTemperatureEntity('sensor.zigbee_temperatur_aqara_badezimmer_temperature')).toBe(bath);
    expect(profileForTemperatureEntity('sensor.alexa_gaj230043055005a_temperature')).toBe(bedroom);
    expect(profileForTemperatureEntity('sensor.zigbee_temperatur_aqara_keller_temperature')).toBe(basement);
  });

  it('gibt Aussenfuehlern und unbekannten Sensoren kein Raumprofil', () => {
    expect(profileForTemperatureEntity('sensor.zigbee_temperatur_aqara_garten_temperature')).toBeNull();
    expect(profileForTemperatureEntity('sensor.ble_aabbccddeeff_temperature')).toBeNull();
    expect(profileForTemperatureEntity(null)).toBeNull();
  });

  it('jeder Raumfuehler der Geraeteliste hat ein Profil', () => {
    for (const device of HOUSE_DEVICES) {
      if (device.kind === 'climate' && device.roomSensor && device.temperature) {
        expect(profileForTemperatureEntity(device.temperature)).withContext(device.id).not.toBeNull();
      }
    }
  });

  it('bewertet relativ zum Zielbereich des Raums', () => {
    // 21 Grad: im Wohnraum im Ziel, im Bad zu kuehl, im Schlafzimmer zu warm.
    expect(roomTemperatureLevel(21, living)).toBe(0);
    expect(roomTemperatureLevel(21, bath)).toBe(-1);
    expect(roomTemperatureLevel(21, bedroom)).toBe(1);
    expect(roomTemperatureLevel(19, bath)).toBe(-2);
    expect(roomTemperatureLevel(24, basement)).toBe(3);
  });

  it('visualMap-Stuecke decken sich mit der Stufenfarbe', () => {
    const pieces = roomTemperatureColorPieces(bath);
    for (const celsius of [10, 19.9, 20, 21.9, 22, 24, 24.1, 26, 26.1, 28, 28.1, 35]) {
      const matching = pieces.filter(piece =>
        (piece.lt === undefined || celsius < piece.lt) &&
        (piece.lte === undefined || celsius <= piece.lte) &&
        (piece.gt === undefined || celsius > piece.gt) &&
        (piece.gte === undefined || celsius >= piece.gte));
      expect(matching.length).withContext(`${celsius}`).toBe(1);
      expect(matching[0].color).withContext(`${celsius}`).toBe(roomTemperatureColor(celsius, bath));
    }
  });
});
