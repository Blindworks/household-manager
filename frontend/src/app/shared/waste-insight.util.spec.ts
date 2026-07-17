import { buildWasteInsight } from './waste-insight.util';
import { WasteCollectionEvent } from '../models/waste-collection.model';

function event(daysUntil: number, label: string, date = '2026-07-20'): WasteCollectionEvent {
  return { date, label, daysUntil };
}

describe('buildWasteInsight', () => {

  it('liefert keine Meldung, wenn nichts ansteht', () => {
    expect(buildWasteInsight([])).toBeNull();
  });

  it('fasst mehrere Termine zu einer Textzeile zusammen', () => {
    const insight = buildWasteInsight([event(1, 'Biotonne'), event(2, 'Gelbe Tonne')]);

    expect(insight?.text).toBe('Morgen: Biotonne · Übermorgen: Gelbe Tonne');
    expect(insight?.title).toBe('Müllabfuhr');
  });

  it('uebersetzt daysUntil in relative Tageslabels', () => {
    expect(buildWasteInsight([event(0, 'Hausmüll')])?.text).toBe('Heute: Hausmüll');
    expect(buildWasteInsight([event(1, 'Hausmüll')])?.text).toBe('Morgen: Hausmüll');
    expect(buildWasteInsight([event(2, 'Hausmüll')])?.text).toBe('Übermorgen: Hausmüll');
  });

  it('nennt ab dem dritten Tag den Wochentag', () => {
    // 2026-07-20 ist ein Montag.
    expect(buildWasteInsight([event(4, 'Altpapier', '2026-07-20')])?.text)
      .toBe('Montag: Altpapier');
  });

  it('liest den Wochentag lokal, nicht als UTC-Mitternacht', () => {
    // Die Kurzform new Date('2026-07-20') parst als UTC und zeigte bei negativem
    // Offset den Sonntag. Der Test haelt die lokale Auslegung fest.
    const insight = buildWasteInsight([event(9, 'Altpapier', '2026-07-20')]);

    const erwartet = new Date(2026, 6, 20).toLocaleDateString('de-DE', { weekday: 'long' });
    expect(insight?.text).toBe(`${erwartet}: Altpapier`);
  });

  it('faerbt den Indikator rot, wenn heute oder morgen etwas ansteht', () => {
    expect(buildWasteInsight([event(0, 'Hausmüll')])?.tone).toBe('error');
    expect(buildWasteInsight([event(1, 'Hausmüll')])?.tone).toBe('error');
  });

  it('faerbt den Indikator gelb, wenn uebermorgen etwas ansteht', () => {
    expect(buildWasteInsight([event(2, 'Hausmüll')])?.tone).toBe('tertiary');
  });

  it('bleibt bei spaeteren Terminen blau', () => {
    expect(buildWasteInsight([event(3, 'Hausmüll')])?.tone).toBe('primary');
  });

  it('richtet die Farbe bei mehreren Terminen nach dem naechstliegenden', () => {
    expect(buildWasteInsight([event(1, 'Biotonne'), event(2, 'Gelbe Tonne')])?.tone)
      .toBe('error');
    expect(buildWasteInsight([event(2, 'Gelbe Tonne'), event(3, 'Altpapier')])?.tone)
      .toBe('tertiary');
  });
});
