import { correctionDelta, purchasePresets, snapToStep, stepAmount } from './pet-supply-entry.util';

describe('pet-supply-entry.util', () => {
  describe('snapToStep', () => {
    it('rundet auf das Raster und schneidet Gleitkomma-Reste ab', () => {
      expect(snapToStep(0.1 + 0.2, 0.5)).toBe(0.5);
      expect(snapToStep(1.2, 0.5)).toBe(1);
      expect(snapToStep(1.3, 0.5)).toBe(1.5);
      expect(snapToStep(7.4, 1)).toBe(7);
    });

    it('schneidet den Gleitkomma-Rest bei 0,1-Raster ab', () => {
      // 3 * 0.1 waere 0.30000000000000004 - das darf nicht im Feld landen.
      expect(String(snapToStep(0.3, 0.1))).toBe('0.3');
    });
  });

  describe('stepAmount', () => {
    it('geht einen Rasterschritt hoch und runter', () => {
      expect(stepAmount(2, 0.5, 1, 0.5)).toBe(2.5);
      expect(stepAmount(2, 0.5, -1, 0.5)).toBe(1.5);
      expect(stepAmount(7, 1, 1, 0)).toBe(8);
    });

    it('faellt nie unter die Untergrenze', () => {
      expect(stepAmount(0.5, 0.5, -1, 0.5)).toBe(0.5);
      expect(stepAmount(0, 1, -1, 0)).toBe(0);
    });

    it('startet bei leerem Feld auf der Untergrenze', () => {
      expect(stepAmount(null, 0.5, 1, 0.5)).toBe(0.5);
      expect(stepAmount(null, 1, -1, 0)).toBe(0);
    });

    it('springt von einem Wert ausserhalb des Rasters auf den naechsten Rasterpunkt in der Richtung', () => {
      // 2,3 Dosen sind kein gueltiger Bestand: hoch ergibt 2,5, runter 2,0 - nie 2,8.
      expect(stepAmount(2.3, 0.5, 1, 0.5)).toBe(2.5);
      expect(stepAmount(2.3, 0.5, -1, 0.5)).toBe(2);
      expect(stepAmount(0.3, 0.1, 1, 0.1)).toBe(0.4);
    });

    it('behandelt NaN wie ein leeres Feld', () => {
      expect(stepAmount(Number.NaN, 0.5, 1, 0.5)).toBe(0.5);
      expect(correctionDelta(Number.NaN, 5)).toBeNull();
    });
  });

  describe('purchasePresets', () => {
    it('bietet ein Viertel, die Haelfte und das Auffuellen bis zum Ziel', () => {
      const presets = purchasePresets({ targetAmount: 48, amountRemaining: 5, step: 0.5 });
      expect(presets).toEqual([
        { amount: 12, refill: false },
        { amount: 24, refill: false },
        { amount: 43, refill: true }
      ]);
    });

    it('rundet die Anteile auf das Raster des Vorrats', () => {
      // 30 / 4 waere 7,5 Tabletten - bei Raster 1 nicht buchbar, wird 8.
      const presets = purchasePresets({ targetAmount: 30, amountRemaining: 0, step: 1 });
      expect(presets.map(p => p.amount)).toEqual([8, 15, 30]);
    });

    it('laesst das Auffuellen weg, wenn der Vorrat voll ist', () => {
      const presets = purchasePresets({ targetAmount: 48, amountRemaining: 48, step: 0.5 });
      expect(presets.map(p => p.amount)).toEqual([12, 24]);
      expect(presets.some(p => p.refill)).toBeFalse();
    });

    it('zeigt einen Anteil nicht doppelt, wenn er dem Auffuellen entspricht', () => {
      // 24 von 48: die Haelfte IST das Auffuellen - dann nur die Auffuellen-Variante.
      const presets = purchasePresets({ targetAmount: 48, amountRemaining: 24, step: 0.5 });
      expect(presets).toEqual([
        { amount: 12, refill: false },
        { amount: 24, refill: true }
      ]);
    });

    it('zeigt Viertel und Haelfte nur einmal, wenn sie auf denselben Rasterwert fallen', () => {
      // Ziel 2 bei Raster 1: Viertel (0,5 -> 1) und Haelfte (1) sind derselbe Chip.
      const presets = purchasePresets({ targetAmount: 2, amountRemaining: 0, step: 1 });
      expect(presets).toEqual([
        { amount: 1, refill: false },
        { amount: 2, refill: true }
      ]);
    });
  });

  describe('correctionDelta', () => {
    it('liefert die Differenz zum aktuellen Bestand ohne Gleitkomma-Reste', () => {
      expect(correctionDelta(4.5, 5)).toBe(-0.5);
      expect(correctionDelta(40, 5)).toBe(35);
      expect(correctionDelta(0.3, 0.1)).toBe(0.2);
    });

    it('liefert null bei leerem Feld', () => {
      expect(correctionDelta(null, 5)).toBeNull();
    });

    it('liefert 0 bei unveraendertem Bestand', () => {
      expect(correctionDelta(5, 5)).toBe(0);
    });
  });
});
