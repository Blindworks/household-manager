import { buildVentilationInsight } from './ventilation-insight.util';
import { VentilationAssessment } from '../models/ventilation.model';

function assessment(overrides: Partial<VentilationAssessment> = {}): VentilationAssessment {
  return {
    recommended: true,
    outdoorTemperature: 21.2,
    rooms: [
      { name: 'Schlafzimmer', temperature: 26.4 },
      { name: 'Wohnzimmer', temperature: 24.6 }
    ],
    evaluatedAt: '2026-08-12T18:40:00',
    ...overrides
  };
}

describe('buildVentilationInsight', () => {

  it('liefert keine Karte ohne Bewertung', () => {
    expect(buildVentilationInsight(null)).toBeNull();
  });

  it('liefert keine Karte, wenn keine Aussage moeglich ist', () => {
    expect(buildVentilationInsight(assessment({ recommended: null }))).toBeNull();
  });

  it('liefert keine Karte, wenn Lueften nichts bringt', () => {
    expect(buildVentilationInsight(assessment({ recommended: false, rooms: [] }))).toBeNull();
  });

  it('baut die Sammelkarte mit gerundeten Temperaturen', () => {
    const insight = buildVentilationInsight(assessment());

    expect(insight?.title).toBe('Lüften lohnt sich');
    expect(insight?.icon).toBe('air');
    expect(insight?.tone).toBe('secondary');
    expect(insight?.text)
      .toBe('Draußen 21° — kühler als Schlafzimmer (26°), Wohnzimmer (25°)');
  });

  it('liefert keine Karte bei fehlendem Aussenwert trotz recommended', () => {
    // Defensive: eine widerspruechliche Antwort (recommended, aber kein Aussenwert)
    // darf keine "Draußen null°"-Karte erzeugen.
    expect(buildVentilationInsight(assessment({ outdoorTemperature: null }))).toBeNull();
  });
});
