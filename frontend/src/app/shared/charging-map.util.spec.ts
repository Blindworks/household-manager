import { ChargingStation } from '../models/charging.model';
import { initialViewRadiusMeters } from './charging-map.util';

describe('initialViewRadiusMeters', () => {
  function stationsAt(...distances: number[]): ChargingStation[] {
    return distances.map((distanceMeters, i) => ({
      stationId: String(i), name: `Station ${i}`, lat: 0, lon: 0,
      distanceMeters, total: 2, free: 1, favorite: false
    }));
  }

  it('reicht bis zur fuenftnaechsten Station statt ueber den ganzen Suchkreis', () => {
    const stations = stationsAt(9000, 400, 1200, 2500, 8000, 1800, 3100);
    expect(initialViewRadiusMeters(stations, 10)).toBe(3100);
  });

  it('nimmt bei weniger als fuenf Stationen die entfernteste', () => {
    expect(initialViewRadiusMeters(stationsAt(1500, 4200), 10)).toBe(4200);
  });

  it('zoomt nie enger als 1 km', () => {
    expect(initialViewRadiusMeters(stationsAt(100, 200, 300, 400, 500), 10)).toBe(1000);
  });

  it('geht nie ueber den Suchradius hinaus', () => {
    expect(initialViewRadiusMeters(stationsAt(1500, 2000), 1.5)).toBe(1500);
  });

  it('zeigt ohne Stationen 3 km, gekappt auf den Suchradius', () => {
    expect(initialViewRadiusMeters([], 10)).toBe(3000);
    expect(initialViewRadiusMeters([], 2)).toBe(2000);
  });
});
