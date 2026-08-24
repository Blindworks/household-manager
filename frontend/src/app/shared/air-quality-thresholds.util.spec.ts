import {
  AIR_QUALITY_LEVEL_COLORS,
  airQualityColorPieces,
  airQualityLevel,
  airQualityLevelColor
} from './air-quality-thresholds.util';

describe('air-quality-thresholds.util', () => {
  describe('airQualityLevel', () => {
    it('stuft Feinstaub nach den EEA-Baendern ein', () => {
      expect(airQualityLevel('pm25', 5)).toBe(0);
      expect(airQualityLevel('pm25', 15)).toBe(1);
      expect(airQualityLevel('pm25', 22)).toBe(2);
      expect(airQualityLevel('pm25', 40)).toBe(3);
      expect(airQualityLevel('pm25', 80)).toBe(4);

      expect(airQualityLevel('pm10', 15)).toBe(0);
      expect(airQualityLevel('pm10', 120)).toBe(4);
    });

    it('zaehlt die Grenze selbst noch zur besseren Stufe', () => {
      expect(airQualityLevel('pm25', 10)).toBe(0);
      expect(airQualityLevel('pm25', 10.1)).toBe(1);
      expect(airQualityLevel('co', 9)).toBe(1);
      expect(airQualityLevel('co', 9.1)).toBe(2);
    });

    it('stuft CO und VOC nach ihren eigenen Baendern ein', () => {
      expect(airQualityLevel('co', 0.4)).toBe(0);
      expect(airQualityLevel('co', 200)).toBe(4);
      expect(airQualityLevel('voc', 50)).toBe(0);
      expect(airQualityLevel('voc', 300)).toBe(2);
      expect(airQualityLevel('voc', 5000)).toBe(4);
    });

    it('dreht die Skala beim IAQ um - hohe Werte sind gut', () => {
      expect(airQualityLevel('iaq', 90)).toBe(0);
      expect(airQualityLevel('iaq', 50)).toBe(2);
      expect(airQualityLevel('iaq', 10)).toBe(3);
    });

    it('liefert zu jeder Stufe eine Farbe der Ampel', () => {
      expect(airQualityLevelColor('pm25', 5)).toBe(AIR_QUALITY_LEVEL_COLORS[0]);
      expect(airQualityLevelColor('pm25', 80)).toBe(AIR_QUALITY_LEVEL_COLORS[4]);
    });
  });

  describe('airQualityColorPieces', () => {
    it('deckt die gesamte Skala luecken- und ueberschneidungsfrei ab', () => {
      const pieces = airQualityColorPieces('pm10');

      expect(pieces.length).toBe(5);
      expect(pieces[0]).toEqual({ lte: 20, color: AIR_QUALITY_LEVEL_COLORS[0] });
      expect(pieces[1]).toEqual({ gt: 20, lte: 40, color: AIR_QUALITY_LEVEL_COLORS[1] });
      expect(pieces[4]).toEqual({ gt: 100, color: AIR_QUALITY_LEVEL_COLORS[4] });
    });

    it('faerbt den IAQ umgekehrt', () => {
      const pieces = airQualityColorPieces('iaq');

      expect(pieces[0].color).toBe(AIR_QUALITY_LEVEL_COLORS[3]);
      expect(pieces[pieces.length - 1].color).toBe(AIR_QUALITY_LEVEL_COLORS[0]);
    });

    it('nutzt dieselben Schwellen wie die Stufeneinteilung', () => {
      // Ein Wert genau auf der Grenze muss in der Kachel und im Graphen dieselbe
      // Farbe bekommen - sonst widerspraechen sich Zahl und Linie.
      const pieces = airQualityColorPieces('pm25');
      const boundaryPiece = pieces.find(piece => piece.lte === 10);

      expect(boundaryPiece?.color).toBe(airQualityLevelColor('pm25', 10));
    });
  });
});
