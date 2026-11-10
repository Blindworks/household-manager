import { buildWasteInsights } from './waste-insight.util';
import { WasteCollectionEvent } from '../models/waste-collection.model';

function event(daysUntil: number, label: string, date = '2026-07-20'): WasteCollectionEvent {
  return { date, label, daysUntil };
}

/** 2026-07-20, lokale Uhrzeit. */
function at(hours: number, minutes = 0): Date {
  return new Date(2026, 6, 20, hours, minutes);
}

const MORNING = at(8);

describe('buildWasteInsights', () => {

  it('liefert keine Meldung, wenn nichts ansteht', () => {
    expect(buildWasteInsights([], MORNING)).toEqual([]);
  });

  it('zeigt jeden Termin als eigenen Eintrag', () => {
    const insights = buildWasteInsights([event(1, 'Biotonne'), event(2, 'Gelbe Tonne')], MORNING);

    expect(insights.map(i => i.text)).toEqual(['Morgen: Biotonne', 'Übermorgen: Gelbe Tonne']);
    expect(insights.every(i => i.title === 'Müllabfuhr')).toBeTrue();
  });

  it('uebersetzt daysUntil in relative Tageslabels', () => {
    expect(buildWasteInsights([event(0, 'Hausmüll')], MORNING)[0].text).toBe('Heute: Hausmüll');
    expect(buildWasteInsights([event(1, 'Hausmüll')], MORNING)[0].text).toBe('Morgen: Hausmüll');
    expect(buildWasteInsights([event(2, 'Hausmüll')], MORNING)[0].text).toBe('Übermorgen: Hausmüll');
  });

  it('nennt ab dem dritten Tag den Wochentag', () => {
    // 2026-07-20 ist ein Montag.
    expect(buildWasteInsights([event(4, 'Altpapier', '2026-07-20')], MORNING)[0].text)
      .toBe('Montag: Altpapier');
  });

  it('liest den Wochentag lokal, nicht als UTC-Mitternacht', () => {
    // Die Kurzform new Date('2026-07-20') parst als UTC und zeigte bei negativem
    // Offset den Sonntag. Der Test haelt die lokale Auslegung fest.
    const insights = buildWasteInsights([event(9, 'Altpapier', '2026-07-20')], MORNING);

    const erwartet = new Date(2026, 6, 20).toLocaleDateString('de-DE', { weekday: 'long' });
    expect(insights[0].text).toBe(`${erwartet}: Altpapier`);
  });

  it('zeigt einen Termin von heute bis 9:59 Uhr', () => {
    expect(buildWasteInsights([event(0, 'Hausmüll')], at(9, 59)).length).toBe(1);
  });

  it('blendet einen Termin von heute ab 10 Uhr aus', () => {
    expect(buildWasteInsights([event(0, 'Hausmüll')], at(10))).toEqual([]);
    expect(buildWasteInsights([event(0, 'Hausmüll')], at(18))).toEqual([]);
  });

  it('laesst spaetere Termine ab 10 Uhr stehen', () => {
    const insights = buildWasteInsights([event(0, 'Hausmüll'), event(1, 'Biotonne')], at(11));

    expect(insights.map(i => i.text)).toEqual(['Morgen: Biotonne']);
  });

  it('faerbt jeden Eintrag nach seinem eigenen Termin', () => {
    const tones = buildWasteInsights(
      [event(1, 'Biotonne'), event(2, 'Gelbe Tonne'), event(3, 'Altpapier')], MORNING
    ).map(i => i.tone);

    expect(tones).toEqual(['error', 'tertiary', 'primary']);
  });

  it('faerbt heute rot', () => {
    expect(buildWasteInsights([event(0, 'Hausmüll')], MORNING)[0].tone).toBe('error');
  });
});
