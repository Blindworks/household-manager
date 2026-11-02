import { SEARCH_MAX_DIAGONAL_KM, SearchBox, boxDiagonalKm, searchButtonState } from './charging-search.util';

describe('charging-search.util', () => {
  const home = { lat: 50, lon: 8 };
  /** Rund 2,2 km x 1,4 km um das Zuhause. */
  const small: SearchBox = { fromLat: 49.99, toLat: 50.01, fromLon: 7.99, toLon: 8.01 };

  it('nennt dieselbe Obergrenze wie das Backend', () => {
    expect(SEARCH_MAX_DIAGONAL_KM).toBe(30);
  });

  it('misst die Diagonale zwischen den Ecken', () => {
    expect(boxDiagonalKm(small)).toBeGreaterThan(2);
    expect(boxDiagonalKm(small)).toBeLessThan(3);
  });

  describe('searchButtonState', () => {
    it('ist versteckt, solange der Ausschnitt vollstaendig im Suchkreis liegt', () => {
      expect(searchButtonState(small, home, 10, null)).toBe('hidden');
    });

    it('zeigt "search", sobald eine Ecke den Suchkreis verlaesst', () => {
      const shifted: SearchBox = { fromLat: 50.05, toLat: 50.2, fromLon: 7.99, toLon: 8.01 };
      expect(searchButtonState(shifted, home, 10, null)).toBe('search');
    });

    it('zeigt "search", wenn eine Ecke im Kartenviereck ausserhalb des Kreises liegt, obwohl die Mitte drin ist', () => {
      // Kreis 5 km: die Mitte (Zuhause) ist drin, die Ecken des 12 km breiten Ausschnitts nicht.
      const wide: SearchBox = { fromLat: 49.95, toLat: 50.05, fromLon: 7.92, toLon: 8.08 };
      expect(searchButtonState(wide, home, 5, null)).toBe('search');
    });

    it('zeigt "zoom-in" bei mehr als 30 km Diagonale', () => {
      const huge: SearchBox = { fromLat: 49.85, toLat: 50.15, fromLon: 7.85, toLon: 8.15 };
      expect(boxDiagonalKm(huge)).toBeGreaterThan(SEARCH_MAX_DIAGONAL_KM);
      expect(searchButtonState(huge, home, 10, null)).toBe('zoom-in');
    });

    it('ist versteckt, wenn dieser Ausschnitt schon gesucht wurde', () => {
      const away: SearchBox = { fromLat: 51.0, toLat: 51.02, fromLon: 9.0, toLon: 9.02 };
      expect(searchButtonState(away, home, 10, away)).toBe('hidden');
    });

    it('ist versteckt, wenn der Ausschnitt innerhalb des zuletzt gesuchten liegt', () => {
      const searched: SearchBox = { fromLat: 51.0, toLat: 51.1, fromLon: 9.0, toLon: 9.1 };
      const inside: SearchBox = { fromLat: 51.02, toLat: 51.05, fromLon: 9.02, toLon: 9.05 };
      expect(searchButtonState(inside, home, 10, searched)).toBe('hidden');
    });

    it('zeigt "search" wieder, wenn der Ausschnitt ueber den gesuchten hinausragt', () => {
      const searched: SearchBox = { fromLat: 51.0, toLat: 51.02, fromLon: 9.0, toLon: 9.02 };
      const moved: SearchBox = { fromLat: 51.01, toLat: 51.03, fromLon: 9.0, toLon: 9.02 };
      expect(searchButtonState(moved, home, 10, searched)).toBe('search');
    });

    it('ist versteckt ohne Ausschnitt oder Zuhause', () => {
      expect(searchButtonState(null, home, 10, null)).toBe('hidden');
      expect(searchButtonState(small, null, 10, null)).toBe('hidden');
    });
  });
});
